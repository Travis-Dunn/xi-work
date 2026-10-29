package com.imet;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the application's home directory — the root from which
 * {@code lexicons/} and {@code plugins/} directories are located.
 * <p>
 * Resolution order:
 * <ol>
 *   <li>System property {@code imet.home} if set (manual override).</li>
 *   <li>{@code jpackage} environment: the {@code app.dir} property points
 *       to {@code <install>/app}, where {@code lexicons/} and
 *       {@code plugins/} are placed by the installer.</li>
 *   <li>Fallback: the current working directory (for IDE / dev use).</li>
 * </ol>
 */
public final class AppPaths {

    private static final Path HOME = resolveHome();

    private AppPaths() { }

    /** Returns the application home directory. */
    public static Path home() {
        return HOME;
    }

    /** Returns {@code <home>/lexicons}. */
    public static Path lexiconsDir() {
        return HOME.resolve("lexicons");
    }

    /** Returns {@code <home>/plugins}. */
    public static Path pluginsDir() {
        return HOME.resolve("plugins");
    }

    private static Path resolveHome() {
        // 1. Explicit override.
        String override = System.getProperty("imet.home");
        if (override != null) {
            return Path.of(override);
        }

        // 2. jpackage: app.dir → <install>/app (lexicons/ and plugins/ live here).
        String appDir = System.getProperty("app.dir");
        if (appDir != null) {
            Path path = Path.of(appDir);
            if (Files.isDirectory(path)) {
                return path;
            }
        }

        // 3. Fallback to working directory.
        return Path.of(System.getProperty("user.dir"));
    }
}