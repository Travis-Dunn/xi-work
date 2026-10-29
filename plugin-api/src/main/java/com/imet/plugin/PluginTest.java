package com.imet.plugin;

import java.io.IOException;

public interface PluginTest {
    byte[] messageType_q(byte[] arg);

    void messageType_c(byte[] arg);

    /* Called by the app after it opens the device's port. */
    void setTransport(PluginTransport transport);

    /* Called right after setTransport. Device-specific handshake goes here;
       throw if the device doesn't respond as expected. */
    void startComm() throws IOException;

    /* Called before the app closes the port. Must not throw. */
    void stopComm();
}
