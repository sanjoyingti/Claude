#!/usr/bin/env bash
# Lists every protected rule marker in Arin's source as "file|rule" (used to build and check the baseline).
grep -rnoE "ARIN-PROTECTED(-RULE)?: [A-Za-z0-9 _-]+" app/src/main/java \
  | sed -E 's#^app/src/main/java/com/arin/##; s#:[0-9]+:ARIN-PROTECTED(-RULE)?: #|#; s/ +$//' \
  | sed -E 's/\|([a-z0-9-]+( [a-z0-9-]+)*).*/|\1/' | sort -u
