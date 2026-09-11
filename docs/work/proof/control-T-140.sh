#!/usr/bin/env bash
#
# The negative control for T-140.
#
# T-140's claim is a NUMBER: one GET /api/v1/shopping-list sends one statement against
# stock_movements, where it used to send three. A test written after a fix, against the fixed code,
# passes whether or not it exercises the defect at all — so this puts the defect back and shows the
# number come back with it. A control that only showed some tests going red would prove less.
#
# WHAT IS REVERTED. Only the two call sites in ShoppingListService that hand the already-read stock
# map to its two collaborators. The new variants they call stay in the tree, unused. That is the
# whole of the fix's mechanism — "pass the answer in instead of asking again" — so removing exactly
# it, and nothing else, is what makes the red attributable.
#
# THE FIVE CONDITIONS docs/work/README.md asks for, and where each is met:
#
#   1. Patch loudly.        set -euo pipefail, and a perl substitution whose result is checked.
#   2. Prove it applied.    diff against the saved copy BEFORE running anything; abort if identical.
#   3. Baseline the tree,   the saved copy is of the WORKING TREE, not of HEAD. HEAD is three
#      not HEAD.            completed-but-uncommitted tasks behind this tree (T-120, T-132, T-139),
#                           so `git diff HEAD` here says nothing about what this control changed.
#   4. Anchors must match   grep -cF each anchor and require exactly 1. An anchor that matched two
#      exactly once.        sites would strip somebody else's work and produce a plausible false red.
#   5. Name the artefact    control-T-140.log, control-T-140-*.  The scratchpad is shared by every
#      for the task.        agent in this checkout and two builders have collided on control.log.
#
# Restoration is trapped rather than trusted, and verified byte-for-byte: a builder that dies
# mid-control must not leave the tree broken for the next one.
#
# Run it through the verify lock:
#   tools/work-lock.sh run verify docs/work/proof/control-T-140.sh

set -euo pipefail

ROOT="/Users/Rajeev/Workspace/kitchen-management-system"
SVC="$ROOT/backend/src/main/java/org/iskcon/kms/shoppinglist/ShoppingListService.java"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/control-T-140-XXXXXX")"
SAFE="$WORK/ShoppingListService.java.verified"
REPORT="$ROOT/backend/build/reports/perf/T-139-shopping-list.txt"

export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

echo "=============================================================================="
echo " T-140 negative control — put the three reads back and watch the count return"
echo " Started $(date)"
echo " Scratch: $WORK"
echo "=============================================================================="
echo

# --- 0. Keep a copy of the VERIFIED WORKING TREE, and restore from it whatever happens ----------

cp "$SVC" "$SAFE"

restore() {
  local status=$?
  echo
  echo "--- restoring ShoppingListService.java from the verified copy -----------------"
  cp "$SAFE" "$SVC"
  if cmp -s "$SAFE" "$SVC"; then
    echo "RESTORED, byte-for-byte identical to the copy taken before the patch."
  else
    echo "!!! RESTORE FAILED. The tree is left patched. Copy is at $SAFE"
  fi
  echo "--- control finished with status $status --------------------------------------"
  exit "$status"
}
trap restore EXIT

# --- 1. The anchors, each of which must match exactly once --------------------------------------

A1='sufficiencyService.shortfallFeed(onHandBase)'
A2='for (InventoryItemService.LowStockLine item : inventoryItemService.lowStock(onHandBase)) {'

for anchor in "$A1" "$A2"; do
  n=$(grep -cF -- "$anchor" "$SVC" || true)
  echo "anchor matched $n time(s): $anchor"
  if [ "$n" -ne 1 ]; then
    echo "ABORTING: expected exactly 1 match, found $n. Patching would change the wrong thing."
    exit 2
  fi
done
echo

# --- 2. Put the defect back ---------------------------------------------------------------------
#
# Stream 1 goes back to asking SufficiencyService for a feed it computes from its own reading of the
# ledger; stream 2 goes back to InventoryItemService.lowStock(), which reads the ledger by batch.
# StockItemView is named fully-qualified so no import has to be restored as well.

perl -0pi -e "s/\Q$A1\E/sufficiencyService.shortfallFeed()/" "$SVC"
perl -0pi -e "s/\Q$A2\E/for (org.iskcon.kms.inventory.StockItemView item : inventoryItemService.lowStock()) {/" "$SVC"

# --- 3. Prove the tree actually changed, against the copy rather than against HEAD ---------------

echo "--- what the control changed (diff against the verified working copy) ---------"
if cmp -s "$SAFE" "$SVC"; then
  echo "ABORTING: the file is unchanged. The patch matched nothing and the run below would be"
  echo "a green control that controlled nothing."
  exit 3
fi
diff -u "$SAFE" "$SVC" || true
echo

# --- 4. The fast leg: the in-suite guard, which prints the count in its failure message ----------

echo "=============================================================================="
echo " LEG 1 — ShoppingListIT, the guard that runs in the normal suite"
echo " Expect: theLedgerIsSummedOncePerPageLoad FAILS, expected size 1 but was 3."
echo "=============================================================================="
set +e
( cd "$ROOT/backend" && ./gradlew --no-daemon test --tests '*ShoppingListIT*' )
leg1=$?
set -e
echo "LEG 1 exit status: $leg1"
echo

# --- 5. The slow leg: the same count at five years of history, plus the pg_stat scan delta -------

echo "=============================================================================="
echo " LEG 2 — the T-139 harness at 146,150 stock movements"
echo " Expect: the assertion fails at 3L, and the report's statement table reads 3."
echo "=============================================================================="
set +e
( cd "$ROOT/backend" && KMS_PERF=1 ./gradlew --no-daemon --rerun-tasks test \
    --tests '*ShoppingListPerformanceIT*' )
leg2=$?
set -e
echo "LEG 2 exit status: $leg2"
echo

echo "--- the statement and scan counts, read out of the report the run just wrote ---"
if [ -f "$REPORT" ]; then
  sed -n '/What ONE page load actually asks the database for/,/EXPLAIN (ANALYZE/p' "$REPORT"
else
  echo "no report at $REPORT"
fi
echo

if [ "$leg1" -eq 0 ] || [ "$leg2" -eq 0 ]; then
  echo "!!! A LEG PASSED WITH THE FIX REMOVED. Read that as an alarm, not as reassurance:"
  echo "!!! it means the control did not control what it claims to."
  exit 4
fi

echo "Both legs red with the fix removed, which is what this control set out to show."
