package com.imet.device;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The text file the Configuration screen saves and loads (SoW #24, #25).
 *
 * <p>One setting per line, as {@code name=value}. Blank lines and lines
 * starting with {@code #} are ignored. For example:
 * <pre>
 *   # iMet-X4 configuration, saved by iMet-XI.
 *   fileVersion=1
 *   serialNumber=923812
 *   dataPublishingFrequency=1
 *   pressureBias=28.0389
 *   j3BaudRate=115200
 *   dataPublishing=ON
 *   J9=temperature,94205,+8.95E-004,+2.95E-004,-3.33E-006,+2.57E-007
 *   J8=none
 *   J5=humidity,0x28
 * </pre>
 * A temperature sensor's values are its AT serial number and TCal1 to
 * TCal4; a humidity sensor's is its I2C address.
 *
 * <p>A setting may be left out. Loading such a file leaves that setting
 * as it is on screen, so a file with the {@code serialNumber} and
 * {@code pressureBias} lines deleted can be given to someone else without
 * replacing theirs. The board data format is not in the file: it is
 * always all columns, comma delimited (SoW #16).
 *
 * <p>The names are this file's own, not the X4's commands: turning
 * settings into commands is the plugin's job.
 */
public final class X4SettingsFile {

    /** Raised when a file is not a configuration this version can load. */
    public static final class FormatException extends Exception {
        FormatException(String message) {
            super(message);
        }
    }

    private static final String VERSION = "1";

    private static final String KEY_VERSION   = "fileVersion";
    private static final String KEY_SERIAL    = "serialNumber";
    private static final String KEY_FREQUENCY = "dataPublishingFrequency";
    private static final String KEY_BIAS      = "pressureBias";
    private static final String KEY_J3_BAUD   = "j3BaudRate";
    private static final String KEY_DATA      = "dataPublishing";

    private static final String NONE        = "none";
    private static final String TEMPERATURE = "temperature";
    private static final String HUMIDITY    = "humidity";

    /** /CJ3 values (X4 manual section 3.3.3). */
    private static final List<String> BAUD_RATES =
            List.of("9600", "19200", "38400", "57600", "115200");

    private X4SettingsFile() { }

    /** Writes the settings to {@code file}, replacing any file there. */
    public static void save(X4Settings settings, Path file) throws IOException {
        Files.writeString(file, format(settings), StandardCharsets.UTF_8);
    }

    /**
     * Reads the settings in {@code file}.
     *
     * @return the settings; one the file leaves out is {@code null}, and
     *         a port it leaves out has no entry
     * @throws FormatException if a line can't be understood; the message
     *                         says which and why
     */
    public static X4Settings load(Path file) throws IOException, FormatException {
        return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    /** The file's text for the given settings. */
    static String format(X4Settings settings) {
        String eol = System.lineSeparator();
        StringBuilder text = new StringBuilder();
        text.append("# iMet-X4 configuration, saved by iMet-XI.").append(eol);
        text.append("# Load it with \"Load from File\" on the X4 Configuration screen.").append(eol);
        text.append("# A setting left out of this file is left as it is on screen.").append(eol);
        line(text, KEY_VERSION, VERSION);
        line(text, KEY_SERIAL, settings.serialNumber());
        line(text, KEY_FREQUENCY, settings.dataPublishingFrequency());
        line(text, KEY_BIAS, settings.pressureBias());
        line(text, KEY_J3_BAUD, settings.j3BaudRate());
        if (settings.dataPublishing() != null) {
            line(text, KEY_DATA, settings.dataPublishing() ? "ON" : "OFF");
        }
        // J9 first, as on the screen.
        for (int port = X4Settings.LAST_PORT; port >= X4Settings.FIRST_PORT; port--) {
            X4Settings.Port setting = settings.port(port);
            if (setting == null) {
                continue;
            }
            switch (setting.sensorId()) {
                case X4Settings.SENSOR_NONE ->
                        line(text, "J" + port, NONE);
                case X4Settings.SENSOR_TEMPERATURE ->
                        line(text, "J" + port, TEMPERATURE + "," + setting.values());
                case X4Settings.SENSOR_HUMIDITY ->
                        line(text, "J" + port, HUMIDITY + "," + setting.values());
                default -> { } // nothing this file can describe
            }
        }
        return text.toString();
    }

    private static void line(StringBuilder text, String key, String value) {
        if (value != null) {
            text.append(key).append('=').append(value).append(System.lineSeparator());
        }
    }

    /** Reads the settings from a file's lines; see {@link #load}. */
    static X4Settings parse(List<String> lines) throws FormatException {
        String serial = null;
        String frequency = null;
        String bias = null;
        String baud = null;
        Boolean data = null;
        Map<Integer, X4Settings.Port> ports = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (i == 0 && line.startsWith("﻿")) {
                line = line.substring(1).trim(); // Notepad's byte order mark
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String where = "Line " + (i + 1) + ": ";
            int equals = line.indexOf('=');
            if (equals < 1) {
                throw new FormatException(where + "expected name=value.");
            }
            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            if (!seen.add(key.toLowerCase(Locale.ROOT))) {
                throw new FormatException(where + key + " is given twice.");
            }

            if (key.equalsIgnoreCase(KEY_VERSION)) {
                if (!value.equals(VERSION)) {
                    throw new FormatException(where + "this file is version " + value
                            + "; this iMet-XI reads version " + VERSION + ".");
                }
            } else if (key.equalsIgnoreCase(KEY_SERIAL)) {
                // Letters and digits, 12 at most (manual section 3.3.15)
                if (!value.matches("[A-Za-z0-9]{1,12}")) {
                    throw new FormatException(where + key
                            + " must be 1 to 12 letters and digits.");
                }
                serial = value;
            } else if (key.equalsIgnoreCase(KEY_FREQUENCY)) {
                frequency = number(value, where, key);
            } else if (key.equalsIgnoreCase(KEY_BIAS)) {
                bias = number(value, where, key);
            } else if (key.equalsIgnoreCase(KEY_J3_BAUD)) {
                if (!BAUD_RATES.contains(value)) {
                    throw new FormatException(where + key + " must be one of "
                            + String.join(", ", BAUD_RATES) + ".");
                }
                baud = value;
            } else if (key.equalsIgnoreCase(KEY_DATA)) {
                if (!value.equalsIgnoreCase("ON") && !value.equalsIgnoreCase("OFF")) {
                    throw new FormatException(where + key + " must be ON or OFF.");
                }
                data = value.equalsIgnoreCase("ON");
            } else if (key.matches("[Jj][4-9]")) {
                ports.put(key.charAt(1) - '0', port(value, where, key.toUpperCase(Locale.ROOT)));
            } else {
                throw new FormatException(where + "\"" + key + "\" is not a setting.");
            }
        }

        if (serial == null && frequency == null && bias == null && baud == null
                && data == null && ports.isEmpty()) {
            throw new FormatException("There are no X4 settings in this file.");
        }
        return new X4Settings(serial, frequency, bias, baud, data, "0,0", ports, List.of());
    }

    private static X4Settings.Port port(String value, String where, String name)
            throws FormatException {
        String[] parts = value.split(",", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        String sensor = parts[0].toLowerCase(Locale.ROOT);
        switch (sensor) {
            case NONE:
                if (parts.length != 1) {
                    throw new FormatException(where + name + "=none takes no values.");
                }
                return new X4Settings.Port(X4Settings.SENSOR_NONE, "");
            case TEMPERATURE:
                if (parts.length != 6) {
                    throw new FormatException(where + name + "=temperature needs the AT "
                            + "serial number and four TCal values, separated by commas.");
                }
                // Serial number: unsigned integer (manual section 4.3.2)
                if (!parts[1].matches("\\d{1,10}")) {
                    throw new FormatException(where + name
                            + ": the AT serial number must be a whole number.");
                }
                for (int i = 2; i < 6; i++) {
                    number(parts[i], where, name + ": TCal" + (i - 1));
                }
                // Kept as written, so no digits are lost.
                return new X4Settings.Port(X4Settings.SENSOR_TEMPERATURE,
                        String.join(",", List.of(parts).subList(1, 6)));
            case HUMIDITY:
                if (parts.length != 2 || !parts[1].matches("0[xX][0-9A-Fa-f]{1,2}")) {
                    throw new FormatException(where + name
                            + "=humidity needs an I2C address such as 0x28.");
                }
                return new X4Settings.Port(X4Settings.SENSOR_HUMIDITY, parts[1]);
            default:
                throw new FormatException(where + name
                        + " must be none, temperature or humidity.");
        }
    }

    private static String number(String value, String where, String name)
            throws FormatException {
        try {
            if (!value.isEmpty() && Double.isFinite(Double.parseDouble(value))) {
                return value;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new FormatException(where + name + " must be a number.");
    }
}
