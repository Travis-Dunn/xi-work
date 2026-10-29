package com.imet.serial;

import jssc.SerialPort;
import jssc.SerialPortException;
import jssc.SerialPortList;

import javax.swing.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Continuously scans system serial ports for known InterMet devices.
 * <p>
 * The scanner runs on a single daemon thread at a configurable interval
 * (default 1 Hz). Each tick it:
 * <ol>
 *   <li>Enumerates available serial ports via {@code jssc}.</li>
 *   <li>Skips any ports currently <em>claimed</em> by the application
 *       (i.e. ports the user has explicitly connected to).</li>
 *   <li>Probes each remaining port for supported devices.</li>
 *   <li>Compares the new discovery set to the previous one and, if it
 *       changed, notifies registered listeners on the Swing EDT.</li>
 * </ol>
 * <h3>X4 probe protocol</h3>
 * Opens the port at 115 200 baud, 8-N-1, sends {@code /ACK?\r\n}, and
 * waits up to {@value #PROBE_TIMEOUT_MS} ms for an {@code ACK} response.
 * The port is always closed after probing regardless of outcome.
 */
public class DeviceScanner {

    /** Time in milliseconds to wait for a probe response. */
    private static final int PROBE_TIMEOUT_MS = 350;

    /** Small delay after opening the port before writing (let FTDI settle). */
    private static final int POST_OPEN_DELAY_MS = 50;

    private final ScheduledExecutorService executor;
    private final Set<String> claimedPorts = ConcurrentHashMap.newKeySet();
    private final List<DeviceScannerListener> listeners = new CopyOnWriteArrayList<>();

    /** Last-known discovery set — used to detect changes. */
    private volatile Set<DiscoveredDevice> lastDiscovered = Set.of();

    /** Handle for the currently scheduled scan task, if any. */
    private ScheduledFuture<?> scanTask;

    public DeviceScanner() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DeviceScanner");
            t.setDaemon(true);
            return t;
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Starts scanning at approximately 1 Hz.  Safe to call multiple times
     * (subsequent calls are no-ops while a scan is already running).
     */
    public void start() {
        if (scanTask == null || scanTask.isCancelled()) {
            scanTask = executor.scheduleWithFixedDelay(this::scan, 0, 1, TimeUnit.SECONDS);
        }
    }

    /**
     * Stops scanning and clears the current discovery set, notifying
     * listeners that no devices are detected.  The background thread
     * remains alive so {@link #start()} can be called again later.
     */
    public void stop() {
        if (scanTask != null) {
            scanTask.cancel(false);
            scanTask = null;
        }
        // Clear discoveries so the UI resets to "NOT DETECTED".
        if (!lastDiscovered.isEmpty()) {
            lastDiscovered = Set.of();
            SwingUtilities.invokeLater(() -> fireDevicesChanged(Set.of()));
        }
    }

    /**
     * Permanently shuts down the background thread.
     * Call once at application exit.
     */
    public void shutdown() {
        stop();
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Claimed-port management
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Marks a port as "claimed" so the scanner will skip it.
     * Call this when the user connects to a port for active use.
     */
    public void claimPort(String portName) {
        claimedPorts.add(portName);
    }

    /**
     * Releases a previously claimed port so it becomes eligible for
     * probing again.  Call this when the user disconnects.
     */
    public void releasePort(String portName) {
        claimedPorts.remove(portName);
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Listener management
    // ────────────────────────────────────────────────────────────────────────

    public void addListener(DeviceScannerListener listener) {
        listeners.add(listener);
    }

    public void removeListener(DeviceScannerListener listener) {
        listeners.remove(listener);
    }

    /** Returns an unmodifiable snapshot of the current discoveries. */
    public Set<DiscoveredDevice> getDiscoveredDevices() {
        return lastDiscovered;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Core scan loop
    // ────────────────────────────────────────────────────────────────────────

    private void scan() {
        try {
            String[] portNames = SerialPortList.getPortNames();
            Set<DiscoveredDevice> discovered = new LinkedHashSet<>();

            for (String portName : portNames) {
                if (claimedPorts.contains(portName)) {
                    continue;
                }

                // Currently we only probe for X4.
                if (probeX4(portName)) {
                    discovered.add(new DiscoveredDevice(
                            portName, DiscoveredDevice.DeviceType.IMET_X4));
                }
            }

            // Only notify if the set actually changed.
            if (!discovered.equals(lastDiscovered)) {
                lastDiscovered = Collections.unmodifiableSet(discovered);
                Set<DiscoveredDevice> snapshot = lastDiscovered;
                SwingUtilities.invokeLater(() -> fireDevicesChanged(snapshot));
            }
        } catch (Exception e) {
            // Swallow — scanner is best-effort; a single failed tick
            // should not crash the loop.
            System.err.println("DeviceScanner tick error: " + e.getMessage());
        }
    }

    private void fireDevicesChanged(Set<DiscoveredDevice> devices) {
        for (DeviceScannerListener listener : listeners) {
            try {
                listener.onDevicesChanged(devices);
            } catch (Exception e) {
                System.err.println("DeviceScannerListener error: " + e.getMessage());
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  X4 probe
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Attempts to identify an iMet-X4 Hub on the given port.
     * <p>
     * Opens the port at 115 200 baud (8-N-1), sends {@code /ACK?\r\n},
     * and checks for an {@code ACK} response within the timeout window.
     * The port is always closed before this method returns.
     *
     * @return {@code true} if the device responded with ACK
     */
    private boolean probeX4(String portName) {
        SerialPort port = new SerialPort(portName);
        try {
            port.openPort();
            port.setParams(
                    SerialPort.BAUDRATE_115200,
                    SerialPort.DATABITS_8,
                    SerialPort.STOPBITS_1,
                    SerialPort.PARITY_NONE
            );
            port.setFlowControlMode(SerialPort.FLOWCONTROL_NONE);

            // Brief pause to let the FTDI chip settle after open.
            Thread.sleep(POST_OPEN_DELAY_MS);

            // Drain any stale data sitting in the receive buffer.
            if (port.getInputBufferBytesCount() > 0) {
                port.readBytes(port.getInputBufferBytesCount());
            }

            // Send the ACK probe.
            port.writeBytes("/ACK?\r\n".getBytes());

            // Read response with a short timeout.
            String response = readResponse(port, PROBE_TIMEOUT_MS);
            return response != null && response.contains("ACK");

        } catch (SerialPortException | InterruptedException e) {
            // Port busy, permissions, or thread interrupted — not an X4.
            return false;
        } finally {
            try {
                if (port.isOpened()) {
                    port.closePort();
                }
            } catch (SerialPortException ignored) {
                // Best-effort close.
            }
        }
    }

    /**
     * Reads bytes from the port until timeout, accumulating into a String.
     * Uses short polling intervals to stay responsive without burning CPU.
     */
    private String readResponse(SerialPort port, int timeoutMs)
            throws SerialPortException, InterruptedException {

        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            int available = port.getInputBufferBytesCount();
            if (available > 0) {
                byte[] chunk = port.readBytes(available);
                sb.append(new String(chunk));

                // If we see a line terminator we have a complete response.
                if (sb.indexOf("\n") >= 0) {
                    break;
                }
            } else {
                Thread.sleep(20);   // avoid tight spin
            }
        }

        return sb.length() > 0 ? sb.toString().trim() : null;
    }
}