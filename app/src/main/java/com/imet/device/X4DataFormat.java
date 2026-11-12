package com.imet.device;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The layout of the X4's data lines, worked out from its settings, and
 * the parser that splits a line into the values the Data screen shows
 * (SoW #27–#32). The rules are written up in
 * {@code docs/x4-data-format.md}.
 * <p>
 * A data line is the board's columns followed by the columns of whatever
 * J4, J5, J6, J7, J8 and J9 are configured for, in that order (X4 manual
 * section 4.1). Which columns are present depends on the board's bitmask
 * ({@code /CBD}) and each port's sensor ({@code /CJ#}), so nothing can be
 * placed until those have been read (#27).
 * <p>
 * Values are never changed or reformatted: what the device sent is what
 * comes out (#31).
 */
public final class X4DataFormat {

    /** Board data columns before any are masked out (manual section 4.2). */
    public static final int BOARD_COLUMNS = 20;

    /** Index of the board's Uptime column, used to tell a header from data. */
    private static final int UPTIME = 3;

    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(\\.\\d+)?");

    /**
     * One data line, split up.
     *
     * @param board the board values, indexed like the board columns in
     *              manual section 4.2; {@code null} where the device is
     *              configured to leave the column out
     * @param ports the values of each port that has a sensor with columns,
     *              keyed by port number, in the sensor's column order
     */
    public record Line(String[] board, Map<Integer, String[]> ports) { }

    private final String problem;
    private final String warning;
    private final String delimiter;

    /** Board column index of each board value on the line, in line order. */
    private final int[] boardColumns;

    /** Column count of each readable port, in line order. */
    private final Map<Integer, Integer> portColumns;

    /** Columns on a line, or the least there can be if {@link #openEnded}. */
    private final int columnCount;

    /** True if a port's column count is unknown, so later columns can't be placed. */
    private final boolean openEnded;

    private X4DataFormat(String problem, String warning, String delimiter,
                         int[] boardColumns, Map<Integer, Integer> portColumns,
                         boolean openEnded) {
        this.problem = problem;
        this.warning = warning;
        this.delimiter = delimiter;
        this.boardColumns = boardColumns;
        this.portColumns = portColumns;
        this.openEnded = openEnded;
        int count = boardColumns.length;
        for (int columns : portColumns.values()) {
            count += columns;
        }
        this.columnCount = count;
    }

    private static X4DataFormat unusable(String problem) {
        return new X4DataFormat(problem, null, ",", new int[0], Map.of(), false);
    }

    /**
     * Works out the data line layout from the settings read at connect.
     * Always returns a format; check {@link #problem()} to see whether it
     * can parse anything.
     */
    public static X4DataFormat from(X4Settings settings) {
        if (!settings.isComplete()) {
            return unusable("Could not read: " + String.join(", ", settings.missing()));
        }

        // /CBD is "<format char><delimiter char><bitmask>", e.g. "0,30720".
        String cbd = settings.boardDataFormat();
        int mask;
        try {
            mask = Integer.parseInt(cbd.substring(2).trim());
        } catch (RuntimeException e) {
            return unusable("The X4's board data format (" + cbd + ") is not understood");
        }
        if (mask < 0 || mask >= (1 << BOARD_COLUMNS)) {
            return unusable("The X4's board data format (" + cbd + ") is not understood");
        }
        String delimiter = cbd.substring(1, 2);

        // Index 0 is the most significant bit; a 1 bit removes the column
        // (manual section 3.3.1).
        int[] kept = new int[BOARD_COLUMNS - Integer.bitCount(mask)];
        int next = 0;
        for (int i = 0; i < BOARD_COLUMNS; i++) {
            if ((mask >> (BOARD_COLUMNS - 1 - i) & 1) == 0) {
                kept[next++] = i;
            }
        }

        Map<Integer, Integer> portColumns = new LinkedHashMap<>();
        for (int port = X4Settings.FIRST_PORT; port <= X4Settings.LAST_PORT; port++) {
            int columns = columnsOf(settings.port(port));
            if (columns < 0) {
                // Everything from this port on can't be located.
                return new X4DataFormat(null,
                        "J" + port + " is set to a sensor iMet-XI doesn't know, so J"
                                + port + " and later ports can't be shown",
                        delimiter, kept, portColumns, true);
            }
            portColumns.put(port, columns);
        }
        return new X4DataFormat(null, null, delimiter, kept, portColumns, false);
    }

    /**
     * @return how many columns a port adds to the line, or -1 if that
     *         isn't known. A sensor's own bitmask is taken to be 0 (all
     *         columns), which is all iMet-XI writes (SoW #16).
     */
    private static int columnsOf(X4Settings.Port port) {
        switch (port.sensorId()) {
            case X4Settings.SENSOR_NONE:
                return 0;
            case X4Settings.SENSOR_TEMPERATURE: // serial number, temperature
            case X4Settings.SENSOR_HUMIDITY:    // humidity, humidity temperature
                return 2;
            case X4Settings.SENSOR_SERIAL_FORWARD:
                // Values are "<baud>,<columns>" (manual section 4.3.4).
                String[] values = port.values().split(",");
                try {
                    int columns = Integer.parseInt(values[1].trim());
                    return (columns >= 1 && columns <= 10) ? columns : -1;
                } catch (RuntimeException e) {
                    return -1;
                }
            default:
                return -1;
        }
    }

    /**
     * Why no line can be parsed, to show the user; {@code null} if the
     * format is usable.
     */
    public String problem() {
        return problem;
    }

    /**
     * Something the user should know about a usable format (part of the
     * line can't be shown); {@code null} if there is nothing.
     */
    public String warning() {
        return warning;
    }

    /** Number of columns a data line has (the least, if a port is unknown). */
    public int columnCount() {
        return columnCount;
    }

    /** Number of columns in {@code line}, counted the way {@link #parse} does. */
    public int countColumns(String line) {
        return line.split(Pattern.quote(delimiter), -1).length;
    }

    /**
     * Splits one line from J1.
     *
     * @param line a line without its line ending
     * @return the values, or {@code null} if this is not a data line in
     *         this format: a reply to a command, a header (#32), a partial
     *         line, or a line from a device whose settings have changed
     */
    public Line parse(String line) {
        if (problem != null) {
            return null;
        }
        // -1 keeps trailing empty values, which #31 says to show.
        String[] values = line.split(Pattern.quote(delimiter), -1);
        if (openEnded ? values.length < columnCount : values.length != columnCount) {
            return null;
        }
        if (isHeader(values)) {
            return null;
        }

        String[] board = new String[BOARD_COLUMNS];
        int next = 0;
        for (int column : boardColumns) {
            board[column] = values[next++];
        }

        Map<Integer, String[]> ports = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> port : portColumns.entrySet()) {
            String[] portValues = new String[port.getValue()];
            for (int i = 0; i < portValues.length; i++) {
                portValues[i] = values[next++];
            }
            if (portValues.length > 0) {
                ports.put(port.getKey(), portValues);
            }
        }
        return new Line(board, ports);
    }

    /**
     * Tells the two header lines (manual Figure 4-1) from data (#32). The
     * title line is a name followed by nothing but delimiters; the
     * column-name line has a name where a data line has the uptime, which
     * is always a number.
     * <p>
     * A real X4 (firmware as of 2026-11) does not send the header on J1 at
     * all, so this is a precaution.
     */
    private boolean isHeader(String[] values) {
        if (values.length > 1) {
            boolean restEmpty = true;
            for (int i = 1; i < values.length; i++) {
                if (!values[i].isEmpty()) {
                    restEmpty = false;
                    break;
                }
            }
            if (restEmpty) {
                return true;
            }
        }
        for (int i = 0; i < boardColumns.length; i++) {
            if (boardColumns[i] == UPTIME) {
                String uptime = values[i].trim();
                return !uptime.isEmpty() && !NUMBER.matcher(uptime).matches();
            }
        }
        return false;
    }
}
