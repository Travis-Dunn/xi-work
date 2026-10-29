package com.imet.plugin;

import jssc.SerialPort;
import jssc.SerialPortException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class PluginTestExample implements PluginTest {
    /* How long to wait for the reply to /ACK? before giving up. */
    private static final long ACK_TIMEOUT_MS = 500;

    /* Set by the app once it has opened J1; null when disconnected. */
    private PluginTransport transport;

    @Override
    public void setTransport(PluginTransport transport) {
        this.transport = transport;
    }

    /* Verifies the X4 is on the other end of the transport (manual 3.3.10).
       If data publishing is on (TDP=ON), data lines can arrive before the
       ACK, so they are skipped rather than treated as a failed reply. */
    @Override
    public void startComm() throws IOException {
        if (transport == null || !transport.isConnected()) {
            throw new IOException("No connection to the device.");
        }

        transport.flush();
        transport.write("/ACK?\r\n".getBytes(StandardCharsets.US_ASCII));

        if (readLineWithPrefix("ACK", ACK_TIMEOUT_MS) == null) {
            throw new IOException("Device did not respond with ACK.");
        }
    }

    @Override
    public void stopComm() {
        transport = null;
    }

    /* Transport counterpart of readResponseWithPrefix: reads lines until one
       starts with prefix, discarding streamed data lines along the way.
       Returns null if no such line arrives before the timeout. */
    private String readLineWithPrefix(String prefix, long timeoutMs)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;

        long remaining;
        while ((remaining = deadline - System.currentTimeMillis()) > 0) {
            byte[] chunk = transport.read(256, remaining);
            sb.append(new String(chunk, StandardCharsets.US_ASCII));

            int newlineIdx;
            while ((newlineIdx = sb.indexOf("\n")) >= 0) {
                String line = sb.substring(0, newlineIdx).trim();
                sb.delete(0, newlineIdx + 1);

                if (line.startsWith(prefix)) {
                    return line;
                }
            }
        }
        return null;
    }

    @Override
    public byte[] messageType_q(byte[] arg) {
        String srn = "";
        String portName = "/dev/serial/by-id/usb-FTDI_FT230X_Basic_UART_DU0CQLT0-if00-port0";
        SerialPort port = new SerialPort(portName);
        try {
            port.openPort();
            port.setParams(
                    SerialPort.BAUDRATE_115200,
                    SerialPort.DATABITS_8,
                    SerialPort.STOPBITS_1,
                    SerialPort.PARITY_NONE
            );

            port.setFlowControlMode(SerialPort.FLOWCONTROL_NONE);

            Thread.sleep(50);

            if (port.getInputBufferBytesCount() > 0) {
                port.readBytes(port.getInputBufferBytesCount());
            }

            /* echo and print ACK */
            port.writeBytes("/ACK?\r\n".getBytes());
            String ack = readResponseWithPrefix(port, "ACK",
                    500);
            if (ack != null) {
                System.out.println("This is the plugin speaking.");
                System.out.println("This should be 'ACK': [" + ack + "]");
            } else {
                System.out.println("No ACK response received");
            }

            String argstr = new String(arg, StandardCharsets.UTF_8);

            if ("serialNumber".equals(argstr)) {
                /* query SRN */
                port.writeBytes("/SRN?\r\n".getBytes());
                String srnResponse = readResponseWithPrefix(port, "SRN=",
                        500);
                if (srnResponse != null) {
                    srn = srnResponse.substring("SRN=".length());
                }
            } else {
                srn = "(Plugin) Does not support that message";
            }
        } catch (SerialPortException | InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            if (port.isOpened()) {
                try {
                    port.closePort();
                } catch (SerialPortException ignored) {}
            }
        }

        return srn.getBytes();
    }

    @Override
    public void messageType_c(byte[] arg) {
        String srn;
        String portName = "/dev/serial/by-id/usb-FTDI_FT230X_Basic_UART_DU0CQLT0-if00-port0";
        SerialPort port = new SerialPort(portName);
        try {
            port.openPort();
            port.setParams(
                    SerialPort.BAUDRATE_115200,
                    SerialPort.DATABITS_8,
                    SerialPort.STOPBITS_1,
                    SerialPort.PARITY_NONE
            );

            port.setFlowControlMode(SerialPort.FLOWCONTROL_NONE);

            Thread.sleep(50);

            if (port.getInputBufferBytesCount() > 0) {
                port.readBytes(port.getInputBufferBytesCount());
            }
            /* echo and print ACK */
            port.writeBytes("/ACK?\r\n".getBytes());
            String ack = readResponseWithPrefix(port, "ACK",
                    500);
            if (ack != null) {
                System.out.println("This is the plugin speaking.");
                System.out.println("This should be 'ACK': [" + ack + "]");
            } else {
                System.out.println("No ACK response received");
                String[] supportedByApp = new String[] {
                        "serialNumber (query)",
                        "serialNumber (command)"
                };
            }

            /* arg is "<name>=<value>"; split on the first '=' */
            String argstr = new String(arg, StandardCharsets.UTF_8);
            int eq = argstr.indexOf('=');
            String name = (eq >= 0) ? argstr.substring(0, eq) : argstr;
            String value = (eq >= 0) ? argstr.substring(eq + 1) : "";

            if ("serialNumber".equals(name)) {
                port.writeBytes(("/SRN=" + value + "\r\n").getBytes());
            } else {
                srn = "(Plugin) Does not support that message";
            }

        } catch (SerialPortException | InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            if (port.isOpened()) {
                try {
                    port.closePort();
                } catch (SerialPortException ignored) {}
            }
        }
    }

    private String readResponse(SerialPort port) throws SerialPortException, InterruptedException {
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + 350;

        while (System.currentTimeMillis() < deadline) {
            int available = port.getInputBufferBytesCount();
            if (available > 0) {
                byte[] chunk = port.readBytes(available);
                sb.append(new String(chunk));

                if (sb.indexOf("\n") >= 0) {
                    break;
                }
            } else {
                Thread.sleep(20);
            }
        }
        return !sb.isEmpty() ? sb.toString().trim() : null;
    }
    private String readResponseWithPrefix(SerialPort port, String prefix,
                                          long timeoutMs)
            throws SerialPortException, InterruptedException {
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            int available = port.getInputBufferBytesCount();
            if (available > 0) {
                byte[] chunk = port.readBytes(available);
                sb.append(new String(chunk));

                /* discard regular streamed lines */
                int newlineIdx;
                while ((newlineIdx = sb.indexOf("\n")) >= 0) {
                    String line = sb.substring(0, newlineIdx).trim();
                    sb.delete(0, newlineIdx + 1);

                    /* found our line */
                    if (line.startsWith(prefix)) {
                        return line;
                    }
                }
            } else {
                Thread.sleep(20);
            }
        }
        return null;
    }
}
