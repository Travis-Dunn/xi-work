package com.imet;

import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class Console {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static void println(String s) {
        System.out.println(s);
    }

    /* Diagnostic line with a timestamp and the calling thread, e.g.
       14:02:07.315 [SwingWorker-pool-1-thread-1] J1 TX "/ACK?\r\n" */
    public static void log(String s) {
        System.out.println(LocalTime.now().format(TIME)
                + " [" + Thread.currentThread().getName() + "] " + s);
    }

    /* Quotes raw serial bytes so line endings and control characters are
       visible: CR, LF and tab as \r \n \t, other non-printable bytes as
       \xNN. */
    public static String escape(byte[] data) {
        return escape(new String(data, StandardCharsets.ISO_8859_1));
    }

    public static String escape(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\r': sb.append("\\r"); break;
                case '\n': sb.append("\\n"); break;
                case '\t': sb.append("\\t"); break;
                case '\\': sb.append("\\\\"); break;
                default:
                    if (c >= 0x20 && c < 0x7F) {
                        sb.append(c);
                    } else {
                        sb.append(String.format("\\x%02X", (int) c));
                    }
            }
        }
        return sb.append('"').toString();
    }
}
