package com.imet.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LexiconTest {

    /**
     * A single message declared by the lexicon, along with which of the three
     * variants it supports.
     */
    public record Message(
            String name,
            boolean hasQuery,
            boolean hasCommand,
            boolean hasParameterizedQuery) {
    }

    private int deviceID;
    private String pluginFilename;
    private List<Message> messages;

    public LexiconTest(int deviceID, String pluginFilename,
                       List<Message> messages) {
        this.deviceID = deviceID;
        this.pluginFilename = pluginFilename;
        this.messages = messages != null ?
                new ArrayList<>(messages) :
                new ArrayList<>();
    }

    public void setDeviceID(int id) { deviceID = id; }
    public void setPluginFilename(String filename) {
        pluginFilename = filename;
    }

    public int getDeviceID() { return deviceID; }
    public String getPluginFilename() { return pluginFilename; }

    public List<Message> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    /**
     * Backwards-compatible accessor. Returns just the message names, derived
     * from {@link #getMessages()}.
     */
    public List<String> getMessageNames() {
        List<String> names = new ArrayList<>(messages.size());
        for (Message m : messages) {
            names.add(m.name());
        }
        return Collections.unmodifiableList(names);
    }
}