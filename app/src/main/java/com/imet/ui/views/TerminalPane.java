package com.imet.ui.views;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.nio.charset.StandardCharsets;

/**
 * Read-only terminal for one device port (SoW #33, #34).
 * <p>
 * Shows received bytes decoded as ASCII. Carriage returns are dropped so
 * CRLF line ends show as a single line break. Keeps the last
 * {@value #MAX_LINES} lines. New output scrolls into view only while the
 * view is already at the bottom; if the user has scrolled up, the view
 * stays where it is.
 */
class TerminalPane extends JScrollPane {

    /** History length; the oldest lines are removed beyond this. */
    static final int MAX_LINES = 2000;

    /**
     * How close to the bottom (in pixels) still counts as "at the bottom",
     * so a view a pixel or two short of the end keeps following.
     */
    private static final int BOTTOM_SLACK = 4;

    private final JTextArea area;

    TerminalPane() {
        super(VERTICAL_SCROLLBAR_ALWAYS, HORIZONTAL_SCROLLBAR_AS_NEEDED);

        area = new JTextArea(3, 80);
        area.setEditable(false);
        area.setLineWrap(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setBackground(new Color(0xF4F5F7));

        // Scrolling is decided in append(), not by where the caret is.
        ((DefaultCaret) area.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);

        setViewportView(area);
        setBorder(BorderFactory.createLineBorder(new Color(0xD0D0D0)));
        setMinimumSize(new Dimension(100, 50));
    }

    /** Appends received bytes. Must be called on the EDT. */
    void append(byte[] data) {
        String text = new String(data, StandardCharsets.US_ASCII).replace("\r", "");
        if (text.isEmpty()) {
            return;
        }

        JScrollBar bar = getVerticalScrollBar();
        boolean atBottom = bar.getValue() + bar.getVisibleAmount()
                >= bar.getMaximum() - BOTTOM_SLACK;

        area.append(text);
        int removedLines = trimHistory();

        if (atBottom) {
            // Moving the caret to the end scrolls it into view once the
            // text area has been laid out with the new text.
            area.setCaretPosition(area.getDocument().getLength());
        } else if (removedLines > 0) {
            // Keep the lines the user is looking at in place.
            int rowHeight = area.getFontMetrics(area.getFont()).getHeight();
            bar.setValue(Math.max(0, bar.getValue() - removedLines * rowHeight));
        }
    }

    /** Removes everything shown. Must be called on the EDT. */
    void clear() {
        area.setText("");
    }

    /** Removes the oldest lines beyond {@link #MAX_LINES}; returns how many. */
    private int trimHistory() {
        int excess = area.getLineCount() - MAX_LINES;
        if (excess <= 0) {
            return 0;
        }
        try {
            area.replaceRange("", 0, area.getLineStartOffset(excess));
        } catch (BadLocationException e) {
            // Can't happen: excess is less than the line count.
            return 0;
        }
        return excess;
    }
}