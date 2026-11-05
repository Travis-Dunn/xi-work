package com.imet.plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class PluginTestExample implements PluginTest {
    /* How long to wait for the reply to /ACK? before giving up. */
    private static final long ACK_TIMEOUT_MS = 500;

    /* How long to wait for the reply to a query before giving up. */
    private static final long REPLY_TIMEOUT_MS = 500;

    /* Set by the app once it has opened J1; null when disconnected.
       All access is synchronized, so one exchange with the device runs at
       a time and the transport can't be swapped out mid-exchange. */
    private PluginTransport transport;

    @Override
    public synchronized void setTransport(PluginTransport transport) {
        this.transport = transport;
    }

    /* Verifies the X4 is on the other end of the transport (manual 3.3.10).
       If data publishing is on (TDP=ON), data lines can arrive before the
       ACK, so they are skipped rather than treated as a failed reply. */
    @Override
    public synchronized void startComm() throws IOException {
        if (!isConnected()) {
            throw new IOException("No connection to the device.");
        }

        transport.flush();
        transport.write("/ACK?\r\n".getBytes(StandardCharsets.US_ASCII));

        if (readLineWithPrefix("ACK", ACK_TIMEOUT_MS) == null) {
            throw new IOException("Device did not respond with ACK.");
        }
    }

    @Override
    public synchronized void stopComm() {
        transport = null;
    }

    /* Query. arg is the lexicon message name, e.g. "serialNumber". Sends
       the poll command (e.g. /SRN?) and returns the value from the reply
       (the text after "SRN="), skipping streamed data lines. Returns null
       if the message isn't supported, there is no connection, or no reply
       arrives in time. */
    @Override
    public synchronized byte[] messageType_q(byte[] arg) {
        String name = new String(arg, StandardCharsets.US_ASCII);
        String key = commandFor(name);
        if (key == null) {
            System.out.println("X4 plugin: query [" + name + "] is not supported");
            return null;
        }
        if (!isConnected()) {
            System.out.println("X4 plugin: query [" + name + "] needs a J1 connection");
            return null;
        }

        try {
            transport.flush();
            transport.write(("/" + key + "?\r\n").getBytes(StandardCharsets.US_ASCII));
            String line = readLineWithPrefix(key + "=", REPLY_TIMEOUT_MS);
            if (line == null) {
                return null;
            }
            return line.substring(key.length() + 1).getBytes(StandardCharsets.US_ASCII);
        } catch (IOException e) {
            System.out.println("X4 plugin: query [" + name + "] failed: " + e.getMessage());
            return null;
        }
    }

    /* Command. arg is "<lexicon message name>=<value>", e.g.
       "serialNumber=1234". Sends the assignment (e.g. /SRN=1234). The X4
       doesn't reply to assignments, so there is nothing to wait for. Does
       nothing if the message isn't supported or there is no connection. */
    @Override
    public synchronized void messageType_c(byte[] arg) {
        String argstr = new String(arg, StandardCharsets.US_ASCII);
        int eq = argstr.indexOf('=');
        String name = (eq >= 0) ? argstr.substring(0, eq) : argstr;
        String value = (eq >= 0) ? argstr.substring(eq + 1) : "";

        String key = commandFor(name);
        if (key == null) {
            System.out.println("X4 plugin: command [" + name + "] is not supported");
            return;
        }
        if (!isConnected()) {
            System.out.println("X4 plugin: command [" + name + "] needs a J1 connection");
            return;
        }

        try {
            transport.write(("/" + key + "=" + value + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            System.out.println("X4 plugin: command [" + name + "] failed: " + e.getMessage());
        }
    }

    /* Maps a lexicon message name to its X4 command (manual 3.3), or null
       if this plugin doesn't support it yet. */
    private static String commandFor(String messageName) {
        switch (messageName) {
            case "serialNumber":
                return "SRN";
            default:
                return null;
        }
    }

    private boolean isConnected() {
        return transport != null && transport.isConnected();
    }

    /* Reads lines until one starts with prefix, discarding streamed data
       lines along the way. A leading '/' on the reply is ignored: the
       manual shows poll replies both with and without it. Returns the line
       without the '/', or null if no such line arrives before the timeout. */
    private String readLineWithPrefix(String prefix, long timeoutMs)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;

        long remaining;
        while ((remaining = deadline - System.currentTimeMillis()) > 0) {
            byte[] chunk = transport.read(256, remaining);
            sb.append(new String(chunk, StandardCharsets.US_ASCII));

            int newlineIdx;
            while ((newlineIdx = sb.indexOf("\n")) >= 0) {
                String line = sb.substring(0, newlineIdx).trim();
                sb.delete(0, newlineIdx + 1);

                if (line.startsWith("/")) {
                    line = line.substring(1);
                }
                if (line.startsWith(prefix)) {
                    return line;
                }
            }
        }
        return null;
    }
}
