package com.imet.ui.views;

import com.imet.Console;
import com.imet.device.X4Connection;
import com.imet.plugin.PluginTest;

import javax.swing.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Configuration view panel. Displays:
 * <ul>
 *   <li>Product image on the left</li>
 *   <li>Board-level settings across the top (serial number, publishing freq,
 *       pressure bias, baud rate, data toggle)</li>
 *   <li>Port rows J9–J4 with sensor-type dropdowns</li>
 *   <li>Action buttons along the bottom (Restore Defaults | Fetch, Load, Save, Configure)</li>
 * </ul>
 *
 * <p>Call {@link #setConnection(X4Connection)} when the J1 connection is
 * established or dropped.  A live connection enables the Fetch button, which
 * queries the device for all current settings and populates the form fields.
 */
public class ConfigurationPanel extends JPanel {

    private static final Color BACKGROUND  = new Color(228, 230, 235);
    private static final Color CARD_BG     = Color.WHITE;
    private static final Color BUTTON_COLOR = new Color(122, 162, 214);
    private static final Color BUTTON_TEXT  = Color.WHITE;
    private static final Color ERROR_RED   = new Color(255, 25, 25);

    // ── Board-row interactive fields ─────────────────────────────────────────
    // Promoted to instance scope so applyResults() can write to them after fetch.

    /** X4 Serial Number — free-text, up to 12 alphanumeric characters. */
    private JTextField srnField;

    /**
     * Data Publishing Frequency — floating-point, 0.0–10.0 Hz (§3.3.6).
     * Model uses double rather than int so fractional firmware values round-trip
     * without loss.
     */
    private JSpinner dpfSpinner;

    /**
     * Pressure Bias — floating-point, -99999.99–99999.99 (§3.3.8).
     * Factory-calibrated; model uses double.
     */
    private JSpinner cpbSpinner;

    /** J3 Baud Rate combo — values as defined in §3.3.3. */
    private JComboBox<String> j3BaudCombo;

    /** Data publishing toggle — maps to /TDP=ON|OFF (§3.3.9). */
    private JToggleButton dataToggle;

    /**
     * Port sensor-type combos keyed by port number (4–9).
     * Populated bottom-to-top (J9 first) in {@link #createSettingsPanel()}.
     */
    private final Map<Integer, JComboBox<String>> portCombos = new LinkedHashMap<>();

    // ── Action buttons ───────────────────────────────────────────────────────

    // (No Fetch button — fetch is triggered automatically on tab focus.)

    // ── Status label ─────────────────────────────────────────────────────────

    /**
     * Unobtrusive label shown to the right of the "Configuration" heading.
     * Displays "Fetching…" while a fetch is in progress, "Synced" once
     * complete, or is blank when no connection is active.
     */
    private JLabel statusLabel;

    // ── Connection ───────────────────────────────────────────────────────────

    /** Active J1 connection, supplied by the parent when J1 connects or drops. */
    private X4Connection connection;

    // ────────────────────────────────────────────────────────────────────────
    //  Construction
    // ────────────────────────────────────────────────────────────────────────

    public ConfigurationPanel() {
        setLayout(new BorderLayout());
        setBackground(BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JPanel card = new JPanel(new BorderLayout(16, 12));
        card.setBackground(CARD_BG);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xD0D0D0)),
                BorderFactory.createEmptyBorder(16, 20, 16, 20)
        ));

        // ── Heading row: bold title + unobtrusive status label ──
        JPanel headingRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        headingRow.setBackground(CARD_BG);

        JLabel heading = new JLabel("Configuration");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 18f));
        headingRow.add(heading);

        statusLabel = new JLabel("");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 12f));
        statusLabel.setForeground(new Color(0x999999));
        headingRow.add(statusLabel);

        card.add(headingRow, BorderLayout.NORTH);

        JPanel centre = new JPanel(new BorderLayout(20, 0));
        centre.setBackground(CARD_BG);
        centre.add(createImagePanel(), BorderLayout.WEST);
        centre.add(createSettingsPanel(), BorderLayout.CENTER);
        card.add(centre, BorderLayout.CENTER);

        card.add(createButtonBar(), BorderLayout.SOUTH);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(BACKGROUND);
        wrapper.add(card, BorderLayout.NORTH);
        add(wrapper, BorderLayout.CENTER);

        // Auto-fetch whenever this panel becomes visible (e.g. user clicks
        // "Configuration" in the breadcrumb, or navigates back to the tab).
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                performFetch();
            }
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Connection management
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Called by the parent panel when the J1 connection is established or
     * dropped.  A non-null value enables the Fetch button; {@code null}
     * disables it.
     *
     * @param conn the active {@link X4Connection}, or {@code null} if
     *             the connection has been closed
     */
    public void setConnection(X4Connection conn) {
        this.connection = conn;
        if (conn != null && conn.isConnected()) {
            // If the Configuration panel is already the active card, fetch now;
            // otherwise the ComponentListener will fire when the user navigates here.
            if (isShowing()) {
                performFetch();
            }
        } else {
            // Connection dropped — clear the status so stale "Synced" isn't shown.
            statusLabel.setText("");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Fetch from device
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Asks the device, through the X4 plugin, for its current settings and
     * populates the panel fields with the returned values.
     *
     * <p>Lexicon messages used (the X4 command each polls in brackets):
     * <pre>
     *   serialNumber (/SRN?)   dataPublishingFrequency (/DPF?)
     *   pressureBias (/CPB?)   j3BaudRate (/CJ3?)
     *   toggleDataPublishing (/TDP?)
     *   configurePortTo... for ports 4-9 (/CJ4? - /CJ9?)
     * </pre>
     *
     * <p>The plugin skips streamed data lines, so this works with data
     * publishing on.  A setting with no reply is left at its current value.
     */
    private void performFetch() {
        // Silent no-op if not connected — called speculatively from componentShown
        // before J1 may be established.
        if (connection == null || !connection.isConnected()) return;

        statusLabel.setText("Fetching\u2026"); // "Fetching…"
        statusLabel.setForeground(new Color(122, 162, 214)); // blue, matches app accent

        new SwingWorker<Map<String, String>, Void>() {

            @Override
            protected Map<String, String> doInBackground() {
                PluginTest plugin = connection.getPlugin();
                // Keyed by the X4 setting, as applyResults() expects.
                Map<String, String> results = new LinkedHashMap<>();

                String[][] settings = {
                        {"SRN", "serialNumber"},
                        {"DPF", "dataPublishingFrequency"},
                        {"CPB", "pressureBias"},
                        {"CJ3", "j3BaudRate"},
                        {"TDP", "toggleDataPublishing"},
                };
                for (String[] setting : settings) {
                    String value = ascii(plugin.messageType_q(bytes(setting[1])));
                    if (value != null) {
                        results.put(setting[0], value.trim());
                    } else {
                        Console.log("Fetch got no " + setting[1]);
                    }
                }

                // Port sensors, as sensor IDs.
                for (int port = 4; port <= 9; port++) {
                    Integer id = querySensorId(plugin, port);
                    if (id != null) {
                        results.put("CJ" + port, id.toString());
                    }
                }

                return results;
            }

            @Override
            protected void done() {
                Map<String, String> results;
                try {
                    results = get();
                } catch (ExecutionException ex) {
                    Console.log("Fetch failed: " + ex.getCause());
                    statusLabel.setText("Fetch failed");
                    statusLabel.setForeground(new Color(255, 25, 25));
                    return;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }

                applyResults(results);
                statusLabel.setText("Synced");
                statusLabel.setForeground(new Color(0x999999));
            }
        }.execute();
    }

    /**
     * Applies a map of {@code KEY → value} pairs returned by the device to the
     * corresponding UI fields.  Unknown or missing keys are silently skipped so
     * that a partial fetch (e.g. one field missed due to streaming noise) does
     * not reset unrelated fields.
     */
    private void applyResults(Map<String, String> results) {

        // Serial number (SRN=<alphanumeric, ≤12 chars>)
        String srn = results.get("SRN");
        if (srn != null) srnField.setText(srn);

        // Data publishing frequency (DPF=<float 0–10>)
        String dpf = results.get("DPF");
        if (dpf != null) {
            try { dpfSpinner.setValue(Double.parseDouble(dpf)); }
            catch (NumberFormatException ignored) {}
        }

        // Pressure bias (CPB=<float>)
        String cpb = results.get("CPB");
        if (cpb != null) {
            try { cpbSpinner.setValue(Double.parseDouble(cpb)); }
            catch (NumberFormatException ignored) {}
        }

        // J3 baud rate (CJ3=<integer baud>)
        String cj3 = results.get("CJ3");
        if (cj3 != null) j3BaudCombo.setSelectedItem(cj3.trim());

        // Data publishing toggle (toggleDataPublishing: 1 = ON, 0 = OFF)
        String tdp = results.get("TDP");
        if (tdp != null) dataToggle.setSelected("1".equals(tdp.trim()));

        // Port sensor types (CJ4–CJ9), as sensor IDs (§4.3)
        for (int port = 4; port <= 9; port++) {
            String val = results.get("CJ" + port);
            if (val == null) continue;

            try {
                selectSensorById(portCombos.get(port), Integer.parseInt(val));
            } catch (NumberFormatException ignored) {}
        }
    }

    /**
     * Asks the plugin which sensor a port is set to, using the lexicon's
     * configurePortTo... parameterized queries.  Only the sensors this
     * screen offers for the port are checked (see getSensorOptions()).
     *
     * @return the sensor ID, or {@code null} if a query got no reply or the
     *         port is set to a sensor this screen doesn't offer
     */
    private static Integer querySensorId(PluginTest plugin, int port) {
        String empty = ascii(plugin.messageType_p(bytes("configurePortToEmpty=" + port)));
        if (empty == null) {
            Console.log("Fetch got no sensor for J" + port);
            return null;
        }
        if (empty.equals("1")) {
            return 0;
        }

        String message;
        int sensorId;
        switch (port) {
            case 4, 5 -> {
                message = "configurePortToHYT271";
                sensorId = 4;
            }
            case 8, 9 -> {
                message = "configurePortToAirThermistor";
                sensorId = 3;
            }
            default -> {
                Console.log("Fetch: J" + port + " is set to a sensor this screen doesn't offer");
                return null;
            }
        }

        byte[] values = plugin.messageType_p(bytes(message + "=" + port));
        if (values == null) {
            Console.log("Fetch got no sensor for J" + port);
            return null;
        }
        if (values.length == 0) {
            Console.log("Fetch: J" + port + " is set to a sensor this screen doesn't offer");
            return null;
        }
        return sensorId;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static String ascii(byte[] b) {
        return (b == null) ? null : new String(b, StandardCharsets.US_ASCII);
    }

    /**
     * Selects the combo-box item corresponding to the given peripheral sensor ID
     * (see §4.3 of the X4 User Guide).  If the ID is unrecognised or the
     * matching string is not present in this combo (e.g. a temperature sensor ID
     * on a J4/J5 combo that only lists humidity options), falls back silently to
     * "No Sensor" at index 0.
     */
    private void selectSensorById(JComboBox<String> combo, int sensorId) {
        if (combo == null) return;

        String target = switch (sensorId) {
            case 0 -> "No Sensor";
            case 3 -> "10085B iMet-X4 Temperature Sensor";
            case 4 -> "10086B iMet-X4 Humidity Sensor";
            // IDs 1, 2, 5, 6, 7 (§4.3.5) are reserved / not exposed in the UI yet.
            default -> null;
        };

        if (target != null) {
            for (int i = 0; i < combo.getItemCount(); i++) {
                if (target.equals(combo.getItemAt(i))) {
                    combo.setSelectedItem(target);
                    return;
                }
            }
        }

        // Fallback — "No Sensor" is always index 0 in every port's option list.
        if (combo.getItemCount() > 0) combo.setSelectedIndex(0);
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Product image
    // ────────────────────────────────────────────────────────────────────────

    private JPanel createImagePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(CARD_BG);

        JLabel imageLabel;
        var imageUrl = getClass().getClassLoader().getResource("product_img.png");
        if (imageUrl != null) {
            ImageIcon rawIcon = new ImageIcon(imageUrl);
            Image scaled = rawIcon.getImage().getScaledInstance(-1, 380, Image.SCALE_SMOOTH);
            imageLabel = new JLabel(new ImageIcon(scaled));
        } else {
            imageLabel = new JLabel("[ product image ]", SwingConstants.CENTER);
            imageLabel.setPreferredSize(new Dimension(180, 380));
            imageLabel.setBorder(BorderFactory.createLineBorder(new Color(0xCCCCCC)));
            imageLabel.setForeground(Color.GRAY);
        }

        panel.add(imageLabel, BorderLayout.NORTH);
        return panel;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Settings area (board row + port rows)
    // ────────────────────────────────────────────────────────────────────────

    private JPanel createSettingsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(CARD_BG);

        panel.add(createBoardRow());
        panel.add(Box.createVerticalStrut(16));

        // Port rows J9 down to J4 (top to bottom matches the physical connector order).
        for (int port = 9; port >= 4; port--) {
            panel.add(createPortRow("J" + port, port));
            if (port > 4) panel.add(Box.createVerticalStrut(12));
        }

        return panel;
    }

    /**
     * Top row: Board | X4 Serial Number | Data Publishing Freq. |
     *          Pressure Bias | J3 Baud Rate | Data toggle | ⓘ
     */
    private JPanel createBoardRow() {
        JPanel row = new JPanel(new GridBagLayout());
        row.setBackground(CARD_BG);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(0, 8, 0, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill   = GridBagConstraints.NONE;
        gbc.gridy  = 0;

        // "Board" label spanning two rows so it aligns with the field labels.
        gbc.gridx      = 0;
        gbc.gridheight = 2;
        gbc.anchor     = GridBagConstraints.NORTHWEST;
        JLabel boardLabel = new JLabel("Board");
        boardLabel.setFont(boardLabel.getFont().deriveFont(Font.PLAIN, 13f));
        row.add(boardLabel, gbc);
        gbc.gridheight = 1;
        gbc.anchor     = GridBagConstraints.WEST;

        // X4 Serial Number
        srnField = new JTextField(10);
        gbc.gridx = 1;
        row.add(createFieldColumn("X4 Serial Number", srnField), gbc);

        // Data Publishing Freq. — double model, 0.0–10.0 Hz (spec §3.3.6 says ≤10 Hz).
        dpfSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 10.0, 0.5));
        gbc.gridx = 2;
        row.add(createFieldColumn("Data Publishing Freq.", dpfSpinner), gbc);

        // Pressure Bias — double model, ±99999.99 (spec §3.3.8).
        cpbSpinner = new JSpinner(new SpinnerNumberModel(0.0, -99999.99, 99999.99, 0.01));
        gbc.gridx = 3;
        row.add(createFieldColumn("Pressure Bias", cpbSpinner), gbc);

        // J3 Baud Rate
        j3BaudCombo = new JComboBox<>(new String[]{"9600", "19200", "38400", "57600", "115200"});
        j3BaudCombo.setSelectedItem("115200");
        gbc.gridx = 4;
        row.add(createFieldColumn("J3 Baud Rate", j3BaudCombo), gbc);

        // Data toggle
        gbc.gridx = 5;
        JPanel dataCol = new JPanel();
        dataCol.setLayout(new BoxLayout(dataCol, BoxLayout.Y_AXIS));
        dataCol.setBackground(CARD_BG);
        JLabel dataLabel = new JLabel("Data");
        dataLabel.setFont(dataLabel.getFont().deriveFont(Font.PLAIN, 12f));
        dataLabel.setForeground(new Color(0x666666));
        dataLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        dataCol.add(dataLabel);
        dataCol.add(Box.createVerticalStrut(4));
        dataToggle = createToggleSwitch();
        dataToggle.setAlignmentX(Component.LEFT_ALIGNMENT);
        dataCol.add(dataToggle);
        row.add(dataCol, gbc);

        // Info icon
        gbc.gridx = 6;
        JLabel infoIcon = new JLabel("\u24D8");
        infoIcon.setFont(infoIcon.getFont().deriveFont(Font.BOLD, 18f));
        infoIcon.setForeground(new Color(122, 162, 214));
        infoIcon.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        infoIcon.setToolTipText("Board information");
        row.add(infoIcon, gbc);

        return row;
    }

    /**
     * Creates one port row and registers the combo box in {@link #portCombos}.
     *
     * @param portName  display label, e.g. "J9"
     * @param portNumber numeric port number (4–9), used as the map key
     */
    private JPanel createPortRow(String portName, int portNumber) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        row.setBackground(CARD_BG);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel portLabel = new JLabel(portName);
        portLabel.setFont(portLabel.getFont().deriveFont(Font.PLAIN, 13f));
        portLabel.setPreferredSize(new Dimension(30, 28));
        row.add(portLabel);

        JComboBox<String> sensorCombo = new JComboBox<>(getSensorOptions(portNumber));
        sensorCombo.setPreferredSize(new Dimension(260, 28));
        row.add(sensorCombo);

        portCombos.put(portNumber, sensorCombo);
        return row;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Toggle switch
    // ────────────────────────────────────────────────────────────────────────

    private JToggleButton createToggleSwitch() {
        JToggleButton toggle = new JToggleButton() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth(), h = getHeight(), arc = h;
                g2.setColor(isSelected() ? new Color(50, 220, 20) : new Color(255, 25, 25));
                g2.fillRoundRect(0, 0, w, h, arc, arc);

                int thumbDiameter = h - 4;
                int thumbX = isSelected() ? w - thumbDiameter - 2 : 2;
                g2.setColor(Color.WHITE);
                g2.fillOval(thumbX, 2, thumbDiameter, thumbDiameter);

                g2.dispose();
            }
        };
        toggle.setPreferredSize(new Dimension(44, 22));
        toggle.setMinimumSize(new Dimension(44, 22));
        toggle.setMaximumSize(new Dimension(44, 22));
        toggle.setContentAreaFilled(false);
        toggle.setBorderPainted(false);
        toggle.setFocusPainted(false);
        toggle.setOpaque(false);
        return toggle;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Bottom button bar
    // ────────────────────────────────────────────────────────────────────────

    private JPanel createButtonBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(CARD_BG);
        bar.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));

        // Left: Restore Defaults
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setBackground(CARD_BG);
        left.add(createStyledButton("Restore Defaults"));
        bar.add(left, BorderLayout.WEST);

        // Right: Load | Save | Configure
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setBackground(CARD_BG);
        right.add(createStyledButton("Load"));
        right.add(createStyledButton("Save"));
        right.add(createStyledButton("Configure"));
        bar.add(right, BorderLayout.EAST);

        return bar;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Helpers
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
        if (field instanceof JSpinner spinner) {
            spinner.setPreferredSize(new Dimension(100, 28));
        }
        col.add(field);
        return col;
    }

    /**
     * Styled rounded button matching the HardwarePanel button style.
     * Reads {@code getBackground()} as the base colour so the disabled
     * state (grey) works without special-casing.
     */
    private JButton createStyledButton(String text) {
        JButton button = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);

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

    /**
     * Returns the sensor options available for a given port number.
     * Matches the peripheral assignments in the X4 User Guide §2.3:
     * J8/J9 → temperature (analog), J6/J7 → third-party (no default),
     * J4/J5 → humidity (I²C).
     */
    private String[] getSensorOptions(int port) {
        return switch (port) {
            case 8, 9 -> new String[]{"No Sensor", "10085B iMet-X4 Temperature Sensor"};
            case 6, 7 -> new String[]{"No Sensor"};
            case 4, 5 -> new String[]{"No Sensor", "10086B iMet-X4 Humidity Sensor"};
            default   -> new String[]{"No Sensor"};
        };
    }

    /**
     * Non-modal error dialog styled consistently with the rest of the app.
     */
    private void showErrorDialog(String message) {
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

        JLabel body = new JLabel(
                "<html><body style='width:300px;'>" + message + "</body></html>");
        body.setFont(body.getFont().deriveFont(Font.PLAIN, 13f));
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(body);
        content.add(Box.createVerticalStrut(14));

        JButton ok = new JButton("OK");
        ok.setAlignmentX(Component.LEFT_ALIGNMENT);
        ok.addActionListener(e -> dialog.dispose());
        content.add(ok);

        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setAlwaysOnTop(true);
        dialog.setVisible(true);
    }
}