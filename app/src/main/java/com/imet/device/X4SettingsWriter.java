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
 * <p>The X4 doesn't reply to an assignment, so the only way to know a
 * setting was taken is to read it back; {@link #differences} does the
 * comparison.
 *
 * <p>Lexicon commands used (the X4 command each sends in brackets):
 * <pre>
 *   serialNumber (/SRN=)          dataPublishingFrequency (/DPF=)
 *   pressureBias (/CPB=)          j3BaudRate (/CJ3=)
 *   resetBoardData (/CBD=0,0)     configurePortTo... (/CJ4= - /CJ9=)
 *   toggleDataPublishing (/TDP=)
 * </pre>
 */
final class X4SettingsWriter {

    /** Pause after each command, so the X4 isn't sent a burst. */
    private static final int COMMAND_GAP_MS = 150;

    /**
     * How many times to ask the X4 for an ACK after the last command
     * before reading the settings back. A real X4 publishes nothing for
     * two to three seconds after its data format changes (measured
     * 2026-11-12), and each try waits the plugin's 2 s timeout.
     */
    private static final int ACK_TRIES = 5;

    private X4SettingsWriter() { }

    /**
     * Sends every setting in {@code wanted}, then waits until the device
     * answers an ACK again. A port that {@code wanted} has no entry for
     * is not written.
     */
    static void write(PluginTest plugin, X4Settings wanted) {
        command(plugin, "serialNumber=" + wanted.serialNumber());
        command(plugin, "dataPublishingFrequency=" + wanted.dataPublishingFrequency());
        command(plugin, "pressureBias=" + wanted.pressureBias());
        command(plugin, "j3BaudRate=" + wanted.j3BaudRate());
        command(plugin, "resetBoardData");

        for (int port = X4Settings.FIRST_PORT; port <= X4Settings.LAST_PORT; port++) {
            X4Settings.Port setting = wanted.port(port);
            if (setting == null) {
                continue;
            }
            switch (setting.sensorId()) {
                case X4Settings.SENSOR_NONE ->
                        command(plugin, "configurePortToEmpty=" + port);
                case X4Settings.SENSOR_TEMPERATURE ->
                        command(plugin, "configurePortToAirThermistor=" + port
                                + "," + setting.values());
                case X4Settings.SENSOR_HUMIDITY ->
                        command(plugin, "configurePortToHYT271=" + port
                                + "," + setting.values());
                case X4Settings.SENSOR_SERIAL_FORWARD ->
                        command(plugin, "configurePortToSerialForward=" + port
                                + "," + setting.values());
                default -> Console.log("J" + port + " not written: no command for sensor "
                        + setting.sensorId());
            }
        }

        // Last, so data starts (or stops) in the new format.
        command(plugin, "toggleDataPublishing=" + (wanted.dataPublishing() ? "1" : "0"));

        for (int attempt = 1; attempt <= ACK_TRIES; attempt++) {
            byte[] ack = plugin.messageType_q(bytes("acknowledgeDevice"));
            if (ack != null && ack.length == 1 && ack[0] == '1') {
                return;
            }
            Console.log("X4 not answering after the configuration (try " + attempt + ")");
        }
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
            if (got == null || got.sensorId() != asked.sensorId()
                    || !sameValues(asked.values(), got.values())) {
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

    private static void command(PluginTest plugin, String command) {
        plugin.messageType_c(bytes(command));
        try {
            Thread.sleep(COMMAND_GAP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
