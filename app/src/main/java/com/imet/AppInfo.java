package com.imet;

/**
 * Central location for application identity and version metadata.
 * <p>
 * Part number per SoW: 600703.
 * Versioning: semantic (MAJOR.MINOR.PATCH).
 */
public final class AppInfo {

    private AppInfo() { }

    public static final String APP_NAME    = "iMet-XI";
    public static final String VERSION     = "0.1.0";
    public static final String PART_NUMBER = "600703";

    /** Window title per SoW req #1: "iMet-XI [version]" */
    public static final String WINDOW_TITLE = APP_NAME + " v" + VERSION;

    /** Full part number string per SoW req #4: "600703-[version]" */
    public static final String FULL_PART_NUMBER = PART_NUMBER + "-v" + VERSION;

    // Contact info per SoW req #3.
    public static final String CONTACT_WEBSITE = "https://www.intermetsystems.com";
    public static final String CONTACT_EMAIL   = "info@intermetsystems.com";
    public static final String CONTACT_PHONE   = "+01-616-971-1005";
}