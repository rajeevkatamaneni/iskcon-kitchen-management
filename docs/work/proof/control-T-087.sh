#!/usr/bin/env bash
#
# Negative control for T-087 — put the refusal back and watch the recording fail with KMS-400042.
#
# Conditions from docs/work/README.md lesson 4, all five:
#   1. set -e, so a patch step that matches nothing stops the script instead of producing a green
#      control that controlled nothing.
#   2. grep -c the anchor and assert the COUNT, not the presence — an anchor that matches twice
#      would strip somebody else's fix from the same file and produce a plausible false red.
#   3. prove the tree actually changed. sha256 before and after, not `git diff --stat`: this task
#      also adds a NEW file (V115), and a stat line says nothing about one of those.
#   4. --rerun-tasks, so Gradle cannot answer from an up-to-date cache.
#   5. restore through an EXIT trap, then diff byte-for-byte against a copy taken before the patch.
#
# And the fourth condition from wave 7: the log is named for THIS task, because the scratchpad is
# shared by every agent in this checkout and control.log has been read as somebody else's evidence
# before now.

set -euo pipefail

ROOT=/Users/Rajeev/Workspace/kitchen-management-system
SCRATCH=/private/tmp/claude-501/-Users-Rajeev-Workspace-kitchen-management-system/d8a15814-5daf-4b1d-ac53-4d3dcc17162c/scratchpad
TARGET="$ROOT/backend/src/main/java/org/iskcon/kms/inventory/InventoryConsumptionService.java"
PRISTINE="$SCRATCH/T-087-pristine-InventoryConsumptionService.java"
LOG="$SCRATCH/control-T-087.log"

ANCHOR='		Plan plan = computePlan(request.recipeId(), request.targetYield(), request.batchOverrides());'

cp "$TARGET" "$PRISTINE"
restore() {
  cp "$PRISTINE" "$TARGET"
  if diff -q "$PRISTINE" "$TARGET" >/dev/null; then
    echo "CONTROL: restored, byte-for-byte identical to the pre-patch copy"
  else
    echo "CONTROL: *** RESTORE FAILED — the tree is left patched ***"
  fi
}
trap restore EXIT

# --- condition 2: the anchor must match exactly once -------------------------
count=$(grep -c -F "$ANCHOR" "$TARGET")
echo "CONTROL: anchor matches $count time(s); 1 expected"
if [ "$count" -ne 1 ]; then
  echo "CONTROL: aborting — the anchor does not identify exactly the one site to patch"
  exit 2
fi

before=$(shasum -a 256 "$TARGET" | cut -d' ' -f1)

# --- patch the refusal back in ----------------------------------------------
# Fully-qualified names on purpose: the fix removed the ApplicationException and ErrorCode imports,
# and a control that also has to patch an import block is a control with two anchors to get wrong.
python3 - "$TARGET" <<'PY'
import sys
path = sys.argv[1]
s = open(path).read()
anchor = "\t\tPlan plan = computePlan(request.recipeId(), request.targetYield(), request.batchOverrides());"
refusal = anchor + """
\t\tif (!plan.sufficient()) {
\t\t\tthrow new org.iskcon.kms.error.ApplicationException(
\t\t\t\t\torg.iskcon.kms.error.ErrorCode.INSUFFICIENT_STOCK, Map.of(
\t\t\t\t\t\t\t"recipeId", request.recipeId()));
\t\t}"""
assert s.count(anchor) == 1, "anchor count changed under us"
open(path, "w").write(s.replace(anchor, refusal))
PY

after=$(shasum -a 256 "$TARGET" | cut -d' ' -f1)
echo "CONTROL: sha256 before $before"
echo "CONTROL: sha256 after  $after"
if [ "$before" = "$after" ]; then
  echo "CONTROL: aborting — the file did not change, so nothing would be under test"
  exit 3
fi
echo "CONTROL: the refusal is back in the tree:"
grep -n -A 4 -F "$ANCHOR" "$TARGET"

# --- condition 4: deny Gradle its up-to-date shortcut ------------------------
cd "$ROOT/backend"
./gradlew test --rerun-tasks \
  --tests "*InventoryConsumptionIT*" \
  --tests "*MealPlanIT*" \
  --tests "*MealCorrectionIT*" 2>&1 || true
