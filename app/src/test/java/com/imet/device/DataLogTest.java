package com.imet.device;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataLogTest {

    @TempDir
    Path dir;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private String contents(DataLog log) throws IOException {
        return new String(Files.readAllBytes(log.file()), StandardCharsets.ISO_8859_1);
    }

    @Test
    void recordsBytesExactly() throws IOException {
        DataLog log = new DataLog(dir.resolve("a.txt"));
        log.write(new byte[] {0, (byte) 0xFF});
        log.write(bytes("420,11/12/26\r\nACK\r\n"));
        // On disk before close.
        assertEquals("\u0000ÿ420,11/12/26\r\nACK\r\n", contents(log));
        log.close();
        assertEquals("\u0000ÿ420,11/12/26\r\nACK\r\n", contents(log));
    }

    @Test
    void markBetweenLinesIsWrittenAtOnce() throws IOException {
        DataLog log = new DataLog(dir.resolve("b.txt"));
        log.mark("before any data");
        log.write(bytes("1,2,3\r\n"));
        log.mark("ref 21.5");
        assertEquals("Mark: before any data\r\n1,2,3\r\nMark: ref 21.5\r\n", contents(log));
        log.close();
    }

    @Test
    void markDuringALineWaitsForItsEnd() throws IOException {
        DataLog log = new DataLog(dir.resolve("c.txt"));
        log.write(bytes("1,2"));
        log.mark("one");
        log.mark("two");
        assertEquals("1,2", contents(log));
        log.write(bytes(",3"));
        log.write(bytes(",4\r\n5,6"));
        assertEquals("1,2,3,4\r\nMark: one\r\nMark: two\r\n5,6", contents(log));
        log.close();
    }

    @Test
    void closeWritesAMarkThatWasStillWaiting() throws IOException {
        DataLog log = new DataLog(dir.resolve("d.txt"));
        log.write(bytes("1,2"));
        log.mark("late");
        log.close();
        log.close();
        assertEquals("1,2\r\nMark: late\r\n", contents(log));
    }

    @Test
    void closedLogRefusesWrites() throws IOException {
        DataLog log = new DataLog(dir.resolve("e.txt"));
        log.close();
        assertThrows(IOException.class, () -> log.write(bytes("x")));
        assertThrows(IOException.class, () -> log.mark("x"));
    }
}
