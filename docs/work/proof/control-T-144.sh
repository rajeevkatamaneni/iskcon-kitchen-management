#!/usr/bin/env bash
#
# T-144 — negative control for "1 pieces" on the printed documents.
#
# What this is for: a test that passes tells you nothing until you have watched it fail for the
# reason you think it is testing. Each arm below removes one piece of the fix, asserts the tree
# really changed, runs the tests that ought to notice, and requires them to be RED.
#
# Three things this script is careful about, each learned here the hard way:
#
#   1. Restoration is from a `cp` snapshot, never `git checkout --`. Three other builders are live
#      in this checkout and one wiped its own work that way.
#   2. Every patch is anchored on an exact string whose occurrence count is asserted first, so a
#      patch that matches nothing — or matches twice — stops the run instead of silently doing
#      nothing and letting a green arm look like evidence.
#   3. The fixtures land on exactly one. "2 pieces" was correct before this fix and after it, so an
#      arm driven by a quantity of two would go green with the defect fully in place and prove
#      nothing at all. Arm A's job card prints one banana; arm A2's prints 0.7 of one, rounded.
#
set -euo pipefail

cd "$(dirname "$0")/../../.."
ROOT="$PWD"
SNAP="$(mktemp -d)"
LOCK="tools/work-lock.sh"

MAIN_UNIT="backend/src/main/java/org/iskcon/kms/ingredient/Unit.java"
MAIN_QTY="backend/src/main/java/org/iskcon/kms/ingredient/Quantities.java"
TEST_QTY="backend/src/test/java/org/iskcon/kms/ingredient/QuantitiesTest.java"
TEST_GUARD="backend/src/test/java/org/iskcon/kms/ingredient/UnitLabelAgreementTest.java"
TEST_CARD="backend/src/test/java/org/iskcon/kms/document/JobCardIT.java"

FILES=("$MAIN_UNIT" "$MAIN_QTY" "$TEST_QTY" "$TEST_GUARD" "$TEST_CARD")

# ---- snapshot ---------------------------------------------------------------------------------
for f in "${FILES[@]}"; do
	mkdir -p "$SNAP/$(dirname "$f")"
	cp "$f" "$SNAP/$f"
done
echo "snapshot taken in $SNAP"

restore() {
	for f in "${FILES[@]}"; do
		cp "$SNAP/$f" "$ROOT/$f"
	done
	echo "--- restored from the snapshot ---"
}
trap restore EXIT

# ---- anchors ----------------------------------------------------------------------------------
# Asserted before anything is patched. A silently-missing anchor is the single most common way a
# control script lies to you.
anchor() {
	local file="$1" needle="$2" want="$3"
	local got
	got="$(grep -cF -- "$needle" "$file" || true)"
	if [ "$got" != "$want" ]; then
		echo "ANCHOR FAILED: [$needle] found $got times in $file, wanted $want" >&2
		exit 1
	fi
	echo "anchor OK: [$needle] x$got in $(basename "$file")"
}

anchor "$MAIN_QTY"   'return format.format(value) + " " + unit.label(value);'  1
anchor "$MAIN_UNIT"  'PIECES("pieces", "piece", Family.COUNT, 1);'             1
anchor "$MAIN_UNIT"  'public String label(BigDecimal count) {'                 1
anchor "$TEST_QTY"   'class OneOfAThing {'                                     1
anchor "$TEST_CARD"  'void oneOfACountedThingIsSingularOnThePrintedCard()'     1
anchor "$TEST_CARD"  'void aCountedLineThatRoundsToOneIsSingular()'            1
anchor "$TEST_GUARD" 'void plainLabelCallSitesAreTheKnownOnes()'               1

# ---- how an arm is run ------------------------------------------------------------------------
# Expecting RED: a zero exit here means the fix was removed and nothing noticed, which is the
# failure this whole script exists to detect.
expect_red() {
	local name="$1"; shift
	echo
	echo "=== $name — expecting RED ==="
	if "$LOCK" run verify "$*" > "$SNAP/arm.log" 2>&1; then
		echo "ARM WENT GREEN WITH THE FIX REMOVED: $name" >&2
		tail -40 "$SNAP/arm.log" >&2
		exit 1
	fi
	grep -E "FAILED|Failed:|expected|but was" "$SNAP/arm.log" | head -14 || true
	echo "=== $name was red, as it must be ==="
}

# ---- arm A: the funnel forgets the number ------------------------------------------------------
# Quantities.say() goes back to reading the plural label directly — the defect exactly as it stood
# before this task. Everything downstream of it is a printed document.
echo
echo "### arm A — Quantities.say() throws the number away again"
perl -0pi -e 's/\Qreturn format.format(value) + " " + unit.label(value);\E/return format.format(value) + " " + unit.label();/' "$MAIN_QTY"
git diff --stat -- "$MAIN_QTY"

expect_red "A1 — the formatter's vector table and the guard" \
	'cd backend && ./gradlew test --no-daemon --tests "*QuantitiesTest*" --tests "*UnitLabelAgreementTest*"'

expect_red "A2 — the printed job card, one banana and 0.7 of one" \
	'cd backend && ./gradlew test --no-daemon --tests "*JobCardIT*"'

cp "$SNAP/$MAIN_QTY" "$ROOT/$MAIN_QTY"

# ---- arm B: the word exists but PIECES has no singular ------------------------------------------
# The funnel is wired up correctly and Unit simply has nothing different to say. This is the arm
# that proves the fix is the singular itself and not merely the plumbing.
echo
echo "### arm B — PIECES declares no singular"
perl -0pi -e 's/\QPIECES("pieces", "piece", Family.COUNT, 1);\E/PIECES("pieces", "pieces", Family.COUNT, 1);/' "$MAIN_UNIT"
git diff --stat -- "$MAIN_UNIT"

expect_red "B — the formatter's vector table" \
	'cd backend && ./gradlew test --no-daemon --tests "*QuantitiesTest*"'

cp "$SNAP/$MAIN_UNIT" "$ROOT/$MAIN_UNIT"

# ---- arm C: somebody adds a new plural call site -------------------------------------------------
# The guard's whole claim is that a fifth reader of the plural label cannot appear unnoticed. This
# adds one and requires the guard to say so.
echo
echo "### arm C — a new unguarded reader of the plural label"
perl -0pi -e 's/\Qprivate static String say(BigDecimal value, Unit unit, int maxDecimals) {\E/private static String say(BigDecimal value, Unit unit, int maxDecimals) {\n\t\tString sneaked = unit.label();/' "$MAIN_QTY"
git diff --stat -- "$MAIN_QTY"

expect_red "C — the call-site guard" \
	'cd backend && ./gradlew test --no-daemon --tests "*UnitLabelAgreementTest*"'

cp "$SNAP/$MAIN_QTY" "$ROOT/$MAIN_QTY"

# ---- restored ------------------------------------------------------------------------------------
echo
for f in "${FILES[@]}"; do
	cp "$SNAP/$f" "$ROOT/$f"
done
anchor "$MAIN_QTY"  'return format.format(value) + " " + unit.label(value);' 1
anchor "$MAIN_UNIT" 'PIECES("pieces", "piece", Family.COUNT, 1);'            1
echo
echo "ALL ARMS WERE RED. The control is evidence."
