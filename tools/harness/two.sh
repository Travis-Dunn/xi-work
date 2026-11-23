#!/bin/sh
# Runs the real app against two fake X4s, one per tab, and prints what
# each tab shows, then which commands each fake received. It also turns
# manufacturing features on and prints what the activity indicator shows
# (screenshot: out/two-activity.png). Linux only;
# needs Xvfb. Build first: mvn clean package (from the repo root).
#
#   tools/harness/two.sh
set -u
H=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$H/../.." && pwd)
D=$ROOT/distribution/target/xi-work
O=$H/out
mkdir -p "$O"

javac -d "$O" -cp "$D/xi-app.jar" "$H/Harness.java" "$H/TwoTabs.java" || exit 1

python3 "$ROOT/tools/fake_x4.py" --link "$O/port-twoA" --j3-link "$O/j3-twoA" --set SRN=AAA111 --verbose > "$O/fake-twoA.log" 2>&1 &
A=$!
python3 "$ROOT/tools/fake_x4.py" --link "$O/port-twoB" --j3-link "$O/j3-twoB" --set SRN=BBB222 --verbose > "$O/fake-twoB.log" 2>&1 &
B=$!
sleep 0.5

(cd "$D" && xvfb-run -a java -cp "xi-app.jar:$O" TwoTabs \
    "$O/port-twoA" "$O/port-twoB" < /dev/null > "$O/out-two.log" 2>&1)

kill $A $B 2>/dev/null
grep -E "HARNESS|Exception|Error" "$O/out-two.log"
echo "X4 A (AAA111) was sent: $(grep -o 'DPF=[0-9.]*' "$O/fake-twoA.log" | tr '\n' ' ')"
echo "X4 B (BBB222) was sent: $(grep -o 'DPF=[0-9.]*' "$O/fake-twoB.log" | tr '\n' ' ')"
