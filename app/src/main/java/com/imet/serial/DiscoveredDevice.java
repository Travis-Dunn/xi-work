package com.imet.serial;

/**
 * Immutable snapshot of a device found by the {@link DeviceScanner}.
 *
 * @param portName   system port name, e.g. "COM9" or "/dev/ttyUSB0"
 * @param deviceType the product family identified during probing
 */
public record DiscoveredDevice(String portName, DeviceType deviceType) {

    public enum DeviceType {
        /** iMet-X4 Hub — identified via {@code /ACK?\r\n} at 115200 baud on J1. */
        IMET_X4
        // Future device types (XQ2, XF, 4RSB) will be added here.
    }
}