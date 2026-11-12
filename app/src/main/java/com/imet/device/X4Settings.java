package com.imet.device;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The X4's configuration as read from the device when J1 connected
 * (SoW #14). One read is shared by the Hardware, Configuration and Data
 * screens; it is not refreshed while connected.
 * <p>
 * Values are kept as text, exactly as the plugin returned them. A value
 * that could not be read is {@code null} and is named in
 * {@link #missing()}.
 */
public final class X4Settings {

    public static final int FIRST_PORT = 4;
    public static final int LAST_PORT  = 9;

    /** Sensor IDs used with /CJ4–/CJ9 (X4 manual section 4.3). */
    public static final int SENSOR_NONE           = 0;
    public static final int SENSOR_TEMPERATURE    = 3;
    public static final int SENSOR_HUMIDITY       = 4;
    public static final int SENSOR_SERIAL_FORWARD = 6;

    /**
     * The port answered, but it is set to a sensor the plugin has no
     * message for (a reserved ID, manual section 4.3.5).
     */
    public static final int SENSOR_UNKNOWN = -1;

    /**
     * What a port is configured for.
     *
     * @param sensorId one of the SENSOR_ constants
     * @param values   the sensor's settings as the lexicon lists them,
     *                 comma separated: serial number and four
     *                 coefficients for the temperature sensor, the I2C
     *                 address for the humidity sensor, baud rate and
     *                 column count for serial forward; empty otherwise
     */
    public record Port(int sensorId, String values) { }

    private final String serialNumber;
    private final String dataPublishingFrequency;
    private final String pressureBias;
    private final String j3BaudRate;
    private final Boolean dataPublishing;
    private final String boardDataFormat;
    private final Map<Integer, Port> ports;
    private final List<String> missing;

    X4Settings(String serialNumber, String dataPublishingFrequency,
               String pressureBias, String j3BaudRate, Boolean dataPublishing,
               String boardDataFormat, Map<Integer, Port> ports,
               List<String> missing) {
        this.serialNumber = serialNumber;
        this.dataPublishingFrequency = dataPublishingFrequency;
        this.pressureBias = pressureBias;
        this.j3BaudRate = j3BaudRate;
        this.dataPublishing = dataPublishing;
        this.boardDataFormat = boardDataFormat;
        this.ports = Collections.unmodifiableMap(new LinkedHashMap<>(ports));
        this.missing = List.copyOf(missing);
    }

    /** /SRN */
    public String serialNumber() { return serialNumber; }

    /** /DPF, in Hz. */
    public String dataPublishingFrequency() { return dataPublishingFrequency; }

    /** /CPB */
    public String pressureBias() { return pressureBias; }

    /** /CJ3 */
    public String j3BaudRate() { return j3BaudRate; }

    /** /TDP: TRUE for ON, FALSE for OFF. */
    public Boolean dataPublishing() { return dataPublishing; }

    /**
     * /CBD as the device reports it, e.g. {@code 0,30720}: the format
     * character, the delimiter character, then the bitmask of board
     * columns left out of the data stream.
     */
    public String boardDataFormat() { return boardDataFormat; }

    /**
     * @param port port number, 4–9
     * @return what the port is configured for, or {@code null} if it
     *         could not be read
     */
    public Port port(int port) { return ports.get(port); }

    /**
     * Names of the settings that could not be read, in the order they
     * were asked for; empty if everything was read.
     */
    public List<String> missing() { return missing; }

    /** Returns {@code true} if every setting was read. */
    public boolean isComplete() { return missing.isEmpty(); }
}
