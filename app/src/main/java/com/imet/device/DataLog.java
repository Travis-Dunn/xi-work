package com.imet.device;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A log file of everything received from a device port, byte for byte
 * (SoW #37), with the user's marks in between (SoW #38).
 * <p>
 * A mark is the line {@code Mark: <text>\r\n}. It is never written into
 * the middle of a line of data: if the last thing written was part of a
 * line, the mark waits for that line's end.
 * <p>
 * Each write goes to disk before the call returns, so the file is whole
 * up to the last byte received even if the program stops without closing
 * it. Safe to use from more than one thread.
 */
public final class DataLog implements Closeable {

    private final Path file;
    private final OutputStream out;

    /** True if the last byte written ended a line, or nothing is written yet. */
    private boolean atLineStart = true;

    /** Marks waiting for the current line of data to end. */
    private final List<byte[]> waitingMarks = new ArrayList<>();

    private boolean closed;

    /**
     * Creates the file, replacing any file already there.
     *
     * @throws IOException if the file can't be created
     */
    public DataLog(Path file) throws IOException {
        this.file = file;
        this.out = new BufferedOutputStream(Files.newOutputStream(file));
    }

    /** The file being written. */
    public Path file() {
        return file;
    }

    /**
     * Records bytes exactly as received.
     *
     * @throws IOException if the file can't be written, or is closed
     */
    public synchronized void write(byte[] data) throws IOException {
        ensureOpen();
        int from = 0;
        if (!waitingMarks.isEmpty()) {
            // Write up to the end of the line in progress, then the marks.
            for (int i = 0; i < data.length; i++) {
                if (data[i] == '\n') {
                    out.write(data, 0, i + 1);
                    from = i + 1;
                    writeWaitingMarks();
                    break;
                }
            }
        }
        out.write(data, from, data.length - from);
        if (data.length > 0) {
            atLineStart = data[data.length - 1] == '\n';
        }
        out.flush();
    }

    /**
     * Records a mark: {@code "Mark: " + text + "\r\n"}.
     *
     * @throws IOException if the file can't be written, or is closed
     */
    public synchronized void mark(String text) throws IOException {
        ensureOpen();
        waitingMarks.add(("Mark: " + text + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        if (atLineStart) {
            writeWaitingMarks();
            out.flush();
        }
    }

    /**
     * Writes any mark still waiting, then closes the file. Safe to call
     * more than once.
     */
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (!waitingMarks.isEmpty()) {
                // The line in progress never ended; end it so the marks
                // still get lines of their own.
                out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                writeWaitingMarks();
            }
        } finally {
            out.close();
        }
    }

    private void writeWaitingMarks() throws IOException {
        for (byte[] mark : waitingMarks) {
            out.write(mark);
        }
        waitingMarks.clear();
        atLineStart = true;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("The log file is closed.");
        }
    }
}
