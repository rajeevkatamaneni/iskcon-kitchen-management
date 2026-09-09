#!/usr/bin/env bash
#
# T-086 negative control. Put the defect back — make Status judge `onHand` again, exactly as it did
# before this task — and watch the nearly-fully-committed item read Fine.
#
# Five conditions, per docs/work/README.md lesson 4:
#   set -e                      a patch that matches nothing stops the script
#   grep -c the anchor          and assert the count, so an anchor that matches twice aborts
#   git diff --stat             prove the tree actually changed before running anything
#   --rerun-tasks               deny Gradle its up-to-date shortcut
#   EXIT trap + byte-for-byte   the tree is restored even if this dies mid-run
# and the log is named for the task, because the scratchpad is shared by every agent in this
# checkout and two builders both writing control.log is how one reads the other's evidence.

set -euo pipefail

ROOT=/Users/Rajeev/Workspace/kitchen-management-system
SRC="$ROOT/backend/src/main/java/org/iskcon/kms/inventory/InventoryItemService.java"
WORK=/private/tmp/claude-501/-Users-Rajeev-Workspace-kitchen-management-system/d8a15814-5daf-4b1d-ac53-4d3dcc17162c/scratchpad
SAVED="$WORK/InventoryItemService.verified.java"

cp "$SRC" "$SAVED"
VERIFIED_SUM=$(shasum -a 256 < "$SAVED")
echo "control: verified copy sha256 $VERIFIED_SUM"
restore() {
  cp "$SAVED" "$SRC"
  AFTER_SUM=$(shasum -a 256 < "$SRC")
  echo "control: restored file  sha256 $AFTER_SUM"
  if [ "$VERIFIED_SUM" = "$AFTER_SUM" ]; then
    echo "control: restored $SRC byte-for-byte"
  else
    echo "control: RESTORE FAILED — $SRC differs from the verified copy" >&2
    exit 2
  fi
}
trap restore EXIT

ANCHOR='available.compareTo(item.reorderThreshold())'
COUNT=$(grep -c -F "$ANCHOR" "$SRC")
echo "control: anchor '$ANCHOR' matched $COUNT time(s)"
if [ "$COUNT" -ne 1 ]; then
  echo "control: expected exactly 1 site to strip, found $COUNT — aborting" >&2
  exit 1
fi

python3 - "$SRC" <<'PY'
import sys
path = sys.argv[1]
s = open(path).read()
old = """		boolean belowThreshold = available.signum() < 0
				|| (item.reorderThreshold() != null && available.compareTo(item.reorderThreshold()) < 0);"""
new = """		boolean belowThreshold = item.reorderThreshold() != null
				&& onHand.compareTo(item.reorderThreshold()) < 0;"""
if s.count(old) != 1:
    sys.exit("control: the fix block matched %d times, not 1" % s.count(old))
open(path, 'w').write(s.replace(old, new))
print("control: Status judges onHand again")
PY

# Two proofs that the tree moved, because one of them is weaker than it looks. `git diff` is
# against HEAD and would be non-empty from this task's own work whether the control applied or not;
# the diff against the verified copy is the one that can only be non-empty if the fix came out.
echo "control: the fix, taken back out ----------------------------"
if diff -u "$SAVED" "$SRC"; then
  echo "control: the patch changed nothing — aborting" >&2
  exit 1
fi
echo "control: patched file  sha256 $(shasum -a 256 < "$SRC")"
echo "control: and against HEAD -----------------------------------"
git -C "$ROOT" diff --stat -- "$SRC"
echo "-------------------------------------------------------------"

cd "$ROOT/backend"
./gradlew test --tests "*InventoryStockIT*" --rerun-tasks || echo "control: gradle exited $? (a red run is the point)"
