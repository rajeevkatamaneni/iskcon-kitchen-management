#!/usr/bin/env bash
#
# T-131 — the negative control.
#
# Four claims are broken, one stage at a time, each restored before the next begins.
#
#   Stage 1 (the reported defect, on demand). The vendor screen is put back to the version on
#           HEAD — the one deployed to staging — with the new tests left exactly as written. That
#           is the bug reproduced: a supply row offers only Remove, so the lead time T-090 shipped
#           cannot be reached on any supply the vendor already has.
#
#   Stage 2 (the two null/zero guards). `numberOrNull`'s blank test is removed so the box is
#           coerced the ordinary way — `Number("")` is 0 — and `boxValue` is rewritten with the
#           falsy test that reads a real 0 as "nothing recorded". Both compile, both look right,
#           and each collapses one half of the unknown-versus-same-day distinction.
#
#   Stage 3 (the upsert). The conflict target moves from `(vendor_id, ingredient_id)` to `(id)`,
#           which never matches because the id is generated per statement. The row is inserted a
#           second time instead of updated, and the unique index refuses it — the endpoint stops
#           being an edit and becomes a duplicate-key error.
#
#   Stage 4 (the preference the server moves). The statement that clears another vendor's
#           preference is switched off, so ticking Preferred on an existing row collides with
#           `vendor_supplies_one_preferred` instead of moving the preference. This is the index the
#           brief asked to be careful of, and this is the proof that the care lives on the server.
#
# The five conditions:
#   1. `set -euo pipefail`, so a patch step that fails stops the script instead of being read as a
#      control that passed.
#   2. every anchor is counted as an exact fixed string and asserted to equal the number of sites
#      meant to change; the same literal does the counting and the replacing, so an anchor that
#      matched twice cannot silently strip somebody else's work in the same file.
#   3. the tree is proved to have actually changed, diffed against a copy of the VERIFIED WORKING
#      TREE rather than against HEAD — none of T-131 is committed, so `git diff HEAD` would go
#      quiet at exactly the moment the control was working.
#   4. `--rerun-tasks` on every gradle leg, so an incremental build cannot answer UP-TO-DATE, and
#      no `tail` anywhere on it, so nothing about the run is hidden by a pipe.
#   5. every file is restored through an EXIT trap and then compared byte-for-byte with `cmp -s`.
#
# Artefacts are named for the task (`control-T-131.*`): the scratchpad is shared by every agent in
# this checkout, and two builders writing `control.log` a minute apart is how one of them nearly
# pasted the other's failures into its own proof.

set -euo pipefail

ROOT="/Users/Rajeev/Workspace/kitchen-management-system"
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

PAGE="$ROOT/frontend/app/vendors/[id]/page.tsx"
SERVICE="$ROOT/backend/src/main/java/org/iskcon/kms/vendor/VendorService.java"

SAFE="$(mktemp -d "${TMPDIR:-/tmp}/control-T-131.XXXXXX")"
cp "$PAGE" "$SAFE/page.tsx"
cp "$SERVICE" "$SAFE/VendorService.java"

restore() {
	local rc=$?
	cp "$SAFE/page.tsx" "$PAGE"
	cp "$SAFE/VendorService.java" "$SERVICE"
	for pair in "page.tsx|$PAGE" "VendorService.java|$SERVICE"; do
		local name="${pair%%|*}" path="${pair#*|}"
		if cmp -s "$SAFE/$name" "$path"; then
			echo "control-T-131: restored $path byte-for-byte from $SAFE/$name"
		else
			echo "control-T-131: !!! $path DID NOT RESTORE — recover it from $SAFE/$name"
		fi
	done
	echo "control-T-131: exit $rc"
}
trap restore EXIT

# Conditions 2 and 3 in one step, and deliberately so: the literal that is counted is the literal
# that is replaced, so there is no way for the check and the edit to be looking at different text.
# Aborts loudly if the count is anything other than the number of sites named.
#
# `<<'PY'` and not `<<-'PY'`, which is how this went wrong the first time it was run: the dash form
# strips EVERY leading tab, not one level of them, so the body arrived at python with the `if`'s
# block flattened against it and the whole thing died on an IndentationError. `set -e` stopped the
# script there and the EXIT trap put both files back byte-for-byte — which is the pair of guards
# doing exactly their job, and the reason the failure cost nothing. Left written down rather than
# quietly fixed, because a heredoc that eats its own indentation is not obvious from reading it.
patch_once() {  # patch_once <file> <expected-count> <old> <new>
python3 - "$@" <<'PY'
import sys
path, want, old, new = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
src = open(path, encoding="utf-8").read()
got = src.count(old)
print(f"  {path.rsplit('/', 1)[-1]}: [{old}] x{got} (expected {want})")
if got != want:
    print(f"control-T-131: ABORT — anchor matched {got} times, expected {want}. Nothing patched.")
    sys.exit(3)
open(path, "w", encoding="utf-8").write(src.replace(old, new))
PY
}

# Condition 3 — and it prints the change, so the log says WHAT was done rather than that something was.
changed() {  # changed <saved-copy> <live-file>
	if diff -u "$1" "$2" > /dev/null; then
		echo "control-T-131: ABORT — $2 is identical to the verified copy; the patch did not apply."
		exit 4
	fi
	diff -u "$1" "$2" | grep -E '^[-+][^-+]' || true
}

put_back() {  # put_back <saved-copy> <live-file> <label>
	cp "$1" "$2"
	cmp -s "$1" "$2" && echo "control-T-131: $3 restored before the next stage"
}

echo "############################################################"
echo "# STAGE 1 — the reported defect, reproduced on demand"
echo "############################################################"
echo
echo "=== the screen is put back to HEAD; the tests are untouched ==="
git -C "$ROOT" show "HEAD:frontend/app/vendors/[id]/page.tsx" > "$PAGE"
echo "=== the tree actually changed (condition 3) — first 30 changed lines ==="
changed "$SAFE/page.tsx" "$PAGE" | head -30
echo
echo "=== tsc, then vitest, tests exactly as written ==="
set +e
(cd "$ROOT/frontend" && npx tsc --noEmit && echo "control-T-131: tsc silent on HEAD's screen" \
	&& npx vitest run __tests__/vendor-supply-edit.test.tsx __tests__/vendor-lead-time.test.tsx)
echo "control-T-131: stage 1 frontend exit=$?"
set -e

put_back "$SAFE/page.tsx" "$PAGE" "stage 1"
echo

echo "############################################################"
echo "# STAGE 2 — the two null/zero guards"
echo "############################################################"
echo
echo "=== anchors counted before any edit (condition 2) ==="
patch_once "$PAGE" 1 'return t === "" ? null : Number(t);' 'return Number(raw);'
patch_once "$PAGE" 1 'return n === null ? "" : String(n);' 'return n ? String(n) : "";'
echo
echo "=== the tree actually changed: verified copy vs patched file (condition 3) ==="
changed "$SAFE/page.tsx" "$PAGE"
echo
echo "=== tsc, then vitest ==="
set +e
(cd "$ROOT/frontend" && npx tsc --noEmit && npx vitest run \
	__tests__/vendor-supply-edit.test.tsx __tests__/vendor-lead-time.test.tsx)
echo "control-T-131: stage 2 frontend exit=$?"
set -e

put_back "$SAFE/page.tsx" "$PAGE" "stage 2"
echo

echo "############################################################"
echo "# STAGE 3 — the upsert the whole screen rests on"
echo "############################################################"
echo
echo "=== anchor counted before any edit (condition 2) ==="
patch_once "$SERVICE" 1 \
	'ON CONFLICT (vendor_id, ingredient_id) DO UPDATE' \
	'ON CONFLICT (id) DO UPDATE'
echo
echo "=== the tree actually changed (condition 3) ==="
changed "$SAFE/VendorService.java" "$SERVICE"
echo
echo "=== gradle, --rerun-tasks, nothing piped through tail (condition 4) ==="
set +e
(cd "$ROOT/backend" && ./gradlew --no-daemon test --rerun-tasks --tests "*VendorIT*")
echo "control-T-131: stage 3 backend exit=$?"
set -e

put_back "$SAFE/VendorService.java" "$SERVICE" "stage 3"
echo

echo "############################################################"
echo "# STAGE 4 — the preference the server moves out of the way"
echo "############################################################"
echo
echo "=== anchor counted before any edit (condition 2) ==="
# `&& false` rather than deleting the block: the statement stays compiled and stays readable in the
# diff, and Java has no unreachable-code error for an `if` whose condition is merely always false.
patch_once "$SERVICE" 1 \
	'if (request.preferred()) {' \
	'if (request.preferred() && false) {'
echo
echo "=== the tree actually changed (condition 3) ==="
changed "$SAFE/VendorService.java" "$SERVICE"
echo
echo "=== gradle, --rerun-tasks, nothing piped through tail (condition 4) ==="
set +e
(cd "$ROOT/backend" && ./gradlew --no-daemon test --rerun-tasks --tests "*VendorIT*")
echo "control-T-131: stage 4 backend exit=$?"
set -e

echo
echo "control-T-131: done — the restore runs from the EXIT trap below."
