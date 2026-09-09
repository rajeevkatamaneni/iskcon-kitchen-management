#!/usr/bin/env bash
# Negative control for T-066. Patches the fix back out, runs the tests AS WRITTEN, restores.
set -euo pipefail

ROOT=/Users/Rajeev/Workspace/kitchen-management-system
SP=/private/tmp/claude-501/-Users-Rajeev-Workspace-kitchen-management-system/d8a15814-5daf-4b1d-ac53-4d3dcc17162c/scratchpad
BK="$SP/control-T-066-backup"
LOG="$SP/control-T-066.log"

CTRL=/Users/Rajeev/Workspace/kitchen-management-system/backend/src/main/java/org/iskcon/kms/purchaseorder/PurchaseOrderController.java
POS=/Users/Rajeev/Workspace/kitchen-management-system/backend/src/main/java/org/iskcon/kms/purchaseorder/PurchaseOrderService.java
VPS=/Users/Rajeev/Workspace/kitchen-management-system/backend/src/main/java/org/iskcon/kms/vendor/VendorPerformanceService.java
PAGE="/Users/Rajeev/Workspace/kitchen-management-system/frontend/app/orders/[id]/page.tsx"

rm -rf "$BK"; mkdir -p "$BK"
cp "$CTRL" "$BK/PurchaseOrderController.java"
cp "$POS"  "$BK/PurchaseOrderService.java"
cp "$VPS"  "$BK/VendorPerformanceService.java"
cp "$PAGE" "$BK/page.tsx"

restore() {
  cp "$BK/PurchaseOrderController.java" "$CTRL"
  cp "$BK/PurchaseOrderService.java"    "$POS"
  cp "$BK/VendorPerformanceService.java" "$VPS"
  cp "$BK/page.tsx"                     "$PAGE"
  echo "--- RESTORE: byte-for-byte diff against the verified copies ---"
  diff "$BK/PurchaseOrderController.java" "$CTRL"  && echo "OK controller"
  diff "$BK/PurchaseOrderService.java"    "$POS"   && echo "OK po service"
  diff "$BK/VendorPerformanceService.java" "$VPS"  && echo "OK vendor performance"
  diff "$BK/page.tsx"                     "$PAGE"  && echo "OK page"
  cmp "$BK/PurchaseOrderController.java" "$CTRL"
  cmp "$BK/PurchaseOrderService.java"    "$POS"
  cmp "$BK/VendorPerformanceService.java" "$VPS"
  cmp "$BK/page.tsx"                     "$PAGE"
  echo "--- RESTORE: cmp clean on all four ---"
}
trap restore EXIT

# ---- anchors: each must match EXACTLY once, or abort -------------------------
A1='@PostMapping("/{id}/arrivals")'
A2='WHEN l.ingredient_id IS NULL THEN l.arrived_on IS NULL'
A3='rs.getObject("first_arrival_on", LocalDate.class));'
A4='const outstandingArrivals = lines.filter((l) => l.ingredientId === null && l.arrivedOn === null);'

assert_one() {
  local file="$1" anchor="$2" name="$3"
  local n
  n=$(grep -cF "$anchor" "$file" || true)
  if [ "$n" -ne 1 ]; then
    echo "ABORT: expected 1 occurrence of $name in $file, found $n"
    exit 9
  fi
  echo "anchor $name: 1 occurrence, as expected"
}
assert_one "$CTRL" "$A1" "the arrivals route"
assert_one "$POS"  "$A2" "the described-line branch of isFullyAccountedFor"
assert_one "$VPS"  "$A3" "the first-arrival term in countOrders"
assert_one "$PAGE" "$A4" "outstandingArrivals on the order screen"

# ---- patch the fix back out --------------------------------------------------
perl -0pi -e 's/\@PostMapping\("\/\{id\}\/arrivals"\)/\@PostMapping("\/\{id\}\/arrivals-removed-by-control")/' "$CTRL"
perl -0pi -e 's/WHEN l\.ingredient_id IS NULL THEN l\.arrived_on IS NULL/WHEN l.ingredient_id IS NULL THEN false/' "$POS"
perl -0pi -e 's/rs\.getObject\("first_arrival_on", LocalDate\.class\)\);/(LocalDate) null);/' "$VPS"
perl -0pi -e 's/const outstandingArrivals = lines\.filter\(\(l\) => l\.ingredientId === null \&\& l\.arrivedOn === null\);/const outstandingArrivals: PurchaseOrderLineView[] = [];/' "$PAGE"

# ---- prove the tree actually changed ----------------------------------------
# git diff --stat proves nothing about a NEW file, so this compares against the
# verified copies taken above rather than against the index.
echo "--- PROOF THE PATCH APPLIED (diff vs the verified copies) ---"
for pair in "$BK/PurchaseOrderController.java:$CTRL" "$BK/PurchaseOrderService.java:$POS" "$BK/VendorPerformanceService.java:$VPS" "$BK/page.tsx:$PAGE"; do
  b="${pair%%:*}"; f="${pair#*:}"
  if cmp -s "$b" "$f"; then echo "ABORT: $f is unchanged — the patch did not apply"; exit 9; fi
  echo "changed: $f"
  diff "$b" "$f" | sed -n '1,12p' || true
done

# ---- run the tests AS WRITTEN ------------------------------------------------
echo "=== FRONTEND (tests unmodified) ==="
cd "$ROOT/frontend"
npx vitest run __tests__/described-po-line.test.tsx 2>&1 | tail -45 || true

echo "=== BACKEND (--rerun-tasks, so no UP-TO-DATE shortcut) ==="
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
cd "$ROOT/backend"
./gradlew test --rerun-tasks --tests "*DescribedPurchaseLineIT*" --tests "*VendorPerformanceIT*" 2>&1 | tail -70 || true
