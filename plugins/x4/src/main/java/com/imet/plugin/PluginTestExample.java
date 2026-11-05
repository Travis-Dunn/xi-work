package com.imet.plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class PluginTestExample implements PluginTest {
    /* How long to wait for the reply to /ACK? before giving up. */
    private static final long ACK_TIMEOUT_MS = 500;

    /* How long to wait for the reply to a query before giving up. */
    private static final long REPLY_TIMEOUT_MS = 500;

    /* Sensor IDs used with /CJ4-/CJ9 (manual 4.3). */
    private static final int SENSOR_NONE = 0;
    private static final int SENSOR_TEMPERATURE = 3;
    private static final int SENSOR_HUMIDITY = 4;
    private static final int SENSOR_SERIAL_FORWARD = 6;

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

    /* Query. arg is the lexicon message name, e.g. "serialNumber". Polls
       the setting (e.g. /SRN?) and returns its value (the text after
       "SRN="). */
    @Override
    public synchronized byte[] messageType_q(byte[] arg) {
        String name = new String(arg, StandardCharsets.US_ASCII);
        String key = settingFor(name);
        if (key == null) {
            log("query [" + name + "] is not supported");
            return null;
        }

        String value = poll(key, name);
        if (value != null && key.equals("TDP")) {
            /* The lexicon reports TDP as 1/0; the device says ON/OFF. */
            value = value.equalsIgnoreCase("ON") ? "1"
                    : value.equalsIgnoreCase("OFF") ? "0" : null;
        }
        return ascii(value);
    }

    /* Command. arg is "<lexicon message name>=<value>", e.g.
       "serialNumber=1234". Sends the assignment (e.g. /SRN=1234). The X4
       doesn't reply to assignments, so there is nothing to wait for. */
    @Override
    public synchronized void messageType_c(byte[] arg) {
        String argstr = new String(arg, StandardCharsets.US_ASCII);
        int eq = argstr.indexOf('=');
        String name = (eq >= 0) ? argstr.substring(0, eq) : argstr;
        String value = (eq >= 0) ? argstr.substring(eq + 1) : "";

        String key = settingFor(name);
        if (key == null) {
            log("command [" + name + "] is not supported");
            return;
        }
        if (key.equals("TDP")) {
            /* The lexicon takes 1/0; the device takes ON/OFF. */
            value = value.equals("1") ? "ON" : value.equals("0") ? "OFF" : null;
            if (value == null) {
                log("command [" + argstr + "] needs 1 or 0");
                return;
            }
        }
        if (!isConnected()) {
            log("command [" + name + "] needs a J1 connection");
            return;
        }

        try {
            transport.write(("/" + key + "=" + value + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            log("command [" + name + "] failed: " + e.getMessage());
        }
    }

    /* Parameterized query. arg is "<lexicon message name>=<argument>".
       Supports the configurePortTo... messages, whose argument is a port
       number 4-9. Each polls /CJ#? and answers for its own sensor:
         configurePortToEmpty          "1" if the port has no sensor, else "0"
         configurePortToAirThermistor  serial number and the four
                                       coefficients, if set to ID 3
         configurePortToHYT271         I2C address, if set to ID 4
         configurePortToSerialForward  baud rate and header columns, if
                                       set to ID 6
       The last three return an empty result if the port is set to a
       different sensor. The trailing bitmask is not returned; the lexicon
       doesn't list it. */
    @Override
    public synchronized byte[] messageType_p(byte[] arg) {
        String argstr = new String(arg, StandardCharsets.US_ASCII);
        int eq = argstr.indexOf('=');
        String name = (eq >= 0) ? argstr.substring(0, eq) : argstr;
        String argument = (eq >= 0) ? argstr.substring(eq + 1).trim() : "";

        int wantedId;
        int valueCount;
        switch (name) {
            case "configurePortToEmpty":
                wantedId = SENSOR_NONE;
                valueCount = 0;
                break;
            case "configurePortToAirThermistor":
                wantedId = SENSOR_TEMPERATURE;
                valueCount = 5;
                break;
            case "configurePortToHYT271":
                wantedId = SENSOR_HUMIDITY;
                valueCount = 1;
                break;
            case "configurePortToSerialForward":
                wantedId = SENSOR_SERIAL_FORWARD;
                valueCount = 2;
                break;
            default:
                log("parameterized query [" + name + "] is not supported");
                return null;
        }

        int port;
        try {
            port = Integer.parseInt(argument);
        } catch (NumberFormatException e) {
            port = -1;
        }
        if (port < 4 || port > 9) {
            log("parameterized query [" + argstr + "] needs a port number 4-9");
            return null;
        }

        /* The reply is the sensor ID followed by its settings, e.g.
           "4,0x28,0" (manual 3.3.4, 4.3). */
        String config = poll("CJ" + port, argstr);
        if (config == null) {
            return null;
        }
        String[] parts = config.split(",");
        int id;
        try {
            id = Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException e) {
            log("parameterized query [" + argstr + "] got an unreadable reply: " + config);
            return null;
        }

        if (wantedId == SENSOR_NONE) {
            return ascii(id == SENSOR_NONE ? "1" : "0");
        }
        if (id != wantedId) {
            return new byte[0];
        }
        if (parts.length < 1 + valueCount) {
            log("parameterized query [" + argstr + "] got too few values: " + config);
            return null;
        }
        StringBuilder values = new StringBuilder();
        for (int i = 1; i <= valueCount; i++) {
            if (i > 1) {
                values.append(',');
            }
            values.append(parts[i].trim());
        }
        return ascii(values.toString());
    }

    /* Maps a lexicon message to the X4 setting it polls and assigns
       (manual 3.3), or null if this plugin doesn't support it. */
    private static String settingFor(String messageName) {
        switch (messageName) {
            case "serialNumber":
                return "SRN";
            case "dataPublishingFrequency":
                return "DPF";
            case "pressureBias":
                return "CPB";
            case "toggleDataPublishing":
                return "TDP";
            case "j3BaudRate":
                return "CJ3";
            default:
                return null;
        }
    }

    /* Sends /KEY? and returns the reply's value (the text after "KEY="),
       skipping streamed data lines. Returns null if there is no
       connection or no reply arrives in time; what is logged names the
       message being answered. */
    private String poll(String key, String message) {
        if (!isConnected()) {
            log("[" + message + "] needs a J1 connection");
            return null;
        }
        try {
            transport.flush();
            transport.write(("/" + key + "?\r\n").getBytes(StandardCharsets.US_ASCII));
            String line = readLineWithPrefix(key + "=", REPLY_TIMEOUT_MS);
            return (line == null) ? null : line.substring(key.length() + 1);
        } catch (IOException e) {
            log("[" + message + "] failed: " + e.getMessage());
            return null;
        }
    }

    private boolean isConnected() {
        return transport != null && transport.isConnected();
    }

    private static byte[] ascii(String s) {
        return (s == null) ? null : s.getBytes(StandardCharsets.US_ASCII);
    }

    private static void log(String message) {
        System.out.println("X4 plugin: " + message);
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
