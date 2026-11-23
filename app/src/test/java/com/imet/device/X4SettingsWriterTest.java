package com.imet.device;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class X4SettingsWriterTest {

    private static final String COEFFICIENTS =
            "+8.95098529E-004,+2.95526317E-004,-3.33149775E-006,+2.57965989E-007";

    private static X4Settings wanted() {
        Map<Integer, X4Settings.Port> ports = new LinkedHashMap<>();
        ports.put(4, new X4Settings.Port(X4Settings.SENSOR_HUMIDITY, "0x28"));
        ports.put(8, new X4Settings.Port(X4Settings.SENSOR_TEMPERATURE, "78255," + COEFFICIENTS));
        ports.put(9, new X4Settings.Port(X4Settings.SENSOR_NONE, ""));
        // J5, J6 and J7 are not written.
        return X4Settings.toWrite("420", "1", "28.0389", "115200", true, ports);
    }

    /** What a device might report after taking {@link #wanted()}. */
    private static X4Settings device(String dpf, String cbd, Map<Integer, X4Settings.Port> changes) {
        Map<Integer, X4Settings.Port> ports = new LinkedHashMap<>();
        ports.put(4, new X4Settings.Port(X4Settings.SENSOR_HUMIDITY, "0X28"));
        ports.put(5, new X4Settings.Port(X4Settings.SENSOR_UNKNOWN, ""));
        ports.put(6, new X4Settings.Port(X4Settings.SENSOR_SERIAL_FORWARD, "9600,4"));
        ports.put(7, new X4Settings.Port(X4Settings.SENSOR_NONE, ""));
        // The device reformats numbers and keeps about seven digits.
        ports.put(8, new X4Settings.Port(X4Settings.SENSOR_TEMPERATURE,
                "78255,8.950985E-04,2.955263E-04,-3.331498E-06,2.579660E-07"));
        ports.put(9, new X4Settings.Port(X4Settings.SENSOR_NONE, ""));
        ports.putAll(changes);
        return new X4Settings("420", dpf, "28.03890", "115200", true, cbd, ports, List.of());
    }

    @Test
    void reformattedNumbersAreTheSame() {
        assertEquals(List.of(),
                X4SettingsWriter.differences(wanted(), device("1.000000", "0,0", Map.of())));
    }

    @Test
    void settingsNotTakenAreNamed() {
        assertEquals(List.of("Data Publishing Freq.", "Board data format", "J8", "J9"),
                X4SettingsWriter.differences(wanted(), device("5.000000", "0,30720", Map.of(
                        8, new X4Settings.Port(X4Settings.SENSOR_TEMPERATURE,
                                "11111," + COEFFICIENTS),
                        9, new X4Settings.Port(X4Settings.SENSOR_TEMPERATURE,
                                "78255," + COEFFICIENTS)))));
    }

    @Test
    void settingsNotReadBackAreNamed() {
        X4Settings unread = new X4Settings(null, null, null, null, null, null,
                Map.of(), List.of("everything"));
        assertEquals(List.of("X4 Serial Number", "Data Publishing Freq.", "Pressure Bias",
                        "J3 Baud Rate", "Data", "Board data format", "J4", "J8", "J9"),
                X4SettingsWriter.differences(wanted(), unread));
    }

    @Test
    void numbersAreComparedByValue() {
        assertTrue(X4SettingsWriter.sameNumber("1", "1.000000"));
        assertTrue(X4SettingsWriter.sameNumber("0", "0.000000"));
        assertTrue(X4SettingsWriter.sameNumber("-3.33149775E-006", "-3.331498E-06"));
        assertTrue(X4SettingsWriter.sameNumber("0x28", "0X28"));
        assertFalse(X4SettingsWriter.sameNumber("0x28", "0x29"));
        assertFalse(X4SettingsWriter.sameNumber("28.0389", "28.039"));
        assertFalse(X4SettingsWriter.sameNumber("10", "ERR"));
        assertFalse(X4SettingsWriter.sameNumber("1", null));
    }
}
