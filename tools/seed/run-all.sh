#!/usr/bin/env bash
# Seed a temple's month of operations, in the order the phases actually depend on each other.
#
#   tools/seed/run-all.sh --api http://localhost:8091 --tenant <id> [--groups 1,2,3,4]
#
# Every phase is re-runnable, so this can be run again to add a later group to a temple that
# already has the earlier ones. Nothing is recreated.
#
# ---------------------------------------------------------------------------
# The one ordering rule that is not obvious, and that cost a rebuild to find
#
# **All the buying happens before any of the cooking.** Phase 12 records what was cooked, and
# recording draws stock. Run it before the deliveries have arrived and the drawdown finds an
# empty store: the application does not refuse it — a kitchen that has already cooked the food
# cannot un-cook it — so it books the shortfall as USED_BEYOND_RECORDED_STOCK instead.
#
# Measured, running the cooking after a single ordering round: **1,574 lines cooked beyond
# recorded stock against 681 drawn from it.** More than twice as much food came from nowhere as
# came from the store. Adding more ordering rounds afterwards does not repair it, because the
# movements are already written and they are append-only.
#
# A temple buys weekly, so the ordering cycle runs four times across the four-week window, and
# all four rounds finish before the first meal is recorded.
# ---------------------------------------------------------------------------
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

# The variable holding the wanted groups is WANTED, and the name is not a style choice.
# **`GROUPS` is a built-in array in bash**, holding the current user's group ids. Assigning to it
# does not take, and it reads back as the first of those ids — "20", the staff group, on this
# machine. Every comparison then failed, and this script ran cheerfully to the end having executed
# no phase at all, exiting 0. Hence also the "nothing matched" check further down: a runner that
# does nothing must say so.
API=""
TENANT=""
WANTED="1,2,3,4"
ROUNDS="4"

while [ $# -gt 0 ]; do
  case "$1" in
    --api)     API="$2"; shift 2 ;;
    --tenant)  TENANT="$2"; shift 2 ;;
    --groups)  WANTED="$2"; shift 2 ;;
    --rounds)  ROUNDS="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$API" ] || [ -z "$TENANT" ]; then
  echo "usage: $0 --api <url> --tenant <id> [--groups 1,2,3,4] [--rounds 4]" >&2
  exit 2
fi
if [ -z "${KMS_SEED_PASSWORD:-}" ]; then
  echo "KMS_SEED_PASSWORD is not set. No password lives in this repository; see the README." >&2
  exit 2
fi

PROBLEMS=""

# A phase exits 1 when it finishes but has something to report — two ingredients with no vendor,
# a unit the recipes and the catalogue disagree about. That is a finding, not a failure, and
# halting the run on it means the operator fixes one thing, runs again, and waits to discover the
# next. So the run continues and every phase that had something to say is listed at the end.
#
# A phase that dies outright — a traceback, a refused connection — exits 2 or more, and that does
# stop the run, because everything after it would be building on nothing.
run() {
  local script="$1"; shift
  echo
  echo "============================================================"
  echo "  $script $*"
  echo "============================================================"
  local code=0
  python3 "$HERE/$script" --api "$API" --tenant "$TENANT" "$@" || code=$?
  if [ "$code" -eq 0 ]; then
    return 0
  fi
  if [ "$code" -eq 1 ]; then
    PROBLEMS="$PROBLEMS  $script"$'\n'
    echo "  ^ $script finished with something to report; the run continues."
    return 0
  fi
  echo "  ^ $script FAILED (exit $code). Stopping: everything after it builds on this." >&2
  exit "$code"
}

wants() { case ",$WANTED," in (*",$1,"*) return 0 ;; (*) return 1 ;; esac; }

RUNNING=""
for g in 1 2 3 4; do
  if wants "$g"; then RUNNING="$RUNNING $g"; fi
done
echo "api $API | tenant $TENANT | groups$RUNNING | $ROUNDS ordering round(s)"
if [ -z "$RUNNING" ]; then
  echo "No group matched \"$WANTED\". Nothing to do." >&2
  exit 2
fi

# Group 1 — until these are done every other screen is empty.
if wants 1; then
  run phase00_preflight.py
  run phase01_opening_stock.py
  run phase02_recipes.py
  run phase03_complete_ingredients.py
  run phase04_staff_check.py --confirm-kitchens
fi

# Group 2 — the story the procurement work was built for. The plan first, then every ordering
# round, because the cooking in group 3 draws against whatever these leave on the shelf.
if wants 2; then
  run phase05_meal_plan.py
  for round_no in $(seq 1 "$ROUNDS"); do
    run phase06_orders.py --round "$round_no"
    run phase07_deliveries.py --round "$round_no"
    run phase08_invoices.py --round "$round_no"
  done
fi

# Group 3 — breadth. The cooking is last here for the reason at the top of this file.
if wants 3; then
  run phase09_kitchens.py
  run phase10_ingredient_requests.py
  run phase11_joint_festivals.py
  run phase12_cooking.py
fi

# Group 4 — the parts that are cheapest to drop if time runs out.
if wants 4; then
  run phase13_volunteers.py
  run phase14_giving.py
  run phase15_equipment.py
fi

echo
if [ -n "$PROBLEMS" ]; then
  echo "============================================================"
  echo "  These phases finished but had something to report."
  echo "  Scroll back to each one; the detail is under its own heading."
  echo
  printf '%s' "$PROBLEMS"
  echo "============================================================"
fi

echo
echo "============================================================"
echo "  Seeding finished. Now make the paperwork read as history:"
echo
echo "  docker exec -i kms-postgres psql -U kms_migration -d <db> \\"
echo "    -v tenant=$TENANT -f - < tools/seed/backdate.sql"
echo "============================================================"
