package com.imet.ui;

import com.imet.AppInfo;
import com.imet.AppPaths;
import com.imet.Console;
import com.imet.discovery.ResourceScanner;
import com.imet.discovery.ResourceScannerListener;
import com.imet.error.ErrorHandler;
import com.imet.plugin.LexiconTest;
import com.imet.plugin.PluginTest;
import com.imet.serial.DeviceScanner;
import com.imet.ui.views.HardwarePanel;
import com.imet.plugin.Loader;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

/**
 * Top-level application window. Hosts a tabbed pane where each tab
 * represents an independent session (mirroring the original iMet-XS
 * "New Tab" / "+" behaviour).
 * <p>
 * SoW requirements addressed here:
 * <ul>
 *   <li>#1 — Title in top left: "iMet-XI [version]"</li>
 *   <li>#2 — Menu in top right (gear icon, currently three-dot placeholder)</li>
 *   <li>#3 — Contact info in menu: website, email, phone</li>
 *   <li>#4 — Part number and version in menu</li>
 *   <li>#5 — Manufacturing features toggle in menu</li>
 * </ul>
 */
public class MainFrame extends JFrame implements ResourceScannerListener {

    private final JTabbedPane tabbedPane;
    private int tabCounter = 0;

    /** Global flag for manufacturing feature visibility (SoW req #5). */
    private boolean manufacturingEnabled = false;

    DeviceScanner deviceScanner;
    private final ResourceScanner resourceScanner;
    private final Thread consoleThread;

    private static final String ERR_STR_PLUGIN_DIR =
            "Unable to find plugin directory.";

    public MainFrame() {
        super(AppInfo.WINDOW_TITLE);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1280, 720);
        setMinimumSize(new Dimension(800, 500));
        setLocationRelativeTo(null);

        if (Loader.LoadLexiconsAndPlugins() != Loader.SUCCESS) {
            ErrorHandler.ShowDialog("Error", "Critical",
                    "Problem");
            consoleThread = null;
        } else {
            consoleThread = new Thread(this::tempCliTest, "imet-console");
            consoleThread.setDaemon(true);
        }


        tabbedPane = new JTabbedPane(JTabbedPane.TOP);
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);

        deviceScanner = new DeviceScanner();
        // Scanner starts stopped; it is activated when manufacturing
        // features are enabled via the application menu (SoW req #5).

        Path lexpath = AppPaths.lexiconsDir();

        resourceScanner = new ResourceScanner(
                lexpath , AppPaths.pluginsDir());
        resourceScanner.addListener(this);
        resourceScanner.start();

        addNewTab();
        addPlusTab();

        // Intercept clicks on the "+" tab to create a new tab instead of selecting it.
        tabbedPane.addChangeListener(e -> {
            if (Boolean.TRUE.equals(tabbedPane.getClientProperty("suppressNewTab"))) {
                return;
            }
            int selected = tabbedPane.getSelectedIndex();
            if (selected >= 0 && isPlusTab(selected)) {
                SwingUtilities.invokeLater(() -> {
                    tabbedPane.removeTabAt(tabbedPane.getTabCount() - 1);
                    addNewTab();
                    addPlusTab();
                    tabbedPane.setSelectedIndex(tabbedPane.getTabCount() - 2);
                });
            }
        });

        setLayout(new BorderLayout());
        add(tabbedPane, BorderLayout.CENTER);


    }

    // ────────────────────────────────────────────────────────────────────────
    //  TEMP: console-based test harness (no GUI involvement)
    //  TODO: remove once real device interaction is wired into the GUI.
    // ────────────────────────────────────────────────────────────────────────

    /** Start console input after the frame has been initialized and shown. */
    public void startConsoleTest() {
        if (consoleThread != null) {
            consoleThread.start();
        }
    }

    private void tempCliTest() {
        LexiconTest lexiconX4 = Loader.GetLexiconX4();
        PluginTest pluginX4 = Loader.GetPluginX4();

        // Each lexicon entry expands into up to three message variants,
        // depending on which messageType flags it declares. Mark each
        // declared variant with 'o' if XI implements it, 'x' if not.
        String[] supportedByApp = new String[] {
                "serialNumber (query)",
                "serialNumber (command)"
        };
        Set<String> supportedSet = new HashSet<>(Arrays.asList(supportedByApp));

        // Build the numbered menu of supported (name, variant) pairs.
        // Only supported messages get numbers — unsupported ones are listed
        // for visibility but can't be selected.
        List<String[]> menu = new ArrayList<>();  // each entry: {name, variant}

        Console.println("Lexicon messages (o = supported by XI, x = not):");
        for (LexiconTest.Message m : lexiconX4.getMessages()) {
            if (m.hasQuery())              listVariant(m.name(), "(query)",               supportedSet, menu);
            if (m.hasCommand())            listVariant(m.name(), "(command)",             supportedSet, menu);
            if (m.hasParameterizedQuery()) listVariant(m.name(), "(parameterized query)", supportedSet, menu);
        }

        if (menu.isEmpty()) {
            Console.println("No supported messages available.");
            return;
        }

        Scanner in = new Scanner(System.in);

        while (true) {
            System.out.print("Enter a message number: ");
            String line = in.hasNextLine() ? in.nextLine().trim() : null;
            if (line == null) break;
            if (line.isEmpty()) continue;

            int choice;
            try {
                choice = Integer.parseInt(line);
            } catch (NumberFormatException e) {
                Console.println("Not a number.");
                continue;
            }

            if (choice < 1 || choice > menu.size()) {
                Console.println("Out of range.");
                continue;
            }

            String[] picked = menu.get(choice - 1);
            String name = picked[0];
            String variant = picked[1];

            // Parameterized variants need an argument from the user.
            String arg = null;
            boolean needsArg = variant.equals("(command)")
                    || variant.equals("(parameterized query)");
            if (needsArg) {
                System.out.print("Enter an argument: ");
                arg = in.hasNextLine() ? in.nextLine() : "";
            }

            if (needsArg) {
                Console.println(String.format(
                        "sending %s as %s with argument [%s]",
                        name, variant, arg));
            } else {
                Console.println(String.format(
                        "sending %s as %s", name, variant));
            }

            // Dispatch. The plugin only exposes _q and _c right now, so
            // (parameterized query) has no path — we'd need a new plugin
            // method for that. For now, only the two serialNumber variants
            // in supportedByApp can actually reach this point.
            switch (variant) {
                case "(query)": {
                    byte[] result = pluginX4.messageType_q(name.getBytes());
                    String resultStr = new String(result, StandardCharsets.UTF_8);
                    Console.println("the device returned [" + resultStr + "]");
                    break;
                }
                case "(command)": {
                    String payload = name + "=" + arg;
                    pluginX4.messageType_c(payload.getBytes());
                    break;
                }
                case "(parameterized query)": {
                    // TODO: plugin needs a parameterized-query method.
                    Console.println("(parameterized query dispatch not implemented yet)");
                    break;
                }
            }
        }
    }

    /** Helper for tempCliTest: print one variant line, add to menu if supported. */
    private static void listVariant(String name, String variant,
                                    Set<String> supportedSet,
                                    List<String[]> menu) {
        String full = name + " " + variant;
        if (supportedSet.contains(full)) {
            menu.add(new String[] {name, variant});
            Console.println(String.format("  %2d. o %s", menu.size(), full));
        } else {
            Console.println("      x " + full);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Menu button + popup
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Creates the menu button that lives in the trailing area of the tab bar.
     * Currently renders the XS-style three-dot icon (⋮); will be replaced
     * with a gear icon when the final asset is provided (SoW req #2 notes
     * "final image is TBD, but it will be a gear symbol").
     */
    private JButton createMenuButton() {
        JButton button = new JButton() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);

                // Draw three vertical dots.
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
                showAppMenu(button);
            }
        });

        return button;
    }

    /**
     * Builds and shows the application popup menu below the given component.
     */
    public void showAppMenu(Component anchor) {
        JPopupMenu menu = new JPopupMenu();

        // ── Contact section (SoW req #3) ──
        JLabel contactHeader = new JLabel("  Contact InterMet");
        contactHeader.setFont(contactHeader.getFont().deriveFont(Font.BOLD, 12f));
        contactHeader.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        menu.add(contactHeader);

        menu.add(createLinkItem("Website: intermetsystems.com",
                AppInfo.CONTACT_WEBSITE));
        menu.add(createLinkItem("Email: " + AppInfo.CONTACT_EMAIL,
                "mailto:" + AppInfo.CONTACT_EMAIL));
        menu.add(createMenuItem("Phone: " + AppInfo.CONTACT_PHONE));

        menu.addSeparator();

        // ── Part number / version (SoW req #4) ──
        JMenuItem versionItem = createMenuItem(AppInfo.FULL_PART_NUMBER);
        versionItem.setEnabled(false);
        menu.add(versionItem);

        menu.addSeparator();

        // ── Manufacturing features toggle (SoW req #5) ──
        JCheckBoxMenuItem mfgToggle = new JCheckBoxMenuItem(
                "Enable Manufacturing Features");
        mfgToggle.setSelected(manufacturingEnabled);
        mfgToggle.addActionListener(e -> {
            manufacturingEnabled = mfgToggle.isSelected();
            onManufacturingToggled();
        });
        menu.add(mfgToggle);

        menu.show(anchor, 0, anchor.getHeight());
    }

    /**
     * Creates a plain, non-actionable menu item (used for display-only text).
     */
    private JMenuItem createMenuItem(String text) {
        JMenuItem item = new JMenuItem(text);
        item.setFont(item.getFont().deriveFont(Font.PLAIN, 12f));
        return item;
    }

    /**
     * Creates a menu item that opens a URI in the default browser/mail client
     * when clicked. Falls back gracefully if Desktop is not supported.
     */
    private JMenuItem createLinkItem(String text, String uri) {
        JMenuItem item = new JMenuItem(text);
        item.setFont(item.getFont().deriveFont(Font.PLAIN, 12f));
        item.setForeground(new Color(122, 162, 214));
        item.addActionListener(e -> {
            if (Desktop.isDesktopSupported()) {
                try {
                    Desktop.getDesktop().browse(new URI(uri));
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this,
                            "Could not open: " + uri,
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        return item;
    }

    /**
     * Called when the manufacturing-features checkbox is toggled.
     * Currently a placeholder — manufacturing views don't exist yet
     * (SoW req #5: "Until the version of this SoW is released to include
     * the manufacturing features, this is just a placeholder.").
     */
    private void onManufacturingToggled() {
        if (manufacturingEnabled) {
            deviceScanner.start();
        } else {
            deviceScanner.stop();
        }

        // Propagate to every tab's HardwarePanel.
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            Component comp = tabbedPane.getComponentAt(i);
            if (comp instanceof TabContentPanel tcp) {
                tcp.GetHardwarePanel().setManufacturingEnabled(manufacturingEnabled);
            }
        }
    }

    /**
     * Returns whether manufacturing features are currently enabled.
     * Other panels can query this to decide visibility of MFG-only controls.
     */
    public boolean isManufacturingEnabled() {
        return manufacturingEnabled;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Resource discovery callback
    // ────────────────────────────────────────────────────────────────────────

    private static final String[] ALL_PRODUCTS =
            {"IMet-X4", "IMet-XQ2", "IMet-XF", "IMet-4RSB"};

    @Override
    public void onResourcesChanged(Set<String> lexiconDevices,
                                   Set<String> pluginDevices) {
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            Component comp = tabbedPane.getComponentAt(i);
            if (comp instanceof TabContentPanel tcp) {
                HardwarePanel hp = tcp.GetHardwarePanel();
                for (String product : ALL_PRODUCTS) {
                    hp.setLexiconAvailable(product, lexiconDevices.contains(product));
                    hp.setPluginAvailable(product, pluginDevices.contains(product));
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Tab management (unchanged from previous implementation)
    // ────────────────────────────────────────────────────────────────────────

    private void addNewTab() {
        tabCounter++;
        String title = (tabCounter == 1) ? "New Tab" : "New Tab " + tabCounter;
        TabContentPanel content = new TabContentPanel(deviceScanner);
        tabbedPane.addTab(title, content);

        int index = tabbedPane.indexOfComponent(content);
        tabbedPane.setTabComponentAt(index, new ClosableTabHeader(tabbedPane, title));
    }

    private void addPlusTab() {
        JPanel placeholder = new JPanel();
        tabbedPane.addTab("+", placeholder);

        JLabel plusLabel = new JLabel("+");
        plusLabel.setFont(plusLabel.getFont().deriveFont(Font.BOLD, 16f));
        plusLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        tabbedPane.setTabComponentAt(tabbedPane.getTabCount() - 1, plusLabel);
    }

    private boolean isPlusTab(int index) {
        Component comp = tabbedPane.getTabComponentAt(index);
        return comp instanceof JLabel label && "+".equals(label.getText());
    }
}