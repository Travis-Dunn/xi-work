package com.imet.discovery;

import java.util.Set;

/**
 * Callback for {@link ResourceScanner} discovery events.
 * <p>
 * All methods are invoked on the Swing EDT so listeners can safely
 * update the UI without additional dispatching.
 */
public interface ResourceScannerListener {

    /**
     * Called whenever the set of discovered lexicons or plugins changes.
     *
     * @param lexiconDevices product names for which a valid lexicon was found
     * @param pluginDevices  product names for which a matching plugin was found
     */
    void onResourcesChanged(Set<String> lexiconDevices, Set<String> pluginDevices);
}