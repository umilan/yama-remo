#!/bin/sh
# Runs the plain-Java unit tests (no Android needed). Called by release.sh before every build;
# a failing test stops the release.
cd "$(dirname "$0")"
OUT=build/tests
rm -rf "$OUT" && mkdir -p "$OUT"
if ! javac -nowarn -encoding UTF-8 -d "$OUT" src/com/yamaremo/kiosk/TextLogic.java \
        tests/com/yamaremo/kiosk/TextLogicTest.java > "$OUT/javac.log" 2>&1; then
    grep -v "Picked up" "$OUT/javac.log"; echo "TESTS DID NOT COMPILE"; exit 1
fi
java -cp "$OUT" com.yamaremo.kiosk.TextLogicTest > "$OUT/result.log" 2>&1
status=$?
grep -v "Picked up" "$OUT/result.log"
exit $status
