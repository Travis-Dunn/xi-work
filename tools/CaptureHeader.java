import jssc.SerialPort;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/*
 * Captures the header an X4 publishes on J1 when its data format changes
 * (SoW #32), so the Data screen's parser can be written against the real
 * thing.
 *
 * Close iMet-XI first (only one program can hold the port), then, from
 * the repo root after a build:
 *
 *   java -cp distribution/target/xi-work/xi-app.jar tools/CaptureHeader.java /dev/ttyUSB0
 *   java -cp distribution\target\xi-work\xi-app.jar tools\CaptureHeader.java COM9
 *
 * What it does to the device, and undoes before it exits:
 *   - turns data publishing on at 1 Hz (/TDP, /DPF)
 *   - changes the board data bitmask (/CBD), then changes it back
 * It does not touch the ports, the serial number or the pressure bias.
 *
 * Everything received is written, byte for byte, to x4-header-capture.txt
 * in the current directory, and printed with \r and \n made visible.
 */
public class CaptureHeader {

    private static final String OUTPUT = "x4-header-capture.txt";

    private static SerialPort port;
    private static final ByteArrayOutputStream everything = new ByteArrayOutputStream();

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: CaptureHeader <J1 port>");
            System.exit(2);
        }

        port = new SerialPort(args[0]);
        port.openPort();
        port.setParams(SerialPort.BAUDRATE_115200, SerialPort.DATABITS_8,
                SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);
        port.setFlowControlMode(SerialPort.FLOWCONTROL_NONE);
        Thread.sleep(100);
        listen(300); // drop whatever was already on its way

        String cbd = null;
        String tdp = null;
        String dpf = null;
        boolean changed = false;
        try {
            if (poll("ACK", "ACK") == null) {
                throw new IllegalStateException("No ACK. Is this the X4's J1 port?");
            }
            cbd = poll("CBD", "CBD=");
            tdp = poll("TDP", "TDP=");
            dpf = poll("DPF", "DPF=");
            if (cbd == null || tdp == null || dpf == null || cbd.length() < 3) {
                throw new IllegalStateException("Could not read CBD, TDP and DPF; "
                        + "nothing was changed.");
            }
            note("Device had CBD=" + cbd + " TDP=" + tdp + " DPF=" + dpf);

            // Same format and delimiter characters, different bitmask.
            String otherCbd = cbd.substring(0, 2)
                    + (cbd.substring(2).trim().equals("0") ? "30720" : "0");

            changed = true;
            send("/TDP=ON");
            send("/DPF=1");
            note("Publishing at 1 Hz; a few data lines in the original format:");
            listen(3500);

            note("Changing to CBD=" + otherCbd + "; a header should follow:");
            send("/CBD=" + otherCbd);
            listen(4500);

            note("Changing back to CBD=" + cbd + "; another header should follow:");
            send("/CBD=" + cbd);
            listen(4500);
        } finally {
            if (changed) {
                note("Restoring the device's settings");
                send("/CBD=" + cbd);
                send("/DPF=" + dpf);
                send("/TDP=" + tdp);
                listen(500);
                String nowCbd = poll("CBD", "CBD=");
                String nowTdp = poll("TDP", "TDP=");
                String nowDpf = poll("DPF", "DPF=");
                boolean restored = cbd.equals(nowCbd) && tdp.equals(nowTdp)
                        && dpf.equals(nowDpf);
                note(restored
                        ? "Restored: CBD=" + nowCbd + " TDP=" + nowTdp + " DPF=" + nowDpf
                        : "NOT RESTORED. Device now has CBD=" + nowCbd + " TDP=" + nowTdp
                          + " DPF=" + nowDpf + "; it had CBD=" + cbd + " TDP=" + tdp
                          + " DPF=" + dpf);
            }
            port.closePort();
            try (FileOutputStream out = new FileOutputStream(OUTPUT)) {
                everything.write(("\n# end of capture\n").getBytes(StandardCharsets.US_ASCII));
                everything.writeTo(out);
            }
            System.out.println("Wrote " + OUTPUT);
        }
    }

    /* A line of ours, marked with '#', in both the console and the file. */
    private static void note(String text) {
        System.out.println("# " + text);
        byte[] line = ("\n# " + text + "\n").getBytes(StandardCharsets.US_ASCII);
        everything.write(line, 0, line.length);
    }

    private static void send(String command) throws Exception {
        note("sent " + command);
        port.writeBytes((command + "\r\n").getBytes(StandardCharsets.US_ASCII));
        Thread.sleep(150);
    }

    /* Reads for a while, recording and printing everything that arrives. */
    private static String listen(long millis) throws Exception {
        StringBuilder text = new StringBuilder();
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            int available = port.getInputBufferBytesCount();
            if (available <= 0) {
                Thread.sleep(20);
                continue;
            }
            byte[] chunk = port.readBytes(available);
            everything.write(chunk, 0, chunk.length);
            String s = new String(chunk, StandardCharsets.ISO_8859_1);
            text.append(s);
            System.out.print(s.replace("\r", "\\r").replace("\n", "\\n\n"));
        }
        return text.toString();
    }

    /* Sends /KEY? and returns the text after the prefix on the reply line,
       skipping data lines; null if no reply comes within 2 s. */
    private static String poll(String key, String prefix) throws Exception {
        note("sent /" + key + "?");
        port.writeBytes(("/" + key + "?\r\n").getBytes(StandardCharsets.US_ASCII));
        StringBuilder text = new StringBuilder();
        long end = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < end) {
            text.append(listen(50));
            for (String line : text.toString().split("\n")) {
                // Only complete lines: the last piece may still be arriving.
                if (!text.toString().contains(line + "\n")) {
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.startsWith("/")) {
                    trimmed = trimmed.substring(1);
                }
                if (trimmed.startsWith(prefix)) {
                    return trimmed.substring(prefix.length());
                }
            }
        }
        return null;
    }
}
