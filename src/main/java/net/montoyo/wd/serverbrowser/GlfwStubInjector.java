package net.montoyo.wd.serverbrowser;

import net.montoyo.wd.utilities.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Makes {@code org.lwjgl.glfw.GLFW} resolvable on DEDICATED servers.
 *
 * The java-cef natives used for server-side browsers translate injected input
 * events by reading GLFW constants through JNI:
 *
 * <pre>ScopedJNIClass cls(env, "org/lwjgl/glfw/GLFW");
 * if (!cls || !objClass) return;   // <- silently drops the event!</pre>
 *
 * Minecraft clients ship LWJGL, dedicated servers do not - so every mouse and
 * keyboard event sent to a server-side browser is silently discarded and
 * players cannot interact with screens.
 *
 * Fix: generate a constants-only GLFW stub class (values copied from the GLFW
 * headers), pack it into a small agent jar and load that agent into our own
 * JVM through a short-lived helper process (self-attach is disallowed by
 * default). The agent appends the jar to the bootstrap class loader search
 * path, which every class loader in the process can see - including the JNI
 * FindClass lookup made from the CEF natives.
 */
final class GlfwStubInjector {
    private GlfwStubInjector() {
    }

    private static final String GLFW_CLASS = "org.lwjgl.glfw.GLFW";

    /** True if org.lwjgl.glfw.GLFW is resolvable from the mod's class loader. */
    static boolean isGlfwPresent() {
        try {
            Class.forName(GLFW_CLASS, false, GlfwStubInjector.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Ensures the GLFW class is available, injecting the stub if necessary.
     * Returns true on success. Logs actionable warnings on failure.
     */
    static boolean ensureGlfwAvailable(Path gameDir) {
        if (isGlfwPresent())
            return true;

        Log.info("LWJGL is not on this server (normal for dedicated servers); "
                + "injecting a GLFW constants stub so browser input injection works...");

        try {
            Path dir = gameDir.resolve("wd_natives");
            Files.createDirectories(dir);
            Path agentJar = dir.resolve("wd-glfw-agent.jar");
            writeAgentJar(agentJar);

            if (!runAttachHelper(agentJar))
                return false;

            // The agent runs asynchronously in this JVM; give it a moment.
            for (int i = 0; i < 40; i++) {
                if (isGlfwPresent()) {
                    Log.info("GLFW stub injected successfully; in-game clicks and keyboard input will reach server-side browsers.");
                    return true;
                }
                Thread.sleep(250);
            }

            Log.warning("GLFW stub injection did not take effect (agent loaded but class still missing).");
            return false;
        } catch (Throwable t) {
            Log.warningEx("Could not inject the GLFW stub. Screens will DISPLAY fine but players "
                    + "will NOT be able to click or type on them. Fixes: run the server on a full JDK "
                    + "(not a stripped runtime), or add the 'lwjgl-glfw' jar to the server classpath.", t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Agent jar assembly
    // ------------------------------------------------------------------

    private static void writeAgentJar(Path agentJar) throws IOException {
        Manifest mf = new Manifest();
        mf.getMainAttributes().putValue("Manifest-Version", "1.0");
        mf.getMainAttributes().putValue("Agent-Class", "net.montoyo.wd.agent.GlfwStubAgent");

        Path tmp = agentJar.resolveSibling(agentJar.getFileName() + ".tmp");
        try (OutputStream os = Files.newOutputStream(tmp);
             JarOutputStream jar = new JarOutputStream(os, mf)) {
            putEntry(jar, "org/lwjgl/glfw/GLFW.class", generateGlfwStub());
            putEntry(jar, "net/montoyo/wd/agent/GlfwStubAgent.class",
                    readOwnClass("net/montoyo/wd/agent/GlfwStubAgent.class"));
            putEntry(jar, "net/montoyo/wd/agent/WdAttachMain.class",
                    readOwnClass("net/montoyo/wd/agent/WdAttachMain.class"));
        }

        try {
            Files.move(tmp, agentJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, agentJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void putEntry(JarOutputStream jar, String name, byte[] data) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(data);
        jar.closeEntry();
    }

    private static byte[] readOwnClass(String resource) throws IOException {
        try (InputStream is = GlfwStubInjector.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null)
                throw new IOException("Resource not found in mod jar: " + resource);
            return is.readAllBytes();
        }
    }

    // ------------------------------------------------------------------
    // Helper process (attach must come from another process)
    // ------------------------------------------------------------------

    private static boolean runAttachHelper(Path agentJar) throws IOException, InterruptedException {
        String javaBin = ProcessHandle.current().info().command()
                .orElse(new File(System.getProperty("java.home"),
                        "bin/java" + (File.separatorChar == '\\' ? ".exe" : "")).getAbsolutePath());

        Process proc = new ProcessBuilder(
                javaBin, "-cp", agentJar.toAbsolutePath().toString(),
                "net.montoyo.wd.agent.WdAttachMain",
                Long.toString(ProcessHandle.current().pid()),
                agentJar.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();

        byte[] output = proc.getInputStream().readAllBytes();
        if (!proc.waitFor(30, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            Log.warning("GLFW stub attach helper timed out.");
            return false;
        }

        if (proc.exitValue() != 0) {
            Log.warning("GLFW stub attach helper failed (exit %d): %s",
                    proc.exitValue(), new String(output, java.nio.charset.StandardCharsets.UTF_8).trim());
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Stub class generation (hand-written class file: no ASM dependency)
    // ------------------------------------------------------------------

    /**
     * Generates a Java 8 class file for a public {@code org.lwjgl.glfw.GLFW}
     * class containing only public static final int constants (with
     * ConstantValue attributes; no methods, no static initializer). This is
     * everything the java-cef natives read from the class.
     */
    private static byte[] generateGlfwStub() throws IOException {
        Map<String, Integer> c = glfwConstants();

        ByteArrayOutputStream bos = new ByteArrayOutputStream(2048);
        DataOutputStream out = new DataOutputStream(bos);

        out.writeInt(0xCAFEBABE);
        out.writeShort(0); // minor
        out.writeShort(52); // major (Java 8)

        // Constant pool: #1 Utf8 this-name, #2 Class this, #3 Utf8 super-name,
        // #4 Class super, #5 Utf8 "I", #6 Utf8 "ConstantValue",
        // then per field: Utf8 name, Integer value
        int n = c.size();
        out.writeShort(7 + 2 * n); // constant_pool_count = entries + 1

        writeUtf8(out, "org/lwjgl/glfw/GLFW");
        out.writeByte(7); // CONSTANT_Class
        out.writeShort(1);
        writeUtf8(out, "java/lang/Object");
        out.writeByte(7);
        out.writeShort(3);
        writeUtf8(out, "I");
        writeUtf8(out, "ConstantValue");

        for (Map.Entry<String, Integer> e : c.entrySet()) {
            writeUtf8(out, e.getKey());
            out.writeByte(3); // CONSTANT_Integer
            out.writeInt(e.getValue());
        }

        out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
        out.writeShort(2); // this_class
        out.writeShort(4); // super_class
        out.writeShort(0); // interfaces

        out.writeShort(n); // fields
        int cpIndex = 7; // first field-name Utf8 index
        for (int i = 0; i < n; i++) {
            out.writeShort(0x0019); // ACC_PUBLIC | ACC_STATIC | ACC_FINAL
            out.writeShort(cpIndex); // name
            out.writeShort(5); // descriptor "I"
            out.writeShort(1); // one attribute
            out.writeShort(6); // "ConstantValue"
            out.writeInt(2); // attribute length
            out.writeShort(cpIndex + 1); // CONSTANT_Integer
            cpIndex += 2;
        }

        out.writeShort(0); // methods
        out.writeShort(0); // class attributes
        out.flush();
        return bos.toByteArray();
    }

    private static void writeUtf8(DataOutputStream out, String s) throws IOException {
        out.writeByte(1); // CONSTANT_Utf8
        out.writeUTF(s);
    }

    /** Constant values copied from glfw3.h; only what the java-cef natives read. */
    private static Map<String, Integer> glfwConstants() {
        Map<String, Integer> c = new LinkedHashMap<>();
        c.put("GLFW_RELEASE", 0);
        c.put("GLFW_PRESS", 1);
        c.put("GLFW_REPEAT", 2);

        c.put("GLFW_MOUSE_BUTTON_1", 0);
        c.put("GLFW_MOUSE_BUTTON_2", 1);
        c.put("GLFW_MOUSE_BUTTON_3", 2);

        c.put("GLFW_MOD_SHIFT", 0x1);
        c.put("GLFW_MOD_CONTROL", 0x2);
        c.put("GLFW_MOD_ALT", 0x4);
        c.put("GLFW_MOD_SUPER", 0x8);

        c.put("GLFW_KEY_ESCAPE", 256);
        c.put("GLFW_KEY_ENTER", 257);
        c.put("GLFW_KEY_TAB", 258);
        c.put("GLFW_KEY_BACKSPACE", 259);
        c.put("GLFW_KEY_INSERT", 260);
        c.put("GLFW_KEY_DELETE", 261);
        c.put("GLFW_KEY_RIGHT", 262);
        c.put("GLFW_KEY_LEFT", 263);
        c.put("GLFW_KEY_DOWN", 264);
        c.put("GLFW_KEY_UP", 265);
        c.put("GLFW_KEY_PAGE_UP", 266);
        c.put("GLFW_KEY_PAGE_DOWN", 267);
        c.put("GLFW_KEY_HOME", 268);
        c.put("GLFW_KEY_END", 269);
        c.put("GLFW_KEY_CAPS_LOCK", 280);
        c.put("GLFW_KEY_SCROLL_LOCK", 281);
        c.put("GLFW_KEY_NUM_LOCK", 282);
        c.put("GLFW_KEY_PRINT_SCREEN", 283);
        c.put("GLFW_KEY_PAUSE", 284);

        c.put("GLFW_KEY_KP_0", 320);
        c.put("GLFW_KEY_KP_1", 321);
        c.put("GLFW_KEY_KP_2", 322);
        c.put("GLFW_KEY_KP_3", 323);
        c.put("GLFW_KEY_KP_4", 324);
        c.put("GLFW_KEY_KP_5", 325);
        c.put("GLFW_KEY_KP_6", 326);
        c.put("GLFW_KEY_KP_7", 327);
        c.put("GLFW_KEY_KP_8", 328);
        c.put("GLFW_KEY_KP_9", 329);
        c.put("GLFW_KEY_KP_ENTER", 335);

        c.put("GLFW_KEY_LEFT_SHIFT", 340);
        c.put("GLFW_KEY_LEFT_CONTROL", 341);
        c.put("GLFW_KEY_LEFT_ALT", 342);
        c.put("GLFW_KEY_LEFT_SUPER", 343);
        c.put("GLFW_KEY_RIGHT_SHIFT", 344);
        c.put("GLFW_KEY_RIGHT_CONTROL", 345);
        c.put("GLFW_KEY_RIGHT_ALT", 346);
        c.put("GLFW_KEY_RIGHT_SUPER", 347);
        return c;
    }
}
