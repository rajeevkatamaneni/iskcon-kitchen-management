#!/usr/bin/env bash
#
# Second half of T-087's negative control: the live defect reproduced on demand, quoting its code.
#
# The first control (control-T-087.log) put the refusal back and got three reds against the tests as
# written. Those reds read "Status expected:<201> but was:<409>", which is the refusal's HTTP status
# rather than its identity, and the brief asks to watch the recording fail with KMS-400042.
#
# So this run patches the refusal back AND restores MealPlanIT exactly as it stood at HEAD — the
# test that this task inverted, which asserted `$.code == "KMS-400042"` on the recording path. That
# is not "a test adjusted to make it fail": it is the test the repository had before today, run
# unaltered. With the refusal back it passes, which is the defect reproduced on demand and named.
#
# Both files are restored through one EXIT trap and diffed byte-for-byte.

set -euo pipefail

ROOT=/Users/Rajeev/Workspace/kitchen-management-system
SCRATCH=/private/tmp/claude-501/-Users-Rajeev-Workspace-kitchen-management-system/d8a15814-5daf-4b1d-ac53-4d3dcc17162c/scratchpad
SERVICE="$ROOT/backend/src/main/java/org/iskcon/kms/inventory/InventoryConsumptionService.java"
TEST="$ROOT/backend/src/test/java/org/iskcon/kms/meal/MealPlanIT.java"
PS="$SCRATCH/T-087-pristine-service.java"
PT="$SCRATCH/T-087-pristine-mealplanit.java"

ANCHOR='		Plan plan = computePlan(request.recipeId(), request.targetYield(), request.batchOverrides());'

cp "$SERVICE" "$PS"
cp "$TEST" "$PT"
restore() {
  cp "$PS" "$SERVICE"
  cp "$PT" "$TEST"
  ok=1
  diff -q "$PS" "$SERVICE" >/dev/null || ok=0
  diff -q "$PT" "$TEST"    >/dev/null || ok=0
  if [ "$ok" = 1 ]; then
    echo "CONTROL2: both files restored, byte-for-byte identical to the pre-patch copies"
  else
    echo "CONTROL2: *** RESTORE FAILED — the tree is left patched ***"
  fi
}
trap restore EXIT

count=$(grep -c -F "$ANCHOR" "$SERVICE")
echo "CONTROL2: service anchor matches $count time(s); 1 expected"
[ "$count" -eq 1 ] || { echo "CONTROL2: aborting on the anchor count"; exit 2; }

# The test file's anchor is the DECLARATION of the test this task replaced, not the bare name.
# The first attempt at this control anchored on the name and aborted on its own guard — the
# replacement test's javadoc names the old one, so the name still occurs once in the working tree.
# That is condition 5 doing exactly what it is for: an anchor that matches the wrong thing stops
# the script instead of producing a plausible red. The declaration occurs 0 times now and 1 time
# at HEAD, which is the fact being asserted.
now=$(grep -c "void recordingShortIsRefused() throws Exception {" "$TEST" || true)
echo "CONTROL2: 'recordingShortIsRefused' occurs $now time(s) in the working tree; 0 expected"
[ "$now" -eq 0 ] || { echo "CONTROL2: aborting — the working tree still carries the old test"; exit 2; }

s_before=$(shasum -a 256 "$SERVICE" | cut -d' ' -f1)
t_before=$(shasum -a 256 "$TEST" | cut -d' ' -f1)

python3 - "$SERVICE" <<'PY'
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
assert s.count(anchor) == 1
open(path, "w").write(s.replace(anchor, refusal))
PY

git -C "$ROOT" show HEAD:backend/src/test/java/org/iskcon/kms/meal/MealPlanIT.java > "$TEST"

s_after=$(shasum -a 256 "$SERVICE" | cut -d' ' -f1)
t_after=$(shasum -a 256 "$TEST" | cut -d' ' -f1)
echo "CONTROL2: service sha256 $s_before -> $s_after"
echo "CONTROL2: test    sha256 $t_before -> $t_after"
[ "$s_before" != "$s_after" ] || { echo "CONTROL2: aborting — service unchanged"; exit 3; }
[ "$t_before" != "$t_after" ] || { echo "CONTROL2: aborting — test unchanged"; exit 3; }

back=$(grep -c "void recordingShortIsRefused() throws Exception {" "$TEST")
echo "CONTROL2: HEAD's test is back, 'recordingShortIsRefused' occurs $back time(s)"
grep -n 'KMS-400042' "$TEST"

cd "$ROOT/backend"
./gradlew test --rerun-tasks --tests "*MealPlanIT.recordingShortIsRefused*" 2>&1 || true
