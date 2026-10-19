#!/usr/bin/env bash
# Try the Kitchen Assistant locally, from a terminal (E10-try). LOCAL DEVELOPMENT ONLY.
#
#   tools/assistant-dev/try.sh start [--port 8095]   # database copy + API + Kannada names
#   python3 tools/assistant-dev/chat.py               # talk to it
#   tools/assistant-dev/try.sh stop [--drop]          # stop; --drop also deletes the copy
#   tools/assistant-dev/try.sh status
#
# Why a copy of the database: the local `kms` database is everyone's local staging copy. The API's
# Flyway migrates whatever it is pointed at, and the assistant's names are written into the glossary,
# so both happen in `kms_try`, made once from `kms` as a template. `kms` itself is only ever read (as
# the template, and once to look up the Temple Admin). `stop` keeps `kms_try`, so what he did in one
# session is still there in the next; `stop --drop` removes it and the next `start` makes a fresh one.
#
# Why the container's previous state is remembered: kms-postgres is often left stopped on this
# machine. `start` starts it only if it has to, and writes that down, so `stop` puts it back the way
# it was found instead of stopping a database someone else had running.
#
# The API runs exactly as tools/local-backend.sh runs it (its safety exports are read from that file,
# not copied, so the two cannot drift): no scheduler, no email, memory secrets, stub providers, and
# TRANSLATION_PROVIDER=stub so nothing calls Google. On top: its own port, DB_URL pointing at the
# copy, and a fixed dev KMS_ASSISTANT_TOKEN_KEY made once and kept in .local/ (gitignored). It is
# `bootRun` without local-backend.sh's continuous-compile watcher, which is for editing, not trying.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
LOCAL="$HERE/.local"
CONTAINER="${PG_CONTAINER:-kms-postgres}"
DB=kms_try
TEMPLATE_DB=kms
NAMES_FILE="$HERE/results/2026-10-04-claude-kn-names.json"
# The seeded Temple Admin T8 used; if that account is not in this copy, the first active Temple Admin.
ADMIN_EMAIL="${KMS_TRY_EMAIL:-ikms.temple-admin.1@trading4good.org}"

mkdir -p "$LOCAL"
chmod 700 "$LOCAL"

say() { printf '%s\n' "$*"; }
die() { printf 'try.sh: %s\n' "$*" >&2; exit 1; }

# Superuser psql, used only for CREATE/DROP DATABASE and the read-only id lookup. The glossary fill
# goes through fill-kn-names.py, which connects as the unprivileged app role under RLS.
pg() { docker exec -i "$CONTAINER" psql -X -q -At -v ON_ERROR_STOP=1 -U kms -d "$1"; }

container_running() { [[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" == true ]]; }

api_pid() {
  # The JVM listening on the port, not the gradle client: bootRun forks the application, and that is
  # the process that has to stop.
  local port; port="$(cat "$LOCAL/port" 2>/dev/null || true)"
  [[ -n "$port" ]] && lsof -nP -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | head -1 || true
}

health() { curl -fsS "http://localhost:$1/health" 2>/dev/null | grep -q '"UP"'; }

cmd_start() {
  local port=8095
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --port) port="${2:?--port needs a number}"; shift 2 ;;
      *) die "unknown option $1" ;;
    esac
  done

  if [[ -n "$(api_pid)" ]] && health "$(cat "$LOCAL/port")"; then
    say "Already running on port $(cat "$LOCAL/port"). Chat: python3 tools/assistant-dev/chat.py"
    return
  fi

  # A free port: the one asked for, or the next free one above it.
  while lsof -nP -tiTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; do port=$((port + 1)); done

  # 1. Database container, remembering whether we were the ones to start it.
  docker inspect "$CONTAINER" >/dev/null 2>&1 || die "no docker container named $CONTAINER"
  if container_running; then
    [[ -f "$LOCAL/started-postgres" ]] || say "kms-postgres already running; stop will leave it running."
  else
    say "Starting kms-postgres (it was stopped; stop will stop it again)."
    docker start "$CONTAINER" >/dev/null
    touch "$LOCAL/started-postgres"
  fi
  for _ in $(seq 1 30); do
    docker exec "$CONTAINER" pg_isready -q -U kms 2>/dev/null && break
    sleep 1
  done
  docker exec "$CONTAINER" pg_isready -q -U kms || die "kms-postgres did not become ready"

  # 2. The copy. CREATE DATABASE ... TEMPLATE needs nobody connected to kms; say so plainly if
  # someone is, rather than kill their sessions.
  if [[ "$(pg postgres <<<"SELECT 1 FROM pg_database WHERE datname = '$DB'")" != 1 ]]; then
    say "Creating database $DB as a copy of $TEMPLATE_DB."
    pg postgres <<<"CREATE DATABASE $DB TEMPLATE $TEMPLATE_DB" \
      || die "could not copy $TEMPLATE_DB (is something connected to it, such as the local API on 8080?)"
  else
    say "Using existing database $DB (kept from last time)."
  fi

  # 3. Who the assistant acts as: the seeded Temple Admin, and their temple. Looked up, not typed in.
  local ids
  ids="$(pg "$DB" <<SQL
SELECT id || ' ' || tenant_id FROM users
WHERE role = 'TEMPLE_ADMIN' AND status = 'ACTIVE'
ORDER BY (lower(email) = lower('$ADMIN_EMAIL')) DESC, created_at, id
LIMIT 1
SQL
)"
  [[ -n "$ids" ]] || die "no active Temple Admin in $DB"
  echo "${ids%% *}" > "$LOCAL/user"
  echo "${ids##* }" > "$LOCAL/tenant"

  # 4. The dev token key: made once, then reused, so tokens minted by chat.py keep verifying across
  # restarts. 256 random bits, base64, as AgentTokenService requires.
  if [[ ! -s "$LOCAL/token-key" ]]; then
    (umask 077; openssl rand -base64 32 > "$LOCAL/token-key")
  fi

  # 5. The API, detached.
  echo "$port" > "$LOCAL/port"
  # shellcheck disable=SC2046
  eval "$(sed -n '/^export JAVA_HOME=/,/SENTRY_DSN=$/p' "$ROOT/tools/local-backend.sh")"
  [[ "${TRANSLATION_PROVIDER:-}" == stub && "${KMS_WORKER_ENABLED:-}" == false ]] \
    || die "could not read the safety environment from tools/local-backend.sh"
  export PORT="$port" DB_URL="jdbc:postgresql://localhost:5432/$DB"
  KMS_ASSISTANT_TOKEN_KEY="$(cat "$LOCAL/token-key")"
  export KMS_ASSISTANT_TOKEN_KEY
  say "Starting the API on port $port (log: tools/assistant-dev/.local/api.log)."
  # Every stream of the detached subshell is redirected, not only gradle's: a background subshell
  # that still holds this script's stdout keeps a caller's pipe open (`try.sh start | tail` would
  # never finish), which is how this was found.
  (cd "$ROOT/backend" && exec nohup ./gradlew bootRun) </dev/null > "$LOCAL/api.log" 2>&1 &
  echo $! > "$LOCAL/gradle.pid"

  local waited=0
  until health "$port"; do
    sleep 2; waited=$((waited + 2))
    if ! kill -0 "$(cat "$LOCAL/gradle.pid")" 2>/dev/null; then
      tail -30 "$LOCAL/api.log" >&2; die "the API stopped while starting; the log tail is above"
    fi
    (( waited < 240 )) || { tail -30 "$LOCAL/api.log" >&2; die "no /health UP after 240 s"; }
  done
  api_pid > "$LOCAL/api.pid"
  say "API up after ${waited}s (pid $(cat "$LOCAL/api.pid"))."

  # 6. Kannada names, exactly the ones T8d stored, never overwriting anything already there.
  KMS_DEV_TENANT="$(cat "$LOCAL/tenant")" DB_NAME="$DB" PG_CONTAINER="$CONTAINER" \
    python3 "$HERE/fill-kn-names.py" --from-file "$NAMES_FILE"

  say ""
  say "Kitchen Assistant is ready on port $port, as the Temple Admin of this temple."
  say "Chat:  python3 tools/assistant-dev/chat.py      (/new starts over, /quit or Ctrl-D leaves)"
  say "Stop:  tools/assistant-dev/try.sh stop"
}

cmd_stop() {
  local drop=false
  [[ "${1:-}" == --drop ]] && drop=true
  local pid; pid="$(api_pid)"
  if [[ -n "$pid" ]]; then
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -9 "$pid" 2>/dev/null || true
    say "API stopped."
  else
    say "API was not running."
  fi
  # The gradle client exits once its child is gone; make sure.
  if [[ -f "$LOCAL/gradle.pid" ]]; then kill "$(cat "$LOCAL/gradle.pid")" 2>/dev/null || true; fi
  rm -f "$LOCAL/api.pid" "$LOCAL/gradle.pid"

  if container_running; then
    if $drop; then
      pg postgres <<<"DROP DATABASE IF EXISTS $DB" && say "Database $DB dropped."
    else
      say "Database $DB kept (stop --drop removes it)."
    fi
    if [[ -f "$LOCAL/started-postgres" ]]; then
      docker stop "$CONTAINER" >/dev/null && say "kms-postgres stopped (start had started it)."
      rm -f "$LOCAL/started-postgres"
    else
      say "kms-postgres left running (it was running before start)."
    fi
  elif $drop; then
    die "kms-postgres is not running, so $DB could not be dropped"
  fi
}

cmd_status() {
  local port; port="$(cat "$LOCAL/port" 2>/dev/null || echo '?')"
  if [[ -n "$(api_pid)" ]] && health "$port"; then say "API: up on port $port"; else say "API: stopped"; fi
  if container_running; then
    say "kms-postgres: running$([[ -f "$LOCAL/started-postgres" ]] && echo ' (started by try.sh)')"
    if [[ "$(pg postgres <<<"SELECT 1 FROM pg_database WHERE datname = '$DB'")" == 1 ]]; then
      say "Database $DB: present, $(pg "$DB" <<<"SELECT count(*) FROM translation_glossary WHERE language = 'kn'") Kannada glossary rows"
    else
      say "Database $DB: absent"
    fi
  else
    say "kms-postgres: stopped"
  fi
  [[ -s "$LOCAL/user" ]] && say "Acting as user $(cat "$LOCAL/user") at temple $(cat "$LOCAL/tenant")"
  return 0
}

case "${1:-}" in
  start) shift; cmd_start "$@" ;;
  stop) shift; cmd_stop "$@" ;;
  status) cmd_status ;;
  *) die "usage: try.sh start [--port N] | stop [--drop] | status" ;;
esac
