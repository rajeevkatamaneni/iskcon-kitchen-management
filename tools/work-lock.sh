#!/usr/bin/env bash
#
# A mutex for agents sharing one checkout.
#
# Several builder agents edit disjoint files at the same time, which is safe. What is not safe is
# what they do next: `./gradlew test` takes a lock on backend/build and one of two concurrent runs
# waits or dies, `next build` and `vitest` both write .next/ and node_modules/.cache, and none of
# it fails loudly enough to be obvious in a transcript. So the *edit* phase runs concurrently and
# the *verify* phase queues here.
#
# The second lock, `release`, is different in kind: it is not a queue but an assertion that exactly
# one agent is committing, pushing and deploying. It fails fast rather than waiting, because a
# second release agent starting up is a mistake to report, not a turn to take.
#
# A directory is the lock, because mkdir is atomic on every filesystem that matters and lockfile(1)
# and flock(1) are neither of them present on macOS.
#
# Usage:
#   tools/work-lock.sh run     <name> <command>   # wait for the lock, run, always free it
#   tools/work-lock.sh acquire <name> [label]     # take it or exit 1; for holding across calls
#   tools/work-lock.sh free    <name>             # give it back
#   tools/work-lock.sh status                     # who holds what, and for how long
#
# `run` sleeps while it waits, so call it with run_in_background — a foreground wait will time out.

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCKS="${ROOT}/.work-locks"
STALE_AFTER=3600   # seconds; a lock older than this is reported, never silently stolen

mkdir -p "$LOCKS"

age_of() { echo $(( $(date +%s) - $(cat "$1/taken-at" 2>/dev/null || date +%s) )); }

describe() {
  local dir="$1"
  printf '%s (held %ss by pid %s%s)' \
    "$(basename "$dir" .lock)" \
    "$(age_of "$dir")" \
    "$(cat "$dir/pid" 2>/dev/null || echo '?')" \
    "$(if [ -s "$dir/label" ]; then printf ' — %s' "$(cat "$dir/label")"; fi)"
}

take() {   # take <dir> <label> -> 0 if acquired
  local dir="$1" label="${2:-}"
  mkdir "$dir" 2>/dev/null || return 1
  echo "$$"              > "$dir/pid"
  date +%s               > "$dir/taken-at"
  printf '%s' "$label"   > "$dir/label"
  return 0
}

warn_if_stale() {
  local dir="$1"
  [ -d "$dir" ] || return 0
  if [ "$(age_of "$dir")" -gt "$STALE_AFTER" ]; then
    echo "work-lock: WARNING — $(describe "$dir"); that is longer than $((STALE_AFTER/60))m." >&2
    echo "work-lock: if the agent that took it is gone, free it deliberately:" >&2
    echo "work-lock:   tools/work-lock.sh free $(basename "$dir" .lock)" >&2
  fi
}

cmd="${1:-}"; shift || true

case "$cmd" in
  run)
    name="${1:?usage: work-lock.sh run <name> <command>}"; shift
    dir="${LOCKS}/${name}.lock"
    waited=0
    until take "$dir" "$*"; do
      if [ "$waited" -eq 0 ]; then echo "work-lock: waiting for '${name}' — $(describe "$dir")"; fi
      warn_if_stale "$dir"
      sleep 5; waited=$((waited + 5))
    done
    [ "$waited" -gt 0 ] && echo "work-lock: acquired '${name}' after ${waited}s"
    trap 'rm -rf "$dir"' EXIT INT TERM
    bash -c "$*"
    status=$?
    echo "work-lock: '${name}' released; command exited ${status}"
    exit $status
    ;;

  acquire)
    name="${1:?usage: work-lock.sh acquire <name> [label]}"; shift
    dir="${LOCKS}/${name}.lock"
    if take "$dir" "${*:-}"; then
      echo "work-lock: acquired '${name}'"
      exit 0
    fi
    echo "work-lock: '${name}' is ALREADY HELD — $(describe "$dir")" >&2
    warn_if_stale "$dir"
    echo "work-lock: stop and report this rather than proceeding without the lock." >&2
    exit 1
    ;;

  free)
    name="${1:?usage: work-lock.sh free <name>}"
    rm -rf "${LOCKS}/${name}.lock"
    echo "work-lock: freed '${name}'"
    ;;

  status)
    shopt -s nullglob
    held=("${LOCKS}"/*.lock)
    if [ ${#held[@]} -eq 0 ]; then echo "work-lock: nothing held"; exit 0; fi
    for dir in "${held[@]}"; do echo "work-lock: $(describe "$dir")"; warn_if_stale "$dir"; done
    ;;

  *)
    sed -n '3,30p' "$0" | sed 's/^# \{0,1\}//'
    exit 64
    ;;
esac
