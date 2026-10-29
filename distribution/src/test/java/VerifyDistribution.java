import com.imet.plugin.PluginTest;
import com.imet.plugin.PluginTransport;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.jar.JarFile;

/** Checks the actual packaged boundary without opening serial ports or Swing. */
public final class VerifyDistribution {
    public static void main(String[] args) throws Exception {
        Path home = Path.of(args[0]);
        String implementation = "com.imet.plugin.PluginTestExample";
        try (JarFile app = new JarFile(home.resolve("xi-app.jar").toFile());
             JarFile plugin = new JarFile(home.resolve("plugins/X4Plugin.jar").toFile())) {
            require(app.getEntry(implementation.replace('.', '/') + ".class") == null,
                    "App must not bundle the X4 implementation");
            for (String api : new String[]{"PluginTest", "PluginTransport"}) {
                String entry = "com/imet/plugin/" + api + ".class";
                require(app.getEntry(entry) != null, "Host must supply " + api);
                require(plugin.getEntry(entry) == null, "Plugin must not duplicate " + api);
            }
            require(plugin.stream().noneMatch(e -> e.getName().startsWith("jssc/")),
                    "Plugin must use the host serial library");
        }
        try {
            Class.forName(implementation);
            throw new AssertionError("X4 implementation is on the application classpath");
        } catch (ClassNotFoundException expected) { }
        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{home.resolve("plugins/X4Plugin.jar").toUri().toURL()},
                PluginTest.class.getClassLoader())) {
            Class<?> type = loader.loadClass(implementation);
            require(type.getClassLoader() == loader, "Implementation must load externally");
            PluginTest plugin = (PluginTest) type.getDeclaredConstructor().newInstance();
            FakeTransport transport = new FakeTransport();
            plugin.setTransport(transport);
            plugin.startComm();
            require(transport.flushed && transport.ackRequested, "Handshake must use host transport");
            plugin.stopComm();
            try {
                plugin.startComm();
                throw new AssertionError("stopComm must release transport");
            } catch (IOException expected) { }
        }
        System.out.println("PASS: separate artifacts, shared API, external classloading, transport lifecycle");
    }
    static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    static final class FakeTransport implements PluginTransport {
        boolean flushed, ackRequested;
        public int write(byte[] bytes) {
            require(new String(bytes, StandardCharsets.US_ASCII).equals("/ACK?\r\n"), "Unexpected handshake");
            ackRequested = true;
            return bytes.length;
        }
        public byte[] read(int maxBytes, long timeoutMs) {
            require(ackRequested, "Read before handshake request");
            return "ACK\r\n".getBytes(StandardCharsets.US_ASCII);
        }
        public int available() { return 0; }
        public void flush() { flushed = true; }
        public boolean isConnected() { return true; }
    }
}
