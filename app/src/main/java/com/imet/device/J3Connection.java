package com.imet.device;

import com.imet.Console;
import jssc.SerialPort;
import jssc.SerialPortException;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Manages the serial connection to an iMet-X4 Hub over J3.
 * <p>
 * J3 is a UART TTL data-output port; unlike J1 it does not require an
 * ACK handshake.  The caller supplies the baud rate (queried from the
 * device via {@code /CJ3?\r\n} over J1 before this connection is opened).
 * The port is always opened at 8-N-1 with no flow control, matching the
 * fixed configuration described in the X4 User Guide §3.2.3.
 * <p>
 * Once the port is open, a reader thread passes every received chunk to
 * the listeners added with {@link #addReceiveListener} (the Data screen's
 * J3 terminal, SoW #34). Nothing is ever sent on J3.
 * <p>
 * Call {@link #disconnect()} or close the instance (it implements
 * {@link AutoCloseable}) to release the port.
 */
public class J3Connection implements AutoCloseable {

    private static final int DATA_BITS    = SerialPort.DATABITS_8;
    private static final int STOP_BITS    = SerialPort.STOPBITS_1;
    private static final int PARITY       = SerialPort.PARITY_NONE;
    private static final int FLOW_CONTROL = SerialPort.FLOWCONTROL_NONE;

    /** Time to let the FTDI chip settle after opening the port. */
    private static final int POST_OPEN_DELAY_MS = 50;

    /** Polling interval of the reader thread while no bytes are waiting. */
    private static final int POLL_INTERVAL_MS = 20;

    /** How long {@link #disconnect} waits for the reader thread to stop. */
    private static final int READER_STOP_TIMEOUT_MS = 500;

    private SerialPort serialPort;

    /** The baud rate the port was opened at; 0 when disconnected. */
    private int baudRate;

    /** Reads J3 while connected; null when disconnected. */
    private Thread readerThread;

    /** Called on the reader thread with each chunk of bytes received. */
    private final List<Consumer<byte[]>> receiveListeners = new CopyOnWriteArrayList<>();

    /**
     * Opens the specified serial port at the given baud rate with 8-N-1
     * settings and no flow control.
     *
     * @param portName system port name, e.g. "COM8" or "/dev/ttyUSB1"
     * @param baudRate baud rate for J3; acceptable values per §3.3.3 are
     *                 9600, 19200, 38400, 57600, and 115200
     * @throws IOException if the port cannot be opened or the thread is
     *                     interrupted while settling
     */
    public void connect(String portName, int baudRate) throws IOException {
        if (serialPort != null && serialPort.isOpened()) {
            throw new IOException("Already connected to " + serialPort.getPortName()
                    + ". Disconnect first.");
        }

        Console.log("J3 opening " + portName + " at " + baudRate + " 8-N-1");
        SerialPort port = new SerialPort(portName);
        try {
            port.openPort();
            port.setParams(baudRate, DATA_BITS, STOP_BITS, PARITY);
            port.setFlowControlMode(FLOW_CONTROL);

            Thread.sleep(POST_OPEN_DELAY_MS);

            // Port is open and settled — keep it.
            this.serialPort = port;
            this.baudRate = baudRate;
            Console.log("J3 port open");

            readerThread = new Thread(() -> readLoop(port), "imet-j3-reader");
            readerThread.setDaemon(true);
            readerThread.start();
            Console.log("J3 reader started");

        } catch (SerialPortException e) {
            Console.log("J3 open failed: " + e.getExceptionType());
            closeQuietly(port);
            throw new IOException("Failed to open " + portName + ": "
                    + e.getExceptionType(), e);

        } catch (InterruptedException e) {
            closeQuietly(port);
            Thread.currentThread().interrupt();
            throw new IOException("Connection interrupted.", e);
        }
    }

    /**
     * Registers a listener for everything received on J3, exactly as it
     * arrives. It is called on the reader thread, so a Swing listener must
     * hand the bytes to the EDT itself.
     */
    public void addReceiveListener(Consumer<byte[]> listener) {
        receiveListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeReceiveListener(Consumer<byte[]> listener) {
        receiveListeners.remove(listener);
    }

    /** Closes the serial port if open. Safe to call multiple times. */
    public void disconnect() {
        if (serialPort != null) {
            Console.log("J3 closing " + serialPort.getPortName());
            // The reader must not be reading when the port closes.
            stopReader();
            closeQuietly(serialPort);
            serialPort = null;
            baudRate = 0;
        }
    }

    /** Returns the baud rate the port is open at, or 0 if it isn't open. */
    public int getBaudRate() {
        return baudRate;
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
            Console.log("J3 reader did not stop within "
                    + READER_STOP_TIMEOUT_MS + " ms");
        }
    }

    /**
     * Reader thread body: polls the port and passes each chunk to the
     * receive listeners. Runs until interrupted by {@link #stopReader} or
     * a serial error. Same shape as X4Connection's J1 reader.
     */
    private void readLoop(SerialPort port) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                int available = port.getInputBufferBytesCount();
                if (available <= 0) {
                    Thread.sleep(POLL_INTERVAL_MS);
                    continue;
                }
                byte[] chunk = port.readBytes(available);
                for (Consumer<byte[]> listener : receiveListeners) {
                    try {
                        listener.accept(chunk);
                    } catch (RuntimeException e) {
                        Console.log("J3 receive listener failed: " + e);
                    }
                }
            }
        } catch (InterruptedException e) {
            // stopReader() asked us to stop.
        } catch (SerialPortException e) {
            Console.log("J3 reader stopped: " + e.getExceptionType());
            return;
        }
        Console.log("J3 reader stopped");
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