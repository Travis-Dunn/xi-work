import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Method;
import java.util.*;

/* Two X4s in two tabs: each tab must read, configure and disconnect its
   own. Uses Harness's helpers. args: <port A> <port B> */
public class TwoTabs {
    static com.imet.ui.TabContentPanel[] tabs(JTabbedPane pane) {
        java.util.List<com.imet.ui.TabContentPanel> list = new ArrayList<>();
        for (int i = 0; i < pane.getTabCount(); i++)
            if (pane.getComponentAt(i) instanceof com.imet.ui.TabContentPanel t) list.add(t);
        return list.toArray(new com.imet.ui.TabContentPanel[0]);
    }
    static com.imet.ui.views.HardwarePanel hw(com.imet.ui.TabContentPanel tab) {
        return Harness.find(tab, com.imet.ui.views.HardwarePanel.class);
    }
    /* Picks the X4 in a tab and connects J1 to the port. */
    static void connect(com.imet.ui.TabContentPanel tab, String port) throws Exception {
        Object hw = hw(tab);
        SwingUtilities.invokeAndWait(() -> { try {
            ButtonGroup g = (ButtonGroup) Harness.get(hw, "productGroup");
            for (AbstractButton b : Collections.list(g.getElements())) if (b.getText().equals("IMet-X4")) b.doClick();
            @SuppressWarnings("unchecked") JComboBox<String> combo = (JComboBox<String>) Harness.get(hw, "j1ComPortCombo");
            combo.addItem(port); combo.setSelectedItem(port);
            Harness.call(hw, "onJ1Connect");
        } catch (Exception e) { throw new RuntimeException(e); } });
        JButton b = (JButton) Harness.get(hw, "j1ConnectButton");
        Thread.sleep(100);
        Harness.waitFor(b::isEnabled, 90000);
        Thread.sleep(300);
        Harness.dialogs();
        System.out.println("HARNESS connected " + port.substring(port.lastIndexOf('/') + 1)
                + ": Configuration screen present=" + (Harness.find(tab, com.imet.ui.views.ConfigurationPanel.class) != null));
    }
    static String state(String name, com.imet.ui.TabContentPanel tab) throws Exception {
        Object cp = Harness.find(tab, com.imet.ui.views.ConfigurationPanel.class);
        Object dp = Harness.find(tab, com.imet.ui.views.X4DataPanel.class);
        return "HARNESS " + name + ": connected=" + Harness.get(hw(tab), "j1Connected")
                + " config srn=" + ((JTextField) Harness.get(cp, "srnField")).getText()
                + " dpf=" + ((JSpinner) Harness.get(cp, "dpfSpinner")).getValue()
                + " status=[" + ((JLabel) Harness.get(cp, "statusLabel")).getText() + "]"
                + " data serial=" + ((JTextField[]) Harness.get(dp, "boardFields"))[0].getText();
    }
    static void configure(com.imet.ui.TabContentPanel tab, double dpf) throws Exception {
        Object cp = Harness.find(tab, com.imet.ui.views.ConfigurationPanel.class);
        SwingUtilities.invokeAndWait(() -> { try { ((JSpinner) Harness.get(cp, "dpfSpinner")).setValue(dpf); } catch (Exception e) { throw new RuntimeException(e); } });
        Harness.clickConfigure(cp);
    }
    public static void main(String[] a) throws Exception {
        com.imet.App.main(new String[0]);
        JFrame frame = null;
        for (int i = 0; i < 100 && frame == null; i++) {
            Thread.sleep(100);
            for (Frame f : Frame.getFrames()) if (f instanceof JFrame j && f.isVisible()) frame = j;
        }
        for (int i = 0; i < 100 && com.imet.plugin.Loader.GetPluginX4() == null; i++) Thread.sleep(100);
        final JFrame fr = frame;
        JTabbedPane pane = Harness.find(frame.getContentPane(), JTabbedPane.class);
        SwingUtilities.invokeAndWait(() -> { try {
            fr.setSize(1500, 950);
            Method add = fr.getClass().getDeclaredMethod("addNewTab"); add.setAccessible(true); add.invoke(fr);
        } catch (Exception e) { throw new RuntimeException(e); } });
        com.imet.ui.TabContentPanel[] t = tabs(pane);
        System.out.println("HARNESS tabs=" + t.length);

        connect(t[0], a[0]);
        connect(t[1], a[1]);
        Thread.sleep(2500);
        System.out.println(state("after both connect, tab 1", t[0]));
        System.out.println(state("after both connect, tab 2", t[1]));

        configure(t[0], 2.0);
        System.out.println(state("after tab 1 set 2 Hz, tab 1", t[0]));
        configure(t[1], 0.5);
        System.out.println(state("after tab 2 set 0.5 Hz, tab 2", t[1]));

        // Disconnect tab 2; tab 1 must still reach its X4.
        Object hw2 = hw(t[1]);
        SwingUtilities.invokeAndWait(() -> { try { Harness.call(hw2, "onJ1Connect"); } catch (Exception e) { throw new RuntimeException(e); } });
        configure(t[0], 1.0);
        System.out.println(state("after tab 2 disconnected and tab 1 set 1 Hz, tab 1", t[0]));

        // Reconnect tab 2, then close it with its x: its port must be released.
        connect(t[1], a[1]);
        Object conn2 = Harness.get(hw2, "j1Connection");
        Method isConnected = conn2.getClass().getMethod("isConnected");
        System.out.println("HARNESS tab 2 reconnected: port open=" + isConnected.invoke(conn2));
        int index = pane.indexOfComponent(t[1]);
        JButton x = Harness.find((Container) pane.getTabComponentAt(index), JButton.class);
        SwingUtilities.invokeAndWait(x::doClick);
        Thread.sleep(500);
        System.out.println("HARNESS tab 2 closed: tabs=" + tabs(pane).length + " port open=" + isConnected.invoke(conn2));
        configure(t[0], 3.0);
        System.out.println(state("after tab 2 closed and tab 1 set 3 Hz, tab 1", t[0]));
        System.exit(0);
    }
}
