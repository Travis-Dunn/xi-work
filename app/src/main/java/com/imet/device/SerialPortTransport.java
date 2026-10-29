package com.imet.device;

import com.imet.plugin.PluginTransport;
import jssc.SerialPort;
import jssc.SerialPortException;

import java.io.IOException;

/**
 * Exposes an already-open jSSC {@link SerialPort} to a plugin as a
 * {@link PluginTransport}.
 * <p>
 * This class never opens or closes the port. The owner (e.g.
 * {@link X4Connection}) does that, and stops handing this object to the
 * plugin once the port is closed.
 */
class SerialPortTransport implements PluginTransport {

    /** Polling interval while waiting for bytes to arrive. */
    private static final int POLL_INTERVAL_MS = 20;

    private final SerialPort port;

    SerialPortTransport(SerialPort port) {
        this.port = port;
    }

    @Override
    public int write(byte[] data) throws IOException {
        try {
            port.writeBytes(data);
            return data.length;
        } catch (SerialPortException e) {
            throw new IOException("Serial error: " + e.getExceptionType(), e);
        }
    }

    @Override
    public byte[] read(int maxBytes, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (true) {
                int available = port.getInputBufferBytesCount();
                if (available > 0) {
                    return port.readBytes(Math.min(available, maxBytes));
                }
                if (System.currentTimeMillis() >= deadline) {
                    return new byte[0];
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
        } catch (SerialPortException e) {
            throw new IOException("Serial error: " + e.getExceptionType(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Read interrupted.", e);
        }
    }

    @Override
    public int available() {
        try {
            return port.getInputBufferBytesCount();
        } catch (SerialPortException e) {
            return 0;
        }
    }

    @Override
    public void flush() throws IOException {
        try {
            int available = port.getInputBufferBytesCount();
            if (available > 0) {
                port.readBytes(available);
            }
        } catch (SerialPortException e) {
            throw new IOException("Serial error: " + e.getExceptionType(), e);
        }
    }

    @Override
    public boolean isConnected() {
        return port.isOpened();
    }
}
