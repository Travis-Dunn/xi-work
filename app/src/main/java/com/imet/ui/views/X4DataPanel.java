package com.imet.ui.views;

import com.imet.device.X4Connection;
import com.imet.device.X4Settings;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * X4 Data view (SoW 205196 section 3.2). Laid out after Figure 3-1, the
 * iMet-XS Data screen:
 * <ul>
 *   <li>Product image on the left (#39)</li>
 *   <li>Board data, always shown (#28)</li>
 *   <li>Port rows J9–J4, filled in for the sensor each port is configured
 *       for (#29)</li>
 *   <li>Card and logging buttons (#35–#38)</li>
 *   <li>Read-only J1 and J3 terminals along the bottom (#33, #34)</li>
 * </ul>
 *
 * <p>So far the J1 terminal is live and the port rows follow the X4's
 * settings (see {@link #setConnection}); the fields are not filled in
 * yet and the buttons have no actions. Which
 * data column feeds which field is described in
 * {@code docs/x4-data-format.md}.
 */
public class X4DataPanel extends JPanel {

    private static final Color BACKGROUND   = new Color(228, 230, 235);
    private static final Color CARD_BG      = Color.WHITE;
    private static final Color BUTTON_COLOR = new Color(122, 162, 214);
    private static final Color BUTTON_TEXT  = Color.WHITE;
    private static final Color LABEL_TEXT   = new Color(0x666666);
    private static final Color UNIT_TEXT    = new Color(0x888888);
    private static final Color ERROR_RED    = new Color(255, 25, 25);

    /** Sensor IDs used with /CJ4–/CJ9 (X4 manual section 4.3). */
    public static final int SENSOR_NONE        = X4Settings.SENSOR_NONE;
    public static final int SENSOR_TEMPERATURE = X4Settings.SENSOR_TEMPERATURE;
    public static final int SENSOR_HUMIDITY    = X4Settings.SENSOR_HUMIDITY;

    /**
     * Board field labels as {name, unit}, in data-column index order
     * (X4 manual section 4.2). Names are copied from iMet-XS (#28), except
     * ground speed, which the device reports in m/s rather than knots.
     */
    private static final String[][] BOARD_LABELS = {
            {"Serial Number",    ""},
            {"Date",             "(YYYY-MM-DD)"},
            {"Time",             "(UTC HH:MM:SS)"},
            {"Run Time",         "(s)"},
            {"Battery Charge",   "(%)"},
            {"Battery Capacity", "(mAh)"},
            {"Battery Power",    "(mW)"},
            {"Battery Current",  "(mA)"},
            {"Battery State",    ""},
            {"Pressure",         "(hPa)"},
            {"Pressure Temp",    "(°C)"},
            {"Humidity",         "(%)"},
            {"Humidity Temp",    "(°C)"},
            {"Satellites",       ""},
            {"Latitude",         "(°)"},
            {"Longitude",        "(°)"},
            {"HDoP",             ""},
            {"Altitude",         "(m)"},
            {"Ground Speed",     "(m/s)"},
            {"Tracking Angle",   "(°)"},
    };

    /** Board fields are shown in two rows of this many. */
    private static final int BOARD_FIELDS_PER_ROW = 10;

    /** Temperature sensor (ID 3) labels, in data-column index order. */
    private static final String[][] TEMPERATURE_LABELS = {
            {"AT Serial Number", ""},
            {"Temperature",      "(°C)"},
    };

    /** Humidity sensor (ID 4) labels, in data-column index order. */
    private static final String[][] HUMIDITY_LABELS = {
            {"Rel. Humidity", "(%)"},
            {"Humidity Temp", "(°C)"},
    };

    /* Sensor names as worded in SoW #16 and the Configuration screen. */
    private static final String TEMPERATURE_NAME = "10085B iMet-X4 Temperature Sensor";
    private static final String HUMIDITY_NAME    = "10086B iMet-X4 Humidity Sensor";

    /** Width of a value field; wide enough for 00.00000001 latitude. */
    private static final int FIELD_WIDTH = 84;

    /** Width of a port row's sensor-name column, so value columns line up. */
    private static final int SENSOR_NAME_WIDTH = 230;

    // ── Display fields ───────────────────────────────────────────────────────

    /** Board value fields, indexed like the board data columns. */
    private final JTextField[] boardFields = new JTextField[BOARD_LABELS.length];

    /** The sensor area of each port row, keyed by port number (4–9). */
    private final Map<Integer, JPanel> portSensorAreas = new HashMap<>();

    /**
     * Value fields of the sensor shown on each port, in data-column index
     * order. No entry for a port with no sensor shown.
     */
    private final Map<Integer, JTextField[]> portFields = new HashMap<>();

    /**
     * Shown next to the "Data" heading. Says why the fields are empty
     * until the settings have been read from the X4 (#27).
     */
    private JLabel statusLabel;

    // ── Card and logging controls ────────────────────────────────────────────

    private JButton eraseCardButton;
    private JButton getFromCardButton;

    /** Progress of a "Get from Card" transfer (#36); hidden until one runs. */
    private JProgressBar cardProgress;

    /** Toggles between "Start Logging" and "Stop Logging" (#37). */
    private JButton logButton;

    /** Enabled only while logging (#38). */
    private JButton markButton;
    private JTextField markField;

    // ── Terminals ────────────────────────────────────────────────────────────

    private TerminalPane j1Terminal;
    private TerminalPane j3Terminal;

    // ── Connection ───────────────────────────────────────────────────────────

    /** J1 connection feeding the J1 terminal; null when disconnected. */
    private X4Connection connection;

    /** Hands J1 bytes from the connection's reader thread to the EDT. */
    private final Consumer<byte[]> j1Receiver =
            data -> SwingUtilities.invokeLater(() -> j1Terminal.append(data));

    // ── Layout ───────────────────────────────────────────────────────────────

    /** Displays on top, terminals below. */
    private JSplitPane split;
    private JScrollPane topScroll;

    // ────────────────────────────────────────────────────────────────────────
    //  Construction
    // ────────────────────────────────────────────────────────────────────────

    public X4DataPanel() {
        setLayout(new BorderLayout());
        setBackground(BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        JPanel card = new JPanel(new BorderLayout(0, 8));
        card.setBackground(CARD_BG);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xD0D0D0)),
                BorderFactory.createEmptyBorder(12, 20, 12, 20)
        ));

        card.add(createHeadingRow(), BorderLayout.NORTH);

        // Top: image + displays + buttons. Bottom: terminals. The divider
        // lets the user trade display space for terminal history; the top
        // scrolls if the window is too small for it.
        topScroll = new JScrollPane(createDisplayArea());
        topScroll.setBorder(BorderFactory.createEmptyBorder());
        topScroll.getViewport().setBackground(CARD_BG);

        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                topScroll, createTerminalArea());
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setBackground(CARD_BG);
        split.setContinuousLayout(true);
        split.setResizeWeight(0.0); // extra height goes to the terminals
        card.add(split, BorderLayout.CENTER);

        // Place the divider once the panel has a real size.
        split.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (split.getHeight() > 0) {
                    fitDisplays();
                    split.removeComponentListener(this);
                }
            }
        });

        add(card, BorderLayout.CENTER);

        // #27: no sensor rows until the port settings are known.
        for (int port = 4; port <= 9; port++) {
            setPortSensor(port, SENSOR_NONE);
        }
    }

    /**
     * Called by the parent when the J1 connection is established or
     * dropped. While connected, everything received on J1 is shown in the
     * J1 terminal (#33). The terminal keeps its history after a
     * disconnect.
     *
     * <p>On connect, each port row gets the fields of the sensor the X4
     * said the port is configured for (#29), from the settings read at
     * that moment (#14).
     *
     * @param conn the active connection, or {@code null} once it closes
     */
    public void setConnection(X4Connection conn) {
        if (connection != null) {
            connection.removeReceiveListener(j1Receiver);
        }
        connection = conn;
        if (conn != null) {
            conn.addReceiveListener(j1Receiver);
        }

        X4Settings settings = (conn != null) ? conn.getSettings() : null;
        if (settings == null) {
            showStatus("Waiting for the X4's settings", false);
            return;
        }
        for (int port = X4Settings.FIRST_PORT; port <= X4Settings.LAST_PORT; port++) {
            X4Settings.Port setting = settings.port(port);
            setPortSensor(port, setting != null ? setting.sensorId() : SENSOR_NONE);
        }
        if (settings.isComplete()) {
            showStatus("", false);
        } else {
            // #27: without every setting the data format isn't known.
            showStatus("Could not read: "
                    + String.join(", ", settings.missing()), true);
        }
    }

    private void showStatus(String text, boolean error) {
        statusLabel.setText(text);
        statusLabel.setForeground(error ? ERROR_RED : new Color(0x999999));
    }

    private JPanel createHeadingRow() {
        JPanel headingRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        headingRow.setBackground(CARD_BG);

        JLabel heading = new JLabel("Data");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 18f));
        headingRow.add(heading);

        statusLabel = new JLabel("Waiting for the X4's settings");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 12f));
        statusLabel.setForeground(new Color(0x999999));
        headingRow.add(statusLabel);

        return headingRow;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Ports
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Shows the labels and value fields for the sensor configured on a port
     * (#29). Any ID other than the temperature or humidity sensor leaves
     * the row empty.
     *
     * @param port     port number, 4–9
     * @param sensorId sensor ID from /CJ#? (X4 manual section 4.3)
     */
    public void setPortSensor(int port, int sensorId) {
        JPanel area = portSensorAreas.get(port);
        if (area == null) {
            throw new IllegalArgumentException("No such port: J" + port);
        }
        area.removeAll();
        portFields.remove(port);

        String name;
        String[][] labels;
        switch (sensorId) {
            case SENSOR_TEMPERATURE -> {
                name = TEMPERATURE_NAME;
                labels = TEMPERATURE_LABELS;
            }
            case SENSOR_HUMIDITY -> {
                name = HUMIDITY_NAME;
                labels = HUMIDITY_LABELS;
            }
            default -> {
                name = null;
                labels = null;
            }
        }

        if (labels != null) {
            area.add(createSensorNameColumn(name));
            JTextField[] fields = new JTextField[labels.length];
            for (int i = 0; i < labels.length; i++) {
                fields[i] = createValueField();
                area.add(createValueColumn(labels[i][0], labels[i][1], fields[i]));
            }
            portFields.put(port, fields);
        }

        area.revalidate();
        area.repaint();
        SwingUtilities.invokeLater(this::fitDisplays);
    }

    /**
     * Moves the divider so the displays get their full height, as far as
     * the terminals' minimum height allows. Left alone, JSplitPane starts
     * the divider at the top's minimum size whenever both halves don't
     * fit, which hides the displays.
     */
    private void fitDisplays() {
        if (split == null || split.getHeight() == 0) {
            return;
        }
        int wanted = topScroll.getPreferredSize().height;
        int limit = split.getHeight() - split.getDividerSize()
                - split.getBottomComponent().getMinimumSize().height;
        split.setDividerLocation(Math.max(0, Math.min(wanted, limit)));
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Display area (image, board, ports, buttons)
    // ────────────────────────────────────────────────────────────────────────

    private JPanel createDisplayArea() {
        JPanel area = new JPanel(new BorderLayout(20, 0));
        area.setBackground(CARD_BG);
        area.add(createImagePanel(), BorderLayout.WEST);

        JPanel displays = new JPanel();
        displays.setLayout(new BoxLayout(displays, BoxLayout.Y_AXIS));
        displays.setBackground(CARD_BG);
        displays.add(createBoardPanel());
        displays.add(Box.createVerticalStrut(8));
        displays.add(createPortsPanel());
        displays.add(Box.createVerticalStrut(8));
        displays.add(createButtonBar());

        // Keep the displays at their natural size, top-left.
        JPanel holder = new JPanel(new BorderLayout());
        holder.setBackground(CARD_BG);
        holder.add(displays, BorderLayout.NORTH);
        area.add(holder, BorderLayout.CENTER);

        return area;
    }

    /** Same image and size as the Configuration screen (#39). */
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

    /** "X4 Board" and its 20 fields in two rows of ten (#28). */
    private JPanel createBoardPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(CARD_BG);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridheight = 2;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.insets = new Insets(0, 0, 4, 8);
        panel.add(createSectionLabel("X4 Board"), gbc);
        gbc.gridheight = 1;
        gbc.anchor = GridBagConstraints.NORTH;
        gbc.insets = new Insets(0, 3, 4, 3);

        for (int i = 0; i < BOARD_LABELS.length; i++) {
            boardFields[i] = createValueField();
            gbc.gridx = 1 + i % BOARD_FIELDS_PER_ROW;
            gbc.gridy = i / BOARD_FIELDS_PER_ROW;
            panel.add(createValueColumn(BOARD_LABELS[i][0], BOARD_LABELS[i][1],
                    boardFields[i]), gbc);
        }

        // Take up any spare width on the right so the grid stays left-aligned
        // with the port labels below it.
        gbc.gridx = 1 + BOARD_FIELDS_PER_ROW;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        panel.add(Box.createHorizontalGlue(), gbc);

        return panel;
    }

    /** Rows J9 down to J4, matching the physical connector order (#29). */
    private JPanel createPortsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(CARD_BG);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        for (int port = 9; port >= 4; port--) {
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setBackground(CARD_BG);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));

            JLabel portLabel = createSectionLabel("J" + port);
            portLabel.setPreferredSize(new Dimension(56, 20));
            portLabel.setVerticalAlignment(SwingConstants.TOP);
            row.add(portLabel, BorderLayout.WEST);

            JPanel sensorArea = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            sensorArea.setBackground(CARD_BG);
            row.add(sensorArea, BorderLayout.CENTER);
            portSensorAreas.put(port, sensorArea);

            panel.add(row);
        }

        return panel;
    }

    /**
     * "Sensor" heading over the sensor's name, as in iMet-XS. Built like a
     * value column, with the name in place of the field, so the name lines
     * up with the value fields beside it.
     */
    private JPanel createSensorNameColumn(String sensorName) {
        JLabel name = new JLabel(sensorName, SwingConstants.CENTER);
        name.setFont(name.getFont().deriveFont(Font.PLAIN, 12f));
        Dimension size = new Dimension(SENSOR_NAME_WIDTH, 26);
        name.setPreferredSize(size);
        name.setMaximumSize(size);
        return createValueColumn("Sensor", "", name);
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Button bar (#35–#38)
    // ────────────────────────────────────────────────────────────────────────

    private JPanel createButtonBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(CARD_BG);
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Left: Erase Card (#35)
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setBackground(CARD_BG);
        eraseCardButton = createStyledButton("Erase Card");
        left.add(eraseCardButton);
        bar.add(left, BorderLayout.WEST);

        // Right: Get from Card (#36) | Start Logging (#37) | Mark (#38)
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setBackground(CARD_BG);

        cardProgress = new JProgressBar(0, 100);
        cardProgress.setStringPainted(true);
        cardProgress.setPreferredSize(new Dimension(140, 20));
        cardProgress.setVisible(false);
        right.add(cardProgress);

        getFromCardButton = createStyledButton("Get from Card");
        right.add(getFromCardButton);

        logButton = createStyledButton("Start Logging");
        // Size for the longer label so the button doesn't resize on toggle.
        logButton.setPreferredSize(new Dimension(
                logButton.getFontMetrics(logButton.getFont())
                        .stringWidth("Stop Logging") + 32, 32));
        right.add(logButton);

        markButton = createStyledButton("Mark");
        markButton.setEnabled(false); // no log open yet
        right.add(markButton);

        markField = new JTextField(14);
        markField.putClientProperty("JTextField.placeholderText", "Mark:");
        markField.setPreferredSize(new Dimension(
                markField.getPreferredSize().width, 32));
        right.add(markField);

        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Terminals (#33, #34)
    // ────────────────────────────────────────────────────────────────────────

    private JPanel createTerminalArea() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(CARD_BG);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        j1Terminal = new TerminalPane();
        j3Terminal = new TerminalPane();

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.insets = new Insets(4, 0, 4, 16);
        panel.add(createSectionLabel("J1 Terminal"), gbc);
        gbc.gridy = 1;
        panel.add(createSectionLabel("J3 Terminal"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(0, 0, 8, 0);
        gbc.gridy = 0;
        panel.add(j1Terminal, gbc);
        gbc.gridy = 1;
        panel.add(j3Terminal, gbc);

        return panel;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────────────────────────────

    private JLabel createSectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        return label;
    }

    /**
     * Read-only field that shows a value exactly as the device sent it
     * (#31). Still focusable so the user can select and copy it.
     */
    private JTextField createValueField() {
        JTextField field = new JTextField();
        field.setEditable(false);
        field.setBackground(CARD_BG);
        Dimension size = new Dimension(FIELD_WIDTH, 26);
        field.setPreferredSize(size);
        field.setMaximumSize(size);
        return field;
    }

    /** Name, then unit (or a blank line), then the value field, centred. */
    private JPanel createValueColumn(String name, String unit, JComponent field) {
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBackground(CARD_BG);

        JLabel nameLabel = new JLabel(name);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.PLAIN, 11f));
        nameLabel.setForeground(LABEL_TEXT);
        nameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        col.add(nameLabel);

        // A space keeps unit-less columns the same height as the others.
        JLabel unitLabel = new JLabel(unit.isEmpty() ? " " : unit);
        unitLabel.setFont(unitLabel.getFont().deriveFont(Font.PLAIN, 10f));
        unitLabel.setForeground(UNIT_TEXT);
        unitLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        col.add(unitLabel);
        col.add(Box.createVerticalStrut(2));

        field.setAlignmentX(Component.CENTER_ALIGNMENT);
        col.add(field);
        return col;
    }

    /**
     * Styled rounded button, same as ConfigurationPanel's. Reads
     * {@code getBackground()} as the base colour so the disabled state
     * (grey) works without special-casing.
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
}