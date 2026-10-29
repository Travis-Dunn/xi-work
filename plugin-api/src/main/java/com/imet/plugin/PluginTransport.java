package com.imet.plugin;

import java.io.IOException;

/**
 * Byte-level access to a device connection, provided by the app to a plugin.
 * <p>
 * The app owns the connection: it opens and closes the port and chooses the
 * baud rate. The plugin only reads and writes bytes through this interface,
 * so it never needs to know which port it is talking to.
 * <p>
 * Prototype of {@code com.intermet.mycelium.plugin.PluginTransport} from
 * pluginBase, trimmed down: it throws {@link IOException} rather than
 * {@code PluginException}, and omits the timed {@code write}.
 * <p>
 * This contract is shared by the app and plugins through plugin-api.
 * The app supplies the API classes at runtime.
 */
public interface PluginTransport {

    /**
     * Sends bytes to the device.
     *
     * @return number of bytes sent
     * @throws IOException if the write fails
     */
    int write(byte[] data) throws IOException;

    /**
     * Reads whatever bytes are available, waiting up to {@code timeoutMs}
     * for at least one to arrive.
     *
     * @return bytes read (at most {@code maxBytes}), or an empty array on
     *         timeout
     * @throws IOException if the read fails
     */
    byte[] read(int maxBytes, long timeoutMs) throws IOException;

    /** Returns the number of bytes that can be read without waiting. */
    int available();

    /**
     * Discards any bytes already received but not yet read.
     *
     * @throws IOException if the flush fails
     */
    void flush() throws IOException;

    /** Returns {@code true} if the connection is open. */
    boolean isConnected();
}
