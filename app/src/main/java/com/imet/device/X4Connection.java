package com.imet.device;

import com.imet.plugin.PluginTest;
import jssc.SerialPort;
import jssc.SerialPortException;

import java.io.IOException;
import java.util.Objects;

/**
 * Manages the serial connection to an iMet-X4 Hub over J1.
 * <p>
 * The connection is opened at 115200 baud, 8-N-1, no flow control.
 * This class owns the port; verifying that an X4 is actually on the other
 * end is delegated to the X4 plugin, which is handed the open port as a
 * {@link com.imet.plugin.PluginTransport}.
 * <p>
 * Once connected, the port remains open for subsequent command
 * exchanges (configuration queries, writes, etc.).  Call
 * {@link #disconnect()} to release the port.
 */
public class X4Connection implements AutoCloseable {

    private static final int BAUD_RATE       = SerialPort.BAUDRATE_115200;
    private static final int DATA_BITS       = SerialPort.DATABITS_8;
    private static final int STOP_BITS       = SerialPort.STOPBITS_1;
    private static final int PARITY          = SerialPort.PARITY_NONE;
    private static final int FLOW_CONTROL    = SerialPort.FLOWCONTROL_NONE;

    /** Time to let the FTDI chip settle after opening the port. */
    private static final int POST_OPEN_DELAY_MS = 50;

    /** Default timeout for a command response. */
    private static final int DEFAULT_TIMEOUT_MS = 500;

    /** Polling interval while waiting for a response. */
    private static final int POLL_INTERVAL_MS = 20;

    private SerialPort serialPort;

    /** Device plugin that verifies the device once the port is open. */
    private final PluginTest plugin;

    /**
     * @param plugin the loaded X4 plugin; it is given access to the port
     *               on {@link #connect} and released on {@link #disconnect}
     */
    public X4Connection(PluginTest plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * Opens the specified serial port and asks the plugin to verify the
     * device ({@link PluginTest#startComm}).
     *
     * @param portName system port name, e.g. "COM9" or "/dev/ttyUSB0"
     * @throws IOException if the port cannot be opened or the plugin
     *                     rejects the device
     */
    public void connect(String portName) throws IOException {
        if (serialPort != null && serialPort.isOpened()) {
            throw new IOException("Already connected to " + serialPort.getPortName()
                    + ". Disconnect first.");
        }

        SerialPort port = new SerialPort(portName);
        try {
            port.openPort();
            port.setParams(BAUD_RATE, DATA_BITS, STOP_BITS, PARITY);
            port.setFlowControlMode(FLOW_CONTROL);

            Thread.sleep(POST_OPEN_DELAY_MS);

        } catch (SerialPortException e) {
            closeQuietly(port);
            throw new IOException("Failed to open " + portName + ": "
                    + e.getExceptionType(), e);

        } catch (InterruptedException e) {
            closeQuietly(port);
            Thread.currentThread().interrupt();
            throw new IOException("Connection interrupted.", e);
        }

        // The port is open — let the plugin check that an X4 is on it.
        // Any failure from the plugin must still release the port.
        try {
            plugin.setTransport(new SerialPortTransport(port));
            plugin.startComm();
        } catch (IOException | RuntimeException e) {
            plugin.stopComm();
            closeQuietly(port);
            throw new IOException(portName + ": " + e.getMessage(), e);
        } catch (AbstractMethodError e) {
            // plugins/X4Plugin.jar was built against the old PluginTest.
            closeQuietly(port);
            throw new IOException("The X4 plugin is out of date. Rebuild "
                    + "X4Plugin.jar and copy it into the plugins folder.", e);
        }

        this.serialPort = port;
    }

    /**
     * Sends a command string and returns the response.
     *
     * @param command   the full command including leading {@code /} and
     *                  trailing {@code \r\n}
     * @param timeoutMs maximum time to wait for a response
     * @return the trimmed response string, or {@code null} if no
     *         response was received within the timeout
     * @throws IOException if the port is not open or a serial error occurs
     */
    public String sendCommand(String command, int timeoutMs) throws IOException {
        if (serialPort == null || !serialPort.isOpened()) {
            throw new IOException("Not connected.");
        }
        try {
            // Drain before sending so we only read the response to this command.
            if (serialPort.getInputBufferBytesCount() > 0) {
                serialPort.readBytes(serialPort.getInputBufferBytesCount());
            }

            serialPort.writeBytes(command.getBytes());
            return readResponse(serialPort, timeoutMs);

        } catch (SerialPortException e) {
            throw new IOException("Serial error: " + e.getExceptionType(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Command interrupted.", e);
        }
    }

    /** Convenience overload using the default timeout. */
    public String sendCommand(String command) throws IOException {
        return sendCommand(command, DEFAULT_TIMEOUT_MS);
    }

    /** Closes the serial port if open. Safe to call multiple times. */
    public void disconnect() {
        if (serialPort != null) {
            plugin.stopComm();
            closeQuietly(serialPort);
            serialPort = null;
        }
    }

    /** Returns {@code true} if the port is currently open. */
    public boolean isConnected() {
        return serialPort != null && serialPort.isOpened();
    }

    /** Returns the name of the connected port, or {@code null}. */
    public String getPortName() {
        return serialPort != null ? serialPort.getPortName() : null;
    }

    @Override
    public void close() {
        disconnect();
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Internal helpers
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Reads bytes from the port until a newline is seen or the timeout
     * expires.  Uses short polling intervals to stay responsive.
     */
    private static String readResponse(SerialPort port, int timeoutMs)
            throws SerialPortException, InterruptedException {

        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            int available = port.getInputBufferBytesCount();
            if (available > 0) {
                byte[] chunk = port.readBytes(available);
                sb.append(new String(chunk));

                if (sb.indexOf("\n") >= 0) {
                    break;
                }
            } else {
                Thread.sleep(POLL_INTERVAL_MS);
            }
        }

        return sb.length() > 0 ? sb.toString().trim() : null;
    }

    /** Best-effort port close; swallows exceptions. */
    private static void closeQuietly(SerialPort port) {
        try {
            if (port != null && port.isOpened()) {
                port.closePort();
            }
        } catch (SerialPortException ignored) {
        }
    }
}