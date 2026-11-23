package com.imet.device;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The contents of the X4's microSD card log, being saved to a file as
 * they arrive on J1 (SoW #36).
 *
 * <p>After /GET the X4 sends log.dat in place of its data lines, with
 * nothing to mark where it ends (manual 3.3.12). /GET? gives its size
 * beforehand, but the X4 goes on logging to the card until /GET is acted
 * on, so slightly more can arrive than that. The end is therefore
 * recognised by the size and a pause together:
 * <ul>
 *   <li>once the expected number of bytes has arrived, the first pause of
 *       {@value #END_QUIET_MS} ms ends the download. The card's data
 *       comes without pauses; data lines come one at a time with pauses
 *       between;</li>
 *   <li>if nothing arrives for {@value #STALL_MS} ms before that, the X4
 *       has stopped sending early;</li>
 *   <li>so it has if, for that long, what arrives is a trickle: less
 *       each second than a tenth of the most that has arrived in one
 *       second. That is the X4 back to publishing data lines. (If its
 *       data lines come at more than a tenth of the card's rate this
 *       can't be told, and the download goes on until cancelled.)</li>
 * </ul>
 * Everything received up to the end is saved, byte for byte. After an
 * early stop that includes the data lines received while making sure.
 *
 * <p>Safe to use from more than one thread: bytes arrive on the
 * connection's reader thread while the screen asks for progress.
 */
public final class CardDownload implements Closeable {

    /** How a download stands. */
    public enum State {
        /** More is expected. */
        RECEIVING,
        /** All of it has arrived. */
        COMPLETE,
        /** The X4 stopped sending before all of it had arrived. */
        STOPPED_EARLY
    }

    /** A pause this long, once everything expected has arrived, is the end. */
    static final long END_QUIET_MS = 300;

    /** A pause this long before that means the X4 has stopped sending. */
    static final long STALL_MS = 5000;

    private final Path file;
    private final long expected;
    private final OutputStream out;

    private long received;
    private long lastActivityMs;
    private boolean closed;

    /* The flow, second by second, to tell the card's data from a trickle
       of data lines. */
    private long secondStartMs;
    private long bytesThisSecond;
    private long mostInASecond;
    private int trickleSeconds;

    /**
     * Creates the file, replacing any file already there. Call this
     * before sending /GET, so that nothing is missed.
     *
     * @param expected the size /GET? reported, in bytes
     * @throws IOException if the file can't be created
     */
    public CardDownload(Path file, long expected) throws IOException {
        this(file, expected, nowMs());
    }

    CardDownload(Path file, long expected, long nowMs) throws IOException {
        this.file = file;
        this.expected = expected;
        this.out = new BufferedOutputStream(Files.newOutputStream(file));
        this.lastActivityMs = nowMs;
        this.secondStartMs = nowMs;
    }

    /** The file being written. */
    public Path file() {
        return file;
    }

    /** The size /GET? reported, in bytes. */
    public long expected() {
        return expected;
    }

    /** How many bytes have been saved. */
    public synchronized long received() {
        return received;
    }

    /**
     * Saves bytes received on J1. Bytes arriving after the download has
     * ended or been closed are ignored: they are data lines again.
     *
     * @throws IOException if the file can't be written
     */
    public void receive(byte[] data) throws IOException {
        receive(data, nowMs());
    }

    synchronized void receive(byte[] data, long nowMs) throws IOException {
        if (closed || state(nowMs) != State.RECEIVING) {
            return;
        }
        out.write(data);
        received += data.length;
        bytesThisSecond += data.length;
        lastActivityMs = nowMs;
    }

    /** Closes off each whole second that has passed. */
    private void countSeconds(long nowMs) {
        while (nowMs - secondStartMs >= 1000) {
            if (bytesThisSecond * 10 < mostInASecond) {
                trickleSeconds++;
            } else {
                trickleSeconds = 0;
            }
            mostInASecond = Math.max(mostInASecond, bytesThisSecond);
            bytesThisSecond = 0;
            secondStartMs += 1000;
        }
    }

    /** How the download stands now. */
    public State state() {
        return state(nowMs());
    }

    synchronized State state(long nowMs) {
        countSeconds(nowMs);
        long quiet = nowMs - lastActivityMs;
        if (received >= expected) {
            return (quiet >= END_QUIET_MS) ? State.COMPLETE : State.RECEIVING;
        }
        boolean stalled = quiet >= STALL_MS || trickleSeconds * 1000L >= STALL_MS;
        return stalled ? State.STOPPED_EARLY : State.RECEIVING;
    }

    /** Closes the file. Safe to call more than once. */
    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            out.close();
        }
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000;
    }
}
