package com.imet.device;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class X4SettingsFileTest {

    private static final String COEFFICIENTS =
            "+8.95098529E-004,+2.95526317E-004,-3.33149775E-006,+2.57965989E-007";

    private static X4Settings parse(String... lines) throws Exception {
        return X4SettingsFile.parse(List.of(lines));
    }

    private static String error(String... lines) {
        return assertThrows(X4SettingsFile.FormatException.class, () -> parse(lines))
                .getMessage();
    }

    @Test
    void whatIsSavedLoadsBackTheSame() throws Exception {
        Map<Integer, X4Settings.Port> ports = new LinkedHashMap<>();
        ports.put(4, new X4Settings.Port(X4Settings.SENSOR_HUMIDITY, "0x28"));
        ports.put(8, new X4Settings.Port(X4Settings.SENSOR_TEMPERATURE, "78255," + COEFFICIENTS));
        ports.put(9, new X4Settings.Port(X4Settings.SENSOR_NONE, ""));
        X4Settings saved = X4Settings.toWrite("420", "2.5", "28.0389", "57600", false, ports);

        String text = X4SettingsFile.format(saved);
        X4Settings loaded = X4SettingsFile.parse(text.lines().toList());

        assertEquals("420", loaded.serialNumber());
        assertEquals("2.5", loaded.dataPublishingFrequency());
        assertEquals("28.0389", loaded.pressureBias());
        assertEquals("57600", loaded.j3BaudRate());
        assertEquals(Boolean.FALSE, loaded.dataPublishing());
        assertEquals(ports.get(4), loaded.port(4));
        assertEquals(ports.get(8), loaded.port(8));
        assertEquals(ports.get(9), loaded.port(9));
        // J5, J6 and J7 weren't saved.
        assertNull(loaded.port(5));
        assertTrue(text.contains("J8=temperature,78255," + COEFFICIENTS), text);
    }

    @Test
    void settingsLeftOutAreNull() throws Exception {
        X4Settings loaded = parse(
                "# only the sensors",
                "",
                "fileVersion=1",
                "  j5 = Humidity , 0x29 ",
                "J9=none");
        assertNull(loaded.serialNumber());
        assertNull(loaded.pressureBias());
        assertNull(loaded.dataPublishing());
        assertEquals(new X4Settings.Port(X4Settings.SENSOR_HUMIDITY, "0x29"), loaded.port(5));
        assertEquals(new X4Settings.Port(X4Settings.SENSOR_NONE, ""), loaded.port(9));
    }

    @Test
    void mistakesAreNamedWithTheirLine() {
        assertEquals("Line 2: \"colour\" is not a setting.",
                error("serialNumber=420", "colour=blue"));
        assertEquals("Line 1: expected name=value.", error("hello"));
        assertEquals("Line 2: SerialNumber is given twice.",
                error("serialNumber=420", "SerialNumber=421"));
        assertEquals("Line 1: j3BaudRate must be one of 9600, 19200, 38400, 57600, 115200.",
                error("j3BaudRate=4800"));
        assertEquals("Line 1: dataPublishing must be ON or OFF.", error("dataPublishing=1"));
        assertEquals("Line 1: pressureBias must be a number.", error("pressureBias=high"));
        assertEquals("Line 1: serialNumber must be 1 to 12 letters and digits.",
                error("serialNumber=42 0"));
        assertEquals("Line 1: J9: TCal4 must be a number.",
                error("J9=temperature,78255,1,2,3,"));
        assertEquals("Line 1: J9=temperature needs the AT serial number and four TCal "
                + "values, separated by commas.", error("J9=temperature,78255,1,2,3"));
        assertEquals("Line 1: J4=humidity needs an I2C address such as 0x28.",
                error("J4=humidity,40"));
        assertEquals("Line 1: J6 must be none, temperature or humidity.",
                error("J6=serial,9600,4"));
        assertEquals("Line 1: \"J3\" is not a setting.", error("J3=none"));
    }

    @Test
    void otherFilesAreRefused() {
        assertEquals("There are no X4 settings in this file.", error("# nothing", ""));
        assertEquals("Line 1: this file is version 2; this iMet-XI reads version 1.",
                error("fileVersion=2", "serialNumber=420"));
    }
}
