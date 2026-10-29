package com.imet.error;

import com.imet.theme.Colors;

import javax.swing.*;
import java.awt.*;

public class ErrorHandler {
    private static final String MISSING_STR = "MISSING STR";

    /**
     * Draws a side effect free dialog in the center of the screen titled
     * "Error".
     *
     * @param title Title
     * @param hdg   Heading
     * @param msg   Message
     */
    public static void ShowDialog(String title, String hdg, String msg) {
        title = title != null ? title : MISSING_STR;
        hdg = hdg != null ? hdg : MISSING_STR;
        msg = msg != null ? msg : MISSING_STR;

        JDialog dlg = new JDialog((Window) null, title);

        dlg.setModal(false);
        dlg.setResizable(false);

        JPanel pnl = new JPanel();
        pnl.setLayout(new BoxLayout(pnl, BoxLayout.Y_AXIS));
        pnl.setBorder(BorderFactory.createEmptyBorder(
                16, 20, 16, 20));
        pnl.setBackground(Colors.GREY);

        JLabel header = new JLabel(hdg);
        header.setFont(header.getFont().deriveFont(Font.BOLD, 15f));
        header.setForeground(Colors.ORANGE);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        pnl.add(header);

        pnl.add(Box.createVerticalStrut(10));


        JLabel body = new JLabel("<html><body style='width:300px;'>"
                + msg + "</body></html>");
        body.setFont(body.getFont().deriveFont(Font.PLAIN, 13f));
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        pnl.add(body);

        pnl.add(Box.createVerticalStrut(14));

        JButton dismissButton = new JButton("Ok");
        dismissButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        dismissButton.addActionListener(e -> dlg.dispose());
        pnl.add(dismissButton);

        dlg.setContentPane(pnl);
        dlg.pack();
        dlg.setLocationRelativeTo(null);
        dlg.setAlwaysOnTop(true);
        dlg.setVisible(true);
    }
}
