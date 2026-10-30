package com.imet.device;

import com.imet.Console;
import jssc.SerialPort;
import jssc.SerialPortException;

import java.io.IOException;

/**
 * Manages the serial connection to an iMet-X4 Hub over J3.
 * <p>
 * J3 is a UART TTL data-output port; unlike J1 it does not require an
 * ACK handshake.  The caller supplies the baud rate (queried from the
 * device via {@code /CJ3?\r\n} over J1 before this connection is opened).
 * The port is always opened at 8-N-1 with no flow control, matching the
 * fixed configuration described in the X4 User Guide §3.2.3.
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

    private SerialPort serialPort;

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
            Console.log("J3 port open");

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

    /** Closes the serial port if open. Safe to call multiple times. */
    public void disconnect() {
        if (serialPort != null) {
            Console.log("J3 closing " + serialPort.getPortName());
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