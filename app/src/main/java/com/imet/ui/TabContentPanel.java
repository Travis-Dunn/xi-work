package com.imet.ui;

import com.imet.serial.DeviceScanner;
import com.imet.ui.views.ConfigurationPanel;
import com.imet.ui.views.HardwarePanel;
import com.imet.ui.views.XQ2DataPanel;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The content area inside each tab. Contains a breadcrumb navigation bar
 * across the top and a card-layout body that swaps between the Hardware,
 * Configuration, and Data views.
 */
public class TabContentPanel extends JPanel {

    public static final String VIEW_HARDWARE = "Hardware";
    public static final String VIEW_CONFIGURATION = "Configuration";
    public static final String VIEW_DATA = "Data";

    private final CardLayout cardLayout;
    private final JPanel bodyPanel;
    private final BreadcrumbBar breadcrumbBar;
    private final HardwarePanel hardwarePanel;

    public TabContentPanel(DeviceScanner scanner) {
        setLayout(new BorderLayout());

        hardwarePanel = new HardwarePanel(scanner);

        breadcrumbBar = new BreadcrumbBar(this);
        add(breadcrumbBar, BorderLayout.NORTH);

        cardLayout = new CardLayout();
        bodyPanel = new JPanel(cardLayout);
        bodyPanel.setBackground(new Color(228, 230, 235));

        // Hardware is always present
        bodyPanel.add(hardwarePanel, VIEW_HARDWARE);

        add(bodyPanel, BorderLayout.CENTER);

        // Listen for device selection
        hardwarePanel.setDeviceSelectionListener(this::onDeviceSelected);

        // Forward J1 connect/disconnect events to the Configuration panel so it
        // can enable its Fetch button and access the device for polling.
        hardwarePanel.setConnectionListener(conn -> {
            for (Component c : bodyPanel.getComponents()) {
                if (c instanceof ConfigurationPanel cp) {
                    cp.setConnection(conn);
                }
            }
        });

        // Initial state: just Hardware
        breadcrumbBar.setViews(List.of(VIEW_HARDWARE));
        showView(VIEW_HARDWARE);

    }

    private void onDeviceSelected(String device) {
        // Remove all cards except Hardware
        for (Component c : bodyPanel.getComponents()) {
            if (c != hardwarePanel) {
                bodyPanel.remove(c);
            }
        }

        // Build the view list and add panels based on device
        List<String> views = new ArrayList<>();
        views.add(VIEW_HARDWARE);

        switch (device) {
            case "IMet-X4" -> {
                views.add(VIEW_CONFIGURATION);
                views.add(VIEW_DATA);
                bodyPanel.add(new ConfigurationPanel(), VIEW_CONFIGURATION);
                bodyPanel.add(createPlaceholder("X4 Data"), VIEW_DATA);
            }
            case "IMet-XQ2" -> {
                views.add(VIEW_DATA);
                bodyPanel.add(new XQ2DataPanel(), VIEW_DATA); // device-specific
            }
            // IMet-XF, IMet-4RSB → just Hardware, no extra views
        }

        breadcrumbBar.setViews(views);
        hardwarePanel.renameParentTab(device);

        bodyPanel.revalidate();
        bodyPanel.repaint();
    }

    /** Switch the visible view by name. */
    public void showView(String viewName) {
        cardLayout.show(bodyPanel, viewName);
    }

    private static JPanel createPlaceholder(String name) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(228, 230, 235));
        JLabel label = new JLabel(name + " (coming soon)", SwingConstants.CENTER);
        label.setFont(label.getFont().deriveFont(Font.ITALIC, 14f));
        label.setForeground(Color.GRAY);
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }

    HardwarePanel GetHardwarePanel() { return hardwarePanel; }
}