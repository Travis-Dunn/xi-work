# Handoff: iMet-XI / X4 work (as of 2026-11-23)

## Who and what

I'm a contractor for InterMet Systems (the client), working on **iMet-XI**, a
Java/Swing rewrite of the client's iMet-XS app (XS source is lost). XI
configures and reads data from InterMet's X-series devices (X4 first, then
XQ2, maybe XF) through device plugins. Work is governed by SoW 205196 Rev 05
(Rev 04 added the XQ2, #40–#56; Rev 05 defined #22).
The X4 manual is 252100.0200 Rev 12. Ask me for either PDF if it's not
attached.

How I like to work: go slowly and explain every step and why. Keep changes
small, and let me review diffs before anything big. Use your judgement where
the SoW is silent. Where the SoW's wording and its pictures disagree, go with
the wording.

Billing: the SoW tracks hours per category (XQ2 plugin, X4 plugin, XF plugin,
iMet-XI software, and Mycelium/pluginBase on a separate PO). Point it out
when work drifts into pluginBase/framework territory.

## Repos

- **`Travis-Dunn/xi-work`**: the single multi-module Maven repo. Work is
  merged to `master` by pull request; check that the last session's branch
  was merged before starting (one was left unmerged once).
- `Travis-Dunn/xi` and `Travis-Dunn/X4-Plugin`: old repos, superseded. Their
  work (branch `plugin-transport-ack`) was carried into xi-work.
- `InterMet-Systems/iMet-XI`: the client's repo; Claude can't reach it. I work
  from a private copy, with the client's OK.
- `InterMet-Systems/pluginBase` (public): the plugin-interface spec
  (`com.intermet.mycelium.*`). The client has cooled on following it
  strictly. Take its narrow interfaces, not the whole framework.

## Layout, build, conventions

- Modules:
  - `plugin-api`: shared `PluginTest` and `PluginTransport`
  - `app`: the Swing app
  - `plugins/x4`: the plugin and `lexicons/lexicon.json`
  - `distribution`: stages a runnable folder plus a zip
- Stack: Java 17, jssc 2.9.5 (serial), FlatLaf, Jackson. `slf4j-nop` silences
  jssc's SLF4J warning.
- **Build:** from the root, `mvn clean package` (or `verify`). That produces:
  - the runnable folder `distribution/target/xi-work/`
  - the portable zip `distribution/target/xi-work-0.1.0.zip` (needs Java 17+)
- **Stale-copy trap:** building or running from IntelliJ does NOT refresh
  `plugins/X4Plugin.jar` or `lexicons/` in the distribution folder. After any
  plugin or lexicon change, run the root Maven build. A stale plugin caused a
  confusing "port busy" error once.
- **Linux:**
  - If `./run.sh` says permission denied, use `sh run.sh`. The zip stores
    755, but some unzip tools or noexec drives lose it.
  - Serial access needs the `dialout` group.
- **Line endings:** Java sources use CRLF, and several have no final newline.
  Keep both; don't reformat files. The lexicon, POMs and docs use LF.
- The app finds `plugins/` and `lexicons/` via `AppPaths`, in this order: the
  `imet.home` property, then jpackage's `app.dir`, then `user.dir`.
- `Loader` loads the lexicon, then the jar it names. The class name
  `com.imet.plugin.PluginTestExample` is hardcoded.
- **Tabs:** each tab is its own device. Every J1 connection gets a plugin
  object of its own (`Loader.NewPluginX4()`): a plugin object holds one
  device's transport, and when the tabs shared one, both tabs' commands
  went to whichever X4 connected last. `Loader.GetPluginX4()` now only
  says the plugin is available. Closing a tab disconnects its J1 and J3.
  A new tab is told which lexicons and plugins the scanner has already
  found (before, a second tab reported them all missing and refused to
  select a product). `tools/harness/two.sh` runs two fake X4s in two tabs.

## Architecture (agreed)

**The app owns the connection**: ports, open/close, baud, threads, UI state
and errors. **The plugin owns the protocol**: commands, reply parsing and
skipping streamed data lines.

- **`X4Connection`** (J1, 115200 8-N-1) opens the port and starts a reader
  thread right away; it's the only reader of the port. Then it hands the
  plugin a `SerialPortTransport` and calls `startComm()` (the ACK handshake).
  `getPlugin()` gives panels the plugin bound to the connection. On
  disconnect: `plugin.stopComm()` first, then stop the reader, then close.
- **The reader thread** passes each chunk to:
  - the transport's 64 KB ring buffer, which the plugin reads, with the
    oldest bytes dropped when full
  - receive listeners (the Data screen's J1 terminal)
- **Message calls** (`PluginTest`, all ASCII):
  - `messageType_q("name")` → value
  - `messageType_c("name=value")` → nothing
  - `messageType_p("name=arg")` → value
  - They never throw (my decision). Failure returns `null` (for `_c`, nothing
    is sent). For `configurePortTo…`, an empty result means "the port is set
    to a different sensor".
- **The plugin** (`PluginTestExample`):
  - prefix-matches replies, skips data lines and ignores a leading `/`
  - runs one call at a time (`synchronized`)
  - ACK and query timeouts are both 2 s (see open item 1)
- **Supported messages:**
  - query + command: `serialNumber`, `dataPublishingFrequency`,
    `pressureBias`, `toggleDataPublishing` (1/0 in the lexicon, ON/OFF on the
    device), `j3BaudRate`
  - query only: `boardDataFormat` (/CBD?, returns e.g. `0,30720`)
  - query only: `acknowledgeDevice` (/ACK?, returns 1 or 0)
  - command only: `resetBoardData` (/CBD=0,0)
  - query only: `sdCardDataSize` (/GET?, bytes in log.dat, or -1)
  - query + command: `eraseSDCardData` (/ERD). Both wait for the X4's
    answer (up to 30 s); the query returns 1 for "Complete", 0 for "Card
    not detected".
  - command only: `getSDCardData` (/GET). It only starts the X4 sending;
    the app reads what follows from J1 itself.
  - parameterized + command: `configurePortToEmpty` / `AirThermistor` /
    `HYT271` / `SerialForward` for ports 4–9. The queries return the values
    the lexicon lists; the trailing bitmask is NOT returned. The commands
    take the port, then those same values, and always send a bitmask of 0.
- **Lexicon** (`lexiconVersion` 0.3.0, 23 messages): 0.3.0 added
  `sdCardDataSize`, gave `eraseSDCardData` a query form, and turned
  `getSDCardData` from a query into a command (my changes, not yet put to
  the client). 0.2.0 added `j3BaudRate` and
  `boardDataFormat`. Fixed `serialNumber`'s `return_q`, and the duplicate
  `return_p` → `return_p_len` in all four `configurePortTo…`.
- **App use of the plugin:**
  - J1 connect reads every setting once (`X4Connection.readSettings`, SoW
    #14) into an `X4Settings`, including `boardDataFormat`. The Hardware,
    Configuration and Data screens all use that one read
    (`connection.getSettings()`); nothing re-reads while connected.
  - Each port needs up to four `_p` calls: "empty?", then each sensor,
    starting with the one the port is meant for.
  - A setting with no reply is named in `X4Settings.missing()` and reported
    on screen. After two unanswered queries in a row the rest are skipped.
  - `X4Connection.sendCommand` has been removed.
- **Configure X4 (#26):** `X4Connection.configure(wanted)` sends the
  settings that differ from what the X4 was last known to hold
  (`X4SettingsWriter`), reads all the settings back, and repeats for any
  that still differ, up to three passes. It returns the names of any the X4
  still doesn't hold. The other screens are then given the new settings.
  - The X4 never acknowledges an assignment, so the read-back is the check.
  - The plugin waits 1.5 s after every assignment before anything else can
    be sent (`ASSIGNMENT_SETTLE_MS`); see "How the X4 handles commands".
    That is why only differing settings are sent.
  - Order: board data format and ports first, then serial number, bias, J3
    baud, frequency, and data on/off last.
  - A port showing "Other (left unchanged)" is not written.
  - Board data is always `/CBD=0,0` (SoW #16), so a device on the factory
    `0,30720` gains the four battery columns the first time.
- **Restore Data Defaults (#22):** asks first, then writes
  `X4Settings.dataDefaults(...)` through the same `configure` as Configure
  X4: J3 at 115200, J4–J9 to no sensor, DPF 1, `/CBD=0,0`, TDP on. The
  serial number and pressure bias are passed in as the X4 holds them, so
  they are never sent. It is the one thing on the screen that writes
  without Configure X4 (my reading of #22 against #26), hence the
  confirmation.
- **Load from File, Save to File (#24, #25):** `X4SettingsFile`. The
  format is ours: `name=value` lines, described in that class. They read
  and fill the screen only; nothing reaches the X4 until Configure X4.
  - Save writes the screen, not the X4, and refuses a screen with a
    mistake on it. A port showing "Other (left unchanged)" is left out.
  - Load leaves alone any setting the file leaves out, and changes
    nothing if the file has a mistake (reported with its line number) or
    names a sensor the screen doesn't offer on that port.
  - A file carries its X4's serial number and pressure bias, so it works
    as a backup. Loaded onto a different X4, the status line warns in
    orange that Configure X4 will replace that X4's. To share a setup
    without them, delete those two lines from the file.
- **The microSD card (#35, #36):** `X4Connection.eraseCard`,
  `cardDataSize`, `startCardData`; `CardDownload` saves the stream.
  - The plugin interface has no call that returns a stream, and log.dat
    can be megabytes (about 11 kB/s at 115200 baud), so the plugin only
    sends `/GET` and the app's J1 listener takes the bytes, counting them
    against `/GET?` for the progress bar.
  - Nothing marks the end of the stream, so `CardDownload` decides: once
    the expected bytes are in, the first 300 ms pause is the end; before
    that, 5 s of silence, or of a trickle under a tenth of the peak rate
    (data lines), is an early stop. Slightly more than `/GET?` said can
    arrive, since the X4 logs to the card until `/GET` is acted on; it is
    all kept.
  - While it runs: the button reads Cancel; the fields ignore J1 (the
    card's lines are old data); the J1 terminal and any PC log still get
    everything. Cancel only stops saving: the manual has no command to
    stop the X4 sending.
  - Unknown until tried on a real X4: whether `/GET?` matches the bytes
    sent, how fast and how evenly the X4 sends, how long `/ERD` takes, and
    whether a data line can slip in just after `/GET` is sent (it would
    land at the start of the file).
- **Calibration files:** `ThermistorCalibration` is not X4-specific; the
  XQ2's "Load Coefficients From File" (#50) names the same file.
- **How the X4 handles commands** (worked out from real-X4 logs,
  2026-11-12; not from the manual):
  - While publishing, the X4 acts on commands once per publishing cycle,
    just before it sends a data line. A cycle takes about 0.7 s however
    high DPF is set (at DPF=3 the uptime still steps by 0.70).
  - At DPF=1 there is a gap of about 0.3 s after each line in which
    commands are answered within 20 ms. At DPF of about 1.4 and above
    there is no gap, and every reply takes up to 0.7 s.
  - Only one command can wait. `/DPF=4` followed 150 ms later by `/DPF?`
    got the reply to `/DPF?` and the 4 was never taken.
  - This also explains the reply latency in open item 1: a 500 ms timeout
    is shorter than a cycle.
  - `tools/fake_x4.py` behaves this way by default (`--busy`).
- **Activity indicator** (not in the SoW; my request, 2026-11-23): a
  line along the bottom of the window, shown only while manufacturing
  features are enabled. It shows what the selected tab last sent on J1:
  "Sending /DPF?" for 2.5 s after each send, then "Idle. Last sent /DPF?
  at 14:03:22" in grey; blank when the tab isn't connected. The bytes
  come from `X4Connection.addSendListener`, attached before connecting so
  the handshake shows too (`ActivityBar`, `TabContentPanel.getLastSent`).
- **Console:** every J1 op, raw byte, reply and plugin decision is logged with
  timestamps. The lines don't say which tab they are from. The console
  test menu (stdin) is OFF but kept: `CONSOLE_TEST_ENABLED` in `MainFrame`.
  Before turning it on, give it a connection's plugin (see the comment
  there); the object it uses now is not connected to anything.

## Commits on the branch (oldest first)

1. `962bb68` Turn off the console test (since turned back on); log J1/J3 traffic
2. `513ca96` Plugin-loaded message; slf4j-nop
3. `cd44cee` `docs/x4-data-format.md`: data column mapping and open questions
4. `83f9d53` X4 Data screen layout (`X4DataPanel`)
5. `1bb9d2b` J1 reader thread and J1 terminal (`TerminalPane`)
6. `cc8dc3b` Transport fed from the reader
7. `79d2c0a` Plugin q/c over the transport (hardcoded port removed)
8. `261a0f3` `j3BaudRate` in the lexicon; `messageType_p`; plugin q/c/p
9. `972abe8` App uses the plugin for the J3 baud and the Configuration fetch
10. `4f91caf` `boardDataFormat`; lexicon return-token fixes

## SoW #27–#39 (X4 Data screen) status

| # | Status |
|---|---|
| 27 No data until settings read | Done: fields fill only once the settings have been read at connect. The J1 terminal shows raw data regardless. |
| 28 Board fields | Done (XS labels; ground speed m/s per my answer). |
| 29 Port fields | Done for the temperature and humidity sensors. |
| 30 Fields stay when a column is off | Done by design. |
| 31 Show exactly as sent | Done (`X4DataFormat`, with unit tests from a real capture). |
| 32 Ignore header | Done as a precaution: a real X4 sends no header on J1 (captured 2026-11-12). |
| 33 J1 terminal | Done (ASCII, 2000-line history, follows only at the bottom). |
| 34 J3 terminal | Done: `J3Connection` has its own reader thread feeding the J3 terminal. Tested on the fake X4 only (no J3 cable). |
| 35 Erase Card | Done: asks first, sends `/ERD`, then shows "Card erased" or "Card not detected". Fake X4 only. |
| 36 Get from Card + progress | Done: `/GET?` for the size, a save dialog, then `/GET`; the bytes are saved as they arrive, with a progress bar and Cancel. Fake X4 only; see "The microSD card". |
| 37 Start/Stop Logging | Done. Records J1 byte for byte (`DataLog`); the save dialog opens in `<install dir>/Data` with a timestamped `.txt` name. Stops on disconnect. |
| 38 Mark | Done. `Mark: <text>\r\n` on a line of its own: a mark made mid-line waits for that line's end. Enter in the field also marks. |
| 39 X4 picture | Done. |

I checked the layout against XS on Windows; it looks good.

## SoW #14–#26 (X4 Configuration screen) status

| # | Status |
|---|---|
| 14 Poll at connect | Done. |
| 15 X4 image | Done. |
| 16 Sensor options and fields | Done. A temperature sensor's "Load from File" reads InterMet's calibration file (`ThermistorCalibration`: serial number, then four coefficients, one per line; e.g. `112804_AT.tcal`). |
| 17–21 Serial, frequency, bias, J3 baud, data toggle | Read and write done. |
| 22 Restore Data Defaults | Done per Rev 05, with the info bubble. Tested on the fake X4 only. |
| 23 Fetch | Not implemented, as the SoW says. |
| 24, 25 Load from File, Save to File | Done; the client left the file format to us. |
| 26 Configure X4 | Done. Two real-X4 tries lost `/DPF=` because another command followed it too closely; the paced version is not yet tried on a real X4. |

## Open items, most pressing first

1. **Reply latency (the J3 baud and occasional ACK failures).**
   - With TDP on at DPF ≥ 1 Hz, replies sometimes take longer than the 500 ms
     timeout. With TDP off or DPF 0.5, they come in under 70 ms.
   - Confirmed late, not lost: after a J3 failure, the `CJ3=` reply shows up
     in the J1 terminal.
   - It varies day to day (one evening, everything was fast).
   - Measured, 10 queries each:

     | Setting | Replies (ms) | Timeouts |
     |---|---|---|
     | As found | 90–350 | 1 |
     | DPF 1 | 30–425 | 4 |
     | DPF 5 | 100–480 | 1 |
     | TDP off | 7–50 | 0 |
     | DPF 0.5 | 14–70 | 0 |

   - Done 2026-11-12: both timeouts raised to 2 s. The client doesn't see
     the latency as a problem. Retrying was discussed and deferred as
     polish.
   - Longer term: turn TDP off while reading the configuration, if the
     client says XS did.
   - Draft client questions:
     - Is this expected from the firmware?
     - What's the longest a reply can take?
     - Can commands be dropped while publishing?
     - Did XS turn TDP off for configuration reads?
     - Could GPS state matter? (0 vs 10 satellites on different days.)
2. **DPF=10 gives `DPF=ERR`.** The manual says "between 0 and 10", but the
   Configuration spinner allows 10. Ask the client.
3. **#14: poll on connect.** Done 2026-11-12 (see Architecture). Still
   open: the client asked for a cache that re-reads whenever something
   changes; for now it is read once per connection.
4. **Port bitmask.** The `configurePortTo…` returns omit each sensor's
   trailing bitmask, which the Data screen needs if a port masks columns.
   Lexicon decision pending.
5. **Data parser (#28–#32).** Done 2026-11-12; rules and limits are in
   `docs/x4-data-format.md`. Still open there: the date label, serial
   forward, reserved IDs, and port bitmasks (assumed 0, see item 4).
6. **Connect error handling.** The J1 Connect `SwingWorker` catches only
   `Exception`. An `Error` (e.g. `AbstractMethodError` from a stale plugin
   jar, or jssc's `UnsatisfiedLinkError`) leaves the UI saying "connected".
   Related offer: warn at load time if the plugin jar's build version doesn't
   match the app's.
7. **Unplugged cable.** The reader logs it and stops, but the UI still says
   connected.
8. **J3** (#34) is read by the app directly, with no plugin involved:
   nothing is ever sent on J3, and its baud comes from the J1 settings. That
   sidesteps pluginBase's one-transport-per-device model rather than
   fitting it. If Configure X4 changes the J3 baud while J3 is connected,
   J3 is disconnected with a notice.
9. **Lexicon odds and ends:**
   - `forwardBytesToJ6`/`J7` list `return_p` on query/command messages.
   - `enablePort` `_p` isn't implemented.
   - Bump `lexiconVersion`?
10. **`notes.txt`** (SoW requirement status and client questions) didn't come
    across from the old repo.
11. **Test tools.** The rounded-button code is duplicated per panel; a shared
    helper would be a small cleanup. The hardware-free test tools are now
    under `tools/`:
    - `tools/fake_x4.py`: a fake X4 on two pseudo-terminals, J1 and J3
      (`--help` lists
      its options, e.g. `--silent`, `--no-srn`, `--delay`, `--set`). Its
      header text and `/VER?` reply are guesses.
    - `tools/CaptureHeader.java`: records what a real X4 sends on J1 when
      its data format changes.
    - `tools/harness/run.sh`: runs the real app against the fake under
      Xvfb, drives the panels through reflection, and prints what they show.
      `FILES=1` and `RESTORE=1` cover #22, #24 and #25; `CAL=<file>` loads
      a calibration file; `CARD=1` and `CARD=cancel` cover #35 and #36.
      The fake's card grows as it publishes, and `--card-stall` cuts a
      download short.

    Gotchas:
    - Run GUI harnesses with the distribution folder as the working
      directory, or `Loader` won't find the plugin.
    - A root container ignores jssc's exclusive port lock, so "port busy"
      can't be reproduced there.

## Suggested next steps

1. A session on a real X4: Configure X4 (paced), Restore Data Defaults,
   Erase Card, Get from Card (compare the saved file with the card's
   log.dat), and J3 with the J3-to-USB cable.
2. Before release: settle the frequency limit (open item 2);
   connection-state errors (open items 6, 7).
3. Tell the client about the lexicon changes in 0.3.0.
4. XQ2 (#40–#56), new in Rev 04.
