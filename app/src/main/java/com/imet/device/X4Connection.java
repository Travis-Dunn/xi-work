package com.imet.device;

import com.imet.Console;
import com.imet.plugin.PluginTest;
import jssc.SerialPort;
import jssc.SerialPortException;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Manages the serial connection to an iMet-X4 Hub over J1.
 * <p>
 * The connection is opened at 115200 baud, 8-N-1, no flow control.
 * This class owns the port; verifying that an X4 is actually on the other
 * end is delegated to the X4 plugin, which is handed the open port as a
 * {@link com.imet.plugin.PluginTransport}.
 * <p>
 * Once connected, the port remains open, and commands to the device go
 * through the plugin ({@link #getPlugin}).  Call {@link #disconnect()} to
 * release the port.
 * <p>
 * Once the port is open, a reader thread is the only thing that reads it.
 * It passes every received chunk to the plugin's transport, then to the
 * listeners added with {@link #addReceiveListener}.
 */
public class X4Connection implements AutoCloseable {

    private static final int BAUD_RATE       = SerialPort.BAUDRATE_115200;
    private static final int DATA_BITS       = SerialPort.DATABITS_8;
    private static final int STOP_BITS       = SerialPort.STOPBITS_1;
    private static final int PARITY          = SerialPort.PARITY_NONE;
    private static final int FLOW_CONTROL    = SerialPort.FLOWCONTROL_NONE;

    /** Time to let the FTDI chip settle after opening the port. */
    private static final int POST_OPEN_DELAY_MS = 50;

    /** Polling interval of the reader thread while no bytes are waiting. */
    private static final int POLL_INTERVAL_MS = 20;

    /** How long {@link #disconnect} waits for the reader thread to stop. */
    private static final int READER_STOP_TIMEOUT_MS = 500;

    private SerialPort serialPort;

    /** Reads J1 while connected; null when disconnected. */
    private Thread readerThread;

    /** The plugin's view of the port, fed by the reader; null when closed. */
    private SerialPortTransport transport;

    /** Called on the reader thread with each chunk of bytes received. */
    private final List<Consumer<byte[]>> receiveListeners = new CopyOnWriteArrayList<>();

    /** Device plugin that verifies the device once the port is open. */
    private final PluginTest plugin;

    /**
     * The device's configuration as read by {@link #readSettings}; null
     * until then and after a disconnect.
     */
    private volatile X4Settings settings;

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

        Console.log("J1 opening " + portName + " at " + BAUD_RATE + " 8-N-1");
        SerialPort port = new SerialPort(portName);
        try {
            port.openPort();
            port.setParams(BAUD_RATE, DATA_BITS, STOP_BITS, PARITY);
            port.setFlowControlMode(FLOW_CONTROL);

            Thread.sleep(POST_OPEN_DELAY_MS);
            Console.log("J1 port open");

        } catch (SerialPortException e) {
            Console.log("J1 open failed: " + e.getExceptionType());
            closeQuietly(port);
            throw new IOException("Failed to open " + portName + ": "
                    + e.getExceptionType(), e);

        } catch (InterruptedException e) {
            closeQuietly(port);
            Thread.currentThread().interrupt();
            throw new IOException("Connection interrupted.", e);
        }

        // The port is open. Start the reader before anything else reads:
        // from here on it is the only reader, and the plugin's transport
        // gets its bytes from it.
        transport = new SerialPortTransport(port, "J1");
        startReader(port, transport);

        // Let the plugin check that an X4 is on it. Any failure from the
        // plugin must still release the port.
        try {
            Console.log("J1 plugin startComm (ACK handshake)");
            plugin.setTransport(transport);
            plugin.startComm();
            Console.log("J1 plugin verified the device");
        } catch (IOException | RuntimeException e) {
            Console.log("J1 plugin rejected the device: " + e);
            plugin.stopComm();
            release(port);
            throw new IOException(portName + ": " + e.getMessage(), e);
        } catch (AbstractMethodError e) {
            // plugins/X4Plugin.jar was built against the old PluginTest.
            Console.log("J1 plugin is out of date: " + e);
            release(port);
            throw new IOException("The X4 plugin is out of date. Rebuild "
                    + "X4Plugin.jar and copy it into the plugins folder.", e);
        }

        this.serialPort = port;
    }

    /**
     * Registers a listener for everything received on J1, exactly as it
     * arrives. It is called on the reader thread, so a Swing listener must
     * hand the bytes to the EDT itself.
     */
    public void addReceiveListener(Consumer<byte[]> listener) {
        receiveListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeReceiveListener(Consumer<byte[]> listener) {
        receiveListeners.remove(listener);
    }

    /**
     * Returns the plugin that talks to the device on this connection. Its
     * message calls work only while connected.
     */
    public PluginTest getPlugin() {
        return plugin;
    }

    /**
     * Reads the device's configuration through the plugin and keeps it for
     * {@link #getSettings} (SoW #14). Takes a second or so, longer if the
     * device is slow to answer, so don't call it on the EDT. Never throws:
     * settings that could not be read are listed in
     * {@link X4Settings#missing()}.
     */
    public X4Settings readSettings() {
        Console.log("J1 reading the X4's settings");
        X4Settings read = X4SettingsReader.read(plugin);
        settings = read;
        return read;
    }

    /**
     * Returns the configuration from the last {@link #readSettings}, or
     * {@code null} if it hasn't been read on this connection.
     */
    public X4Settings getSettings() {
        return settings;
    }

    /** Closes the serial port if open. Safe to call multiple times. */
    public void disconnect() {
        settings = null;
        if (serialPort != null) {
            Console.log("J1 closing " + serialPort.getPortName());
            // The plugin goes first, while the reader still runs, in case
            // it wants a last exchange with the device.
            plugin.stopComm();
            release(serialPort);
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

    private void startReader(SerialPort port, SerialPortTransport transport) {
        readerThread = new Thread(() -> readLoop(port, transport), "imet-j1-reader");
        readerThread.setDaemon(true);
        readerThread.start();
        Console.log("J1 reader started");
    }

    /** Stops the reader thread; it must not be reading when the port closes. */
    private void stopReader() {
        Thread reader = readerThread;
        readerThread = null;
        if (reader == null) {
            return;
        }
        reader.interrupt();
        try {
            reader.join(READER_STOP_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (reader.isAlive()) {
            Console.log("J1 reader did not stop within "
                    + READER_STOP_TIMEOUT_MS + " ms");
        }
    }

    /** Stops the reader, ends the plugin's transport, and closes the port. */
    private void release(SerialPort port) {
        stopReader();
        if (transport != null) {
            transport.close();
            transport = null;
        }
        closeQuietly(port);
    }

    /**
     * Reader thread body: polls the port and passes each chunk to the
     * plugin's transport and the receive listeners. Runs until interrupted
     * by {@link #stopReader} or a serial error.
     */
    private void readLoop(SerialPort port, SerialPortTransport transport) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                int available = port.getInputBufferBytesCount();
                if (available <= 0) {
                    Thread.sleep(POLL_INTERVAL_MS);
                    continue;
                }
                byte[] chunk = port.readBytes(available);

                transport.receive(chunk);
                for (Consumer<byte[]> listener : receiveListeners) {
                    try {
                        listener.accept(chunk);
                    } catch (RuntimeException e) {
                        Console.log("J1 receive listener failed: " + e);
                    }
                }
            }
        } catch (InterruptedException e) {
            // stopReader() asked us to stop.
        } catch (SerialPortException e) {
            Console.log("J1 reader stopped: " + e.getExceptionType());
            return;
        }
        Console.log("J1 reader stopped");
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