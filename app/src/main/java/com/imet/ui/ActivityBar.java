package com.imet.ui;

import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * The activity indicator along the bottom of the window: what the
 * selected tab last sent to its device.  It says more than an hourglass
 * would: while the app is busy with a device the text keeps changing,
 * and it shows which command it has got to.
 *
 * <p>Something sent within the last {@value #BUSY_MS} ms is shown as
 * "Sending"; after that the device is taken to be idle, and the text
 * turns grey and says when the last thing was sent.  Not in the SoW, so
 * it is only shown while manufacturing features are enabled.
 */
class ActivityBar extends JPanel {

    /**
     * How long after something is sent it still counts as going on.
     * Longer than the pause the X4 plugin leaves after each setting it
     * writes (1.5 s), so that Configure X4 reads as busy throughout.
     */
    static final long BUSY_MS = 2500;

    private static final Color BUSY_TEXT = new Color(0x333333);
    private static final Color IDLE_TEXT = new Color(0x999999);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final JLabel label = new JLabel(" ");

    ActivityBar() {
        super(new FlowLayout(FlowLayout.LEFT, 10, 3));
        setBackground(new Color(228, 230, 235));
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xD0D0D0)));
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 12f));
        add(label);
    }

    /**
     * Shows a tab's activity.
     *
     * @param sent  the last thing the tab sent, or null if nothing
     * @param nowMs the time now, as {@link System#currentTimeMillis()}
     */
    void show(TabContentPanel.Sent sent, long nowMs) {
        label.setText(text(sent, nowMs));
        label.setForeground(sent != null && nowMs - sent.timeMs() < BUSY_MS
                ? BUSY_TEXT : IDLE_TEXT);
    }

    static String text(TabContentPanel.Sent sent, long nowMs) {
        if (sent == null) {
            return " "; // keeps the bar's height
        }
        if (nowMs - sent.timeMs() < BUSY_MS) {
            return "Sending " + sent.text();
        }
        return "Idle. Last sent " + sent.text() + " at "
                + TIME.format(Instant.ofEpochMilli(sent.timeMs()));
    }

    /** The text showing; for tests. */
    String text() {
        return label.getText();
    }
}
