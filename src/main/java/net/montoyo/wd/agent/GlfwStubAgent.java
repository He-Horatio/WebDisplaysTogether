package net.montoyo.wd.agent;

import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

/**
 * Java agent loaded into the DEDICATED server JVM at runtime (via
 * {@link WdAttachMain}). Appends the jar given in {@code args} (which contains
 * a constants-only {@code org.lwjgl.glfw.GLFW} stub) to the BOOTSTRAP class
 * loader search path.
 *
 * Why: the java-cef natives resolve GLFW input constants through JNI
 * ({@code FindClass("org/lwjgl/glfw/GLFW")}) and silently drop every mouse and
 * keyboard event when the class is missing. Dedicated servers do not ship
 * LWJGL, so without this stub players cannot interact with screens at all.
 * The bootstrap loader is visible from every class loader in the process,
 * including ModLauncher's transforming loader that owns the CEF classes.
 *
 * This class must only reference java.base / java.instrument types.
 */
public final class GlfwStubAgent {
    private GlfwStubAgent() {
    }

    public static void agentmain(String args, Instrumentation inst) throws Exception {
        inst.appendToBootstrapClassLoaderSearch(new JarFile(args));
    }
}
