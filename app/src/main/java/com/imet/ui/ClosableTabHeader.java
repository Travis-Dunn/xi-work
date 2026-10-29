package com.imet.ui;

import javax.swing.*;
import java.awt.*;

/**
 * Custom tab header component that displays a title and a small close button.
 * When closed, the tab is removed from the parent tabbed pane. The last
 * remaining content tab cannot be closed.
 */
public class ClosableTabHeader extends JPanel {

    public ClosableTabHeader(JTabbedPane tabbedPane, String title) {
        setOpaque(false);
        setLayout(new FlowLayout(FlowLayout.LEFT, 4, 0));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
        add(titleLabel);

        JButton closeButton = new JButton("\u00D7"); // multiplication sign as close icon
        closeButton.setFont(closeButton.getFont().deriveFont(Font.BOLD, 14f));
        closeButton.setMargin(new Insets(0, 2, 0, 2));
        closeButton.setContentAreaFilled(false);
        closeButton.setBorderPainted(false);
        closeButton.setFocusable(false);
        closeButton.setToolTipText("Close tab");

        closeButton.addActionListener(e -> {
            int index = tabbedPane.indexOfTabComponent(this);
            if (index >= 0) {
                // Count real content tabs (all except the "+" tab).
                int contentTabCount = tabbedPane.getTabCount() - 1;
                if (contentTabCount > 1) {
                    // Suppress the "+" tab ChangeListener while removing.
                    tabbedPane.putClientProperty("suppressNewTab", Boolean.TRUE);
                    tabbedPane.removeTabAt(index);
                    tabbedPane.putClientProperty("suppressNewTab", Boolean.FALSE);
                }
            }
        });

        add(closeButton);
    }
}