#!/usr/bin/env bash
#
# T-088 negative control: put the pre-fix comparison back and watch the defect return.
#
# The fix makes the sufficiency walk run over the whole buying window regardless of the range it is
# asked about. The defect was that it walked only the range — and the day screen asks about one day,
# so every day saw the whole sack. This control restores `SufficiencyService` to HEAD (which is that
# single-range walk, and which still compiles: CommittedStockService's `claimsInHorizon()` is
# additive) and runs the tests EXACTLY AS WRITTEN.
#
# The five conditions from docs/work/README.md lesson 4:
#   1. set -e, so a step that matches nothing stops the script instead of producing a false green.
#   2. anchors are counted with grep -c and asserted, before and after, so a patch that applies to
#      nothing or to too much aborts.
#   3. the tree is shown to have actually changed (git diff --stat) before anything is run.
#   4. --rerun-tasks, so Gradle cannot answer from an up-to-date cache.
#   5. the restore is an EXIT trap, not a trailing line, and the restored file is compared
#      byte-for-byte with the verified copy.
# And the sixth, from wave 7's T-012: every artefact is named for this task, because the scratchpad
# is shared by every agent in this checkout.

set -euo pipefail

ROOT="/Users/Rajeev/Workspace/kitchen-management-system"
SVC="${ROOT}/backend/src/main/java/org/iskcon/kms/meal/SufficiencyService.java"
SAVED="$(mktemp -t control-T-088-SufficiencyService)"

cp "$SVC" "$SAVED"
restore() {
  cp "$SAVED" "$SVC"
  if cmp -s "$SAVED" "$SVC"; then
    echo "control-T-088: restored ${SVC} byte-for-byte from ${SAVED}"
  else
    echo "control-T-088: RESTORE FAILED — ${SVC} differs from ${SAVED}" >&2
    exit 99
  fi
}
trap restore EXIT

# --- condition 2, on the fixed file: the anchor that carries the fix, exactly once -------------
fixed_anchor=$(grep -c 'committedStock.claimsInHorizon()' "$SVC")
if [ "$fixed_anchor" -ne 1 ]; then
  echo "control-T-088: expected 1 claimsInHorizon() call in the fixed service, found ${fixed_anchor}" >&2
  exit 1
fi

# --- put the defect back ------------------------------------------------------------------------
git -C "$ROOT" show HEAD:backend/src/main/java/org/iskcon/kms/meal/SufficiencyService.java > "$SVC"

# --- condition 2, on the patched file: the pre-fix single-range walk, exactly once --------------
old_anchor=$(grep -c 'evaluate(loadPlannedMeals(from, to))' "$SVC")
if [ "$old_anchor" -ne 1 ]; then
  echo "control-T-088: expected 1 single-range evaluate() in the reverted service, found ${old_anchor}" >&2
  exit 1
fi
still_fixed=$(grep -c 'claimsInHorizon' "$SVC" || true)
if [ "$still_fixed" -ne 0 ]; then
  echo "control-T-088: the fix is still present in the reverted service (${still_fixed} sites)" >&2
  exit 1
fi

# --- condition 3: prove the tree actually changed ------------------------------------------------
#
# `git diff` is the WRONG instrument here and its silence would be read as a patch that matched
# nothing. The fix is uncommitted, so restoring HEAD makes the working tree *agree* with HEAD and
# git reports nothing at all. The change to prove is against the verified copy, so that is what is
# diffed — and an empty diff is an abort, not a shrug.
echo "=== anchors counted (condition 2) ==="
echo "fixed file:    committedStock.claimsInHorizon() call sites = ${fixed_anchor} (expected exactly 1)"
echo "patched file:  evaluate(loadPlannedMeals(from, to)) sites  = ${old_anchor} (expected exactly 1)"
echo "patched file:  any claimsInHorizon reference               = ${still_fixed} (expected exactly 0)"

echo "=== the tree actually changed: verified copy vs patched file (condition 3) ==="
DIFFOUT="$(mktemp -t control-T-088-diff)"
diff -u "$SAVED" "$SVC" > "$DIFFOUT" || true
if [ ! -s "$DIFFOUT" ]; then
  echo "control-T-088: the patch changed nothing — aborting rather than running a hollow control" >&2
  exit 1
fi
echo "$(grep -c '^+' "$DIFFOUT") added / $(grep -c '^-' "$DIFFOUT") removed lines; the head of it:"
head -25 "$DIFFOUT"
echo "(git itself now reports the file as unmodified, because the control put HEAD back and the fix"
echo " is uncommitted — which is why the diff above is against the verified copy and not against HEAD.)"

# --- condition 4: deny Gradle its up-to-date shortcut --------------------------------------------
echo "=== running SufficiencyIT against the pre-fix service, tests untouched ==="
cd "${ROOT}/backend"
./gradlew test --rerun-tasks --tests "*SufficiencyIT*" || echo "control-T-088: gradle exited non-zero (expected)"
