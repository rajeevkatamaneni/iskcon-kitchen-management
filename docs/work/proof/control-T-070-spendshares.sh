#!/bin/bash
# Negative control for the spendShares fix (T-070 sweep), 2026-09-11.
# Strips the status clause back out and runs GivingPageIT AS WRITTEN.
set -euo pipefail
ROOT=/Users/Rajeev/Workspace/kitchen-management-system
SNAP=/tmp/kms-control-T-070-snapshots
mkdir -p "$SNAP"
SRC="$ROOT/backend/src/main/java/org/iskcon/kms/donation/GivingPageController.java"
cp "$SRC" "$SNAP/GivingPageController.verified"
restore() {
  cp "$SNAP/GivingPageController.verified" "$SRC"
  echo "TRAP: GivingPageController restored from the cp snapshot"
  cmp -s "$SNAP/GivingPageController.verified" "$SRC" && echo "TRAP: byte-for-byte identical to the verified copy"
}
trap restore EXIT

# The anchor is the SQL line and nothing else. Counted, not merely grepped: this file now also
# carries the same two status words inside the new javadoc, so an anchor matching prose rather
# than SQL would strip a comment, leave the fix standing, and produce a plausible green over a
# tree that was never patched.
A="WHERE po.status NOT IN ('DRAFT', 'CANCELLED')"
N=$(grep -cF "$A" "$SRC" || true)
echo "ANCHOR: \"$A\" matches $N site(s) in GivingPageController.java (expected 1)"
[ "$N" = "1" ] || { echo "ABORT: anchor count wrong — refusing to patch"; exit 9; }

# For contrast, prove the words also appear in the javadoc, so the count above is doing real work.
echo "CONTEXT: the bare word DRAFT appears $(grep -c 'DRAFT' "$SRC") time(s) in the file (SQL + prose)"

python3 - "$SRC" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
a="\t\t\t\tWHERE po.status NOT IN ('DRAFT', 'CANCELLED')\n\t\t\t\t  AND po.created_at >= CURRENT_DATE - INTERVAL '30 days'\n"
b="\t\t\t\tWHERE po.created_at >= CURRENT_DATE - INTERVAL '30 days'\n"
assert s.count(a)==1, "SQL block did not match exactly once"
open(p,'w').write(s.replace(a,b,1))
PY
grep -qF "$A" "$SRC" && { echo "ABORT: strip did not land"; exit 9; }
echo "STRIPPED: spendShares back to counting every order, whatever became of it"

echo "=== git diff --stat proving the tree changed ==="
cd "$ROOT" && git diff --stat -- backend/src/main/java/org/iskcon/kms/donation/GivingPageController.java

echo "=== GivingPageIT as written, against the stripped tree ==="
set +e
cd "$ROOT" && ./tools/work-lock.sh run verify \
  'cd backend && ./gradlew test --no-daemon --rerun-tasks --tests "*GivingPageIT*"' \
  2>&1 | grep -E "GivingPageIT >|Total:|Passed:|Failed:|BUILD|AssertionError|expected:|JSON path"
set -e
echo "=== control done; the trap restores now ==="
