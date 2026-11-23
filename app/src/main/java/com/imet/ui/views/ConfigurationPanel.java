package com.imet.ui.views;

import com.imet.device.X4Connection;
import com.imet.device.X4Settings;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Configuration view panel. Displays:
 * <ul>
 *   <li>Product image on the left</li>
 *   <li>Board-level settings across the top (serial number, publishing freq,
 *       pressure bias, baud rate, data toggle)</li>
 *   <li>Port rows J9–J4 with sensor-type dropdowns, and the fields of the
 *       sensor selected (SoW #16)</li>
 *   <li>Action buttons along the bottom (Restore Defaults | Load from File,
 *       Save to File, Configure X4)</li>
 * </ul>
 *
 * <p>Nothing is written to the X4 until Configure X4 is clicked (#26).
 *
 * <p>Call {@link #setConnection(X4Connection)} when the J1 connection is
 * established or dropped.  On connect the form fields are filled from the
 * settings read from the device (SoW #14); there is no Fetch button (#23).
 */
public class ConfigurationPanel extends JPanel {

    private static final Color BACKGROUND  = new Color(228, 230, 235);
    private static final Color CARD_BG     = Color.WHITE;
    private static final Color BUTTON_COLOR = new Color(122, 162, 214);
    private static final Color BUTTON_TEXT  = Color.WHITE;
    private static final Color ERROR_RED   = new Color(255, 25, 25);
    private static final Color STATUS_GREY = new Color(0x999999);

    /* Sensor names as worded in SoW #16. */
    private static final String NO_SENSOR   = "No Sensor";
    private static final String TEMPERATURE = "10085B iMet-X4 Temperature Sensor";
    private static final String HUMIDITY    = "10086B iMet-X4 Humidity Sensor";

    /**
     * Shown for a port the X4 has set to something this screen doesn't
     * offer for it (serial forward, a reserved ID). While it stays
     * selected, Configure X4 leaves that port alone.
     */
    private static final String OTHER = "Other (left unchanged)";

    /** I2C address of the humidity sensor unless told otherwise (manual 4.3.3). */
    private static final String DEFAULT_I2C_ADDRESS = "0x28";

    /**
     * The fields a port's sensor needs (#16), kept for every port so that
     * what was typed survives changing the dropdown and back.
     *
     * @param atSerial temperature sensor: AT serial number
     * @param tcal     temperature sensor: the four calibration coefficients
     * @param i2c      humidity sensor: I2C address
     */
    private record SensorFields(JTextField atSerial, JTextField[] tcal, JTextField i2c) { }

    // ── Board-row interactive fields ─────────────────────────────────────────
    // Instance scope so applySettings() can write to them.

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

    /** Each port's sensor fields, keyed by port number. */
    private final Map<Integer, SensorFields> sensorFields = new LinkedHashMap<>();

    /** Where a port row shows the fields of the sensor selected. */
    private final Map<Integer, JPanel> sensorAreas = new LinkedHashMap<>();

    // ── Action buttons ───────────────────────────────────────────────────────

    // (No Fetch button — the settings are read when J1 connects.)

    /** Writes the screen to the X4 (#26); enabled only while connected. */
    private JButton configureButton;

    /**
     * Told the device's settings after Configure X4 has written and read
     * them back, so the other screens can follow.
     */
    private Consumer<X4Settings> settingsListener;

    // ── Status label ─────────────────────────────────────────────────────────

    /**
     * Unobtrusive label shown to the right of the "Configuration" heading.
     * Displays "Synced" once the device's settings are shown, names any
     * that could not be read, or is blank when no connection is active.
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

    }

    // ────────────────────────────────────────────────────────────────────────
    //  Connection management
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Called by the parent panel when the J1 connection is established or
     * dropped.  On connect, the fields are filled from the settings that
     * were read from the device at that moment (SoW #14).  They are not
     * read again while connected, so switching to this screen never
     * overwrites what the user has entered.
     *
     * @param conn the active {@link X4Connection}, or {@code null} if
     *             the connection has been closed
     */
    public void setConnection(X4Connection conn) {
        this.connection = conn;
        configureButton.setEnabled(conn != null);
        X4Settings settings = (conn != null) ? conn.getSettings() : null;
        if (settings != null) {
            applySettings(settings);
        } else {
            // Connection dropped — clear the status so stale "Synced" isn't shown.
            statusLabel.setText("");
        }
    }

    /**
     * @param listener called on the EDT with the device's settings each
     *                 time Configure X4 has written and read them back
     */
    public void setSettingsListener(Consumer<X4Settings> listener) {
        this.settingsListener = listener;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Settings from the device
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Shows the settings that were read from the X4 when J1 connected
     * (SoW #14).  A setting that could not be read is left at its current
     * value, and the status label says so.
     */
    private void applySettings(X4Settings settings) {

        // Serial number (alphanumeric, ≤12 chars)
        if (settings.serialNumber() != null) {
            srnField.setText(settings.serialNumber());
        }

        // Data publishing frequency (float, Hz)
        String dpf = settings.dataPublishingFrequency();
        if (dpf != null) {
            try { dpfSpinner.setValue(Double.parseDouble(dpf)); }
            catch (NumberFormatException ignored) {}
        }

        // Pressure bias (float)
        String cpb = settings.pressureBias();
        if (cpb != null) {
            try { cpbSpinner.setValue(Double.parseDouble(cpb)); }
            catch (NumberFormatException ignored) {}
        }

        // J3 baud rate
        if (settings.j3BaudRate() != null) {
            j3BaudCombo.setSelectedItem(settings.j3BaudRate());
        }

        // Data publishing toggle
        if (settings.dataPublishing() != null) {
            dataToggle.setSelected(settings.dataPublishing());
        }

        // Port sensors and their fields (§4.3)
        for (int port = X4Settings.FIRST_PORT; port <= X4Settings.LAST_PORT; port++) {
            X4Settings.Port setting = settings.port(port);
            if (setting != null) {
                applyPort(port, setting);
            }
        }

        clearErrorMarks();
        if (settings.isComplete()) {
            statusLabel.setText("Synced");
            statusLabel.setForeground(STATUS_GREY);
        } else {
            statusLabel.setText("Could not read: "
                    + String.join(", ", settings.missing()));
            statusLabel.setForeground(ERROR_RED);
        }
    }

    /**
     * Shows what a port is set to on the device: selects the sensor in the
     * port's dropdown and fills in that sensor's fields.  A setting this
     * screen doesn't offer for the port is shown as {@link #OTHER}.
     */
    private void applyPort(int port, X4Settings.Port setting) {
        JComboBox<String> combo = portCombos.get(port);
        SensorFields fields = sensorFields.get(port);
        String[] values = setting.values().split(",");

        String item = switch (setting.sensorId()) {
            case X4Settings.SENSOR_NONE        -> NO_SENSOR;
            case X4Settings.SENSOR_TEMPERATURE -> TEMPERATURE;
            case X4Settings.SENSOR_HUMIDITY    -> HUMIDITY;
            default                            -> OTHER;
        };
        if (!hasItem(combo, item)) {
            item = OTHER;
        }

        // OTHER is only listed while the device needs it.
        if (item.equals(OTHER)) {
            if (!hasItem(combo, OTHER)) {
                combo.addItem(OTHER);
            }
        } else if (item.equals(TEMPERATURE) && values.length == 5) {
            fields.atSerial().setText(values[0].trim());
            for (int i = 0; i < 4; i++) {
                fields.tcal()[i].setText(values[1 + i].trim());
                fields.tcal()[i].setCaretPosition(0);
            }
        } else if (item.equals(HUMIDITY) && values.length == 1) {
            fields.i2c().setText(values[0].trim());
        }

        combo.setSelectedItem(item);
        if (!item.equals(OTHER)) {
            combo.removeItem(OTHER);
        }
    }

    private static boolean hasItem(JComboBox<String> combo, String item) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (item.equals(combo.getItemAt(i))) {
                return true;
            }
        }
        return false;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Configure X4 (#26)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Writes the settings on screen to the X4, then reads the X4's
     * settings back.  The X4 doesn't acknowledge a setting, so the
     * read-back is how this knows what was taken; anything the X4 doesn't
     * hold as asked is named in red.
     */
    private void onConfigure() {
        X4Connection conn = connection;
        if (conn == null || !conn.isConnected()) {
            showError("Connect J1 on the Hardware screen first.");
            return;
        }

        clearErrorMarks();
        List<String> errors = new ArrayList<>();
        X4Settings wanted = readForm(errors);
        if (!errors.isEmpty()) {
            showError(errors.get(0));
            return;
        }

        configureButton.setEnabled(false);
        statusLabel.setText("Configuring\u2026");
        statusLabel.setForeground(BUTTON_COLOR);

        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() {
                return conn.configure(wanted);
            }

            @Override
            protected void done() {
                // The connection may have been dropped meanwhile.
                if (connection != conn) {
                    return;
                }
                configureButton.setEnabled(true);

                List<String> refused;
                try {
                    refused = get();
                } catch (Exception e) {
                    showError("Configuring failed: " + e);
                    return;
                }

                X4Settings now = conn.getSettings();
                if (refused.isEmpty()) {
                    applySettings(now);
                } else {
                    // Leave what the user entered on screen so it can be
                    // corrected or sent again.
                    showError("The X4 does not have these as set here "
                            + "(click Configure X4 to try again): "
                            + String.join(", ", refused));
                }
                if (settingsListener != null && now != null) {
                    settingsListener.accept(now);
                }
            }
        }.execute();
    }

    /**
     * Reads and checks the screen.  A field that can't be sent is marked
     * and described in {@code errors}.
     *
     * @return the settings to write; not to be used if {@code errors} is
     *         not empty
     */
    private X4Settings readForm(List<String> errors) {
        // Serial number: alphanumeric, 12 characters at most (§3.3.15)
        String srn = srnField.getText().trim();
        if (!srn.matches("[A-Za-z0-9]{1,12}")) {
            markError(srnField, errors,
                    "X4 Serial Number must be 1 to 12 letters and digits.");
        }

        String dpf = spinnerNumber(dpfSpinner, "Data Publishing Freq.", errors);
        String cpb = spinnerNumber(cpbSpinner, "Pressure Bias", errors);
        String cj3 = (String) j3BaudCombo.getSelectedItem();

        Map<Integer, X4Settings.Port> ports = new LinkedHashMap<>();
        for (int port = X4Settings.FIRST_PORT; port <= X4Settings.LAST_PORT; port++) {
            String sensor = (String) portCombos.get(port).getSelectedItem();
            SensorFields fields = sensorFields.get(port);

            if (NO_SENSOR.equals(sensor)) {
                ports.put(port, new X4Settings.Port(X4Settings.SENSOR_NONE, ""));

            } else if (TEMPERATURE.equals(sensor)) {
                // Serial number: unsigned integer (§4.3.2)
                String serial = fields.atSerial().getText().trim();
                if (!serial.matches("\\d{1,10}")) {
                    markError(fields.atSerial(), errors,
                            "J" + port + ": AT Serial Number must be a whole number.");
                }
                StringBuilder values = new StringBuilder(serial);
                for (int i = 0; i < 4; i++) {
                    String coefficient = fields.tcal()[i].getText().trim();
                    if (!isNumber(coefficient)) {
                        markError(fields.tcal()[i], errors,
                                "J" + port + ": TCal" + (i + 1) + " must be a number.");
                    }
                    // Sent as typed, so no digits are lost.
                    values.append(',').append(coefficient);
                }
                ports.put(port, new X4Settings.Port(
                        X4Settings.SENSOR_TEMPERATURE, values.toString()));

            } else if (HUMIDITY.equals(sensor)) {
                String address = fields.i2c().getText().trim();
                if (!address.matches("0[xX][0-9A-Fa-f]{1,2}")) {
                    markError(fields.i2c(), errors,
                            "J" + port + ": I2C Address must look like 0x28.");
                }
                ports.put(port, new X4Settings.Port(
                        X4Settings.SENSOR_HUMIDITY, address));
            }
            // OTHER: no entry, so the port is not written.
        }

        return X4Settings.toWrite(srn, dpf, cpb, cj3, dataToggle.isSelected(), ports);
    }

    /**
     * Returns a spinner's value as the X4 takes it: plain decimal, seven
     * significant digits at most (§3.3.6, §3.3.8).
     */
    private String spinnerNumber(JSpinner spinner, String name, List<String> errors) {
        try {
            spinner.commitEdit(); // pick up a value typed but not yet entered
        } catch (java.text.ParseException e) {
            markError(spinner, errors, name + " must be a number in range.");
            return "";
        }
        double value = ((Number) spinner.getValue()).doubleValue();
        return BigDecimal.valueOf(value).round(new MathContext(7))
                .stripTrailingZeros().toPlainString();
    }

    private static boolean isNumber(String text) {
        if (text.isEmpty()) {
            return false;
        }
        try {
            return Double.isFinite(Double.parseDouble(text));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Errors (#6): red text by the heading, red outline on the field
    // ────────────────────────────────────────────────────────────────────────

    /** Fields currently outlined in red. */
    private final List<JComponent> errorMarks = new ArrayList<>();

    private void markError(JComponent field, List<String> errors, String message) {
        // FlatLaf draws this outline in its error colour.
        field.putClientProperty("JComponent.outline", ERROR_RED);
        errorMarks.add(field);
        errors.add(message);
    }

    private void clearErrorMarks() {
        for (JComponent field : errorMarks) {
            field.putClientProperty("JComponent.outline", null);
        }
        errorMarks.clear();
    }

    private void showError(String message) {
        statusLabel.setText(message);
        statusLabel.setForeground(ERROR_RED);
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
        // Each row leaves room above its dropdown for the sensor fields' labels.
        for (int port = 9; port >= 4; port--) {
            panel.add(createPortRow("J" + port, port));
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
        showAllDigits(dpfSpinner);
        gbc.gridx = 2;
        row.add(createFieldColumn("Data Publishing Freq.", dpfSpinner), gbc);

        // Pressure Bias — double model, ±99999.99 (spec §3.3.8).
        cpbSpinner = new JSpinner(new SpinnerNumberModel(0.0, -99999.99, 99999.99, 0.01));
        showAllDigits(cpbSpinner);
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
     * Creates one port row: the port's name, its sensor dropdown, and an
     * area that shows the fields of the sensor selected (#16).  Registers
     * the dropdown in {@link #portCombos}.
     *
     * @param portName  display label, e.g. "J9"
     * @param portNumber numeric port number (4–9), used as the map key
     */
    private JPanel createPortRow(String portName, int portNumber) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        row.setBackground(CARD_BG);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        // The name and dropdown are built as columns with a blank label, so
        // they line up with the sensor fields, which have labels above them.
        JLabel portLabel = new JLabel(portName);
        portLabel.setFont(portLabel.getFont().deriveFont(Font.PLAIN, 13f));
        portLabel.setPreferredSize(new Dimension(30, 28));
        row.add(createFieldColumn(" ", portLabel));

        JComboBox<String> sensorCombo = new JComboBox<>(getSensorOptions(portNumber));
        sensorCombo.setPreferredSize(new Dimension(260, 28));
        row.add(createFieldColumn(" ", sensorCombo));

        JTextField[] tcal = new JTextField[4];
        for (int i = 0; i < tcal.length; i++) {
            tcal[i] = new JTextField(11);
        }
        JTextField i2c = new JTextField(6);
        i2c.setText(DEFAULT_I2C_ADDRESS);
        sensorFields.put(portNumber, new SensorFields(new JTextField(7), tcal, i2c));

        JPanel sensorArea = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        sensorArea.setBackground(CARD_BG);
        row.add(sensorArea);
        sensorAreas.put(portNumber, sensorArea);

        portCombos.put(portNumber, sensorCombo);
        sensorCombo.addActionListener(e -> showSensorFields(portNumber));
        return row;
    }

    /** Puts the fields of the sensor selected for a port into its row (#16). */
    private void showSensorFields(int port) {
        JPanel area = sensorAreas.get(port);
        SensorFields fields = sensorFields.get(port);
        String sensor = (String) portCombos.get(port).getSelectedItem();
        area.removeAll();

        if (TEMPERATURE.equals(sensor)) {
            area.add(createFieldColumn("AT Serial Number", fields.atSerial()));
            for (int i = 0; i < 4; i++) {
                area.add(createFieldColumn("TCal" + (i + 1), fields.tcal()[i]));
            }
            // Reads the sensor's calibration file. Waiting on a sample of
            // the file to know its format.
            JButton load = createStyledButton("Load from File");
            load.setEnabled(false);
            load.setToolTipText("Not available yet");
            area.add(createFieldColumn(" ", load));
            area.add(createFieldColumn(" ", createInfoIcon(
                    "The AT serial number and TCal values come from the "
                            + "temperature sensor's calibration file.")));
        } else if (HUMIDITY.equals(sensor)) {
            area.add(createFieldColumn("I2C Address", fields.i2c()));
            area.add(createFieldColumn(" ", createInfoIcon(
                    "The humidity sensor's I2C address is 0x28 unless "
                            + "InterMet has said otherwise.")));
        }

        area.revalidate();
        area.repaint();
    }

    private JLabel createInfoIcon(String tooltip) {
        JLabel icon = new JLabel("\u24D8");
        icon.setFont(icon.getFont().deriveFont(Font.BOLD, 18f));
        icon.setForeground(BUTTON_COLOR);
        icon.setToolTipText(tooltip);
        icon.setPreferredSize(new Dimension(icon.getPreferredSize().width, 28));
        return icon;
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
        left.add(createUnavailableButton("Restore Defaults")); // #22, TBD
        bar.add(left, BorderLayout.WEST);

        // Right: Load from File | Save to File | Configure X4
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setBackground(CARD_BG);
        right.add(createUnavailableButton("Load from File")); // #24, TBD
        right.add(createUnavailableButton("Save to File"));   // #25, TBD
        configureButton = createStyledButton("Configure X4");  // #26
        configureButton.setEnabled(false); // until J1 connects
        configureButton.addActionListener(e -> onConfigure());
        right.add(configureButton);
        bar.add(right, BorderLayout.EAST);

        return bar;
    }

    /**
     * Makes a spinner show up to seven decimals, with no thousands
     * separator.  By default a spinner rounds what it shows to three
     * decimals, and then takes that rounded text as its value, which would
     * turn a pressure bias of 28.0389 into 28.039.
     */
    private static void showAllDigits(JSpinner spinner) {
        spinner.setEditor(new JSpinner.NumberEditor(spinner, "0.#######"));
    }

    /** A button for a feature the SoW has not defined yet: shown, but off. */
    private JButton createUnavailableButton(String text) {
        JButton button = createStyledButton(text);
        button.setEnabled(false);
        button.setToolTipText("Not available yet");
        return button;
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
            case 8, 9 -> new String[]{NO_SENSOR, TEMPERATURE};
            case 6, 7 -> new String[]{NO_SENSOR};
            case 4, 5 -> new String[]{NO_SENSOR, HUMIDITY};
            default   -> new String[]{NO_SENSOR};
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