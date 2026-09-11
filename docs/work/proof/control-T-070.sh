#!/bin/bash
# Negative control for the T-070 family, run 2026-09-11 against today's tree.
#
# Nothing was patched by this wave, so there is no new fix to strip. What these three
# controls prove instead is the thing that actually matters for a summed-table defect:
# that the greens reported for T-071, T-072 and T-070's surviving surface are caused by
# the fix, and are not a fixture with no struck/credited row passing for free.
#
# Each control strips the LIVE fix out of today's tree, asserts the anchor matched exactly
# once before stripping, runs the tests AS WRITTEN, and restores from a cp snapshot under
# an EXIT trap. Never `git checkout --` — three other builders are live in this checkout.
set -euo pipefail

ROOT=/Users/Rajeev/Workspace/kitchen-management-system
SNAP=/tmp/kms-control-T-070-snapshots
mkdir -p "$SNAP"

INV="$ROOT/backend/src/main/java/org/iskcon/kms/invoice/VendorInvoiceService.java"
REC="$ROOT/backend/src/main/java/org/iskcon/kms/donation/DonationReconciliationService.java"
PAGE="$ROOT/frontend/app/donations/[id]/page.tsx"

cp "$INV" "$SNAP/VendorInvoiceService.verified"
cp "$REC" "$SNAP/DonationReconciliationService.verified"
cp "$PAGE" "$SNAP/page.tsx.verified"

restore() {
  cp "$SNAP/VendorInvoiceService.verified" "$INV"
  cp "$SNAP/DonationReconciliationService.verified" "$REC"
  cp "$SNAP/page.tsx.verified" "$PAGE"
  echo "TRAP: all three files restored from the cp snapshot"
  cmp -s "$SNAP/VendorInvoiceService.verified" "$INV" && echo "TRAP: VendorInvoiceService identical"
  cmp -s "$SNAP/DonationReconciliationService.verified" "$REC" && echo "TRAP: DonationReconciliationService identical"
  cmp -s "$SNAP/page.tsx.verified" "$PAGE" && echo "TRAP: donations/[id]/page.tsx identical"
}
trap restore EXIT

count() { # count <file> <fixed string> <expected>
  local n; n=$(grep -cF "$2" "$1" || true)
  echo "ANCHOR: '$2' matches $n site(s) in $(basename "$1") (expected $3)"
  [ "$n" = "$3" ] || { echo "ABORT: anchor count wrong — refusing to patch"; exit 9; }
}

# --- Control A: T-071, the credit note that settles a variance ---------------
count "$INV" '.subtract(v.creditedAmount())' 1
python3 - "$INV" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
a='.subtract(v.creditedAmount())'
assert s.count(a)==1, "anchor moved"
open(p,'w').write(s.replace(a,'',1))
PY
grep -q 'subtract(v.creditedAmount())' "$INV" && { echo "ABORT: strip A did not land"; exit 9; }
echo "STRIPPED A: variance back on the gross amount"

# --- Control B: T-072, the struck gift in the reconciliation report ----------
count "$REC" "AND voided_at IS NULL" 1
python3 - "$REC" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
a=" AND voided_at IS NULL"
assert s.count(a)==1, "anchor moved"
open(p,'w').write(s.replace(a,'',1))
PY
grep -q "voided_at IS NULL" "$REC" && { echo "ABORT: strip B did not land"; exit 9; }
echo "STRIPPED B: reconciliation back to selecting struck gifts"

# --- Control C: T-070's surviving surface, the donor-history card ------------
count "$PAGE" 'return row.status === "COMPLETED" && !row.voided;' 1
count "$PAGE" '{r.voided ? <Badge>Voided</Badge> : STATUS_LABEL[r.status] ?? r.status}' 1
python3 - "$PAGE" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
a='return row.status === "COMPLETED" && !row.voided;'
b='{r.voided ? <Badge>Voided</Badge> : STATUS_LABEL[r.status] ?? r.status}'
assert s.count(a)==1 and s.count(b)==1, "anchors moved"
s=s.replace(a,'return row.status === "COMPLETED";',1)
s=s.replace(b,'{STATUS_LABEL[r.status] ?? r.status}',1)
open(p,'w').write(s)
PY
grep -q '!row.voided' "$PAGE" && { echo "ABORT: strip C did not land"; exit 9; }
echo "STRIPPED C: a struck gift now lists as an ordinary COMPLETED one — the T-070 defect, rebuilt"

echo "=== git diff --stat proving the tree changed ==="
cd "$ROOT" && git diff --stat -- \
  backend/src/main/java/org/iskcon/kms/invoice/VendorInvoiceService.java \
  backend/src/main/java/org/iskcon/kms/donation/DonationReconciliationService.java \
  'frontend/app/donations/[id]/page.tsx'

echo "=== BACKEND: tests as written, against the stripped tree ==="
set +e
cd "$ROOT" && ./tools/work-lock.sh run verify \
  'cd backend && ./gradlew test --no-daemon --rerun-tasks --tests "*VendorInvoiceIT*" --tests "*OneTimeDonationIT*"' \
  2>&1 | grep -E "FAILED|PASSED|Total:|Passed:|Failed:|BUILD|AssertionError|NeverWantedButInvoked|expected:"
echo "=== FRONTEND: tests as written, against the stripped tree ==="
cd "$ROOT" && ./tools/work-lock.sh run verify \
  'cd frontend && npx vitest run __tests__/donation-receipt.test.tsx' \
  2>&1 | grep -E "✓|×|FAIL|PASS|Tests |AssertionError|Unable to find|expected"
set -e
echo "=== controls done; the trap restores now ==="
