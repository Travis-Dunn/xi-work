# X4 data line: column mapping for the Data screen

Reference for SoW 205196 Rev 03 items #27–#32. Sources: X4 manual
252100.0200 Rev 12, sections 3.3.1, 3.3.4, 3.3.5 and chapter 4; SoW
Figure 3-1 (iMet-XS Data screen).

## How a data line is built (manual 4.1)

- One line per publication on J1–J3, ending in `\r\n`.
- Columns: board data first, then J4, J5, J6, J7, J8, J9, in that order.
  Each port contributes the columns of whatever sensor it is configured
  for (`/CJ#?`), in the sensor's index order.
- A column switched off by a bitmask is omitted entirely, from both the
  header and the data. Its neighbours shift left.
- `9999` means the sensor had nothing to report or is missing (still a
  column). Show it as is (#31).
- The delimiter is the second character of the `/CBD` setting. XI only
  writes `,` (`/CBD=0,0`), but a device could hold another one.

So the column layout cannot be known until XI has read `/CBD?` and
`/CJ4?`–`/CJ9?`. That is why #27 says to show nothing until the
settings have been retrieved. Note: ConfigurationPanel's fetch does not
poll `/CBD?` yet.

## Bitmasks (manual 3.3.1)

The last value of `/CBD` and of most `/CJ#` settings is a bitmask stored
as a decimal integer. A 1 bit removes a column; 0 keeps everything. Index
0 is the most significant bit. For a field of n columns, column `i` is
removed when bit `(n - 1 - i)` is set.

Example: the default `/CBD=0,30720`. 30720 = bits 11–14 of a 20-bit
field, i.e. board indices 5–8 (battery capacity, power, current,
state) are omitted.

## Board columns (manual 4.2, 20 columns, mask max 1048575)

| Index | Manual header           | Unit / format        | XS label (Figure 3-1)      |
|------:|-------------------------|----------------------|----------------------------|
|     0 | Serial Number           | text, exact          | Serial Number              |
|     1 | Date                    | mm/dd/yy             | Date (YYYY-MM-DD)          |
|     2 | UTC of Position Fix     | hh:mm:ss             | Time (UTC HH:MM:SS)        |
|     3 | Uptime                  | s, 0.00              | Run Time (s)               |
|     4 | Battery Charge          | %, integer           | Battery Charge (%)         |
|     5 | Battery Capacity        | mAh, integer         | Battery Capacity (mAh)     |
|     6 | Battery Power           | mW, integer          | Battery Power (mW)         |
|     7 | Battery Current         | mA, integer          | Battery Current (mA)       |
|     8 | Battery Charge State    | 0–3 code             | Battery State              |
|     9 | Pressure                | mbar, 0000.00        | Pressure (hPa)             |
|    10 | Pressure Temperature    | °C, 000.00           | Pressure Temp (°C)         |
|    11 | Relative Humidity       | %, 000.00            | Humidity (%)               |
|    12 | Humidity Temperature    | °C, 000.00           | Humidity Temp (°C)         |
|    13 | Satellites              | integer              | Satellites                 |
|    14 | Latitude                | °, 00.00000001       | Latitude (°)               |
|    15 | Longitude               | °, 000.00000002      | Longitude (°)              |
|    16 | Horizontal DoP          | 00.00                | HDoP                       |
|    17 | Altitude (orthometric)  | m, 00000.0           | Altitude (m)               |
|    18 | Speed Over Ground       | m/s, 000.000         | Ground Speed (knots); XI uses (m/s) |
|    19 | Tracking Angle (true)   | °, integer           | Tracking Angle (°)         |

Battery Charge State: 0 = no battery, 1 = discharging, 2 = charging,
3 = fully charged. Shown as the raw number (#31).

## Port columns (manual 4.3)

| Sensor ID | Ports  | Columns (index order)                         | Mask     | XS labels                                                       |
|----------:|--------|-----------------------------------------------|----------|-----------------------------------------------------------------|
|         0 | J4–J9  | none                                          | –        | (row left empty)                                                |
|         3 | J8, J9 | 0 Serial Number, 1 Temperature (°C, 000.00)   | last value of `/CJ#`, max 3 | 10085B IMet X4 Temperature Sensor: AT Serial Number, Temperature (°C) |
|         4 | J4, J5 | 0 Relative Humidity (%), 1 Humidity Temperature (°C) | last value of `/CJ#`, max 3 | 10086B IMet X4 Humidity Sensor: Rel. Humidity (%), Humidity Temp (°C) |
|         6 | J6, J7 | `D` columns, `D` = last value of `/CJ#` (1–10) | none     | none in XS (SoW #16: J6/J7 have no sensors in XI)               |
|   2, 5, 7 | –      | reserved; column count unknown                | –        | –                                                               |

`/CJ#?` replies, for reference:
`CJ8=3,<serial>,<A0>,<A1>,<A2>,<A3>,<mask>`, `CJ4=4,0x28,<mask>`,
`CJ6=6,<baud>,<columns>`, `CJ7=0`.

## Worked example (real X4, 2026-10-30, TDP=ON)

```
420,10/30/26,1:12:36,47090.78,100,991.92,30.63,29.30,31.67,0,9999,9999,9999,9999,9999,9999,78255,9999
```

18 values = 16 board columns (`CBD` mask 30720 drops indices 5–8) + 2
from a temperature sensor on J8 or J9 (serial 78255, temperature 9999).

| Value     | Column                 |
|-----------|------------------------|
| 420       | Serial Number          |
| 10/30/26  | Date                   |
| 1:12:36   | UTC of Position Fix    |
| 47090.78  | Uptime                 |
| 100       | Battery Charge         |
| 991.92    | Pressure               |
| 30.63     | Pressure Temperature   |
| 29.30     | Relative Humidity      |
| 31.67     | Humidity Temperature   |
| 0         | Satellites             |
| 9999 ×6   | Latitude … Tracking Angle (no GPS fix) |
| 78255     | J8/J9 temp: Serial Number |
| 9999      | J8/J9 temp: Temperature   |

Note the unpadded hour in `1:12:36` (and `17:31:0` in Figure 3-1):
the device does not zero-pad, and #31 says to show it as sent.

## What a real X4 does (captured 2026-11-12, `tools/CaptureHeader.java`)

- **No header on J1.** Changing `/CBD` with TDP on changed the very next
  data line (18 columns, then 22, then 18) with no header in between.
  The header goes to the microSD card only, as the manual says; SoW #32's
  "published on J1-J3" does not hold for this firmware.
- **A pause after a format change.** Uptime jumped about 2.6 s across
  each `/CBD` change, so the X4 publishes nothing for a moment.
- **Replies share the stream.** `ACK`, `CBD=0,30720` and so on arrive
  between data lines, on lines of their own.
- **A value can come out short.** One line read `18:3:2` between
  `18:3:24` and `18:3:27`. Shown as sent (#31).
- **A NUL byte arrives when the port is opened.** The terminals drop NULs.
- `/DPF?` answers with six decimals: `DPF=1.000000`.

## How XI parses a line (`X4DataFormat`)

1. Nothing is parsed until the settings have been read at connect (#27).
   If any setting is missing, or `/CBD` can't be understood, nothing is
   parsed and the Data screen says why in red.
2. Split on the delimiter from `/CBD`, keeping empty values.
3. The line must have exactly the number of columns the settings give:
   the board columns the mask keeps, plus each port's sensor columns.
   Anything else (a reply, a partial line, a different format) is not
   data and is ignored.
4. Header precaution (#32): also ignored are a line that is one value
   followed by nothing but delimiters (the title row), and a line whose
   uptime value is not a number (the column-name row).
5. Values go to the fields untouched. A board column that is masked out
   leaves its field empty (#30).
6. After three lines in a row that aren't data, the fields are cleared
   and the screen says the data doesn't match the settings, in red. That
   is what happens if another program changes the X4's format.

Assumptions and limits:

- A sensor's own bitmask is taken to be 0, which is all XI writes
  (SoW #16). A device with a non-zero port mask has fewer columns than
  expected, so it lands in rule 6 rather than showing values under the
  wrong labels.
- A port set to a reserved or unknown sensor ID has an unknown number of
  columns. The board and the ports before it are shown; that port and
  later ones are left empty, with an orange note.
- Serial forward (ID 6) columns are counted but not shown.

## Open questions for the client

1. **Date label.** XS says "Date (YYYY-MM-DD)"; the device sends
   `mm/dd/yy` (`10/30/26`). Keep the XS label (#28) or correct it?
2. ~~Ground speed label.~~ Resolved 2026-11-04: label it m/s, as the
   manual says.
3. ~~Header lines (#32).~~ Resolved 2026-11-12: a real X4 sends no
   header on J1. See "What a real X4 does".
4. **Serial forward (ID 6) on J6/J7.** What do its columns contain, and
   can forwarded bytes include the delimiter? If they can, columns after
   J6/J7 can't be located reliably.
5. **Reserved IDs (2, 5, 7) or an unknown ID on a port.** Their column
   count is unknown, so everything after that port can't be placed.
   Proposed: fill the board and earlier ports, leave later ports blank,
   and say why in the status area.
6. **Delimiter.** If `/CBD?` reports a delimiter other than `,`, parse
   with it (proposed), or treat it as unsupported?
