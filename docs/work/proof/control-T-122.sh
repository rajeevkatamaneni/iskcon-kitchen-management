#!/usr/bin/env bash
#
# Negative control for T-122 — run INSIDE the verify lock.
#
#   tools/work-lock.sh run verify 'bash docs/work/proof/control-T-122.sh'
#
# Two controls, because the task has two mechanisms and only one of them is where a reader would
# look:
#
#   CONTROL 1 — put the subtraction back. V116's function is what makes a USED_BEYOND_RECORDED_STOCK
#              row count as zero on hand; flip its 0 to a 1 and the shortfall subtracts again, which
#              is the live defect. Watch on hand go to minus forty kilos.
#
#   CONTROL 2 — the brief's "why", tested as a claim of its own (README lesson 4). It says a row that
#              moves no stock must be retracted in kind, or a correction turns the discrepancy into a
#              plus-forty-kilos adjustment. Put the old always-ADJUSTMENT reversal back and watch a
#              corrected meal finish holding rice nobody delivered.
#
# The five conditions: set -e; grep -c every anchor and assert the count; prove the tree changed
# against this script's own saved copies rather than against HEAD; --rerun-tasks so Gradle cannot
# hand back an up-to-date shortcut; restore through an EXIT trap and diff byte-for-byte; and named
# for this task, because the scratchpad is shared by every agent in this checkout.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"

MIGRATION="backend/src/main/resources/db/migration/V116__on_hand_stops_at_zero.sql"
SERVICE="backend/src/main/java/org/iskcon/kms/inventory/StockMovementService.java"

SAFE="$(mktemp -d)"
cp "$MIGRATION" "$SAFE/migration.orig"
cp "$SERVICE"   "$SAFE/service.orig"

restore() {
  cp "$SAFE/migration.orig" "$MIGRATION"
  cp "$SAFE/service.orig"   "$SERVICE"
  if diff -q "$SAFE/migration.orig" "$MIGRATION" >/dev/null \
     && diff -q "$SAFE/service.orig" "$SERVICE" >/dev/null; then
    echo "CONTROL: restored, byte-for-byte identical to the pre-patch copies"
  else
    echo "CONTROL: !!! RESTORE FAILED — the tree is not as it was. Fix before anything else." >&2
  fi
}
trap restore EXIT

assert_count() {   # assert_count <file> <anchor> <expected>
  local file="$1" anchor="$2" want="$3" got
  got="$(grep -cF "$anchor" "$file" || true)"
  echo "CONTROL: anchor [$anchor] matches ${got} time(s) in $(basename "$file"); ${want} expected"
  if [ "$got" != "$want" ]; then
    echo "CONTROL: aborting — the anchor does not match what this control was written against." >&2
    exit 2
  fi
}

echo "=================================================================="
echo "CONTROL 1 — the shortfall subtracts again"
echo "=================================================================="

A1="WHEN movement_type = 'USED_BEYOND_RECORDED_STOCK' THEN 0"
assert_count "$MIGRATION" "$A1" 1
sha_before="$(shasum -a 256 "$MIGRATION" | cut -d' ' -f1)"
perl -0pi -e "s/\Q$A1\E/WHEN movement_type = 'USED_BEYOND_RECORDED_STOCK' THEN 1/" "$MIGRATION"
sha_after="$(shasum -a 256 "$MIGRATION" | cut -d' ' -f1)"
echo "CONTROL1: sha256 before $sha_before"
echo "CONTROL1: sha256 after  $sha_after"
[ "$sha_before" != "$sha_after" ] || { echo "CONTROL1: the patch changed nothing" >&2; exit 2; }
echo "CONTROL1: diff against my own verified copy (not against HEAD):"
diff -u "$SAFE/migration.orig" "$MIGRATION" | sed -n '1,12p' || true

set +e
(cd backend && ./gradlew test --rerun-tasks --console=plain \
    --tests "*MealPlanIT*" --tests "*InventoryConsumptionIT*" \
    --tests "*InventoryStockIT*" --tests "*MealCorrectionIT*" --tests "*StockMovementLedgerIT*")
echo "CONTROL1: gradle exit $?"
set -e

cp "$SAFE/migration.orig" "$MIGRATION"
echo "CONTROL1: migration put back"

echo "=================================================================="
echo "CONTROL 2 — a discrepancy reversed as an adjustment"
echo "=================================================================="

A2="movesStock ? MovementType.ADJUSTMENT : original.type()"
A3="movesStock ? AdjustmentReason.COUNT_CORRECTION : null"
assert_count "$SERVICE" "$A2" 1
assert_count "$SERVICE" "$A3" 1
sha_before="$(shasum -a 256 "$SERVICE" | cut -d' ' -f1)"
perl -0pi -e "s/\Q$A2\E/MovementType.ADJUSTMENT/" "$SERVICE"
perl -0pi -e "s/\Q$A3\E/AdjustmentReason.COUNT_CORRECTION/" "$SERVICE"
sha_after="$(shasum -a 256 "$SERVICE" | cut -d' ' -f1)"
echo "CONTROL2: sha256 before $sha_before"
echo "CONTROL2: sha256 after  $sha_after"
[ "$sha_before" != "$sha_after" ] || { echo "CONTROL2: the patch changed nothing" >&2; exit 2; }
echo "CONTROL2: the old always-ADJUSTMENT reversal is back in the tree:"
grep -n "MovementType.ADJUSTMENT," "$SERVICE" | head -3

set +e
(cd backend && ./gradlew test --rerun-tasks --console=plain \
    --tests "*StockMovementLedgerIT*" --tests "*MealCorrectionIT*")
echo "CONTROL2: gradle exit $?"
set -e

echo "CONTROL: both controls done; the EXIT trap restores both files now."
