package com.imet.device;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The data lines here were captured from a real X4 on 2026-11-12 with
 * tools/CaptureHeader.java (a temperature sensor configured on J8, none
 * plugged in). Only the latitude and longitude have been replaced.
 */
class X4DataFormatTest {

    private static final X4Settings.Port NONE =
            new X4Settings.Port(X4Settings.SENSOR_NONE, "");
    private static final X4Settings.Port TEMPERATURE =
            new X4Settings.Port(X4Settings.SENSOR_TEMPERATURE,
                    "78255,+8.95098529E-004,+2.95526317E-004,-3.33149775E-006,+2.57965989E-007");
    private static final X4Settings.Port HUMIDITY =
            new X4Settings.Port(X4Settings.SENSOR_HUMIDITY, "0x28");

    /** Settings with the given /CBD and ports; ports not named are empty. */
    private static X4Settings settings(String cbd, Map<Integer, X4Settings.Port> named) {
        Map<Integer, X4Settings.Port> ports = new LinkedHashMap<>();
        for (int port = X4Settings.FIRST_PORT; port <= X4Settings.LAST_PORT; port++) {
            ports.put(port, named.getOrDefault(port, NONE));
        }
        return new X4Settings("420", "1.000000", "0", "115200", true, cbd, ports, List.of());
    }

    @Test
    void defaultMaskLeavesOutTheBatteryColumns() {
        X4DataFormat format = X4DataFormat.from(settings("0,30720", Map.of(8, TEMPERATURE)));
        assertNull(format.problem());
        assertEquals(18, format.columnCount());

        X4DataFormat.Line line = format.parse(
                "420,11/12/26,18:3:20,4301.90,100,980.14,28.68,30.40,29.75,6,"
                        + "42.0000000,-85.0000000,3.55,234.6,0.145,9999,78255,9999");
        assertNotNull(line);
        String[] board = line.board();
        assertEquals("420", board[0]);
        assertEquals("11/12/26", board[1]);
        assertEquals("18:3:20", board[2]);       // not zero-padded, shown as sent
        assertEquals("4301.90", board[3]);
        assertEquals("100", board[4]);
        assertNull(board[5]);                    // battery capacity: masked out
        assertNull(board[6]);
        assertNull(board[7]);
        assertNull(board[8]);
        assertEquals("980.14", board[9]);
        assertEquals("28.68", board[10]);
        assertEquals("30.40", board[11]);
        assertEquals("29.75", board[12]);
        assertEquals("6", board[13]);
        assertEquals("42.0000000", board[14]);
        assertEquals("-85.0000000", board[15]);
        assertEquals("3.55", board[16]);
        assertEquals("234.6", board[17]);
        assertEquals("0.145", board[18]);
        assertEquals("9999", board[19]);
        assertEquals(1, line.ports().size());
        assertArrayEquals(new String[] {"78255", "9999"}, line.ports().get(8));
    }

    @Test
    void maskZeroKeepsEveryBoardColumn() {
        X4DataFormat format = X4DataFormat.from(settings("0,0", Map.of(8, TEMPERATURE)));
        assertEquals(22, format.columnCount());

        // No GPS fix, and a time the device sent one digit short.
        X4DataFormat.Line line = format.parse(
                "420,11/12/26,18:3:2,4307.53,100,1860,0,0,3,980.12,28.69,30.40,29.78,0,"
                        + "9999,9999,9999,9999,9999,9999,78255,9999");
        assertNotNull(line);
        assertEquals("18:3:2", line.board()[2]);
        assertEquals("1860", line.board()[5]);
        assertEquals("0", line.board()[6]);
        assertEquals("0", line.board()[7]);
        assertEquals("3", line.board()[8]);
        assertEquals("980.12", line.board()[9]);
        assertEquals("9999", line.board()[14]);
        assertArrayEquals(new String[] {"78255", "9999"}, line.ports().get(8));
    }

    @Test
    void portsFollowTheBoardInPortOrder() {
        X4DataFormat format = X4DataFormat.from(settings("0,1048575",
                Map.of(4, HUMIDITY, 9, TEMPERATURE,
                        6, new X4Settings.Port(X4Settings.SENSOR_SERIAL_FORWARD, "9600,3"))));
        // Every board column masked out: J4's 2, J6's 3, J9's 2.
        assertEquals(7, format.columnCount());
        X4DataFormat.Line line = format.parse("27.21,21.16,a,b,c,78259,21.03");
        assertNotNull(line);
        for (String value : line.board()) {
            assertNull(value);
        }
        assertArrayEquals(new String[] {"27.21", "21.16"}, line.ports().get(4));
        assertArrayEquals(new String[] {"a", "b", "c"}, line.ports().get(6));
        assertArrayEquals(new String[] {"78259", "21.03"}, line.ports().get(9));
    }

    @Test
    void repliesAndOtherFormatsAreNotData() {
        X4DataFormat format = X4DataFormat.from(settings("0,30720", Map.of(8, TEMPERATURE)));
        assertNull(format.parse("ACK"));
        assertNull(format.parse("CBD=0,30720"));
        assertNull(format.parse(""));
        // A line in the mask-0 format, after the device was changed.
        assertNull(format.parse(
                "420,11/12/26,18:3:24,4306.52,100,1860,0,0,3,980.02,28.65,30.40,29.77,6,"
                        + "42.0000000,-85.0000000,3.54,233.8,0.233,9999,78255,9999"));
    }

    @Test
    void headerLinesAreIgnored() {
        X4DataFormat format = X4DataFormat.from(settings("0,30720", Map.of()));
        assertEquals(16, format.columnCount());
        assertNull(format.parse("X4 Board Sensors,,,,,,,,,,,,,,,"));
        assertNull(format.parse("Serial Number,Date (YMD),UTC Time (HMS),Run Time (s),"
                + "Battery Charge (%),Pressure (mB),Pressure Temperature (C),Humidity (%),"
                + "Humidity Temperature (C),Satellites,Latitude,Longitude,HDoP,Altitude,"
                + "Speed,Tracking Angle"));
    }

    @Test
    void emptyValuesAreKept() {
        X4DataFormat format = X4DataFormat.from(settings("0,30720", Map.of()));
        X4DataFormat.Line line = format.parse(
                "420,,18:3:20,4301.90,100,980.14,28.68,30.40,29.75,6,,,3.55,234.6,0.145,");
        assertNotNull(line);
        assertEquals("", line.board()[1]);
        assertEquals("", line.board()[14]);
        assertEquals("", line.board()[19]);
    }

    @Test
    void otherDelimitersAreUsed() {
        X4DataFormat format = X4DataFormat.from(settings("0;1048560", Map.of()));
        // Only the last four board columns are kept.
        X4DataFormat.Line line = format.parse("3.55;234.6;0.145;9999");
        assertNotNull(line);
        assertEquals("3.55", line.board()[16]);
        assertEquals("9999", line.board()[19]);
    }

    @Test
    void unknownSensorHidesThatPortAndLaterOnes() {
        X4DataFormat format = X4DataFormat.from(settings("0,1048575",
                Map.of(4, HUMIDITY, 5, new X4Settings.Port(X4Settings.SENSOR_UNKNOWN, ""),
                        8, TEMPERATURE)));
        assertNull(format.problem());
        assertTrue(format.warning().contains("J5"));
        X4DataFormat.Line line = format.parse("27.21,21.16,1,2,3,78259,21.03");
        assertNotNull(line);
        assertArrayEquals(new String[] {"27.21", "21.16"}, line.ports().get(4));
        assertEquals(1, line.ports().size());
    }

    @Test
    void incompleteSettingsParseNothing() {
        Map<Integer, X4Settings.Port> ports = new LinkedHashMap<>();
        X4Settings settings = new X4Settings("420", "1", "0", "115200", true, null,
                ports, List.of("Board data format", "J4"));
        X4DataFormat format = X4DataFormat.from(settings);
        assertTrue(format.problem().contains("Board data format"));
        assertNull(format.parse("420,11/12/26"));
    }
}
