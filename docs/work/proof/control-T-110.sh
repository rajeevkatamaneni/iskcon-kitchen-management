#!/usr/bin/env bash
# Negative control for T-110. Two conditions, one hold of the verify lock.
#
#   C1 — strip the "a receipt already exists" branch from DocumentService, so re-issuing
#        stops finding the row that is already there.
#   C2 — strip the voided guard from DonationReceiptService.issueNumber.
#
# Every guard docs/work/README.md lesson 4 asks for: set -e; grep -c each anchor and assert
# the count; the tree proved changed before anything runs; --rerun-tasks so an incremental
# build cannot hand back an up-to-date shortcut; restore through an EXIT trap and diff
# byte-for-byte; and every artefact named for the task, because the scratchpad and
# build/test-results are shared by every agent in this checkout.
#
# ON PROVING THE TREE CHANGED, which the brief singles out:
#   DocumentService.java is TRACKED, so `git diff --stat` sees it.
#   DonationReceiptService.java is UNTRACKED — it is new in this task — so `git diff --stat`
#   says NOTHING about it, and a script trusting the stat alone would have run a control in
#   which only half the patch had applied. Both files are therefore proved changed by
#   checksum, which does not care whether git has heard of them.
set -euo pipefail

ROOT=/Users/Rajeev/Workspace/kitchen-management-system
DOC="$ROOT/backend/src/main/java/org/iskcon/kms/document/DocumentService.java"
REC="$ROOT/backend/src/main/java/org/iskcon/kms/donation/DonationReceiptService.java"
WORK=$(mktemp -d)
RAW="$WORK/gradle-T-110.out"

cp "$DOC" "$WORK/DocumentService.java.verified"
cp "$REC" "$WORK/DonationReceiptService.java.verified"
doc_before=$(shasum -a 256 < "$DOC" | cut -d' ' -f1)
rec_before=$(shasum -a 256 < "$REC" | cut -d' ' -f1)

restore() {
  cp "$WORK/DocumentService.java.verified" "$DOC"
  cp "$WORK/DonationReceiptService.java.verified" "$REC"
  echo "--- restored; byte-for-byte diff against the verified copies ---"
  diff "$WORK/DocumentService.java.verified" "$DOC" && echo "DocumentService.java: identical"
  diff "$WORK/DonationReceiptService.java.verified" "$REC" && echo "DonationReceiptService.java: identical"
}
trap restore EXIT

# --- each anchor must match exactly once ------------------------------------
c1=$(grep -c 'if (existing != null) {' "$DOC")
c2=$(grep -c 'if (d.get("voided_at") != null) {' "$REC")
echo "anchor counts: C1=$c1 (want exactly 1), C2=$c2 (want exactly 1)"
[ "$c1" = "1" ] || { echo "ABORT: C1 anchor matched $c1 sites, wanted exactly 1"; exit 2; }
[ "$c2" = "1" ] || { echo "ABORT: C2 anchor matched $c2 sites, wanted exactly 1"; exit 2; }

# --- patch both fixes out ----------------------------------------------------
perl -0pi -e 's/if \(existing != null\) \{/if (existing != null \&\& false) {/' "$DOC"
perl -0pi -e 's/if \(d\.get\("voided_at"\) != null\) \{/if (false \&\& d.get("voided_at") != null) {/' "$REC"

# --- prove BOTH files actually changed --------------------------------------
doc_after=$(shasum -a 256 < "$DOC" | cut -d' ' -f1)
rec_after=$(shasum -a 256 < "$REC" | cut -d' ' -f1)
echo "DocumentService.java        sha256 before=${doc_before:0:16} after=${doc_after:0:16}"
echo "DonationReceiptService.java sha256 before=${rec_before:0:16} after=${rec_after:0:16}"
[ "$doc_before" != "$doc_after" ] || { echo "ABORT: C1 changed nothing"; exit 3; }
[ "$rec_before" != "$rec_after" ] || { echo "ABORT: C2 changed nothing"; exit 3; }
grep -q 'existing != null && false' "$DOC" || { echo "ABORT: C1 text absent after patching"; exit 3; }
grep -q 'false && d.get("voided_at")' "$REC" || { echo "ABORT: C2 text absent after patching"; exit 3; }

echo "--- git diff --stat, for the tracked file only ---"
git -C "$ROOT" diff --stat -- backend/src/main/java/org/iskcon/kms/document/DocumentService.java
echo "(DonationReceiptService.java is untracked, so it is deliberately absent above:"
git -C "$ROOT" status --porcelain -- backend/src/main/java/org/iskcon/kms/donation/DonationReceiptService.java
echo " — which is why the checksums above are the real guard.)"

# --- run the tests exactly as written ---------------------------------------
echo "--- the suite, with both fixes patched out ---"
cd "$ROOT/backend"
set +e
./gradlew test --rerun-tasks --console=plain \
  --tests "*DonationReceiptIT*" --tests "*DonationReceiptTemplateTest*" > "$RAW" 2>&1
gradle_exit=$?
set -e
echo "gradle exit: $gradle_exit"
echo
echo "=== every FAILED test, with the assertion that failed ==="
grep -E "^[A-Za-z].*(FAILED)$" "$RAW" || true
echo
grep -A4 -E "^[A-Za-z].*FAILED$" "$RAW" | grep -E "Error|expected|Status|Assertion" | sort -u || true
echo
echo "=== summary ==="
sed -n '/Test summary/,/^$/p' "$RAW" | head -14
