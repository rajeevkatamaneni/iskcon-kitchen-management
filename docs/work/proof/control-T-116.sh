#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# T-116 negative control.
#
# Patches the fix out of `frontend/lib/auth-context.tsx` — one edit, the one that
# keeps the server's words instead of throwing them away — runs the guard test,
# and restores the file through an EXIT trap that verifies the restore byte for
# byte. If the patch matches nothing it fails loudly rather than "controlling"
# an unmodified tree, which is how a control comes back BUILD SUCCESSFUL having
# proved nothing.
# ---------------------------------------------------------------------------
set -u

ROOT=/Users/Rajeev/Workspace/kitchen-management-system
TARGET="$ROOT/frontend/lib/auth-context.tsx"
BACKUP="$(mktemp -t control-T-116-auth-context)"
ANCHOR='known ? { code: error.code, message: error.message, action: error.action } : null'

cp "$TARGET" "$BACKUP"
BEFORE_SHA="$(shasum -a 256 "$TARGET" | awk '{print $1}')"
echo "=== target sha256 before: $BEFORE_SHA"

restore() {
  echo
  echo "=== EXIT trap: restoring $TARGET"
  cp "$BACKUP" "$TARGET"
  AFTER_SHA="$(shasum -a 256 "$TARGET" | awk '{print $1}')"
  echo "=== target sha256 after : $AFTER_SHA"
  if cmp -s "$BACKUP" "$TARGET"; then
    echo "=== cmp: restored file is byte-for-byte identical to the backup"
  else
    echo "!!! cmp: RESTORE FAILED — the working tree is not what it was"
    exit 99
  fi
  if [ "$BEFORE_SHA" = "$AFTER_SHA" ]; then
    echo "=== sha256 matches the pre-patch file"
  else
    echo "!!! sha256 DIFFERS from the pre-patch file"
    exit 99
  fi
  rm -f "$BACKUP"
  echo
  echo "=== git diff --stat for the target after restore. This is against HEAD, so it still"
  echo "=== shows T-116's own work — 79 insertions, not 77. The proof that the CONTROL's"
  echo "=== patch is gone is the cmp and the sha256 above, which compare against the file"
  echo "=== as it stood one second before the patch. (T-127 called out T-125 for using the"
  echo "=== HEAD diff as the restore proof; it is the wrong baseline and it is not used here.)"
  git -C "$ROOT" diff --stat -- frontend/lib/auth-context.tsx
  echo "=== (end)"
}
trap restore EXIT

# --- the anchor exists, exactly once ---------------------------------------
COUNT="$(grep -c -F "$ANCHOR" "$TARGET")"
echo "=== grep -c for the anchor: $COUNT (expected 1)"
if [ "$COUNT" != "1" ]; then
  echo "!!! anchor matched $COUNT times, not 1. Refusing to run a control that controls nothing."
  exit 1
fi

# --- patch the fix out ------------------------------------------------------
# The pre-T-116 behaviour exactly: the code is read for the routing and the
# message and next step are dropped on the floor.
python3 - "$TARGET" <<'PY'
import sys
path = sys.argv[1]
src = open(path).read()
old = """        setRefusal(
          known ? { code: error.code, message: error.message, action: error.action } : null
        );"""
new = """        setRefusal(null);"""
if old not in src:
    sys.exit("!!! patch target not found — the control would have controlled nothing")
open(path, "w").write(src.replace(old, new, 1))
print("=== patched: the refusal's words are discarded again")
PY
[ $? -eq 0 ] || exit 1

echo "=== grep -c for the anchor after patching: $(grep -c -F "$ANCHOR" "$TARGET") (expected 0)"
if cmp -s "$BACKUP" "$TARGET"; then
  echo "!!! the file is unchanged after patching. Aborting."
  exit 1
fi
echo "=== cmp: the file really changed"
echo
echo "=== git diff --stat, proving the tree changed"
git -C "$ROOT" diff --stat -- frontend/lib/auth-context.tsx
echo
echo "=== the patched hunk"
git -C "$ROOT" diff -U3 -- frontend/lib/auth-context.tsx | sed -n '1,40p'
echo

# --- the control still compiles, so any failure below is behavioural --------
echo "=== tsc --noEmit on the patched tree"
( cd "$ROOT/frontend" && npx tsc --noEmit )
echo "=== tsc exit: $? (0 means the control compiles; the guard fails on behaviour, not syntax)"
echo

echo "=== vitest, on the patched tree (failures expected)"
( cd "$ROOT/frontend" && npx vitest run __tests__/refusal-words-reach-the-reader.test.tsx )
echo "=== vitest exit: $?"
