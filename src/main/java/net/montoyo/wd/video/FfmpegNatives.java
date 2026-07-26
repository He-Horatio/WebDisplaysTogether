package net.montoyo.wd.video;

import net.montoyo.wd.utilities.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Puts the bundled FFmpeg/JavaCPP native libraries where JavaCPP can use them
 * reliably under Forge.
 *
 * Why this exists: JavaCPP's own resource cache re-checks every library file
 * against its resource URL (size + timestamp) on every {@code Loader.load}.
 * Under ModLauncher the resources live behind {@code union:} URLs which report
 * no stable size/timestamp, so the check always fails and JavaCPP tries to
 * re-extract DLLs that are already loaded - and therefore locked - by this
 * very process. The result is "file in use" errors and permanently poisoned
 * codec classes (black screens).
 *
 * Plain files found through {@code platform.preloadpath} + {@code pathsFirst}
 * bypass the resource cache entirely, so we extract the libraries ourselves,
 * once, into a stable directory shared by all processes.
 */
public final class FfmpegNatives {
    private FfmpegNatives() {
    }

    /** Extracts the natives and points JavaCPP at them. Call before any codec use. */
    public static void setup(Path gameDir) {
        try {
            // IMPORTANT: JavaCPP's Loader reads "org.bytedeco.javacpp.pathsFirst"
            // in its class initializer, so the property must be set before the
            // Loader class is touched in any way (this is also why the platform
            // string is detected manually instead of via Loader.getPlatform()).
            System.setProperty("org.bytedeco.javacpp.pathsFirst", "true");

            String platform = detectPlatform(); // e.g. "windows-x86_64"
            Path outDir = gameDir.resolve("wd_natives").resolve(platform);
            Files.createDirectories(outDir);
            System.setProperty("org.bytedeco.javacpp.platform.preloadpath", outDir.toString());

            int extracted = 0, present = 0;
            for (Path archive : findSourceArchives(platform)) {
                try (ZipFile zip = new ZipFile(archive.toFile())) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (entry.isDirectory() || !name.startsWith("org/bytedeco/") || !name.contains("/" + platform + "/"))
                            continue;

                        String fileName = name.substring(name.lastIndexOf('/') + 1);
                        if (!fileName.endsWith(".dll") && !fileName.contains(".so") && !fileName.endsWith(".dylib"))
                            continue;

                        Path target = outDir.resolve(fileName);
                        if (Files.exists(target) && Files.size(target) == entry.getSize()) {
                            present++;
                            continue;
                        }

                        // Write to a temp file, then move into place atomically so
                        // concurrent processes never see (or load) half a library.
                        Path tmp = outDir.resolve(fileName + "." + ProcessHandle.current().pid() + ".tmp");
                        try (InputStream in = zip.getInputStream(entry)) {
                            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                        }
                        try {
                            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                            extracted++;
                        } catch (IOException e) {
                            Files.deleteIfExists(tmp);
                            // The old file may be locked by a running process; if it
                            // is complete, using it as-is is fine.
                            if (Files.exists(target) && Files.size(target) == entry.getSize())
                                present++;
                            else
                                throw e;
                        }
                    }
                }
            }

            if (extracted + present == 0) {
                Log.warning("Could not find the bundled FFmpeg natives for %s; falling back to JavaCPP's own extraction.", platform);
                return;
            }

            Log.info("FFmpeg natives ready in %s (%d extracted, %d already present).", outDir, extracted, present);
        } catch (Throwable t) {
            Log.warningEx("Could not prepare the FFmpeg natives directory; falling back to JavaCPP's own extraction.", t);
        }
    }

    /** Same naming scheme as JavaCPP's Loader.getPlatform(), without touching the Loader class. */
    private static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();

        String name;
        if (os.startsWith("windows"))
            name = "windows";
        else if (os.contains("mac") || os.contains("darwin"))
            name = "macosx";
        else
            name = "linux";

        String bits;
        if (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64"))
            bits = "x86_64";
        else if (arch.equals("aarch64") || arch.equals("arm64"))
            bits = "arm64";
        else
            bits = arch;

        return name + "-" + bits;
    }

    private static List<Path> findSourceArchives(String platform) {
        List<Path> result = new ArrayList<>();

        // Production: the bytedeco classes + natives are shaded into the mod jar.
        try {
            Path modFile = net.minecraftforge.fml.loading.FMLLoader.getLoadingModList()
                    .getModFileById("webdisplaystogether").getFile().getFilePath();
            if (Files.isRegularFile(modFile))
                result.add(modFile);
        } catch (Throwable ignored) {
        }

        // Dev environment: the bytedeco jars sit on the (legacy) classpath.
        for (String propName : new String[]{"legacyClassPath", "java.class.path"}) {
            String cp = System.getProperty(propName);
            if (cp == null)
                continue;

            for (String item : cp.split(File.pathSeparator)) {
                String base = item.replace('\\', '/').toLowerCase();
                base = base.substring(base.lastIndexOf('/') + 1);
                if ((base.startsWith("ffmpeg-") || base.startsWith("javacpp-")) && base.contains(platform) && base.endsWith(".jar")) {
                    Path p = Paths.get(item);
                    if (Files.isRegularFile(p))
                        result.add(p);
                }
            }
        }

        return result;
    }
}
