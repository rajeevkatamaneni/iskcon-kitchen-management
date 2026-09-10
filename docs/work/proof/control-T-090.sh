#!/bin/bash
# =====================================================================
# T-090 — negative control.
#
# Two mechanisms are claimed by this task, and a passing test proves neither of them on its own: a
# test written after a fix, against the fixed code, passes whether or not it exercises the defect.
# So each claim is broken deliberately and the suite is watched to react.
#
#   A. "An unrecorded lead time is unknown, not zero."  LeadTimes.effectiveDays falls back to the
#      two-day assumption. Patched to fall back to 0 — the exact mistake the brief names — the badge
#      and the shopping list should start saying there is still time for orders that are already
#      too late.
#
#   B. "The third state is a different sentence, not a darker red."  The TOO_LATE branch on both
#      screens is patched to render the ORDER_TODAY words instead. Nothing about the colour changes;
#      if the tests still pass, the third state was decoration.
#
# The five conditions this project's protocol asks for, and where each is:
#   1. set -e / set -o pipefail                          — immediately below
#   2. grep -c the anchor and assert the count           — assert_count(), before every patch
#   3. prove the tree actually changed                   — diff against the verified copy, and abort
#                                                          on an empty diff. Baselined against a COPY
#                                                          of the working tree and never against HEAD:
#                                                          the fix here is uncommitted, so restoring
#                                                          HEAD makes `git diff HEAD` silent exactly
#                                                          when the control is working.
#   4. --rerun-tasks                                     — on the gradle line, so an incremental
#                                                          build cannot answer UP-TO-DATE
#   5. restore through an EXIT trap, then cmp            — restore(), armed before the first edit
#
# And the artefact is named for the task (docs/work/proof/T-090-control.log, never control.log):
# the scratchpad is shared by every agent in this checkout, and two builders in one wave have
# already nearly pasted each other's evidence into their proofs.
# =====================================================================
set -euo pipefail

ROOT=/Users/Rajeev/Workspace/kitchen-management-system
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

LEAD_TIMES=$ROOT/backend/src/main/java/org/iskcon/kms/vendor/LeadTimes.java
MEAL_SERVICES=$ROOT/frontend/components/planner/MealServices.tsx
SHOPPING_PAGE=$ROOT/frontend/app/shopping-list/page.tsx

WORK=$(mktemp -d "${TMPDIR:-/tmp}/control-T-090.XXXXXX")
cp "$LEAD_TIMES"     "$WORK/LeadTimes.java"
cp "$MEAL_SERVICES"  "$WORK/MealServices.tsx"
cp "$SHOPPING_PAGE"  "$WORK/shopping-list-page.tsx"

restore() {
  cp "$WORK/LeadTimes.java"          "$LEAD_TIMES"
  cp "$WORK/MealServices.tsx"        "$MEAL_SERVICES"
  cp "$WORK/shopping-list-page.tsx"  "$SHOPPING_PAGE"
  for pair in "$WORK/LeadTimes.java:$LEAD_TIMES" \
              "$WORK/MealServices.tsx:$MEAL_SERVICES" \
              "$WORK/shopping-list-page.tsx:$SHOPPING_PAGE"; do
    src=${pair%%:*}; dst=${pair##*:}
    if cmp -s "$src" "$dst"; then
      echo "control-T-090: restored $dst byte-for-byte from $src"
    else
      echo "control-T-090: RESTORE FAILED for $dst — the tree is left broken, fix it by hand" >&2
      exit 99
    fi
  done
}
trap restore EXIT

assert_count() { # file, literal, expected
  local n
  n=$(grep -c -F -- "$2" "$1" || true)
  if [ "$n" != "$3" ]; then
    echo "control-T-090: expected $3 occurrence(s) of [$2] in $1, found $n — ABORTING" >&2
    exit 2
  fi
  echo "  $1: [$2] x$n (expected $3)"
}

patch_once() { # file, literal-old, literal-new
  python3 - "$@" <<'PY'
import sys
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path, encoding="utf-8").read()
if s.count(old) != 1:
    sys.exit(f"patch matched {s.count(old)} times in {path}")
open(path, "w", encoding="utf-8").write(s.replace(old, new))
PY
}

echo "=== anchors counted before any edit (condition 2) ==="
assert_count "$LEAD_TIMES"     'return recorded == null ? ASSUMED_LEAD_TIME_DAYS : recorded;' 1
assert_count "$MEAL_SERVICES"  'return <Badge tone="danger">Short · won’t arrive in time</Badge>;' 1
assert_count "$SHOPPING_PAGE"  '<Badge tone="danger">Won’t arrive in time</Badge>' 1

echo
echo "=== A: an unrecorded lead time is scored as zero ==="
patch_once "$LEAD_TIMES" \
  'return recorded == null ? ASSUMED_LEAD_TIME_DAYS : recorded;' \
  'return recorded == null ? 0 : recorded;'

echo "=== B: the third state says what the second one says ==="
patch_once "$MEAL_SERVICES" \
  'return <Badge tone="danger">Short · won’t arrive in time</Badge>;' \
  'return <Badge tone="danger">Short · order today</Badge>;'
patch_once "$SHOPPING_PAGE" \
  '<Badge tone="danger">Won’t arrive in time</Badge>' \
  '<Badge tone="danger">Order today</Badge>'

echo
echo "=== the tree actually changed: verified copy vs patched file (condition 3) ==="
CHANGED=0
for pair in "$WORK/LeadTimes.java:$LEAD_TIMES" \
            "$WORK/MealServices.tsx:$MEAL_SERVICES" \
            "$WORK/shopping-list-page.tsx:$SHOPPING_PAGE"; do
  src=${pair%%:*}; dst=${pair##*:}
  if diff -u "$src" "$dst"; then
    echo "control-T-090: $dst is UNCHANGED after patching — ABORTING" >&2
    exit 3
  fi
  CHANGED=$((CHANGED + 1))
done
echo "control-T-090: $CHANGED of 3 files differ from the verified copy"

echo
echo "=== the backend suite, with the tests exactly as written (condition 4) ==="
set +e
(cd "$ROOT/backend" && ./gradlew --no-daemon test --rerun-tasks \
    --tests "*SufficiencyIT*" --tests "*ShoppingListIT*")
BACKEND=$?
set -e
echo "control-T-090: backend exit=$BACKEND"

echo
echo "=== the frontend suite, with the tests exactly as written ==="
set +e
(cd "$ROOT/frontend" && npx vitest run \
    __tests__/planner-day-routes.test.tsx __tests__/shopping-list.test.tsx)
FRONTEND=$?
set -e
echo "control-T-090: frontend exit=$FRONTEND"

echo
if [ "$BACKEND" -eq 0 ] && [ "$FRONTEND" -eq 0 ]; then
  echo "control-T-090: BOTH SUITES WENT GREEN WITH THE FIX PATCHED OUT — the tests prove nothing." >&2
  exit 4
fi
echo "control-T-090: the defects reproduced on demand (backend=$BACKEND, frontend=$FRONTEND)"
