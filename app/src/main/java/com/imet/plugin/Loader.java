package com.imet.plugin;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imet.Console;
import com.imet.error.ErrorHandler;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.nio.charset.StandardCharsets;

public class Loader {
    public static final String PLUGINS_DIR = "plugins";
    public static final String LEXICONS_DIR = "lexicons";

    public static final int SUCCESS = 0;
    public static final int NO_PLUGIN_DIR = -1;
    public static final int NO_LEXICONS_DIR = -2;

    public static final int DEVICE_ID_X4 = 2;

    private static LexiconTest lexiconX4 = null;

    private static PluginTest pluginX4 = null;

    private static Path pluginsPath;
    private static Path lexiconsPath;
    private static final Map<String, PluginTest> pluginsMap = new HashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static int LoadLexiconsAndPlugins() {
        Loader.pluginsPath = GetPluginsDir();

        if (!Files.isDirectory(Loader.pluginsPath)) {
            return NO_PLUGIN_DIR;
        }

        Loader.lexiconsPath = GetLexiconsDir();

        if (!Files.isDirectory(Loader.lexiconsPath)) {
            return NO_LEXICONS_DIR;
        }

        try (DirectoryStream<Path> lexicons = Files.newDirectoryStream(
                Loader.lexiconsPath, "*.json")) {
            for (Path lexpath : lexicons) {
                LexiconTest lex = LoadLexicon(lexpath);
                String lexFilename = lexpath.getFileName().toString();
                if (lex == null) {
                    Console.println(String.format("Failed to load [%s] as a " +
                            "lexicon.\n", lexFilename));
                } else {
                    Console.println(String.format(
                            "Lexicon declares %d message(s): %s\n",
                            lex.getMessageNames().size(),
                            lex.getMessageNames()));

                    /* TODO: log success */
                    switch (lex.getDeviceID()) {
                        case DEVICE_ID_X4: {
                            lexiconX4 = lex;

                            PluginTest plug = LoadPluginForLexicon(lexiconX4);

                            if (plug == null) {
                                Console.println(String.format("Failed to load" +
                                                " plugin for lexicon [%s].\n",
                                        lexFilename));
                                break;
                            } else {
                                pluginX4 = plug;
                            }
                        } break;
                        default: {
                            Console.println(String.format("Successfully " +
                                            "loaded [%s], but the device is not " +
                                            "supported by XI.\n",
                                    lexpath.getFileName().toString()));
                        }
                    }
                }
            }
        } catch (IOException e) {
            /* TODO: handle this */
        }

        /*
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(
                Loader.pluginsPath, "*.jar")) {
            for (Path jar : jars) {
                PluginTest plug = LoadJar(jar);

                if (plug == null) {
                    Console.println(String.format("Failed to load [%s] as a " +
                            "plugin.\n", jar.getFileName().toString()));
                } else {
                    pluginsMap.put(jar.getFileName().toString(), plug);
                }
            }
        } catch (IOException e) {
            ErrorHandler.ShowDialog("Critical", "Error", "XI " +
                    "encountered an unexpected IOException while trying to " +
                    "load a plugin.\n" + e.getMessage());
        }
         */
        return SUCCESS;
    }

    private static PluginTest LoadJar(Path jar) {
        String filename = jar.getFileName().toString();
        Class<?> clas;
        URLClassLoader loader;
        PluginTest plugin;

        Console.println(String.format("Attempting to load [%s] as " +
                "plugin...\n", filename));

        try {
            loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                    Loader.class.getClassLoader());
            clas = loader.loadClass("com.imet.plugin." +
                    "PluginTestExample");
        } catch (ClassNotFoundException e) {
            Console.println(String.format("Skipping [%s] because a PluginTest" +
                    " implementation was not found.", filename));
            return null;
        } catch (MalformedURLException e) {
            Console.println(String.format("Skipping [%s] because the path was" +
                    " invalid.", filename));
            return null;
        }

        try {
            plugin = (PluginTest) clas.getDeclaredConstructor().newInstance();
        } catch (InvocationTargetException e) {
            /* TODO: add logging */
            return null;
        } catch (InstantiationException e) {
            /* TODO: add logging. this one can throw for a variety of reasons,
            and it's not worth trying to make a good msg, so ask for help and
            print stack trace
             */
            Console.println(String.format("Skipping [%s] because", filename));
            return null;
        } catch (IllegalAccessException e) {
            /* TODO: add logging */
            return null;
        } catch (NoSuchMethodException e) {
            Console.println(String.format("Skipping [%s] because a no-arg " +
                    "constructor was not found.", filename));
            return null;
        } catch (ClassCastException e) {
            Console.println(String.format("Skipping [%s] because it does not " +
                    "implement PluginTest.\n", filename));
            return null;
        }

        /* TODO: add a secret handshake */

        Console.println(String.format("Successfully loaded [%s] as plugin.\n",
                filename));

        return plugin;
    }

    private static PluginTest LoadPluginForLexicon(LexiconTest lex) {
        String jarName = lex.getPluginFilename();
        Path jarPath = pluginsPath.resolve(jarName).normalize();
        Class<?> clas;
        URLClassLoader loader;
        PluginTest plugin;

        Console.println(String.format("Attempting to load [%s] as " +
                "plugin...\n", jarName));

        if (!jarPath.startsWith(pluginsPath)) {
            Console.println(String.format("Skipping plugin [%s] because the " +
                    "path specified in the lexicon resolves outside the " +
                    "plugin directory [%s].\n", jarName, pluginsPath));
            return null;
        }

        if (!Files.isRegularFile(jarPath)) {
            Console.println(String.format("Skipping plugin [%s] because it " +
                            "could not be found in the plugin directory [%s].\n",
                    jarName, pluginsPath));
            return null;
        }

        try {
            loader = new URLClassLoader(new URL[]{jarPath.toUri().toURL()},
                    Loader.class.getClassLoader());
        } catch (MalformedURLException e) {
            Console.println(String.format("Skipping [%s] because the path was" +
                    " invalid.", jarName));
            return null;
        }

        try {
            clas = loader.loadClass("com.imet.plugin." +
                    "PluginTestExample");
            plugin = (PluginTest) clas.getDeclaredConstructor().newInstance();
        } catch (InvocationTargetException e) {
            /* TODO: add logging */
            CloseLoader(loader);
            return null;
        } catch (InstantiationException e) {
            /* TODO: add logging. this one can throw for a variety of reasons,
            and it's not worth trying to make a good msg, so ask for help and
            print stack trace
             */
            Console.println(String.format("Skipping [%s] because", jarName));
            CloseLoader(loader);
            return null;
        } catch (IllegalAccessException e) {
            /* TODO: add logging */
            CloseLoader(loader);
            return null;
        } catch (NoSuchMethodException e) {
            Console.println(String.format("Skipping [%s] because a no-arg " +
                    "constructor was not found.", jarName));
            CloseLoader(loader);
            return null;
        } catch (ClassCastException e) {
            Console.println(String.format("Skipping [%s] because it does not " +
                    "implement PluginTest.\n", jarName));
            CloseLoader(loader);
            return null;
        } catch (ClassNotFoundException e) {
            Console.println(String.format("Skipping [%s] because a PluginTest" +
                    " implementation was not found.", jarName));
            CloseLoader(loader);
            return null;
        }

        /* TODO: close loader in success path as well? */

        Console.println(String.format("Successfully loaded [%s] as plugin.\n",
                jarName));

        return plugin;
    }

    private static LexiconTest LoadLexicon(Path lexicon) {
        String filename = lexicon.getFileName().toString();
        JsonNode rootNode, deviceIDNode, pluginFilenameNode, messagesNode;
        int deviceID;
        String pluginFilename;
        List<LexiconTest.Message> messages = new ArrayList<>();

        try {
            rootNode = MAPPER.readTree(Files.newBufferedReader(lexicon));
        } catch (JacksonException e) {
            Console.println(String.format("Skipping [%s] because it is not " +
                    "valid JSON.\n%s", filename, e.getMessage()));
            return null;
        } catch (IOException e) {
            Console.println(String.format("Skipping [%s] because the file " +
                    "could not be read.\n%s", filename, e.getMessage()));
            return null;
        }

        if (rootNode == null || !rootNode.isObject()) {
            Console.println(String.format("Skipping [%s] because the root " +
                    "node was empty.\n", filename));
            return null;
        }

        deviceIDNode = rootNode.get("deviceID");
        pluginFilenameNode = rootNode.get("pluginFileName");

        /* TODO: put what was found in the msg */
        if (deviceIDNode == null || !deviceIDNode.isIntegralNumber()) {
            Console.println(String.format("Skipping [%s] because [deviceID] " +
                    "was missing or invalid.\n", filename));
            return null;
        }

        if (pluginFilenameNode == null || !pluginFilenameNode.isTextual() ||
                pluginFilenameNode.asText().isBlank()) {
            Console.println(String.format("Skipping [%s] because " +
                    "[pluginFileName] was missing or invalid.\n", filename));
            return null;
        }

        pluginFilename = pluginFilenameNode.asText();
        deviceID = deviceIDNode.asInt();

        /* TODO: print supported device ids */
        if (!(deviceID == DEVICE_ID_X4)) {
            Console.println(String.format("Skipping [%s] because the deviceID" +
                    " [%d] is not supported.\n", filename, deviceID));
            return null;
        }

        messagesNode = rootNode.get("messages");
        if (messagesNode == null) {
            Console.println(String.format("Lexicon [%s] has no [messages] " +
                    "field.\n", filename));
        } else if (!messagesNode.isArray()) {
            Console.println(String.format("Skipping [%s] because [messages] " +
                    "is not an array.\n", filename));
            return null;
        } else {
            for (int i = 0; i < messagesNode.size(); i++) {
                JsonNode messageNode = messagesNode.get(i);
                if (messageNode == null || !messageNode.isObject()) {
                    Console.println(String.format("Skipping message at index" +
                                    " [%d] in [%s] because it is not an " +
                                    "object.\n",
                            i, filename));
                    continue;
                }

                JsonNode nameNode = messageNode.get("messageName");
                if (nameNode == null || !nameNode.isTextual() ||
                        nameNode.asText().isBlank()) {
                    Console.println(String.format("Skipping message at index" +
                            " [%d] in [%s] because [messageName] was missing" +
                            " or invalid.\n", i, filename));
                    continue;
                }

                JsonNode typeNode = messageNode.get("messageType");
                if (typeNode == null || !typeNode.isObject()) {
                    Console.println(String.format("Skipping message [%s] in" +
                            " [%s] because [messageType] was missing or" +
                            " invalid.\n", nameNode.asText(), filename));
                    continue;
                }

                boolean hasQ = typeNode.path("messageType_q").asBoolean(false);
                boolean hasC = typeNode.path("messageType_c").asBoolean(false);
                boolean hasP = typeNode.path("messageType_p").asBoolean(false);

                messages.add(new LexiconTest.Message(
                        nameNode.asText(), hasQ, hasC, hasP));
            }
        }

        /* TODO: log success */
        return new LexiconTest(deviceID, pluginFilename, messages);
    }

    private static Path GetPluginsDir() {
        String override = System.getProperty(PLUGINS_DIR + ".dir");
        Path path;
        if (override != null) {
            path = Path.of(override).toAbsolutePath();
            Console.println(PluginsDirSetSys(path.toString()));
            return path;
        }
        path = Path.of(PLUGINS_DIR).toAbsolutePath();
        Console.println(PluginsDirSetDev(path.toString()));
        return path;
    }

    private static Path GetLexiconsDir() {
        String override = System.getProperty(LEXICONS_DIR + ".dir");
        Path path;
        if (override != null) {
            path = Path.of(override).toAbsolutePath();
            Console.println(LexiconsDirSetSys(path.toString()));
            return path;
        }
        path = Path.of(LEXICONS_DIR).toAbsolutePath();
        Console.println(LexiconsDirSetDev(path.toString()));
        return path;
    }

    private static void ClearX4() {
        lexiconX4 = null;
        pluginX4 = null;
    }

    private static void CloseLoader(URLClassLoader l) {
        try {
            l.close();
        } catch (IOException e) {
            Console.println(UnexpectedExceptionMsg(e));
        }
    }

    public static LexiconTest GetLexiconX4() { return lexiconX4; }
    public static PluginTest GetPluginX4() { return pluginX4; }

    static String PluginsDirSetSys(String s) {
        return String.format("XI running in installed mode. Plugin directory" +
                " set to [%s]", s);
    }

    static String LexiconsDirSetSys(String s) {
        return String.format("XI running in installed mode. Lexicon directory" +
                " set to [%s]", s);
    }

    static String PluginsDirSetDev(String s) {
        return String.format("XI running in development mode. Plugin " +
                "directory set to [%s]", s);
    }

    static String LexiconsDirSetDev(String s) {
        return String.format("XI running in development mode. Lexicon " +
                "directory set to [%s]", s);
    }

    private static final String UNEXPECTED_EXCEPTION = "XI encountered an " +
            "unexpected exception while attempting to load the plugin.\n";

    private static String UnexpectedExceptionMsg(Throwable e) {
        return String.format("An unexpected exception was " +
                        "encountered. As long as there are no symptoms, this " +
                        "probably isn't anything to worry about. Nonetheless, please " +
                        "report the issue via the report button in the title bar.\n%s",
                e.getMessage());
    }
}