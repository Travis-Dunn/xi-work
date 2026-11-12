package com.imet.device;

import com.imet.Console;
import com.imet.plugin.PluginTest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a configuration to the X4 through the X4 plugin (SoW #26), and
 * compares configurations.
 *
 * <p>The X4 doesn't reply to an assignment, so the only way to know a
 * setting was taken is to read it back; {@link #differences} does the
 * comparison, and {@link X4Connection#configure} drives the
 * write-then-read loop.
 *
 * <p>Only the settings that differ are sent. Each assignment costs the
 * plugin's settle time (about 1.5 s: the X4 loses an assignment that is
 * followed too closely by another command), so sending all twelve every
 * time would take most of a minute.
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

    /* Setting names, as differences() reports them and the screens show them. */
    private static final String BOARD_DATA_FORMAT = "Board data format";
    private static final String SERIAL_NUMBER     = "X4 Serial Number";
    private static final String PRESSURE_BIAS     = "Pressure Bias";
    private static final String J3_BAUD_RATE      = "J3 Baud Rate";
    private static final String FREQUENCY         = "Data Publishing Freq.";
    private static final String DATA              = "Data";

    private X4SettingsWriter() { }

    /**
     * Sends the named settings from {@code wanted}. Each command returns
     * once the X4 has had time to act on it. Never throws.
     *
     * @param names which settings to send, as {@link #differences} names
     *              them
     */
    static void write(PluginTest plugin, X4Settings wanted, List<String> names) {
        // The data format first: the X4 goes quiet for a moment after each
        // of these.
        if (names.contains(BOARD_DATA_FORMAT)) {
            command(plugin, "resetBoardData");
        }
        for (int port = X4Settings.FIRST_PORT; port <= X4Settings.LAST_PORT; port++) {
            X4Settings.Port setting = wanted.port(port);
            if (setting == null || !names.contains("J" + port)) {
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
        if (names.contains(SERIAL_NUMBER)) {
            command(plugin, "serialNumber=" + wanted.serialNumber());
        }
        if (names.contains(PRESSURE_BIAS)) {
            command(plugin, "pressureBias=" + wanted.pressureBias());
        }
        if (names.contains(J3_BAUD_RATE)) {
            command(plugin, "j3BaudRate=" + wanted.j3BaudRate());
        }
        if (names.contains(FREQUENCY)) {
            command(plugin, "dataPublishingFrequency=" + wanted.dataPublishingFrequency());
        }
        // Last, so data starts (or stops) in the new format.
        if (names.contains(DATA)) {
            command(plugin, "toggleDataPublishing=" + (wanted.dataPublishing() ? "1" : "0"));
        }
    }

    private static void command(PluginTest plugin, String command) {
        plugin.messageType_c(bytes(command));
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
            different.add(SERIAL_NUMBER);
        }
        if (!sameNumber(wanted.dataPublishingFrequency(), now.dataPublishingFrequency())) {
            different.add(FREQUENCY);
        }
        if (!sameNumber(wanted.pressureBias(), now.pressureBias())) {
            different.add(PRESSURE_BIAS);
        }
        if (now.j3BaudRate() == null || !now.j3BaudRate().equals(wanted.j3BaudRate())) {
            different.add(J3_BAUD_RATE);
        }
        if (now.dataPublishing() == null
                || !now.dataPublishing().equals(wanted.dataPublishing())) {
            different.add(DATA);
        }
        if (now.boardDataFormat() == null
                || !now.boardDataFormat().equals(wanted.boardDataFormat())) {
            different.add(BOARD_DATA_FORMAT);
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
