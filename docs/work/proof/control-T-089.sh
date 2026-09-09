#!/usr/bin/env bash
#
# Negative control for T-089, all five conditions from docs/work/README.md lesson 4.
#
#   1. set -e, so a patch that matches nothing stops the script instead of producing a false green.
#   2. grep -c the anchor and assert the COUNT, not the presence — one site each, never two.
#   3. prove the tree actually changed. `git diff --stat` is NOT usable here: app/supplies/page.tsx
#      is a NEW file and untracked, so git reports nothing about it however hard it is patched.
#      sha256 before and after is the proof that works on tracked and untracked files alike.
#      (`--rerun-tasks` is Gradle's; vitest keeps no result cache, so the sha pair is what denies
#      this run an up-to-date shortcut. This control touches no Gradle build.)
#   4. restore through an EXIT trap and diff byte-for-byte, so a death mid-control cannot leave the
#      tree broken for the other two builders in this wave.
#   5. named for the task — control-T-089.log — because the scratchpad is shared by every agent in
#      this checkout and two builders both writing control.log is how one nearly pasted the other's
#      failures into its own proof.
#
# What is patched out: the two filters that ARE the feature. The whole of T-089 on the client is
# that /ingredients takes the food half of one catalogue and /supplies takes the other half. Strip
# both filters and each screen shows everything, which is precisely the state the four split tests
# exist to forbid.
#
# The tests are run exactly as written. Nothing is adjusted to make it fail.

set -euo pipefail

ROOT=/Users/Rajeev/Workspace/kitchen-management-system
FE="$ROOT/frontend"
WORK="$(cd "$(dirname "$0")" && pwd)"

SUPPLIES="$FE/app/supplies/page.tsx"
INGREDIENTS="$FE/app/ingredients/page.tsx"

SUPPLIES_ANCHOR='const supplies = (data ?? []).filter((i) => i.supply);'
INGREDIENTS_ANCHOR='const food = ingredients.filter((i) => !i.supply);'

sha() { shasum -a 256 "$1" | cut -d' ' -f1; }

cp "$SUPPLIES"    "$WORK/supplies.page.tsx.verified"
cp "$INGREDIENTS" "$WORK/ingredients.page.tsx.verified"

restore() {
  cp "$WORK/supplies.page.tsx.verified"    "$SUPPLIES"
  cp "$WORK/ingredients.page.tsx.verified" "$INGREDIENTS"
  echo "--- restored; byte-for-byte diff against the verified copies ---"
  cmp "$SUPPLIES"    "$WORK/supplies.page.tsx.verified"    && echo "app/supplies/page.tsx: identical"
  cmp "$INGREDIENTS" "$WORK/ingredients.page.tsx.verified" && echo "app/ingredients/page.tsx: identical"
}
trap restore EXIT

# --- condition 2: the anchor matches exactly once, in each file -----------------------------
for pair in "$SUPPLIES|$SUPPLIES_ANCHOR" "$INGREDIENTS|$INGREDIENTS_ANCHOR"; do
  file="${pair%%|*}"; anchor="${pair#*|}"
  n=$(grep -cF "$anchor" "$file" || true)
  echo "anchor count in $(basename "$(dirname "$file")")/page.tsx: $n"
  if [ "$n" -ne 1 ]; then
    echo "ABORT: expected exactly 1 occurrence of the anchor in $file, found $n" >&2
    exit 1
  fi
done

# --- condition 3: patch, and prove the bytes moved ------------------------------------------
before_s=$(sha "$SUPPLIES"); before_i=$(sha "$INGREDIENTS")

perl -0pi -e "s/\Qconst supplies = (data ?? []).filter((i) => i.supply);\E/const supplies = (data ?? []);/" "$SUPPLIES"
perl -0pi -e "s/\Qconst food = ingredients.filter((i) => !i.supply);\E/const food = ingredients;/" "$INGREDIENTS"

after_s=$(sha "$SUPPLIES"); after_i=$(sha "$INGREDIENTS")
echo "supplies    sha256 $before_s -> $after_s"
echo "ingredients sha256 $before_i -> $after_i"
[ "$before_s" != "$after_s" ] || { echo "ABORT: app/supplies/page.tsx unchanged by the patch" >&2; exit 1; }
[ "$before_i" != "$after_i" ] || { echo "ABORT: app/ingredients/page.tsx unchanged by the patch" >&2; exit 1; }

echo "--- the patched lines, read back off disk ---"
grep -n "const supplies = " "$SUPPLIES"
grep -n "const food = "     "$INGREDIENTS"

# --- the run itself, tests exactly as written -----------------------------------------------
cd "$FE"
set +e
npx vitest run __tests__/supplies.test.tsx __tests__/ingredients.test.tsx
echo "vitest exit: $?"
set -e
