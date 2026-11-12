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
            // The user edits a field, leaves the screen, and comes back: it must survive.
            SwingUtilities.invokeAndWait(() -> { try { ((JTextField) get(cp, "srnField")).setText("EDITED"); } catch (Exception e) { throw new RuntimeException(e); } });
            shot(frame.getContentPane(), out + "/" + tag + "-config.png");
            SwingUtilities.invokeAndWait(() -> tab.showView("Data"));
            Thread.sleep(2500);
            com.imet.ui.views.X4DataPanel dp = find(tab, com.imet.ui.views.X4DataPanel.class);
            System.out.println("HARNESS data status=[" + ((JLabel) get(dp, "statusLabel")).getText() + "] portRows=" + ((Map<?, ?>) get(dp, "portFields")).keySet());
            System.out.println("HARNESS data " + dataFields(dp));
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
            System.out.println("HARNESS after disconnect config status=[" + ((JLabel) get(cp, "statusLabel")).getText() + "] data status=[" + ((JLabel) get(dp, "statusLabel")).getText() + "]");
        }
        System.exit(0);
    }
}
