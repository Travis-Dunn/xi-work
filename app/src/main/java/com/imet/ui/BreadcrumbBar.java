package com.imet.ui;

import com.imet.ui.MainFrame;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/**
 * Horizontal breadcrumb-style navigation bar that mirrors the original
 * iMet-XS layout:  [icon]  /  Hardware  /  Configuration  /  Data
 *
 * Each section is a clickable label that switches the parent
 * {@link TabContentPanel} to the corresponding view.
 *
 * Also hosts the application menu button (three-dot icon) on the right side.
 */
public class BreadcrumbBar extends JPanel {

    private static final Color LINK_COLOR = new Color(122, 162, 214);
    private static final Color ACTIVE_COLOR = new Color(0x2B2B2B);
    private static final Color SEPARATOR_COLOR = new Color(0x999999);

    private final TabContentPanel parent;
    private final JPanel linksPanel;
    private final Map<String, JLabel> linkLabels = new LinkedHashMap<>();
    private String activeView;

    public BreadcrumbBar(TabContentPanel parent) {
        this.parent = parent;
        setLayout(new BorderLayout());
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xD0D0D0)),
                BorderFactory.createEmptyBorder(4, 12, 4, 12)
        ));

        // ── Left side: breadcrumb links ──
        linksPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 8));
        linksPanel.setBackground(Color.WHITE);

        // App icon placeholder (small blue circle to mimic the "i" icon).
        JLabel icon = new JLabel("\u24D8"); // circled lowercase i
        icon.setFont(icon.getFont().deriveFont(Font.BOLD, 20f));
        icon.setForeground(LINK_COLOR);
        linksPanel.add(icon);

        addSeparator();
        addLink(TabContentPanel.VIEW_HARDWARE);
        addSeparator();
        addLink(TabContentPanel.VIEW_CONFIGURATION);
        addSeparator();
        addLink(TabContentPanel.VIEW_DATA);

        add(linksPanel, BorderLayout.WEST);

        // ── Right side: three-dot menu button ──
        add(createMenuButton(), BorderLayout.EAST);

        setActiveView(TabContentPanel.VIEW_HARDWARE);
    }

    private void addLink(String viewName) {
        JLabel label = new JLabel(viewName);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 14f));
        label.setForeground(LINK_COLOR);
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));

        label.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                setActiveView(viewName);
                parent.showView(viewName);
            }

            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                if (!viewName.equals(activeView)) {
                    label.setText("<html><u>" + viewName + "</u></html>");
                }
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                label.setText(viewName);
            }
        });

        linkLabels.put(viewName, label);
        linksPanel.add(label);
    }

    private void addSeparator() {
        JLabel sep = new JLabel("  /  ");
        sep.setForeground(SEPARATOR_COLOR);
        sep.setFont(sep.getFont().deriveFont(Font.PLAIN, 14f));
        linksPanel.add(sep);
    }

    private void setActiveView(String viewName) {
        this.activeView = viewName;
        linkLabels.forEach((name, label) -> {
            if (name.equals(viewName)) {
                label.setForeground(ACTIVE_COLOR);
                label.setFont(label.getFont().deriveFont(Font.BOLD));
            } else {
                label.setForeground(LINK_COLOR);
                label.setFont(label.getFont().deriveFont(Font.PLAIN));
            }
            label.setText(name); // reset any underline
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Three-dot menu button (delegates to MainFrame)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Creates the three-dot menu button that was previously in the tab bar.
     * Now lives in the breadcrumb bar, right-aligned, so it stays clear of
     * the tab strip when many tabs are open.
     */
    private JButton createMenuButton() {
        JButton button = new JButton() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);

                int dotSize = 4;
                int cx = getWidth() / 2;
                int cy = getHeight() / 2;
                g2.setColor(new Color(0x555555));

                for (int dy = -8; dy <= 8; dy += 8) {
                    g2.fill(new Ellipse2D.Double(
                            cx - dotSize / 2.0, cy + dy - dotSize / 2.0,
                            dotSize, dotSize));
                }
                g2.dispose();
            }
        };
        button.setPreferredSize(new Dimension(36, 28));
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setToolTipText("Menu");

        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                MainFrame frame = findMainFrame();
                if (frame != null) {
                    frame.showAppMenu(button);
                }
            }
        });

        return button;
    }

    /** Walk up the component hierarchy to locate the owning MainFrame. */
    private MainFrame findMainFrame() {
        Component comp = this;
        while (comp != null) {
            if (comp instanceof MainFrame mf) {
                return mf;
            }
            comp = comp.getParent();
        }
        return null;
    }

    public void setViews(List<String> viewNames) {
        linksPanel.removeAll();
        linkLabels.clear();

        // Re-add the icon
        JLabel icon = new JLabel("\u24D8");
        icon.setFont(icon.getFont().deriveFont(Font.BOLD, 20f));
        icon.setForeground(LINK_COLOR);
        linksPanel.add(icon);

        for (String name : viewNames) {
            addSeparator();
            addLink(name);
        }

        if (!viewNames.isEmpty()) {
            setActiveView(viewNames.get(0));
        }

        linksPanel.revalidate();
        linksPanel.repaint();
    }
}