package com.imet.ui.views;

import javax.swing.*;
import java.awt.*;

public class XQ2DataPanel extends JPanel {
    public XQ2DataPanel() {
        setLayout(new BorderLayout());
        setBackground(new Color(228, 230, 235));
        // XQ2-specific data view content goes here
        JLabel label = new JLabel("XQ2 Data View (coming soon)", SwingConstants.CENTER);
        label.setFont(label.getFont().deriveFont(Font.ITALIC, 14f));
        label.setForeground(Color.GRAY);
        add(label, BorderLayout.CENTER);
    }
}