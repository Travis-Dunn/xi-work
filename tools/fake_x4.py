#!/usr/bin/env python3
"""A stand-in for an iMet-X4 on J1, for testing iMet-XI without hardware.

It opens a pseudo-terminal and answers the X4's commands on it (manual
252100.0200 Rev 12, section 3.3), and streams data lines the way the X4
does (chapter 4). Linux and macOS only.

    python3 tools/fake_x4.py --link /tmp/fake-x4

Then open the printed port (or the link) at any baud rate. The COM port
menus in XI only list real serial ports, so this is for test harnesses
that name the port themselves; the app's own run configuration is for a
real X4.

Like a real X4, it acts on only one command per publishing cycle while
data is on, and loses a command that is still waiting when another
arrives (see FakeX4.run and --busy).

Things this does NOT know from a real X4, and guesses:
  - the header's exact text (it follows manual Figure 4-1)
  - the /VER? reply's format
  - replies to malformed commands (it stays silent, except DPF=ERR)
Treat a pass against this as "the code does what we think the X4 needs",
not as proof against the real device.
"""

import argparse
import os
import select
import sys
import time
import tty

BOARD_HEADERS = [
    "Serial Number", "Date (YMD)", "UTC Time (HMS)", "Run Time (s)",
    "Battery Charge (%)", "Battery Capacity (mAh)", "Battery Power (mW)",
    "Battery Current (mA)", "Battery Charge State", "Pressure (mB)",
    "Pressure Temperature (C)", "Humidity (%)", "Humidity Temperature (C)",
    "Satellites", "Latitude (deg)", "Longitude (deg)", "HDoP",
    "Altitude (m)", "Speed Over Ground (m/s)", "Tracking Angle (deg)",
]

BAUDS = {"9600", "19200", "38400", "57600", "115200"}


def kept(count, mask):
    """Indices of the columns a bitmask leaves in. Index 0 is the most
    significant bit; a 1 bit removes the column (manual 3.3.1)."""
    return [i for i in range(count) if not (mask >> (count - 1 - i)) & 1]


class FakeX4:
    def __init__(self, args):
        self.args = args
        self.settings = {
            "SRN": "420",
            "DPF": "1",
            "CPB": "0",
            "TDP": "ON" if args.tdp == "on" else "OFF",
            "CJ3": "115200",
            "CBD": "0,30720",
            "EJ4": "LOW",
            "EJ5": "LOW",
        }
        for port in range(4, 10):
            self.settings["CJ%d" % port] = "0"
        for item in args.set:
            key, _, value = item.partition("=")
            self.settings[key.upper()] = value
        self.assignments = 0
        self.started = time.time()
        self.next_line = time.time()
        self.card = (b"" if args.card_bytes < 0
                     else self.card_contents(args.card_bytes))

        self.master, self.slave = os.openpty()
        tty.setraw(self.slave)  # no echo, no CR/LF translation
        self.port = os.ttyname(self.slave)
        self.rx = b""

    # ── Output ──────────────────────────────────────────────────────────

    def send(self, text):
        os.write(self.master, text if isinstance(text, bytes)
                 else text.encode("latin-1"))

    def reply(self, text):
        if self.args.silent:
            return
        if self.args.delay:
            time.sleep(self.args.delay / 1000.0)
        if self.args.collide and self.publishing():
            self.send(self.data_line())
        self.send(("/" if self.args.slash else "") + text + "\r\n")

    # ── Data stream ─────────────────────────────────────────────────────

    def publishing(self):
        return self.settings["TDP"] == "ON" and self.frequency() > 0

    def frequency(self):
        try:
            return float(self.settings["DPF"])
        except ValueError:
            return 0.0

    def delimiter(self):
        return self.settings["CBD"][1:2] or ","

    def board_mask(self):
        try:
            return int(self.settings["CBD"][2:])
        except ValueError:
            return 0

    def port_columns(self, port):
        """(headers, values) for whatever a port is configured for."""
        parts = self.settings["CJ%d" % port].split(",")
        sensor = parts[0].strip()
        if sensor == "3":
            serial = parts[1].strip() if len(parts) > 1 else "0"
            headers = ["Serial Number", "Temperature (C)"]
            values = [serial, "%.2f" % (21.0 + port / 100.0)]
        elif sensor == "4":
            headers = ["Humidity (%)", "Humidity Temperature (C)"]
            values = ["%.2f" % (27.0 + port / 100.0), "21.16"]
        elif sensor == "6":
            count = int(parts[2]) if len(parts) > 2 else 1
            return ([str(i + 1) for i in range(count)], ["9999"] * count)
        else:
            return ([], [])
        try:
            mask = int(parts[-1])
        except ValueError:
            mask = 0
        keep = kept(len(headers), mask)
        return ([headers[i] for i in keep], [values[i] for i in keep])

    def board_values(self):
        now = time.gmtime()
        fix = not self.args.no_gps
        return [
            self.settings["SRN"],
            # The X4 does not zero-pad dates or times.
            "%d/%d/%02d" % (now.tm_mon, now.tm_mday, now.tm_year % 100)
            if fix else "9999/9999/9999",
            "%d:%d:%d" % (now.tm_hour, now.tm_min, now.tm_sec),
            "%.2f" % (time.time() - self.started),
            "100", "1904", "-79", "-19", "3",
            "%.2f" % (998.03 + self.bias()),
            "26.49", "15.90", "27.66",
            "9" if fix else "0",
            "42.8768148" if fix else "9999",
            "-85.5608193" if fix else "9999",
            "0.99" if fix else "9999",
            "200.7" if fix else "9999",
            "0.055" if fix else "9999",
            "9999",
        ]

    def bias(self):
        try:
            return float(self.settings["CPB"])
        except ValueError:
            return 0.0

    def columns(self):
        keep = kept(len(BOARD_HEADERS), self.board_mask())
        values = self.board_values()
        headers = [BOARD_HEADERS[i] for i in keep]
        values = [values[i] for i in keep]
        for port in range(4, 10):
            h, v = self.port_columns(port)
            headers += h
            values += v
        return headers, values

    def data_line(self):
        return self.delimiter().join(self.columns()[1]) + "\r\n"

    def header(self):
        headers = self.columns()[0]
        d = self.delimiter()
        title = "X4 Board Sensors" + d * (len(headers) - 1)
        return title + "\r\n" + d.join(headers) + "\r\n"

    def card_contents(self, size):
        text = (self.header() + self.data_line() * 4).encode("latin-1")
        return (text * (size // len(text) + 1))[:size]

    # ── Commands ────────────────────────────────────────────────────────

    def handle(self, line):
        if line.startswith("/"):
            line = line[1:]
        upper = line.upper()

        if upper == "ACK?":
            self.reply("ACK")
        elif upper == "VER?":
            self.reply("VER=1.00")
        elif upper == "RST":
            self.reply("RST")
            self.started = time.time()
        elif upper == "ERD":
            if self.args.card_bytes < 0:
                self.reply("Card not detected")
            else:
                self.card = b""
                self.reply("Complete")
        elif upper == "GET?":
            self.reply("GET=%d" % (len(self.card)
                                   if self.args.card_bytes >= 0 else -1))
        elif upper == "GET":
            if not self.card:
                self.reply("Card not detected")
            else:
                for i in range(0, len(self.card), 512):
                    self.send(self.card[i:i + 512])
                    time.sleep(0.01)
        elif upper.endswith("?"):
            key = upper[:-1]
            if key == "SRN" and self.args.no_srn:
                return
            if key in self.settings:
                value = self.settings[key]
                if key == "DPF":
                    value = "%.6f" % self.frequency()  # as a real X4 prints it
                self.reply(key + "=" + value)
        elif "=" in line:
            key, _, value = line.partition("=")
            self.assign(key.upper(), value)

    def assign(self, key, value):
        """Assignments return nothing (manual 3.3.1)."""
        if key not in self.settings:
            return
        self.assignments += 1
        if self.args.flaky and self.assignments % self.args.flaky == 0:
            return  # ignored, as a real X4 has been seen to do
        if key == "DPF":
            try:
                ok = 0 <= float(value) < 10  # a real X4 rejects 10
            except ValueError:
                ok = False
            if not ok:
                self.reply("DPF=ERR")
                return
        elif key == "TDP":
            value = value.upper()
            if value not in ("ON", "OFF"):
                return
        elif key == "CJ3" and value not in BAUDS:
            return
        elif key in ("EJ4", "EJ5"):
            value = value.upper()
        changed = self.settings[key] != value
        self.settings[key] = value
        # A new header follows a change to the data format (SoW #32).
        if changed and (key == "CBD" or key in
                        ("CJ4", "CJ5", "CJ6", "CJ7", "CJ8", "CJ9")):
            if self.publishing():
                self.send(self.header())

    # ── Main loop ───────────────────────────────────────────────────────

    def run(self):
        """Publishes and answers the way a real X4 has been seen to
        (2026-11-12). Each publishing cycle starts with a busy stretch
        (--busy, 0.7 s) in which commands are not acted on; at its end the
        X4 acts on the one command waiting, publishes a data line, and
        then answers commands at once until the next cycle starts. Only
        one command can wait: a newer one replaces it. When not
        publishing, commands are answered at once."""
        busy = self.args.busy / 1000.0
        cycle_start = time.time()
        published = False
        pending = None
        while True:
            now = time.time()
            if self.publishing():
                period = max(1.0 / self.frequency(), busy)
                if not published and now >= cycle_start + busy:
                    if pending is not None:
                        line, pending = pending, None
                        self.handle(line)
                    if self.publishing():
                        self.send(self.data_line())
                    published = True
                if now >= cycle_start + period:
                    cycle_start = now
                    published = busy <= 0
                    if published:
                        self.send(self.data_line())
                until = cycle_start + (period if published else busy)
                timeout = max(0.0, until - now)
            else:
                cycle_start = now
                published = False
                timeout = 0.5
                if pending is not None:
                    line, pending = pending, None
                    self.handle(line)

            ready, _, _ = select.select([self.master], [], [], timeout)
            if ready:
                self.rx += os.read(self.master, 4096)
                while b"\n" in self.rx:
                    raw, _, self.rx = self.rx.partition(b"\n")
                    line = raw.decode("latin-1").strip()
                    if not line:
                        continue
                    if self.args.verbose:
                        print("rx", repr(line), flush=True)
                    if self.publishing() and not published:
                        if pending is not None and self.args.verbose:
                            print("lost", repr(pending), flush=True)
                        pending = line
                    else:
                        self.handle(line)


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--link", help="make this path a symlink to the port")
    p.add_argument("--tdp", choices=["on", "off"], default="on",
                   help="data publishing at start (default on, as shipped)")
    p.add_argument("--set", action="append", default=[], metavar="KEY=VALUE",
                   help="starting value of a setting, e.g. "
                        "--set CJ4=4,0x28,0 --set DPF=5 (repeatable)")
    p.add_argument("--delay", type=int, default=0, metavar="MS",
                   help="wait this long before each reply")
    p.add_argument("--slash", action="store_true",
                   help="start replies with '/'")
    p.add_argument("--silent", action="store_true",
                   help="never reply (data still streams)")
    p.add_argument("--no-srn", action="store_true",
                   help="don't answer /SRN?")
    p.add_argument("--busy", type=int, default=700, metavar="MS",
                   help="how long each publishing cycle ignores commands "
                        "(default 700, as measured; 0 to answer at once)")
    p.add_argument("--flaky", type=int, default=0, metavar="N",
                   help="ignore every Nth assignment")
    p.add_argument("--collide", action="store_true",
                   help="publish a data line right before each reply")
    p.add_argument("--no-gps", action="store_true",
                   help="report 9999 for the GPS columns")
    p.add_argument("--card-bytes", type=int, default=20000, metavar="N",
                   help="size of log.dat on the card; -1 for no card")
    p.add_argument("--verbose", action="store_true",
                   help="print each command received")
    args = p.parse_args()

    x4 = FakeX4(args)
    if args.link:
        if os.path.islink(args.link):
            os.unlink(args.link)
        os.symlink(x4.port, args.link)
    print("fake X4 on %s%s" % (x4.port,
                               " (link: %s)" % args.link if args.link else ""),
          flush=True)
    try:
        x4.run()
    except KeyboardInterrupt:
        pass
    finally:
        if args.link and os.path.islink(args.link):
            os.unlink(args.link)


if __name__ == "__main__":
    sys.exit(main())
