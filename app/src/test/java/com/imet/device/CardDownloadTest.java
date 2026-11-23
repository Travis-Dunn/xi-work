package com.imet.device;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CardDownloadTest {

    @TempDir
    Path dir;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private String saved(CardDownload download) throws Exception {
        download.close();
        return Files.readString(download.file(), StandardCharsets.US_ASCII);
    }

    @Test
    void endsAtTheFirstPauseOnceEverythingHasArrived() throws Exception {
        CardDownload download = new CardDownload(dir.resolve("card.txt"), 10, 0);
        download.receive(bytes("12345"), 700);          // the X4 took a while to start
        assertEquals(CardDownload.State.RECEIVING, download.state(900));
        download.receive(bytes("67890"), 710);
        assertEquals(10, download.received());
        // All there, but more may be on its way.
        assertEquals(CardDownload.State.RECEIVING, download.state(720));
        assertEquals(CardDownload.State.COMPLETE, download.state(1010));
        // A data line after the pause is not the card's.
        download.receive(bytes("923812,11/5/26\r\n"), 1400);
        assertEquals("1234567890", saved(download));
    }

    /** The card was written to between /GET? and /GET. */
    @Test
    void keepsWhatArrivesBeyondTheExpectedSize() throws Exception {
        CardDownload download = new CardDownload(dir.resolve("card.txt"), 10, 0);
        download.receive(bytes("1234567890"), 100);
        download.receive(bytes("abc"), 150);
        assertEquals(CardDownload.State.RECEIVING, download.state(400));
        assertEquals(CardDownload.State.COMPLETE, download.state(450));
        assertEquals(13, download.received());
        assertEquals("1234567890abc", saved(download));
    }

    @Test
    void aLongSilenceBeforeTheEndMeansItStoppedEarly() throws Exception {
        CardDownload download = new CardDownload(dir.resolve("card.txt"), 10, 0);
        download.receive(bytes("1234"), 100);
        // A short pause is not the end while more is expected.
        assertEquals(CardDownload.State.RECEIVING, download.state(1000));
        download.receive(bytes("56"), 4000);
        assertEquals(CardDownload.State.RECEIVING, download.state(8999));
        assertEquals(CardDownload.State.STOPPED_EARLY, download.state(9000));
        download.receive(bytes("late"), 9500);
        assertEquals("123456", saved(download));
    }

    /** The X4 stops part way and goes back to publishing data lines. */
    @Test
    void dataLinesAfterTheCardsDataStopsAreNoticed() throws Exception {
        CardDownload download = new CardDownload(dir.resolve("card.txt"), 100_000, 0);
        // The card's data: 1 kB every 100 ms for three seconds.
        long now = 700;
        for (int i = 0; i < 30; i++, now += 100) {
            download.receive(new byte[1000], now);
        }
        // Then a 60-byte data line every second.
        for (int i = 0; i < 4; i++) {
            now += 1000;
            download.receive(new byte[60], now);
            assertEquals(CardDownload.State.RECEIVING, download.state(now), "after " + i);
        }
        now += 2000;
        assertEquals(CardDownload.State.STOPPED_EARLY, download.state(now));
    }

    /** A slow start, or a slow second, is not a stop. */
    @Test
    void anUnevenFlowIsNotAStop() throws Exception {
        CardDownload download = new CardDownload(dir.resolve("card.txt"), 1_000_000, 0);
        long now = 0;
        for (int second = 0; second < 30; second++) {
            // Every third second is nearly empty.
            int chunks = (second % 3 == 2) ? 0 : 10;
            for (int i = 0; i < chunks; i++) {
                download.receive(new byte[1000], now + i * 100);
            }
            now += 1000;
            assertEquals(CardDownload.State.RECEIVING, download.state(now));
        }
    }

    @Test
    void nothingAtAllIsAlsoStoppedEarly() throws Exception {
        CardDownload download = new CardDownload(dir.resolve("card.txt"), 10, 0);
        assertEquals(CardDownload.State.RECEIVING, download.state(4999));
        assertEquals(CardDownload.State.STOPPED_EARLY, download.state(5000));
        assertEquals("", saved(download));
    }
}
