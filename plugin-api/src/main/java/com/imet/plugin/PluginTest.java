package com.imet.plugin;

import java.io.IOException;

/* Message calls (messageType_q/_c/_p) take and return ASCII text. A
   message is named as in the plugin's lexicon, and its values take the
   form the lexicon's messageTokens describe. A number keeps the device's
   own formatting, and several values are separated by commas. The calls
   never throw: a failure (unsupported message, no connection, no reply
   in time, bad argument) returns null, or for a command, sends nothing. */
public interface PluginTest {
    /* Query. arg is the message name, e.g. "serialNumber". Returns the
       value, or null on failure. */
    byte[] messageType_q(byte[] arg);

    /* Command. arg is "<message name>=<value>". */
    void messageType_c(byte[] arg);

    /* Parameterized query. arg is "<message name>=<argument>", e.g.
       "configurePortToHYT271=4". Returns the value, or null on failure.
       For a "configurePortTo..." message, an empty result means the port
       is not configured for that sensor. */
    byte[] messageType_p(byte[] arg);

    /* Called by the app after it opens the device's port. */
    void setTransport(PluginTransport transport);

    /* Called right after setTransport. Device-specific handshake goes here;
       throw if the device doesn't respond as expected. */
    void startComm() throws IOException;

    /* Called before the app closes the port. Must not throw. */
    void stopComm();
}
