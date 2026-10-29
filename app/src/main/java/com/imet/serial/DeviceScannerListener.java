package com.imet.serial;

import java.util.Set;

/**
 * Callback for {@link DeviceScanner} discovery events.
 * <p>
 * All methods are invoked on the Swing EDT so listeners can safely
 * update the UI without additional dispatching.
 */
public interface DeviceScannerListener {

    /**
     * Called whenever the set of discovered devices changes — a new device
     * appeared, an existing device disappeared, or both.
     *
     * @param devices the current complete set of discovered devices
     */
    void onDevicesChanged(Set<DiscoveredDevice> devices);
}