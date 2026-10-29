package com.imet.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Periodically scans the {@code lexicons/} and {@code plugins/}
 * directories for device support files and notifies registered
 * listeners when the set of available resources changes.
 * <p>
 * <b>Lexicon detection:</b> any {@code .json} file in the lexicons
 * directory whose {@code "deviceName"} field contains a recognised
 * product identifier (case-insensitive) is counted as a hit for
 * that product.
 * <p>
 * <b>Plugin detection:</b> checks for the file declared by each lexicon's
 * {@code pluginFileName} field within the plugins directory.
 */
public class ResourceScanner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Maps a lowercase substring found in a lexicon's deviceName to
     * the canonical product name used by the UI.  Order doesn't matter
     * but the key must be lowercase for case-insensitive matching.
     */
    private static final Map<String, String> DEVICE_NAME_MAP = Map.of(
            "imet-x4",  "IMet-X4",
            "imet-xq2", "IMet-XQ2",
            "imet-xf",  "IMet-XF",
            "imet-4rsb","IMet-4RSB"
    );

    private final Path lexiconsDir;
    private final Path pluginsDir;
    private final ScheduledExecutorService executor;
    private final List<ResourceScannerListener> listeners = new CopyOnWriteArrayList<>();

    private volatile Set<String> lastLexiconDevices = Set.of();
    private volatile Set<String> lastPluginDevices  = Set.of();
    private ScheduledFuture<?> scanTask;

    public ResourceScanner(Path lexiconsDir, Path pluginsDir) {
        this.lexiconsDir = lexiconsDir;
        this.pluginsDir  = pluginsDir;

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ResourceScanner");
            t.setDaemon(true);
            return t;
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ────────────────────────────────────────────────────────────────────────

    /** Starts scanning at approximately 1 Hz. Safe to call repeatedly. */
    public void start() {
        if (scanTask == null || scanTask.isCancelled()) {
            scanTask = executor.scheduleWithFixedDelay(
                    this::scan, 0, 1, TimeUnit.SECONDS);
        }
    }

    /** Permanently shuts down the background thread. */
    public void shutdown() {
        if (scanTask != null) {
            scanTask.cancel(false);
            scanTask = null;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Listener management
    // ────────────────────────────────────────────────────────────────────────

    public void addListener(ResourceScannerListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ResourceScannerListener listener) {
        listeners.remove(listener);
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Core scan
    // ────────────────────────────────────────────────────────────────────────

    private void scan() {
        try {
            Set<String> lexicons = new LinkedHashSet<>();
            Set<String> plugins = new LinkedHashSet<>();
            scanResources(lexicons, plugins);

            if (!lexicons.equals(lastLexiconDevices)
                    || !plugins.equals(lastPluginDevices)) {
                lastLexiconDevices = Collections.unmodifiableSet(lexicons);
                lastPluginDevices  = Collections.unmodifiableSet(plugins);

                Set<String> lexSnap = lastLexiconDevices;
                Set<String> plugSnap = lastPluginDevices;
                SwingUtilities.invokeLater(() -> fireChanged(lexSnap, plugSnap));
            }
        } catch (Exception e) {
            System.err.println("ResourceScanner tick error: " + e.getMessage());
        }
    }

    private void fireChanged(Set<String> lexicons, Set<String> plugins) {
        for (ResourceScannerListener listener : listeners) {
            try {
                listener.onResourcesChanged(lexicons, plugins);
            } catch (Exception e) {
                System.err.println("ResourceScannerListener error: " + e.getMessage());
            }
        }
    }

    /** Reads each lexicon once so both indicators refer to the same declaration. */
    private void scanResources(Set<String> lexicons, Set<String> plugins) {
        if (!Files.isDirectory(lexiconsDir)) return;
        Path pluginRoot = pluginsDir.toAbsolutePath().normalize();

        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(lexiconsDir, "*.json")) {
            for (Path file : stream) {
                try {
                    JsonNode root = MAPPER.readTree(file.toFile());
                    if (root == null || !root.path("deviceName").isTextual()) continue;
                    String deviceName = root.path("deviceName").asText()
                            .toLowerCase(Locale.ROOT);
                    Set<String> products = new LinkedHashSet<>();
                    for (Map.Entry<String, String> entry : DEVICE_NAME_MAP.entrySet()) {
                        if (deviceName.contains(entry.getKey())) {
                            products.add(entry.getValue());
                        }
                    }
                    lexicons.addAll(products);

                    JsonNode filename = root.path("pluginFileName");
                    if (!filename.isTextual() || filename.asText().isBlank()) continue;
                    Path plugin = pluginRoot.resolve(filename.asText()).normalize();
                    if (plugin.startsWith(pluginRoot) && Files.isRegularFile(plugin)) {
                        plugins.addAll(products);
                    }
                } catch (IOException | InvalidPathException e) {
                    // A malformed or unreadable file must not hide other devices.
                }
            }
        } catch (IOException e) {
            // Directory not readable — treat as empty.
        }
    }
}
