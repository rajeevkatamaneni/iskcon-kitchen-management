#!/usr/bin/env bash
#
# T-114 negative control: patch the fix out, watch the new tests fail, restore through a trap.
#
# Five conditions from docs/work/README.md lesson 4:
#   set -e                                   — a patch that matches nothing stops the script
#   grep -c the anchor and assert the count  — an anchor that matches twice is a false red
#   git diff --stat                          — proof the tree actually changed before anything ran
#   restore through an EXIT trap, then diff  — the tree is shared; a dead control must not break it
#   named control-T-114.log                  — the scratchpad is shared by every agent here
set -euo pipefail

ROOT=/Users/Rajeev/Workspace/kitchen-management-system
SCRATCH=/private/tmp/claude-501/-Users-Rajeev-Workspace-kitchen-management-system/d8a15814-5daf-4b1d-ac53-4d3dcc17162c/scratchpad
FILE="$ROOT/frontend/app/register/page.tsx"
VERIFIED="$SCRATCH/control-T-114.page.verified.tsx"

echo "=== T-114 negative control — $(date) ==="
echo "file under control: $FILE"

cp "$FILE" "$VERIFIED"
restore() {
  cp "$VERIFIED" "$FILE"
  if diff -q "$VERIFIED" "$FILE" >/dev/null; then
    echo "RESTORED: register/page.tsx is byte-for-byte the verified copy again"
  else
    echo "RESTORE FAILED — register/page.tsx differs from the verified copy"
  fi
}
trap restore EXIT

# --- the anchor, counted rather than merely present -------------------------
anchor='signing in will ask which temple you serve at'
n=$(grep -c "$anchor" "$FILE" || true)
echo "anchor occurrences in the file: $n"
[ "$n" = "1" ] || { echo "ABORT: expected 1 occurrence of the anchor, found $n"; exit 9; }

# --- patch the fix out ------------------------------------------------------
python3 - "$FILE" <<'PY'
import sys
path = sys.argv[1]
src = open(path, encoding="utf-8").read()
new = '''function emailAlreadyInUse(templeName: string | null): string {
  void templeName;
  return "There is already an account with that email. Sign in instead.";
}'''
old = '''function emailAlreadyInUse(templeName: string | null): string {
  const list = templeName ? `${templeName}’s list` : "the temple’s list";
  return (
    "There is already an account with that email. Sign in instead. " +
    `If you aren’t on ${list} yet, signing in will ask which temple you serve at, ` +
    "and you can join from there without registering again."
  );
}'''
count = src.count(old)
print(f"function body matched {count} time(s)")
if count != 1:
    sys.exit(9)
open(path, "w", encoding="utf-8").write(src.replace(old, new))
print("patched: the sentence is the pre-T-114 one again")
PY

# --- prove the tree changed before believing any result ---------------------
echo "--- git diff --stat (proof the patch applied) ---"
git -C "$ROOT" diff --stat -- frontend/app/register/page.tsx
changed=$(git -C "$ROOT" diff --numstat -- frontend/app/register/page.tsx | wc -l | tr -d ' ')
[ "$changed" = "1" ] || { echo "ABORT: the file does not show as changed"; exit 9; }
grep -c "$anchor" "$FILE" || echo "anchor is gone, as intended"

# --- run the tests as written, unaltered ------------------------------------
set +e
(cd "$ROOT/frontend" && npx vitest run __tests__/register.test.tsx 2>&1)
code=$?
set -e
echo "=== vitest exit code with the fix removed: $code ==="
[ "$code" = "0" ] && echo "CONTROL FAILED: the suite was green without the fix"
exit 0
