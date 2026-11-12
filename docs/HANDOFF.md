# Handoff: iMet-XI / X4 work (as of 2026-11-12)

## Who and what

I'm a contractor for InterMet Systems (the client), working on **iMet-XI**, a
Java/Swing rewrite of the client's iMet-XS app (XS source is lost). XI
configures and reads data from InterMet's X-series devices (X4 first, then
XQ2, maybe XF) through device plugins. Work is governed by SoW 205196 Rev 03.
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

- **`Travis-Dunn/xi-work`**: the single multi-module Maven repo. Current work
  is on branch `claude/clever-goodall-ae4od5`, 11 commits ahead of `master`.
  No PR has been opened.
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
  - parameterized + command: `configurePortToEmpty` / `AirThermistor` /
    `HYT271` / `SerialForward` for ports 4–9. The queries return the values
    the lexicon lists; the trailing bitmask is NOT returned. The commands
    take the port, then those same values, and always send a bitmask of 0.
- **Lexicon** (`lexiconVersion` 0.2.0, 22 messages): added `j3BaudRate` and
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
- **Console:** every J1 op, raw byte, reply and plugin decision is logged with
  timestamps. The console test menu (stdin) is ON: `CONSOLE_TEST_ENABLED` in
  `MainFrame`. It exercises plugin messages over the GUI's J1 connection.

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
| 35 Erase Card | Button only. |
| 36 Get from Card + progress | Button and hidden progress bar only. |
| 37 Start/Stop Logging | Done. Records J1 byte for byte (`DataLog`); the save dialog opens in `<install dir>/Data` with a timestamped `.txt` name. Stops on disconnect. |
| 38 Mark | Done. `Mark: <text>\r\n` on a line of its own: a mark made mid-line waits for that line's end. Enter in the field also marks. |
| 39 X4 picture | Done. |

I checked the layout against XS on Windows; it looks good.

## SoW #14–#26 (X4 Configuration screen) status

| # | Status |
|---|---|
| 14 Poll at connect | Done. |
| 15 X4 image | Done. |
| 16 Sensor options and fields | Done, except the TCal "Load from File" button (disabled; file format unknown). |
| 17–21 Serial, frequency, bias, J3 baud, data toggle | Read and write done. |
| 22 Restore Defaults | Button disabled; TBD in the SoW. |
| 23 Fetch | Not implemented, as the SoW says. |
| 24, 25 Load from File, Save to File | Buttons disabled; TBD in the SoW. |
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

    Gotchas:
    - Run GUI harnesses with the distribution folder as the working
      directory, or `Loader` won't find the plugin.
    - A root container ignores jssc's exclusive port lock, so "port busy"
      can't be reproduced there.

## Suggested next steps

1. Card buttons (#35, #36).
2. Try J3 on a real X4 with the J3-to-USB cable.
4. Waiting on the client: the TCal "Load from File" (needs a sample
   calibration file), and Restore Defaults, Load from File and Save to File
   (#22, #24, #25, TBD in the SoW). Their buttons are shown but disabled.
