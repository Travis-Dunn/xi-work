package com.imet.ui.views;

import com.imet.device.J3Connection;
import com.imet.device.X4Connection;
import com.imet.plugin.Loader;
import com.imet.plugin.PluginTest;
import java.util.function.Consumer;
import com.imet.serial.DeviceScanner;
import com.imet.serial.DeviceScannerListener;
import com.imet.serial.DiscoveredDevice;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import jssc.SerialPortList;

/**
 * Hardware view panel. Displays a product-selection card on a light-gray
 * background with radio buttons for each supported product.
 * <p>
 * When iMet-X4 is selected the panel shows connection controls for
 * the two host-facing serial ports:
 * <ul>
 *   <li><b>J1</b> — USB configuration port. Fixed at 115200 baud, 8N1.
 *       The user only selects a COM port and clicks Connect.</li>
 *   <li><b>J3</b> — UART TTL data-output port. Fixed at 8N1 but the baud
 *       rate is configurable (supported: 9600, 19200, 38400, 57600, 115200).
 *       Disabled until J1 is connected; on J1 connect the software would
 *       normally query {@code /CJ3?} and auto-set the returned rate.</li>
 * </ul>
 * <p>
 * Per SoW: serial number, firmware version and reprogramming controls are
 * <b>not</b> present on this screen — they will live elsewhere (TBD).
 */
public class HardwarePanel extends JPanel implements DeviceScannerListener {
    private Set<DiscoveredDevice> discoveredDevices = Set.of();

    private static final Color BACKGROUND   = new Color(228, 230, 235);
    private static final Color CARD_BG      = Color.WHITE;
    private static final Color BUTTON_COLOR = new Color(122, 162, 214);
    private static final Color BUTTON_TEXT  = Color.WHITE;
    private static final Color WARN_ORANGE  = new Color(255, 150, 50);
    private static final Color ERROR_RED    = new Color(255, 25, 25);
    private static final Color DETECTED_GREEN = new Color(50, 220, 20);
    private static final Color SECONDARY_GRAY = new Color(0x666666);

    /** Baud rates accepted by the CJ3 command (see manual section 3.3.3). */
    private static final String[] J3_BAUD_RATES =
            {"9600", "19200", "38400", "57600", "115200"};

    // ── iMet-X4: J1 widgets ──
    private JComboBox<String> j1ComPortCombo;
    private JButton j1ConnectButton;

    // ── iMet-X4: J3 widgets ──
    private JComboBox<String> j3ComPortCombo;
    private JComboBox<String> j3BaudCombo;
    private JButton j3ConnectButton;

    // ── XQ2 widgets ──
    private JComboBox<String> xq2ComPortCombo;
    private JButton xq2ConnectButton;

    /** CardLayout wrapper that holds a details card per product. */
    private final JPanel detailsCardPanel;
    private final CardLayout detailsCardLayout;

    /** Tracks simulated connection state for J1. */
    private boolean j1Connected = false;

    /** Active J1 serial connection, if any. */
    private X4Connection j1Connection;

    /** Tracks connection state for J3. */
    private boolean j3Connected = false;

    /** Active J3 serial connection, if any. */
    private J3Connection j3Connection;

    /** Indicator labels keyed by product name for each column. */
    private final Map<String, JLabel> lexiconIndicators = new LinkedHashMap<>();
    private final Map<String, JLabel> pluginIndicators = new LinkedHashMap<>();
    private final Map<String, JLabel> detectedIndicators = new LinkedHashMap<>();

    /** All components in the "Detected" column (header + indicators) for show/hide. */
    private final java.util.List<JComponent> detectedColumnComponents = new java.util.ArrayList<>();

    /** Tracks whether a lexicon / plugin is available for each product. */
    private final Map<String, Boolean> lexiconAvailable = new LinkedHashMap<>();
    private final Map<String, Boolean> pluginAvailable  = new LinkedHashMap<>();

    private ButtonGroup productGroup;

    private DeviceSelectionListener deviceSelectionListener;

    /**
     * Optional callback fired when the J1 connection is established or dropped.
     * Receives the live {@link X4Connection} on connect, or {@code null} on
     * disconnect.  Used by the parent panel to propagate the connection to
     * the Configuration panel.
     */
    private Consumer<X4Connection> connectionListener;

    public void setDeviceSelectionListener(DeviceSelectionListener listener) {
        this.deviceSelectionListener = listener;
    }

    public void setConnectionListener(Consumer<X4Connection> listener) {
        this.connectionListener = listener;
    }

    public HardwarePanel(DeviceScanner scanner) {
        setLayout(new BorderLayout());
        setBackground(BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        // White card container.
        JPanel card = new JPanel(new BorderLayout(24, 12));
        card.setBackground(CARD_BG);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xD0D0D0)),
                BorderFactory.createEmptyBorder(16, 20, 16, 20)
        ));

        // ── Left side: heading + product radio buttons ──
        JPanel leftPanel = new JPanel();
        leftPanel.setLayout(new BoxLayout(leftPanel, BoxLayout.Y_AXIS));
        leftPanel.setBackground(CARD_BG);

        JLabel heading = new JLabel("Hardware");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 18f));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        leftPanel.add(heading);
        leftPanel.add(Box.createVerticalStrut(12));

        JLabel selectLabel = new JLabel("Select Product:");
        selectLabel.setFont(selectLabel.getFont().deriveFont(Font.BOLD, 13f));
        selectLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        leftPanel.add(selectLabel);
        leftPanel.add(Box.createVerticalStrut(4));

        productGroup = new ButtonGroup();
        String[] products = {"IMet-X4", "IMet-XQ2", "IMet-XF", "IMet-4RSB"};

        JPanel productGrid = new JPanel(new GridBagLayout());
        productGrid.setBackground(CARD_BG);
        productGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints pgc = new GridBagConstraints();
        pgc.anchor = GridBagConstraints.WEST;
        pgc.fill = GridBagConstraints.NONE;
        pgc.insets = new Insets(0, 0, 0, 8);

        // ── Header row (gridy = 0) ──
        pgc.gridy = 0;
        pgc.gridx = 0;
        productGrid.add(Box.createHorizontalStrut(1), pgc); // empty above radio buttons

        pgc.anchor = GridBagConstraints.CENTER;

        pgc.gridx = 1;
        JLabel lexiconHeader = new JLabel("Lexicon");
        lexiconHeader.setFont(lexiconHeader.getFont().deriveFont(Font.PLAIN, 10f));
        lexiconHeader.setForeground(SECONDARY_GRAY);
        productGrid.add(lexiconHeader, pgc);

        pgc.gridx = 2;
        JLabel pluginHeader = new JLabel("Plugin");
        pluginHeader.setFont(pluginHeader.getFont().deriveFont(Font.PLAIN, 10f));
        pluginHeader.setForeground(SECONDARY_GRAY);
        productGrid.add(pluginHeader, pgc);

        pgc.gridx = 3;
        JLabel detectedHeader = new JLabel("Detected");
        detectedHeader.setFont(detectedHeader.getFont().deriveFont(Font.PLAIN, 10f));
        detectedHeader.setForeground(SECONDARY_GRAY);
        detectedHeader.setVisible(false);
        productGrid.add(detectedHeader, pgc);
        detectedColumnComponents.add(detectedHeader);

        pgc.anchor = GridBagConstraints.WEST;

        // ── Product rows (gridy = 1..4) ──
        for (int i = 0; i < products.length; i++) {
            String product = products[i];

            pgc.gridy = i + 1; // offset by 1 for header row

            pgc.gridx = 0;
            JRadioButton radio = new JRadioButton(product);
            radio.setFont(radio.getFont().deriveFont(Font.PLAIN, 13f));
            radio.setBackground(CARD_BG);
            productGroup.add(radio);
            productGrid.add(radio, pgc);

            pgc.anchor = GridBagConstraints.CENTER;

            pgc.gridx = 1;
            JLabel lexiconInd = createIndicator(false);
            productGrid.add(lexiconInd, pgc);
            lexiconIndicators.put(product, lexiconInd);

            pgc.gridx = 2;
            JLabel pluginInd = createIndicator(false);
            productGrid.add(pluginInd, pgc);
            pluginIndicators.put(product, pluginInd);

            pgc.gridx = 3;
            JLabel detectedInd = createIndicator(false);
            detectedInd.setVisible(false);
            productGrid.add(detectedInd, pgc);
            detectedIndicators.put(product, detectedInd);
            detectedColumnComponents.add(detectedInd);

            pgc.anchor = GridBagConstraints.WEST;

            lexiconAvailable.put(product, false);
            pluginAvailable.put(product, false);

            radio.addActionListener(e -> onProductSelected(product));
        }

        leftPanel.add(productGrid);

        card.add(leftPanel, BorderLayout.WEST);

        // ── Right side: product-specific connection panels (CardLayout) ──
        detailsCardLayout = new CardLayout();
        detailsCardPanel = new JPanel(detailsCardLayout);
        detailsCardPanel.setBackground(CARD_BG);
        detailsCardPanel.setVisible(false);

        detailsCardPanel.add(buildIMetX4Panel(), "IMet-X4");
        detailsCardPanel.add(buildXQ2Panel(), "IMet-XQ2");

        JPanel detailsWrapper = new JPanel(new BorderLayout());
        detailsWrapper.setBackground(CARD_BG);
        detailsWrapper.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        detailsWrapper.add(detailsCardPanel, BorderLayout.NORTH);
        card.add(detailsWrapper, BorderLayout.CENTER);

        // Wrap so the card sits at the top and doesn't stretch vertically.
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(BACKGROUND);
        wrapper.add(card, BorderLayout.NORTH);

        add(wrapper, BorderLayout.CENTER);

        scanner.addListener(this);
    }

    // ────────────────────────────────────────────────────────────────────────
    //  iMet-X4 details panel — J1 and J3 connection rows
    // ────────────────────────────────────────────────────────────────────────

    private JPanel buildIMetX4Panel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(CARD_BG);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(0, 12, 0, 12);

        // ── J1 row (gridy = 0) ──

        // Row label
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridheight = 1;
        JLabel j1Label = new JLabel("J1");
        j1Label.setFont(j1Label.getFont().deriveFont(Font.BOLD, 13f));
        panel.add(j1Label, gbc);

        // COM Port dropdown
        gbc.gridx = 1;
        j1ComPortCombo = new JComboBox<>(new String[]{"Please select a COM port"});
        j1ComPortCombo.setPreferredSize(new Dimension(190, 28));
        j1ComPortCombo.setPrototypeDisplayValue("Please select a COM port");
        addPortRefreshListener(j1ComPortCombo);
        panel.add(createFieldColumn("COM Port", j1ComPortCombo), gbc);

        // Baud Rate (fixed label)
        gbc.gridx = 2;
        panel.add(createInfoColumn("Baud Rate", "115200"), gbc);

        // Encoding (fixed label)
        gbc.gridx = 3;
        panel.add(createInfoColumn("Encoding", "8N1"), gbc);

        // Connect button
        gbc.gridx = 4;
        gbc.anchor = GridBagConstraints.SOUTHWEST;
        j1ConnectButton = createStyledButton("Connect");
        JPanel j1BtnWrap = new JPanel(new BorderLayout());
        j1BtnWrap.setBackground(CARD_BG);
        j1BtnWrap.setBorder(BorderFactory.createEmptyBorder(18, 0, 0, 0));
        j1BtnWrap.add(j1ConnectButton, BorderLayout.CENTER);
        panel.add(j1BtnWrap, gbc);
        gbc.anchor = GridBagConstraints.WEST;

        j1ConnectButton.addActionListener(e -> onJ1Connect());

        // ── Vertical spacer (gridy = 1) ──

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 5;
        panel.add(Box.createVerticalStrut(12), gbc);
        gbc.gridwidth = 1;

        // ── J3 row (gridy = 2) ──

        // Row label
        gbc.gridx = 0;
        gbc.gridy = 2;
        JLabel j3Label = new JLabel("J3");
        j3Label.setFont(j3Label.getFont().deriveFont(Font.BOLD, 13f));
        panel.add(j3Label, gbc);

        // COM Port dropdown
        gbc.gridx = 1;
        j3ComPortCombo = new JComboBox<>(new String[]{"Please select a COM port"});
        j3ComPortCombo.setPreferredSize(new Dimension(190, 28));
        j3ComPortCombo.setPrototypeDisplayValue("Please select a COM port");
        addPortRefreshListener(j3ComPortCombo);
        panel.add(createFieldColumn("COM Port", j3ComPortCombo), gbc);

        // Baud Rate dropdown (populated per manual section 3.3.3)
        gbc.gridx = 2;
        j3BaudCombo = new JComboBox<>(J3_BAUD_RATES);
        j3BaudCombo.setSelectedItem("115200"); // factory default
        j3BaudCombo.setPreferredSize(new Dimension(100, 28));
        panel.add(createFieldColumn("Baud Rate", j3BaudCombo), gbc);

        // Encoding (fixed label)
        gbc.gridx = 3;
        panel.add(createInfoColumn("Encoding", "8N1"), gbc);

        // Connect button
        gbc.gridx = 4;
        gbc.anchor = GridBagConstraints.SOUTHWEST;
        j3ConnectButton = createStyledButton("Connect");
        JPanel j3BtnWrap = new JPanel(new BorderLayout());
        j3BtnWrap.setBackground(CARD_BG);
        j3BtnWrap.setBorder(BorderFactory.createEmptyBorder(18, 0, 0, 0));
        j3BtnWrap.add(j3ConnectButton, BorderLayout.CENTER);
        panel.add(j3BtnWrap, gbc);
        gbc.anchor = GridBagConstraints.WEST;

        j3ConnectButton.addActionListener(e -> onJ3Connect());

        // J3 starts disabled — requires J1 connection first.
        setJ3Enabled(false);

        return panel;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  XQ2 details panel — single connection row
    // ────────────────────────────────────────────────────────────────────────

    /**
     * XQ2 panel: COM Port (dropdown) | Baud Rate: 57600 (label) |
     *            Encoding: 8N1 (label) | [Connect]
     */
    private JPanel buildXQ2Panel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(CARD_BG);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(0, 12, 0, 12);
        gbc.gridy = 0;

        // COM Port dropdown
        gbc.gridx = 0;
        xq2ComPortCombo = new JComboBox<>(new String[]{"Please select a COM port"});
        xq2ComPortCombo.setPreferredSize(new Dimension(190, 28));
        xq2ComPortCombo.setPrototypeDisplayValue("Please select a COM port");
        addPortRefreshListener(xq2ComPortCombo);
        panel.add(createFieldColumn("COM Port", xq2ComPortCombo), gbc);

        // Baud Rate (fixed label)
        gbc.gridx = 1;
        panel.add(createInfoColumn("Baud Rate", "57600"), gbc);

        // Encoding (fixed label)
        gbc.gridx = 2;
        panel.add(createInfoColumn("Encoding", "8N1"), gbc);

        // Connect button
        gbc.gridx = 3;
        gbc.anchor = GridBagConstraints.SOUTHWEST;
        xq2ConnectButton = createStyledButton("Connect");
        JPanel btnWrap = new JPanel(new BorderLayout());
        btnWrap.setBackground(CARD_BG);
        btnWrap.setBorder(BorderFactory.createEmptyBorder(18, 0, 0, 0));
        btnWrap.add(xq2ConnectButton, BorderLayout.CENTER);
        panel.add(btnWrap, gbc);

        xq2ConnectButton.addActionListener(e -> onXQ2Connect());

        return panel;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Helper: field column (label + editable widget)
    // ────────────────────────────────────────────────────────────────────────

    private JPanel createFieldColumn(String labelText, JComponent field) {
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBackground(CARD_BG);

        JLabel label = new JLabel(labelText);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 12f));
        label.setForeground(new Color(0x666666));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(label);

        col.add(Box.createVerticalStrut(4));

        field.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(field);

        return col;
    }

    /**
     * Helper: info column (small label + larger read-only value label).
     * Used for fixed parameters like J1 baud rate and encoding.
     */
    private JPanel createInfoColumn(String labelText, String value) {
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBackground(CARD_BG);

        JLabel label = new JLabel(labelText);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 12f));
        label.setForeground(new Color(0x666666));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(label);

        col.add(Box.createVerticalStrut(4));

        JLabel valueLabel = new JLabel(value);
        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.PLAIN, 13f));
        valueLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Match combo box height so rows align vertically.
        valueLabel.setPreferredSize(new Dimension(
                valueLabel.getPreferredSize().width, 28));
        col.add(valueLabel);

        return col;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  J3 enable / disable
    // ────────────────────────────────────────────────────────────────────────

    /** Enables or disables the entire J3 row. */
    private void setJ3Enabled(boolean enabled) {
        j3ComPortCombo.setEnabled(enabled);
        j3BaudCombo.setEnabled(enabled);
        j3ConnectButton.setEnabled(enabled);
    }

    // ────────────────────────────────────────────────────────────────────────
    //  COM port enumeration
    // ────────────────────────────────────────────────────────────────────────

    private static final String PORT_PLACEHOLDER = "Please select a COM port";

    /**
     * Attaches a {@link PopupMenuListener} that refreshes the port list
     * each time the dropdown is opened.  The previously selected port is
     * preserved if it is still present after the refresh.
     */
    private void addPortRefreshListener(JComboBox<String> combo) {
        combo.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                refreshPortList(combo);
            }

            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { }
            @Override public void popupMenuCanceled(PopupMenuEvent e) { }
        });
    }

    /**
     * Replaces the items in the given combo box with the current set of
     * system serial ports, plus the placeholder prompt.  If the previously
     * selected port is still available it remains selected; otherwise the
     * selection resets to the placeholder.
     */
    private void refreshPortList(JComboBox<String> combo) {
        String previousSelection = (String) combo.getSelectedItem();
        String[] ports = SerialPortList.getPortNames();

        combo.removeAllItems();
        combo.addItem(PORT_PLACEHOLDER);
        for (String port : ports) {
            combo.addItem(port);
        }

        // Restore previous selection if the port is still present.
        if (previousSelection != null && !PORT_PLACEHOLDER.equals(previousSelection)) {
            boolean found = false;
            for (String port : ports) {
                if (port.equals(previousSelection)) {
                    found = true;
                    break;
                }
            }
            combo.setSelectedItem(found ? previousSelection : PORT_PLACEHOLDER);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Indicator helpers
    // ────────────────────────────────────────────────────────────────────────

    private static final String ICON_CHECK = "\u2713"; // ✓
    private static final String ICON_X     = "\u2717"; // ✗

    /**
     * Creates an indicator label showing a green check or red X.
     */
    private JLabel createIndicator(boolean ok) {
        JLabel label = new JLabel();
        label.setFont(label.getFont().deriveFont(Font.BOLD, 14f));
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setPreferredSize(new Dimension(28, 20));
        setIndicator(label, ok);
        return label;
    }

    /** Updates an indicator label to show check (green) or X (red). */
    private void setIndicator(JLabel label, boolean ok) {
        label.setText(ok ? ICON_CHECK : ICON_X);
        label.setForeground(ok ? DETECTED_GREEN : ERROR_RED);
    }

    /**
     * Shows or hides the "Detected" column (header + all per-product
     * indicators).  Called by the owning frame when the manufacturing
     * features toggle changes.
     */
    public void setManufacturingEnabled(boolean enabled) {
        for (JComponent comp : detectedColumnComponents) {
            comp.setVisible(enabled);
        }
        revalidate();
        repaint();
    }

    /** Updates the lexicon indicator for the given product. */
    public void setLexiconAvailable(String product, boolean available) {
        lexiconAvailable.put(product, available);
        JLabel ind = lexiconIndicators.get(product);
        if (ind != null) setIndicator(ind, available);
    }

    /** Updates the plugin indicator for the given product. */
    public void setPluginAvailable(String product, boolean available) {
        pluginAvailable.put(product, available);
        JLabel ind = pluginIndicators.get(product);
        if (ind != null) setIndicator(ind, available);
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Product selection handler
    // ────────────────────────────────────────────────────────────────────────

    private void onProductSelected(String product) {
        // IMet-XF and IMet-4RSB are placeholders — selecting them does nothing.
        if ("IMet-XF".equals(product) || "IMet-4RSB".equals(product)) {
            detailsCardPanel.setVisible(false);
            return;
        }

        boolean hasLexicon = Boolean.TRUE.equals(lexiconAvailable.get(product));
        boolean hasPlugin  = Boolean.TRUE.equals(pluginAvailable.get(product));

        if (!hasLexicon || !hasPlugin) {
            detailsCardPanel.setVisible(false);
            productGroup.clearSelection();
            showMissingResourceNotice(product, hasLexicon, hasPlugin);
            return;
        }

        detailsCardPanel.setVisible(true);
        detailsCardLayout.show(detailsCardPanel, product);
        revalidate();
        repaint();

        if (deviceSelectionListener != null) {
            deviceSelectionListener.onDeviceSelected(product);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Connection handlers
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Handles the J1 Connect / Disconnect button.
     * <p>
     * On <b>Connect</b>: validates the selected port, opens it at
     * 115200 baud 8-N-1, and has the X4 plugin verify the device
     * (it sends {@code /ACK?}).  Runs on a background thread to avoid
     * blocking the EDT.
     * On success the J3 row is enabled; on failure an error dialog
     * is shown.
     * <p>
     * On <b>Disconnect</b>: closes the port and resets the UI.
     */
    private void onJ1Connect() {
        // ── Disconnect path ──
        if (j1Connected) {
            // J3 cannot exist without J1 — tear it down first if active.
            if (j3Connected) {
                forceDisconnectJ3();
            }
            if (j1Connection != null) {
                j1Connection.disconnect();
                j1Connection = null;
            }
            j1Connected = false;
            j1ConnectButton.setText("Connect");
            j1ConnectButton.setBackground(BUTTON_COLOR);
            j1ComPortCombo.setEnabled(true);
            setJ3Enabled(false);
            if (connectionListener != null) connectionListener.accept(null);
            return;
        }

        // ── Connect path ──
        String selectedPort = (String) j1ComPortCombo.getSelectedItem();
        if (selectedPort == null || PORT_PLACEHOLDER.equals(selectedPort)) {
            showErrorNotice("Please select a COM port first.");
            return;
        }

        PluginTest plugin = Loader.GetPluginX4();
        if (plugin == null) {
            showErrorNotice("The X4 plugin is not loaded.");
            return;
        }

        // Disable the button while the connection attempt runs.
        j1ConnectButton.setEnabled(false);
        j1ConnectButton.setText("Connecting...");

        new SwingWorker<Void, Void>() {
            private String errorMessage;

            /** The open connection; only set if every step succeeded. */
            private X4Connection openedConnection;

            /**
             * The baud rate string returned by {@code /CJ3?\r\n}, e.g. {@code "115200"}.
             * {@code null} if the query failed or the response was unparseable.
             */
            private String queriedJ3Baud;

            /**
             * Set when the device returns a J3 baud rate that is not in the
             * supported list, so we can show a targeted warning in {@link #done()}.
             */
            private String unrecognizedJ3Baud;

            @Override
            protected Void doInBackground() {
                X4Connection connection = new X4Connection(plugin);
                try {
                    connection.connect(selectedPort);

                    // ── Query J3 baud rate ──────────────────────────────────
                    // Response format (§3.3.3): "CJ3=<value>" (no leading slash).
                    String response = connection.sendCommand("/CJ3?\r\n");
                    if (response != null && response.startsWith("CJ3=")) {
                        String candidate = response.substring(4).trim();
                        boolean supported = false;
                        for (String rate : J3_BAUD_RATES) {
                            if (rate.equals(candidate)) {
                                supported = true;
                                break;
                            }
                        }
                        if (supported) {
                            queriedJ3Baud = candidate;
                        } else {
                            unrecognizedJ3Baud = candidate;
                        }
                    }
                    // If response is null or malformed we leave both fields null
                    // and warn the user in done().

                    openedConnection = connection;

                } catch (Exception e) {
                    // The port may already be open (e.g. /CJ3? failed after
                    // the handshake), so release it or the next attempt
                    // finds the port busy.
                    connection.disconnect();
                    errorMessage = e.getMessage();
                }
                return null;
            }

            @Override
            protected void done() {
                if (errorMessage != null) {
                    // ── Connection failed ──
                    j1ConnectButton.setText("Connect");
                    j1ConnectButton.setBackground(BUTTON_COLOR);
                    j1ConnectButton.setEnabled(true);
                    showErrorNotice(errorMessage);
                } else {
                    // ── Connection succeeded ──
                    j1Connection = openedConnection;
                    j1Connected = true;
                    j1ConnectButton.setText("Disconnect");
                    j1ConnectButton.setBackground(DETECTED_GREEN);
                    j1ConnectButton.setEnabled(true);
                    j1ComPortCombo.setEnabled(false);
                    setJ3Enabled(true);

                    // Notify interested panels (e.g. ConfigurationPanel) that a
                    // connection is now available.
                    if (connectionListener != null) connectionListener.accept(j1Connection);

                    // Apply the queried J3 baud rate, or warn if unavailable.
                    if (queriedJ3Baud != null) {
                        j3BaudCombo.setSelectedItem(queriedJ3Baud);
                    } else if (unrecognizedJ3Baud != null) {
                        showErrorNotice(
                                "Device reported an unrecognized J3 baud rate: \""
                                        + unrecognizedJ3Baud + "\".<br><br>"
                                        + "Supported rates are: 9600, 19200, 38400, 57600, 115200.<br>"
                                        + "Please select the correct rate manually before connecting J3.");
                    } else {
                        // No response to /CJ3? — warn but don't block the user.
                        showErrorNotice(
                                "Could not query the J3 baud rate from the device.<br><br>"
                                        + "The default (115200) has been left selected. "
                                        + "Please verify the baud rate before connecting J3.");
                    }
                }
            }
        }.execute();
    }

    /**
     * Handles the J3 Connect / Disconnect button.
     * <p>
     * On <b>Connect</b>: validates the selected port, opens it at the baud
     * rate shown in the J3 dropdown with 8-N-1 settings (no handshake —
     * J3 is a data-output port, not a command port).  Runs on a background
     * thread to avoid blocking the EDT.  On success the button turns green
     * and shows "Connected"; on failure an error dialog appears.
     * <p>
     * On <b>Connected</b> (i.e. already connected): closes the port and
     * resets the J3 UI row.
     */
    private void onJ3Connect() {
        if (!j1Connected) {
            showErrorNotice("J3 requires an active J1 connection first.");
            return;
        }

        // ── Disconnect path ──
        if (j3Connected) {
            forceDisconnectJ3();
            return;
        }

        // ── Connect path ──
        String selectedPort = (String) j3ComPortCombo.getSelectedItem();
        if (selectedPort == null || PORT_PLACEHOLDER.equals(selectedPort)) {
            showErrorNotice("Please select a COM port for J3 first.");
            return;
        }

        String baudStr = (String) j3BaudCombo.getSelectedItem();
        int baudRate;
        try {
            baudRate = Integer.parseInt(baudStr);
        } catch (NumberFormatException ex) {
            showErrorNotice("Invalid baud rate selected for J3: " + baudStr);
            return;
        }

        // Disable controls while the connection attempt runs.
        j3ConnectButton.setEnabled(false);
        j3ConnectButton.setText("Connecting...");
        j3ComPortCombo.setEnabled(false);
        j3BaudCombo.setEnabled(false);

        final int finalBaudRate = baudRate;
        new SwingWorker<Void, Void>() {
            private String errorMessage;

            @Override
            protected Void doInBackground() {
                try {
                    J3Connection connection = new J3Connection();
                    connection.connect(selectedPort, finalBaudRate);
                    j3Connection = connection;
                } catch (Exception e) {
                    errorMessage = e.getMessage();
                }
                return null;
            }

            @Override
            protected void done() {
                if (errorMessage != null) {
                    // ── Connection failed — restore controls ──
                    j3ConnectButton.setText("Connect");
                    j3ConnectButton.setBackground(BUTTON_COLOR);
                    j3ConnectButton.setEnabled(true);
                    j3ComPortCombo.setEnabled(true);
                    j3BaudCombo.setEnabled(true);
                    showErrorNotice(errorMessage);
                } else {
                    // ── Connection succeeded ──
                    j3Connected = true;
                    j3ConnectButton.setText("Disconnect");
                    j3ConnectButton.setBackground(DETECTED_GREEN);
                    j3ConnectButton.setEnabled(true);
                    // Port and baud are locked while connected.
                    j3ComPortCombo.setEnabled(false);
                    j3BaudCombo.setEnabled(false);
                }
            }
        }.execute();
    }

    /**
     * Tears down the J3 connection and resets the J3 UI row to its
     * disconnected state.  Safe to call whether or not J3 is actually
     * connected.  Always runs on the EDT.
     */
    private void forceDisconnectJ3() {
        if (j3Connection != null) {
            j3Connection.disconnect();
            j3Connection = null;
        }
        j3Connected = false;
        j3ConnectButton.setText("Connect");
        j3ConnectButton.setBackground(BUTTON_COLOR);
        j3ComPortCombo.setEnabled(true);
        j3BaudCombo.setEnabled(true);
    }

    /**
     * Simulates connecting to the XQ2.
     * In a real build this would open the selected COM port at 57600 baud, 8N1.
     */
    private void onXQ2Connect() {
        showSimulatedPortNotice("XQ2", "57600", "8N1");
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Notification dialogs
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Warning shown when a product is selected but its lexicon and/or
     * plugin cannot be found.  Tells the user which files are missing
     * and where to put them.
     */
    private void showMissingResourceNotice(String product,
                                           boolean hasLexicon,
                                           boolean hasPlugin) {
        Window owner = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(owner, "Missing Resources");
        dialog.setModal(false);
        dialog.setResizable(false);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        content.setBackground(CARD_BG);

        JLabel heading = new JLabel("Missing Resources");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 15f));
        heading.setForeground(WARN_ORANGE);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(heading);

        content.add(Box.createVerticalStrut(10));

        StringBuilder sb = new StringBuilder("<html><body style='width:320px;'>");
        sb.append("Cannot connect to <b>").append(product).append("</b>. ");

        if (!hasLexicon && !hasPlugin) {
            sb.append("No lexicon or plugin was found. "
                    + "Please place the lexicon file in the <b>\\lexicons</b> "
                    + "directory and the plugin file in the <b>\\plugins</b> directory.");
        } else if (!hasLexicon) {
            sb.append("No lexicon file was found. "
                    + "Please place the appropriate lexicon file in the "
                    + "<b>\\lexicons</b> directory.");
        } else {
            sb.append("No plugin was found. "
                    + "Please place the appropriate plugin file in the "
                    + "<b>\\plugins</b> directory.");
        }

        sb.append("</body></html>");

        JLabel body = new JLabel(sb.toString());
        body.setFont(body.getFont().deriveFont(Font.PLAIN, 13f));
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(body);

        content.add(Box.createVerticalStrut(14));

        JButton okButton = new JButton("OK");
        okButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        okButton.addActionListener(e -> dialog.dispose());
        content.add(okButton);

        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setAlwaysOnTop(true);
        dialog.setVisible(true);
    }

    /**
     * Non-modal warning shown when a Connect button is clicked.
     * Describes what would happen during a real connection.
     */
    private void showSimulatedPortNotice(String port, String baud, String encoding) {
        Window owner = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(owner, "Simulated Connection");
        dialog.setModal(false);
        dialog.setResizable(false);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        content.setBackground(CARD_BG);

        JLabel heading = new JLabel("Simulated Connection \u2014 " + port);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 15f));
        heading.setForeground(WARN_ORANGE);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(heading);

        content.add(Box.createVerticalStrut(10));

        String message = "<html><body style='width:320px;'>"
                + "Serial connectivity is not yet implemented. "
                + "In a real build the software would now open the selected COM port "
                + "at <b>" + baud + " baud, " + encoding + "</b>"
                + (port.equals("J1")
                ? " and send <code>/ACK?</code> to verify the device is responding, "
                + "then query <code>/CJ3?</code> to determine the J3 baud rate."
                : ".")
                + "<br><br>No actual device communication has occurred."
                + "</body></html>";
        JLabel body = new JLabel(message);
        body.setFont(body.getFont().deriveFont(Font.PLAIN, 13f));
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(body);

        content.add(Box.createVerticalStrut(14));

        JButton dismissButton = new JButton("Acknowledged");
        dismissButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        dismissButton.addActionListener(e -> dialog.dispose());
        content.add(dismissButton);

        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setAlwaysOnTop(true);
        dialog.setVisible(true);
    }

    /**
     * Non-modal error dialog using the SoW red (255, 25, 25) for genuine
     * error conditions such as attempting to connect J3 before J1.
     */
    private void showErrorNotice(String errorMessage) {
        Window owner = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(owner, "Error");
        dialog.setModal(false);
        dialog.setResizable(false);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        content.setBackground(CARD_BG);

        JLabel heading = new JLabel("Error");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 15f));
        heading.setForeground(ERROR_RED);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(heading);

        content.add(Box.createVerticalStrut(10));

        JLabel body = new JLabel("<html><body style='width:300px;'>"
                + errorMessage + "</body></html>");
        body.setFont(body.getFont().deriveFont(Font.PLAIN, 13f));
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(body);

        content.add(Box.createVerticalStrut(14));

        JButton dismissButton = new JButton("OK");
        dismissButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        dismissButton.addActionListener(e -> dialog.dispose());
        content.add(dismissButton);

        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setAlwaysOnTop(true);
        dialog.setVisible(true);
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Tab renaming
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Walk up the component tree to find the enclosing JTabbedPane and
     * rename the tab that contains this panel to the selected product name.
     */
    public void renameParentTab(String title) {
        Component comp = this;
        while (comp != null) {
            Container parent = comp.getParent();
            if (parent instanceof JTabbedPane tabbedPane) {
                int index = tabbedPane.indexOfComponent(comp);
                if (index >= 0) {
                    Component header = tabbedPane.getTabComponentAt(index);
                    if (header instanceof JPanel headerPanel) {
                        for (Component child : headerPanel.getComponents()) {
                            if (child instanceof JLabel label && !"\u00D7".equals(label.getText())) {
                                label.setText(title);
                                break;
                            }
                        }
                    }
                    tabbedPane.setTitleAt(index, title);
                }
                return;
            }
            comp = parent;
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Styled button (matches ConfigurationPanel style)
    // ────────────────────────────────────────────────────────────────────────

    private JButton createStyledButton(String text) {
        JButton button = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                Color base = getBackground();
                Color bg;
                if (!isEnabled()) {
                    bg = new Color(180, 180, 180);
                } else if (getModel().isPressed()) {
                    bg = base.darker();
                } else if (getModel().isRollover()) {
                    bg = base.brighter();
                } else {
                    bg = base;
                }

                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);

                g2.setColor(BUTTON_TEXT);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int textX = (getWidth() - fm.stringWidth(getText())) / 2;
                int textY = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(getText(), textX, textY);

                g2.dispose();
            }
        };
        button.setBackground(BUTTON_COLOR);
        button.setFont(button.getFont().deriveFont(Font.PLAIN, 12f));
        button.setPreferredSize(new Dimension(
                button.getFontMetrics(button.getFont()).stringWidth(text) + 32, 32));
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setOpaque(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    @Override
    public void onDevicesChanged(Set<DiscoveredDevice> devices) {
        this.discoveredDevices = devices;

        // Build a set of product names that have a detected device.
        Set<String> detectedProducts = new java.util.HashSet<>();
        for (DiscoveredDevice device : devices) {
            switch (device.deviceType()) {
                case IMET_X4 -> detectedProducts.add("IMet-X4");
                // Future device types map here.
            }
        }

        // Update each detected indicator.
        for (Map.Entry<String, JLabel> entry : detectedIndicators.entrySet()) {
            setIndicator(entry.getValue(), detectedProducts.contains(entry.getKey()));
        }
    }
}