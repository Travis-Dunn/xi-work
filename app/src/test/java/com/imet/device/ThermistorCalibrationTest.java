package com.imet.device;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ThermistorCalibrationTest {

    private static String error(String... lines) {
        return assertThrows(ThermistorCalibration.FormatException.class,
                () -> ThermistorCalibration.parse(List.of(lines))).getMessage();
    }

    /** The lines of 112804_AT.tcal, a file from InterMet. */
    @Test
    void readsACalibrationFile() throws Exception {
        ThermistorCalibration read = ThermistorCalibration.parse(List.of(
                "112804",
                "+9.05774591E-004",
                "+2.92117982E-004",
                "-3.02418697E-006",
                "+2.48233806E-007",
                ""));
        assertEquals("112804", read.serialNumber());
        // Exactly as written: no digits lost, signs kept.
        assertEquals(List.of("+9.05774591E-004", "+2.92117982E-004",
                "-3.02418697E-006", "+2.48233806E-007"), read.coefficients());
    }

    @Test
    void otherFilesAreRefused() {
        assertEquals("Expected a serial number and four coefficients, one per line, "
                + "but found 4 lines.", error("112804", "1", "2", "3"));
        assertEquals("Expected a serial number and four coefficients, one per line, "
                + "but found 0 lines.", error("", ""));
        assertEquals("The first line must be the sensor's serial number, a whole number.",
                error("+9.05E-004", "1", "2", "3", "4"));
        assertEquals("Line 4 must be a number.",
                error("112804", "1", "2", "three", "4"));
    }
}
