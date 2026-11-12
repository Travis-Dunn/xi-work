#!/bin/sh
# Runs the real app against tools/fake_x4.py and prints what the screens
# show. Linux only; needs Xvfb. Build first: mvn clean package (from the
# repo root).
#
#   tools/harness/run.sh <tag> [fake_x4.py options...]
#   tools/harness/run.sh normal --set CJ4=4,0x28,0 --set DPF=5
#
# Set POKE to a command to send the fake X4 mid-run, behind the app's
# back, e.g. POKE=/CBD=0,0 to change the data format under it.
#
# Output goes to tools/harness/out/: <tag>-config.png, <tag>-data.png,
# and the app's console log in out-<tag>.log.
set -u
H=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$H/../.." && pwd)
D=$ROOT/distribution/target/xi-work
O=$H/out
tag=$1; shift
mkdir -p "$O"

javac -d "$O" -cp "$D/xi-app.jar" "$H/Harness.java" || exit 1

python3 "$ROOT/tools/fake_x4.py" --link "$O/port-$tag" "$@" > "$O/fake-$tag.log" 2>&1 &
FAKE=$!
sleep 0.5

# The app looks for plugins/ and lexicons/ in its working directory.
(cd "$D" && xvfb-run -a java -cp "xi-app.jar:$O" Harness \
    "$O/port-$tag" "$O" "$tag" ${POKE:+"$POKE"} < /dev/null > "$O/out-$tag.log" 2>&1)

kill $FAKE 2>/dev/null
grep -E "HARNESS|Exception|Error" "$O/out-$tag.log"
