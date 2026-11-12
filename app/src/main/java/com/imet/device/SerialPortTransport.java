package com.imet.device;

import com.imet.Console;
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
 * <p>
 * Writes go straight to the port. Reads do not: the owner's reader thread
 * is the only thing that reads the port, and it passes what it receives to
 * {@link #receive}. This class keeps the most recent
 * {@value #BUFFER_SIZE} bytes for the plugin to read; when the plugin
 * isn't reading, older bytes are dropped.
 */
class SerialPortTransport implements PluginTransport {

    /** Bytes kept for the plugin; at least a few seconds of streamed data. */
    static final int BUFFER_SIZE = 64 * 1024;

    private final SerialPort port;

    /** Prefix for console log lines, e.g. "J1". */
    private final String label;

    /* Ring buffer of received bytes, guarded by this object's monitor. */
    private final byte[] buffer = new byte[BUFFER_SIZE];
    private int start;
    private int count;

    /** Set by {@link #close}; reads fail from then on. */
    private boolean closed;

    SerialPortTransport(SerialPort port, String label) {
        this.port = port;
        this.label = label;
    }

    /**
     * Called by the owner's reader thread with each chunk received. Drops
     * the oldest bytes if the buffer is full.
     */
    synchronized void receive(byte[] data) {
        for (byte b : data) {
            if (count == buffer.length) {
                start = (start + 1) % buffer.length;
                count--;
            }
            buffer[(start + count) % buffer.length] = b;
            count++;
        }
        notifyAll();
    }

    /**
     * Called by the owner before it closes the port. Wakes any read that is
     * waiting, which then fails.
     */
    synchronized void close() {
        closed = true;
        notifyAll();
    }

    @Override
    public int write(byte[] data) throws IOException {
        try {
            Console.log(label + " plugin TX " + Console.escape(data));
            port.writeBytes(data);
            return data.length;
        } catch (SerialPortException e) {
            throw new IOException("Serial error: " + e.getExceptionType(), e);
        }
    }

    @Override
    public synchronized byte[] read(int maxBytes, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (count == 0) {
                if (closed) {
                    throw new IOException("Connection closed.");
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    Console.log(label + " plugin RX nothing within "
                            + timeoutMs + " ms");
                    return new byte[0];
                }
                wait(remaining);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Read interrupted.", e);
        }

        byte[] chunk = new byte[Math.min(count, maxBytes)];
        for (int i = 0; i < chunk.length; i++) {
            chunk[i] = buffer[(start + i) % buffer.length];
        }
        start = (start + chunk.length) % buffer.length;
        count -= chunk.length;

        Console.log(label + " plugin RX " + Console.escape(chunk));
        return chunk;
    }

    @Override
    public synchronized int available() {
        return count;
    }

    @Override
    public synchronized void flush() {
        if (count > 0) {
            // Streamed data can fill the buffer between plugin calls, so
            // log the size; the J1 terminal shows the bytes themselves.
            Console.log(label + " plugin flush discarded " + count + " bytes");
            start = 0;
            count = 0;
        }
    }

    @Override
    public synchronized boolean isConnected() {
        return !closed && port.isOpened();
    }
}
