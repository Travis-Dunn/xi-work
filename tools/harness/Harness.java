import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;

/* Drives the real app against a port named on the command line.
   args: <port> <output dir> [tag] [command to send mid-run] */
public class Harness {
    static Object get(Object o, String f) throws Exception {
        Field fld = o.getClass().getDeclaredField(f); fld.setAccessible(true); return fld.get(o);
    }
    static void call(Object o, String m) throws Exception {
        Method me = o.getClass().getDeclaredMethod(m); me.setAccessible(true); me.invoke(o);
    }
    static <T> T find(Container c, Class<T> k) {
        for (Component x : c.getComponents()) {
            if (k.isInstance(x)) return k.cast(x);
            if (x instanceof Container cc) { T t = find(cc, k); if (t != null) return t; }
        }
        return null;
    }
    static void texts(Container c, java.util.List<String> out) {
        for (Component x : c.getComponents()) {
            if (x instanceof JLabel l && l.getText() != null && !l.getText().isBlank()) out.add(l.getText());
            if (x instanceof Container cc) texts(cc, out);
        }
    }
    static void shot(Component c, String path) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                BufferedImage img = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics(); c.paint(g); g.dispose();
                javax.imageio.ImageIO.write(img, "png", new File(path));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
    static String terminalText(Object dp, String name) throws Exception {
        return ((javax.swing.JTextArea) get(get(dp, name), "area")).getText();
    }
    static void dialogs() throws Exception {
        for (Window w : Window.getWindows()) if (w instanceof JDialog d && d.isVisible()) {
            java.util.List<String> t = new ArrayList<>(); texts(d.getContentPane(), t);
            System.out.println("HARNESS dialog [" + d.getTitle() + "] " + t);
            SwingUtilities.invokeAndWait(d::dispose);
        }
    }
    static String dataFields(Object dp) throws Exception {
        StringBuilder sb = new StringBuilder("board=[");
        for (JTextField f : (JTextField[]) get(dp, "boardFields")) sb.append(f.getText()).append('|');
        sb.append("]");
        for (Map.Entry<?, ?> e : new TreeMap<>((Map<?, ?>) get(dp, "portFields")).entrySet()) {
            sb.append(" J").append(e.getKey()).append("=[");
            for (JTextField f : (JTextField[]) e.getValue()) sb.append(f.getText()).append('|');
            sb.append("]");
        }
        return sb.toString();
    }
    @SuppressWarnings("unchecked")
    static void pick(Object cp, int port, String item) throws Exception {
        ((Map<Integer, JComboBox<String>>) get(cp, "portCombos")).get(port).setSelectedItem(item);
    }
    static Object sensor(Object cp, int port) throws Exception {
        return ((Map<?, ?>) get(cp, "sensorFields")).get(port);
    }
    static JTextField field(Object rec, String name) throws Exception {
        Method m = rec.getClass().getDeclaredMethod(name); m.setAccessible(true); return (JTextField) m.invoke(rec);
    }
    static JTextField[] tcal(Object rec) throws Exception {
        Method m = rec.getClass().getDeclaredMethod("tcal"); m.setAccessible(true); return (JTextField[]) m.invoke(rec);
    }
    static void clickConfigure(Object cp) throws Exception {
        JButton b = (JButton) get(cp, "configureButton");
        SwingUtilities.invokeAndWait(b::doClick);
        long t0 = System.currentTimeMillis();
        Thread.sleep(100);
        while (!b.isEnabled() && System.currentTimeMillis() - t0 < 60000) Thread.sleep(50);
        Thread.sleep(200);
        System.out.println("HARNESS configure took ~" + (System.currentTimeMillis() - t0) + " ms; status=[" + ((JLabel) get(cp, "statusLabel")).getText() + "]");
    }
    /* CONFIGURE=1: fill in the Configuration screen and click Configure X4,
       first with a mistake in it, then corrected. */
    static void configure(Object hw, Object cp, JFrame frame, String out, String tag) throws Exception {
        String[] coefficients = {"+8.95098529E-004", "+2.95526317E-004", "-3.33149775E-006", "+2.57965989E-007"};
        SwingUtilities.invokeAndWait(() -> { try {
            ((JTextField) get(cp, "srnField")).setText("XI123");
            ((JSpinner) get(cp, "dpfSpinner")).setValue(Double.parseDouble(System.getenv().getOrDefault("DPF", "2.5")));
            ((JSpinner) get(cp, "cpbSpinner")).setValue(28.0389);
            ((JComboBox<?>) get(cp, "j3BaudCombo")).setSelectedItem("57600");
            ((JToggleButton) get(cp, "dataToggle")).setSelected(true);
            pick(cp, 9, "10085B iMet-X4 Temperature Sensor");
            field(sensor(cp, 9), "atSerial").setText("11111");
            for (int i = 0; i < 3; i++) tcal(sensor(cp, 9))[i].setText(coefficients[i]);   // TCal4 left blank
            pick(cp, 8, "No Sensor");
            pick(cp, 4, "10086B iMet-X4 Humidity Sensor");
        } catch (Exception e) { throw new RuntimeException(e); } });
        clickConfigure(cp);
        shot(frame.getContentPane(), out + "/" + tag + "-config-error.png");
        SwingUtilities.invokeAndWait(() -> { try { tcal(sensor(cp, 9))[3].setText(coefficients[3]); } catch (Exception e) { throw new RuntimeException(e); } });
        clickConfigure(cp);
        System.out.println("HARNESS after configure j3Baud(hardware)=" + ((JComboBox<?>) get(hw, "j3BaudCombo")).getSelectedItem()
                + " srn=" + ((JTextField) get(cp, "srnField")).getText() + " dpf=" + ((JSpinner) get(cp, "dpfSpinner")).getValue()
                + " cpb=" + ((JSpinner) get(cp, "cpbSpinner")).getValue() + " tcal1=" + tcal(sensor(cp, 9))[0].getText());
    }
    /* LOG=1: log to a file (skipping the file dialog), mark twice, stop. */
    static void logging(Object dp, String out, String tag) throws Exception {
        java.nio.file.Path file = java.nio.file.Path.of(out, tag + "-log.txt");
        Method start = dp.getClass().getDeclaredMethod("startLogging", java.nio.file.Path.class);
        start.setAccessible(true);
        JButton logButton = (JButton) get(dp, "logButton"), markButton = (JButton) get(dp, "markButton");
        JTextField markField = (JTextField) get(dp, "markField");
        JLabel status = (JLabel) get(dp, "logStatusLabel");
        // The real button: check what the file dialog offers, then cancel it.
        SwingUtilities.invokeLater(logButton::doClick);
        JFileChooser chooser = null;
        for (int i = 0; i < 50 && chooser == null; i++) {
            Thread.sleep(100);
            for (Window w : Window.getWindows()) if (w instanceof JDialog d && d.isVisible()) chooser = find(d.getContentPane(), JFileChooser.class);
        }
        if (chooser != null) {
            final JFileChooser c = chooser;
            System.out.println("HARNESS log dialog offers " + c.getSelectedFile() + " filter=[" + c.getFileFilter().getDescription() + "]");
            SwingUtilities.invokeAndWait(c::cancelSelection);
            Thread.sleep(300);
        } else System.out.println("HARNESS log dialog did not open");
        System.out.println("HARNESS log before: button=[" + logButton.getText() + "] enabled=" + logButton.isEnabled() + " mark enabled=" + markButton.isEnabled());
        SwingUtilities.invokeAndWait(() -> { try { start.invoke(dp, file); } catch (Exception e) { throw new RuntimeException(e); } });
        System.out.println("HARNESS log started: button=[" + logButton.getText() + "] mark enabled=" + markButton.isEnabled() + " status=[" + status.getText() + "]");
        Thread.sleep(1500);
        SwingUtilities.invokeAndWait(() -> { markField.setText("ref 21.5 C"); markButton.doClick(); });
        Thread.sleep(1700);
        SwingUtilities.invokeAndWait(() -> { markField.setText("second"); markField.postActionEvent(); });
        Thread.sleep(1200);
        SwingUtilities.invokeAndWait(logButton::doClick);   // Stop Logging
        System.out.println("HARNESS log stopped: button=[" + logButton.getText() + "] mark enabled=" + markButton.isEnabled() + " status=[" + status.getText() + "]");
        // Starting where nothing can be created must say so.
        SwingUtilities.invokeAndWait(() -> { try { start.invoke(dp, java.nio.file.Path.of(out, "no-such-dir", "x.txt")); } catch (Exception e) { throw new RuntimeException(e); } });
        System.out.println("HARNESS log bad path: button=[" + logButton.getText() + "] status=[" + status.getText() + "]");
    }
    public static void main(String[] a) throws Exception {
        String port = a[0], out = a[1], tag = a.length > 2 ? a[2] : "run";
        com.imet.App.main(new String[0]);
        JFrame frame = null;
        for (int i = 0; i < 100 && frame == null; i++) {
            Thread.sleep(100);
            for (Frame f : Frame.getFrames()) if (f instanceof JFrame j && f.isVisible()) frame = j;
        }
        for (int i = 0; i < 100 && com.imet.plugin.Loader.GetPluginX4() == null; i++) Thread.sleep(100);
        System.out.println("HARNESS plugin loaded: " + (com.imet.plugin.Loader.GetPluginX4() != null));
        final JFrame fr = frame;
        SwingUtilities.invokeAndWait(() -> fr.setSize(1500, 950));
        com.imet.ui.views.HardwarePanel hw = find(frame.getContentPane(), com.imet.ui.views.HardwarePanel.class);
        com.imet.ui.TabContentPanel tab = (com.imet.ui.TabContentPanel) SwingUtilities.getAncestorOfClass(com.imet.ui.TabContentPanel.class, hw);
        SwingUtilities.invokeAndWait(() -> {
            try {
                ButtonGroup g = (ButtonGroup) get(hw, "productGroup");
                for (AbstractButton b : Collections.list(g.getElements())) if (b.getText().equals("IMet-X4")) b.doClick();
                @SuppressWarnings("unchecked") JComboBox<String> combo = (JComboBox<String>) get(hw, "j1ComPortCombo");
                combo.addItem(port); combo.setSelectedItem(port);
                call(hw, "onJ1Connect");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        long t0 = System.currentTimeMillis();
        JButton btn = (JButton) get(hw, "j1ConnectButton");
        while (!btn.isEnabled() && System.currentTimeMillis() - t0 < 90000) Thread.sleep(50);
        Thread.sleep(300);
        System.out.println("HARNESS connect took ~" + (System.currentTimeMillis() - t0) + " ms; connected=" + get(hw, "j1Connected")
                + " j3Baud=" + ((JComboBox<?>) get(hw, "j3BaudCombo")).getSelectedItem());
        for (Window w : Window.getWindows()) if (w instanceof JDialog d && d.isVisible()) {
            java.util.List<String> t = new ArrayList<>(); texts(d.getContentPane(), t);
            System.out.println("HARNESS dialog [" + d.getTitle() + "] " + t);
            SwingUtilities.invokeAndWait(d::dispose);
        }
        String j3Port = System.getenv("J3PORT");
        if (j3Port != null && Boolean.TRUE.equals(get(hw, "j1Connected"))) {
            SwingUtilities.invokeAndWait(() -> { try {
                @SuppressWarnings("unchecked") JComboBox<String> combo = (JComboBox<String>) get(hw, "j3ComPortCombo");
                combo.addItem(j3Port); combo.setSelectedItem(j3Port);
                call(hw, "onJ3Connect");
            } catch (Exception e) { throw new RuntimeException(e); } });
            JButton j3 = (JButton) get(hw, "j3ConnectButton");
            for (int i = 0; i < 100 && !j3.isEnabled(); i++) Thread.sleep(50);
            Thread.sleep(200);
            System.out.println("HARNESS J3 connected=" + get(hw, "j3Connected") + " button=[" + j3.getText() + "]");
            dialogs();
        }
        if (Boolean.TRUE.equals(get(hw, "j1Connected"))) {
            SwingUtilities.invokeAndWait(() -> tab.showView("Configuration"));
            Thread.sleep(500);
            com.imet.ui.views.ConfigurationPanel cp = find(tab, com.imet.ui.views.ConfigurationPanel.class);
            StringBuilder sb = new StringBuilder();
            sb.append("srn=").append(((JTextField) get(cp, "srnField")).getText());
            sb.append(" dpf=").append(((JSpinner) get(cp, "dpfSpinner")).getValue());
            sb.append(" cpb=").append(((JSpinner) get(cp, "cpbSpinner")).getValue());
            sb.append(" j3=").append(((JComboBox<?>) get(cp, "j3BaudCombo")).getSelectedItem());
            sb.append(" tdp=").append(((JToggleButton) get(cp, "dataToggle")).isSelected());
            Map<?, ?> pc = (Map<?, ?>) get(cp, "portCombos");
            for (Map.Entry<?, ?> e : pc.entrySet()) sb.append(" J").append(e.getKey()).append("=").append(((JComboBox<?>) e.getValue()).getSelectedItem());
            sb.append(" status=[").append(((JLabel) get(cp, "statusLabel")).getText()).append("]");
            System.out.println("HARNESS config " + sb);
            if (System.getenv("CONFIGURE") != null) configure(hw, cp, frame, out, tag);
            if (j3Port != null) { System.out.println("HARNESS J3 after configure connected=" + get(hw, "j3Connected") + " baud menu=" + ((JComboBox<?>) get(hw, "j3BaudCombo")).getSelectedItem()); dialogs(); }
            // The user edits a field, leaves the screen, and comes back: it must survive.
            SwingUtilities.invokeAndWait(() -> { try { ((JTextField) get(cp, "srnField")).setText("EDITED"); } catch (Exception e) { throw new RuntimeException(e); } });
            shot(frame.getContentPane(), out + "/" + tag + "-config.png");
            SwingUtilities.invokeAndWait(() -> tab.showView("Data"));
            Thread.sleep(2500);
            com.imet.ui.views.X4DataPanel dp = find(tab, com.imet.ui.views.X4DataPanel.class);
            System.out.println("HARNESS data status=[" + ((JLabel) get(dp, "statusLabel")).getText() + "] portRows=" + ((Map<?, ?>) get(dp, "portFields")).keySet());
            System.out.println("HARNESS data " + dataFields(dp));
            if (j3Port != null) {
                String j1 = terminalText(dp, "j1Terminal"), j3t = terminalText(dp, "j3Terminal");
                String[] lines = j3t.split("\n");
                System.out.println("HARNESS J3 terminal lines=" + (j3t.isEmpty() ? 0 : lines.length) + " J1 terminal lines=" + j1.split("\n").length
                        + " last J3 line=[" + lines[lines.length - 1] + "]");
            }
            if (System.getenv("LOG") != null) logging(dp, out, tag);
            shot(frame.getContentPane(), out + "/" + tag + "-data.png");
            if (a.length > 3) {
                // Send the device a command behind the app's back, as another
                // program would, then see what the Data screen makes of it.
                try (java.io.FileOutputStream f = new java.io.FileOutputStream(port)) {
                    f.write((a[3] + "\r\n").getBytes());
                }
                Thread.sleep(4500);
                System.out.println("HARNESS after [" + a[3] + "] data status=[" + ((JLabel) get(dp, "statusLabel")).getText() + "]");
                System.out.println("HARNESS after [" + a[3] + "] data " + dataFields(dp));
            }
            SwingUtilities.invokeAndWait(() -> tab.showView("Configuration"));
            Thread.sleep(1500);
            System.out.println("HARNESS after returning to Configuration srn=" + ((JTextField) get(cp, "srnField")).getText());
            SwingUtilities.invokeAndWait(() -> { try { call(hw, "onJ1Connect"); } catch (Exception e) { throw new RuntimeException(e); } });
            if (j3Port != null) System.out.println("HARNESS J3 after J1 disconnect connected=" + get(hw, "j3Connected") + " button=[" + ((JButton) get(hw, "j3ConnectButton")).getText() + "] enabled=" + ((JButton) get(hw, "j3ConnectButton")).isEnabled());
            System.out.println("HARNESS after disconnect config status=[" + ((JLabel) get(cp, "statusLabel")).getText() + "] data status=[" + ((JLabel) get(dp, "statusLabel")).getText() + "]");
        }
        System.exit(0);
    }
}
