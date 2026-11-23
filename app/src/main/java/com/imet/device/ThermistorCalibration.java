package com.imet.device;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A temperature sensor's calibration file from InterMet, e.g.
 * {@code 112804_AT.tcal}: the sensor's serial number on the first line,
 * then its four calibration coefficients, one per line.
 * <pre>
 *   112804
 *   +9.05774591E-004
 *   +2.92117982E-004
 *   -3.02418697E-006
 *   +2.48233806E-007
 * </pre>
 *
 * @param serialNumber the sensor's (AT) serial number
 * @param coefficients the four coefficients, as written in the file so
 *                     that no digits are lost
 */
public record ThermistorCalibration(String serialNumber, List<String> coefficients) {

    /** Raised when a file is not a calibration file. */
    public static final class FormatException extends Exception {
        FormatException(String message) {
            super(message);
        }
    }

    /**
     * Reads a calibration file.
     *
     * @throws FormatException if it isn't one; the message says why
     */
    public static ThermistorCalibration load(Path file) throws IOException, FormatException {
        return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    /** Reads a calibration file's lines; see {@link #load}. */
    static ThermistorCalibration parse(List<String> lines) throws FormatException {
        // Blank lines are skipped: the file ends with one.
        List<String> values = new ArrayList<>();
        for (String line : lines) {
            String value = line.replace("﻿", "").trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        if (values.size() != 5) {
            throw new FormatException("Expected a serial number and four "
                    + "coefficients, one per line, but found " + values.size()
                    + (values.size() == 1 ? " line." : " lines."));
        }
        // Serial number: unsigned integer (X4 manual section 4.3.2)
        if (!values.get(0).matches("\\d{1,10}")) {
            throw new FormatException("The first line must be the sensor's "
                    + "serial number, a whole number.");
        }
        for (int i = 1; i <= 4; i++) {
            try {
                if (Double.isFinite(Double.parseDouble(values.get(i)))) {
                    continue;
                }
            } catch (NumberFormatException e) {
                // reported below
            }
            throw new FormatException("Line " + (i + 1) + " must be a number.");
        }
        return new ThermistorCalibration(values.get(0), List.copyOf(values.subList(1, 5)));
    }
}
