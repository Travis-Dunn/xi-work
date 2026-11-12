package com.imet.device;

import com.imet.Console;
import com.imet.plugin.PluginTest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a configuration to the X4 through the X4 plugin (SoW #26), and
 * checks what the device holds afterwards.
 *
 * <p>The X4 doesn't reply to an assignment, and a real X4 has been seen
 * to ignore one sent in a burst with others (2026-11-12: /DPF=3 sent
 * 150 ms after /SRN= was not taken, twice). So each setting is handled on
 * its own: ask the device what it has, send the assignment only if that
 * differs, ask again, and send again if it still differs. Waiting for the
 * reply to each question also paces the commands to the device.
 *
 * <p>Lexicon commands used (the X4 command each sends in brackets):
 * <pre>
 *   resetBoardData (/CBD=0,0)     configurePortTo... (/CJ4= - /CJ9=)
 *   serialNumber (/SRN=)          pressureBias (/CPB=)
 *   j3BaudRate (/CJ3=)            dataPublishingFrequency (/DPF=)
 *   toggleDataPublishing (/TDP=)
 * </pre>
 */
final class X4SettingsWriter {

    /** How long to give the X4 to act on a command before asking about it. */
    private static final int COMMAND_GAP_MS = 150;

    /** How many times a setting is sent before giving up on it. */
    private static final int SENDS_PER_SETTING = 3;

    /**
     * After this many questions in a row get no reply, stop: the X4 has
     * gone, and each further question would cost the plugin's full
     * timeout.
     */
    private static final int MAX_SILENCES_IN_A_ROW = 3;

    private final PluginTest plugin;
    private int silencesInARow;

    private X4SettingsWriter(PluginTest plugin) {
        this.plugin = plugin;
    }

    /**
     * Brings the device to {@code wanted}, one setting at a time. A port
     * that {@code wanted} has no entry for is not written. Never throws;
     * use {@link #differences} on a fresh read to see what was taken.
     */
    static void write(PluginTest plugin, X4Settings wanted) {
        new X4SettingsWriter(plugin).write(wanted);
    }

    private void write(X4Settings wanted) {
        // The data format first: the X4 goes quiet for a moment after each
        // of these, and nothing else should be waiting on it then.
        set("Board data format", "resetBoardData",
                () -> same(query("boardDataFormat"), wanted.boardDataFormat()));

        for (int port = X4Settings.FIRST_PORT; port <= X4Settings.LAST_PORT; port++) {
            X4Settings.Port setting = wanted.port(port);
            if (setting == null) {
                continue;
            }
            String command;
            switch (setting.sensorId()) {
                case X4Settings.SENSOR_NONE ->
                        command = "configurePortToEmpty=" + port;
                case X4Settings.SENSOR_TEMPERATURE ->
                        command = "configurePortToAirThermistor=" + port
                                + "," + setting.values();
                case X4Settings.SENSOR_HUMIDITY ->
                        command = "configurePortToHYT271=" + port
                                + "," + setting.values();
                case X4Settings.SENSOR_SERIAL_FORWARD ->
                        command = "configurePortToSerialForward=" + port
                                + "," + setting.values();
                default -> {
                    Console.log("J" + port + " not written: no command for sensor "
                            + setting.sensorId());
                    continue;
                }
            }
            final int p = port;
            set("J" + port, command, () -> {
                X4Settings.Port now = X4SettingsReader.readPort(plugin, p);
                return (now == null) ? null : samePort(setting, now);
            });
        }

        set("X4 Serial Number", "serialNumber=" + wanted.serialNumber(),
                () -> same(query("serialNumber"), wanted.serialNumber()));
        set("Pressure Bias", "pressureBias=" + wanted.pressureBias(),
                () -> sameNumberOrNull(query("pressureBias"), wanted.pressureBias()));
        set("J3 Baud Rate", "j3BaudRate=" + wanted.j3BaudRate(),
                () -> same(query("j3BaudRate"), wanted.j3BaudRate()));
        set("Data Publishing Freq.",
                "dataPublishingFrequency=" + wanted.dataPublishingFrequency(),
                () -> sameNumberOrNull(query("dataPublishingFrequency"),
                        wanted.dataPublishingFrequency()));

        // Last, so data starts (or stops) in the new format.
        String tdp = wanted.dataPublishing() ? "1" : "0";
        set("Data", "toggleDataPublishing=" + tdp,
                () -> same(query("toggleDataPublishing"), tdp));
    }

    /** Answers "does the device hold this setting as wanted?", or null for no reply. */
    private interface Check {
        Boolean holds();
    }

    /**
     * Makes one setting hold: sends {@code command} unless the device
     * already has it, and again if the device doesn't have it afterwards.
     */
    private void set(String name, String command, Check check) {
        for (int sends = 0; ; sends++) {
            if (silencesInARow >= MAX_SILENCES_IN_A_ROW) {
                return;
            }
            Boolean holds = check.holds();
            if (holds == null) {
                if (++silencesInARow == MAX_SILENCES_IN_A_ROW) {
                    Console.log("X4 stopped answering; not writing the remaining settings");
                }
            } else {
                silencesInARow = 0;
                if (holds) {
                    if (sends > 1) {
                        Console.log(name + " taken on send " + sends);
                    }
                    return;
                }
            }
            if (sends == SENDS_PER_SETTING) {
                Console.log(name + " not taken after " + sends + " sends");
                return;
            }
            plugin.messageType_c(bytes(command));
            try {
                Thread.sleep(COMMAND_GAP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private String query(String message) {
        byte[] reply = plugin.messageType_q(bytes(message));
        return (reply == null) ? null
                : new String(reply, StandardCharsets.US_ASCII).trim();
    }

    private static Boolean same(String got, String wanted) {
        return (got == null) ? null : got.equals(wanted);
    }

    private static Boolean sameNumberOrNull(String got, String wanted) {
        return (got == null) ? null : sameNumber(wanted, got);
    }

    private static boolean samePort(X4Settings.Port asked, X4Settings.Port got) {
        return got.sensorId() == asked.sensorId()
                && sameValues(asked.values(), got.values());
    }

    /**
     * Compares what was written with what the device now reports.
     *
     * @return the name of each setting the device doesn't hold as asked,
     *         or couldn't be read back
     */
    static List<String> differences(X4Settings wanted, X4Settings now) {
        List<String> different = new ArrayList<>();

        if (now.serialNumber() == null
                || !now.serialNumber().equals(wanted.serialNumber())) {
            different.add("X4 Serial Number");
        }
        if (!sameNumber(wanted.dataPublishingFrequency(), now.dataPublishingFrequency())) {
            different.add("Data Publishing Freq.");
        }
        if (!sameNumber(wanted.pressureBias(), now.pressureBias())) {
            different.add("Pressure Bias");
        }
        if (now.j3BaudRate() == null || !now.j3BaudRate().equals(wanted.j3BaudRate())) {
            different.add("J3 Baud Rate");
        }
        if (now.dataPublishing() == null
                || !now.dataPublishing().equals(wanted.dataPublishing())) {
            different.add("Data");
        }
        if (now.boardDataFormat() == null
                || !now.boardDataFormat().equals(wanted.boardDataFormat())) {
            different.add("Board data format");
        }

        for (int port = X4Settings.FIRST_PORT; port <= X4Settings.LAST_PORT; port++) {
            X4Settings.Port asked = wanted.port(port);
            if (asked == null) {
                continue; // not written
            }
            X4Settings.Port got = now.port(port);
            if (got == null || !samePort(asked, got)) {
                different.add("J" + port);
            }
        }
        return different;
    }

    /** Compares two comma-separated lists of sensor settings. */
    private static boolean sameValues(String asked, String got) {
        String[] a = asked.isEmpty() ? new String[0] : asked.split(",");
        String[] g = got.isEmpty() ? new String[0] : got.split(",");
        if (a.length != g.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!sameNumber(a[i], g[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether two values are the same number. The device reports numbers
     * in its own format (1 comes back as 1.000000) and keeps about seven
     * significant digits, so text can't be compared. Hex values such as an
     * I2C address (0x28) are compared as integers.
     */
    static boolean sameNumber(String asked, String got) {
        if (asked == null || got == null) {
            return false;
        }
        String a = asked.trim();
        String g = got.trim();
        if (a.equalsIgnoreCase(g)) {
            return true;
        }
        try {
            if (isHex(a) || isHex(g)) {
                return isHex(a) && isHex(g)
                        && Long.parseLong(a.substring(2), 16) == Long.parseLong(g.substring(2), 16);
            }
            double x = Double.parseDouble(a);
            double y = Double.parseDouble(g);
            return Math.abs(x - y) <= 1e-6 * Math.max(Math.abs(x), Math.abs(y)) + 1e-12;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isHex(String s) {
        return s.length() > 2 && (s.startsWith("0x") || s.startsWith("0X"));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
