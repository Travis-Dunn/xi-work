package com.imet;

import com.formdev.flatlaf.FlatLightLaf;
import com.imet.ui.MainFrame;

import javax.swing.*;

public class App {

    public static void main(String[] args) {
        FlatLightLaf.setup();

        UIManager.put("TabbedPane.showTabSeparators", true);
        UIManager.put("TabbedPane.tabSeparatorsFullHeight", true);

        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
            frame.startConsoleTest();
        });
    }
}