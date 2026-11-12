package com.imet.device;

import com.imet.Console;
import com.imet.plugin.PluginTest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the X4's configuration through the X4 plugin (SoW #14).
 *
 * <p>Lexicon messages used (the X4 command each polls in brackets):
 * <pre>
 *   serialNumber (/SRN?)          dataPublishingFrequency (/DPF?)
 *   pressureBias (/CPB?)          j3BaudRate (/CJ3?)
 *   toggleDataPublishing (/TDP?)  boardDataFormat (/CBD?)
 *   configurePortTo... for ports 4-9 (/CJ4? - /CJ9?)
 * </pre>
 *
 * <p>The plugin skips streamed data lines, so this works with data
 * publishing on.
 */
final class X4SettingsReader {

    /**
     * After this many queries in a row get no reply, the rest are not
     * sent. Each unanswered query costs the plugin's full reply timeout,
     * so without this a device that stops answering would hold up the
     * connect for about a minute.
     */
    private static final int MAX_FAILURES_IN_A_ROW = 2;

    private final PluginTest plugin;
    private final List<String> missing = new ArrayList<>();
    private int failuresInARow;

    private X4SettingsReader(PluginTest plugin) {
        this.plugin = plugin;
    }

    /**
     * Reads every setting. Never throws: a setting with no reply is left
     * out and named in {@link X4Settings#missing()}.
     */
    static X4Settings read(PluginTest plugin) {
        return new X4SettingsReader(plugin).read();
    }

    private X4Settings read() {
        String srn = query("serialNumber", "X4 Serial Number");
        String dpf = query("dataPublishingFrequency", "Data Publishing Freq.");
        String cpb = query("pressureBias", "Pressure Bias");
        String cj3 = query("j3BaudRate", "J3 Baud Rate");

        // The lexicon reports TDP as 1 (ON) or 0 (OFF).
        String tdpText = query("toggleDataPublishing", "Data output");
        Boolean tdp = (tdpText == null) ? null : tdpText.equals("1");

        String cbd = query("boardDataFormat", "Board data format");

        Map<Integer, X4Settings.Port> ports = new LinkedHashMap<>();
        for (int port = X4Settings.FIRST_PORT; port <= X4Settings.LAST_PORT; port++) {
            X4Settings.Port setting = readPort(port);
            if (setting != null) {
                ports.put(port, setting);
            } else {
                missing.add("J" + port);
            }
        }

        if (missing.isEmpty()) {
            Console.log("X4 settings read");
        } else {
            Console.log("X4 settings read, except: " + String.join(", ", missing));
        }
        return new X4Settings(srn, dpf, cpb, cj3, tdp, cbd, ports, missing);
    }

    /** Sends a query; a missing reply is recorded under {@code label}. */
    private String query(String message, String label) {
        if (givenUp()) {
            missing.add(label);
            return null;
        }
        String value = ascii(plugin.messageType_q(bytes(message)));
        countReply(value != null);
        if (value == null) {
            missing.add(label);
            return null;
        }
        return value.trim();
    }

    /**
     * Finds what a port is configured for, with the lexicon's
     * configurePortTo... parameterized queries: first "is it empty?",
     * then each sensor in turn, starting with the one the port is meant
     * for (X4 manual sections 2.3 and 4.3).
     *
     * @return the port's setting, or {@code null} if a query got no reply
     */
    private X4Settings.Port readPort(int port) {
        String empty = portQuery("configurePortToEmpty", port);
        if (empty == null) {
            return null;
        }
        if (empty.equals("1")) {
            return new X4Settings.Port(X4Settings.SENSOR_NONE, "");
        }

        String[] messages;
        int[] sensorIds;
        switch (port) {
            case 4, 5 -> {
                messages = new String[] {"configurePortToHYT271",
                        "configurePortToAirThermistor", "configurePortToSerialForward"};
                sensorIds = new int[] {X4Settings.SENSOR_HUMIDITY,
                        X4Settings.SENSOR_TEMPERATURE, X4Settings.SENSOR_SERIAL_FORWARD};
            }
            case 6, 7 -> {
                messages = new String[] {"configurePortToSerialForward",
                        "configurePortToHYT271", "configurePortToAirThermistor"};
                sensorIds = new int[] {X4Settings.SENSOR_SERIAL_FORWARD,
                        X4Settings.SENSOR_HUMIDITY, X4Settings.SENSOR_TEMPERATURE};
            }
            default -> {
                messages = new String[] {"configurePortToAirThermistor",
                        "configurePortToHYT271", "configurePortToSerialForward"};
                sensorIds = new int[] {X4Settings.SENSOR_TEMPERATURE,
                        X4Settings.SENSOR_HUMIDITY, X4Settings.SENSOR_SERIAL_FORWARD};
            }
        }

        for (int i = 0; i < messages.length; i++) {
            String values = portQuery(messages[i], port);
            if (values == null) {
                return null;
            }
            // An empty result means "set to a different sensor".
            if (!values.isEmpty()) {
                return new X4Settings.Port(sensorIds[i], values);
            }
        }

        Console.log("J" + port + " is set to a sensor the plugin has no message for");
        return new X4Settings.Port(X4Settings.SENSOR_UNKNOWN, "");
    }

    private String portQuery(String message, int port) {
        if (givenUp()) {
            return null;
        }
        String value = ascii(plugin.messageType_p(bytes(message + "=" + port)));
        countReply(value != null);
        return (value == null) ? null : value.trim();
    }

    private boolean givenUp() {
        return failuresInARow >= MAX_FAILURES_IN_A_ROW;
    }

    private void countReply(boolean replied) {
        if (replied) {
            failuresInARow = 0;
        } else if (++failuresInARow == MAX_FAILURES_IN_A_ROW) {
            Console.log("X4 stopped answering; not asking for the remaining settings");
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static String ascii(byte[] b) {
        return (b == null) ? null : new String(b, StandardCharsets.US_ASCII);
    }
}
