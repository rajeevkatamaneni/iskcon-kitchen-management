#!/usr/bin/env bash
#
# Negative control for T-108, T-143 and T-077.
#
# Each arm takes one line of the fix back out and shows the tests going red. A green suite proves
# nothing on its own: it is only evidence if it would have been red without the change.
#
# Every anchor is counted rather than merely found (docs/work/README.md, Rajeev's own condition), so
# a patch that silently matches nothing — or matches twice — stops the run instead of producing a
# comfortable log. Restoration is from a `cp` snapshot and never `git checkout --`: three other
# builders are live in this checkout and a checkout would take their work with it.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
FE="${ROOT}/frontend"
SNAP="$(mktemp -d)"
trap 'restore' EXIT

FILES=(
  "lib/format.ts"
  "components/planner/MealServices.tsx"
  "components/EquipmentForm.tsx"
  "app/equipment/new/page.tsx"
  "components/LanguageSection.tsx"
)

snapshot() {
  for f in "${FILES[@]}"; do
    mkdir -p "${SNAP}/$(dirname "$f")"
    cp "${FE}/${f}" "${SNAP}/${f}"
  done
}

restore() {
  for f in "${FILES[@]}"; do
    [ -f "${SNAP}/${f}" ] && cp "${SNAP}/${f}" "${FE}/${f}"
  done
  echo "--- restored from the snapshot ---"
}

# grep -c on a fixed string, asserted against the count we expect to be there.
anchor() {
  local file="$1" needle="$2" want="$3" got
  got="$(grep -cF -- "$needle" "${FE}/${file}" || true)"
  if [ "$got" != "$want" ]; then
    echo "ANCHOR FAILED: [$needle] x$got in $file, expected x$want" >&2
    exit 1
  fi
  echo "anchor OK: [$needle] x$want in $(basename "$file")"
}

patch() {  # file  from  to
  python3 - "$1" "$2" "$3" <<'PY'
import sys
path, a, b = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path).read()
n = s.count(a)
assert n == 1, f"patch text found {n} times in {path}, expected once"
open(path, "w").write(s.replace(a, b))
PY
}

# Fails the run when the tests it is handed pass.
must_fail() {
  local what="$1"; shift
  echo
  echo "=== $what — expecting RED ==="
  if (cd "${FE}" && npx vitest run "$@" 2>&1 | tail -30); then
    echo "CONTROL FAILED: $what passed with the fix removed" >&2
    exit 1
  fi
  echo "=== $what was red, as it must be ==="
}

snapshot

# ---------------------------------------------------------------- T-108, arm 1
# The shared formatter picks its unit label without ever seeing the number, which is the whole
# defect: one plastic stool reads "1 pieces".
anchor "lib/format.ts" 'unitLabelFor(value, unit)}`;' 1
anchor "lib/format.ts" 'const UNIT_SINGULAR: Record<string, string> = {' 1
patch "${FE}/lib/format.ts" \
  'maximumFractionDigits: maxDecimals })} ${unitLabelFor(value, unit)}`;' \
  'maximumFractionDigits: maxDecimals })} ${UNIT_LABEL[unit] ?? unit}`;'

# ---------------------------------------------------------------- T-108, arm 2
# The guard that stops the next screen building the phrase by hand. It reads source text, so the
# arm puts the hand-built phrase back.
anchor "components/planner/MealServices.tsx" 'unitLabelFor(entry.planned, unit(entry.mealPlanId))' 1
patch "${FE}/components/planner/MealServices.tsx" \
  '{entry.planned.toLocaleString("en-IN")} {unitLabelFor(entry.planned, unit(entry.mealPlanId))}' \
  '{entry.planned.toLocaleString("en-IN")} {unitLabel(unit(entry.mealPlanId))}'

# ---------------------------------------------------------------- T-143, arm 1
# The tick is collected by the form and then dropped on the way out, because the screen only asks
# for a schedule when there is an interval or a company. That is T-090's defect exactly.
anchor "app/equipment/new/page.tsx" 'input.neverNeedsServicing ||' 1
anchor "app/equipment/new/page.tsx" 'neverNeedsServicing: input.neverNeedsServicing,' 1
patch "${FE}/app/equipment/new/page.tsx" \
  '        (input.intervalCount != null ||
          input.neverNeedsServicing ||' \
  '        (input.intervalCount != null ||'

# ---------------------------------------------------------------- T-143, arm 2
# "When checked, the Service interval box is cleared out and uneditable."
anchor "components/EquipmentForm.tsx" 'if (ticked) setIntervalCount("");' 1
anchor "components/EquipmentForm.tsx" 'disabled={never}' 2
patch "${FE}/components/EquipmentForm.tsx" \
  '    setNever(ticked);
    if (ticked) setIntervalCount("");' \
  '    setNever(ticked);'

# ---------------------------------------------------------------- T-077
# Back to seeding the state from the prop: read once on the first render, never again.
anchor "components/LanguageSection.tsx" 'const [picked, setPicked] = useState<string | null>(null);' 1
anchor "components/LanguageSection.tsx" 'const language = picked ?? (initial ?? "en-IN").split("-")[0];' 1
patch "${FE}/components/LanguageSection.tsx" \
  '  const [picked, setPicked] = useState<string | null>(null);
  // Stored region-qualified ("kn-IN"); chosen as a bare language, which is what a person picks.
  const language = picked ?? (initial ?? "en-IN").split("-")[0];' \
  '  const [language, setPicked] = useState((initial ?? "en-IN").split("-")[0]);'

echo
echo "=== the tree really did change ==="
(cd "${ROOT}" && git diff --stat -- \
  frontend/lib/format.ts \
  frontend/components/planner/MealServices.tsx \
  frontend/components/EquipmentForm.tsx \
  frontend/app/equipment/new/page.tsx)
echo "(components/LanguageSection.tsx is new and untracked, so it has no diff line; shown instead:)"
grep -nF 'const [language, setPicked] = useState(' "${FE}/components/LanguageSection.tsx"

must_fail "T-108 — the shared quantity formatter"      __tests__/quantities.test.ts
must_fail "T-108 — the hand-built phrase guard"        __tests__/design-system.test.ts
must_fail "T-108 — the purchase order screen"          __tests__/described-po-line.test.tsx
must_fail "T-143 — registering something never serviced" __tests__/equipment-new.test.tsx
must_fail "T-077 — the language picker"                __tests__/settings-language.test.tsx

echo
echo "ALL ARMS WERE RED. The control is evidence."
