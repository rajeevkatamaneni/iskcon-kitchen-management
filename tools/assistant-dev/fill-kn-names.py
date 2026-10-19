#!/usr/bin/env python3
"""Fill the temple's Kannada glossary for stock items, with names written by Claude (E10-T8d).
LOCAL DEVELOPMENT ONLY. A one-off: it will be replaced by the runner (E10-S3) or an API-key call.

    KMS_DEV_TENANT=<tenants.id> DB_NAME=kms_t8d python3 tools/assistant-dev/fill-kn-names.py [--dry-run]
    KMS_DEV_TENANT=<tenants.id> DB_NAME=kms_try python3 tools/assistant-dev/fill-kn-names.py \
        --from-file tools/assistant-dev/results/2026-10-04-claude-kn-names.json

--from-file (added for E10-try) skips the Claude call and inserts the names a previous run saved, so a
fresh copy of the database gets exactly the names T8d measured, letter for letter, instead of a new
set from a new call that could spell some of them differently. Same checks, same insert, same
never-overwrite rule; only the source of the names changes.

Why this exists
---------------
E10-T8b measured the assistant spelling 12 of 28 stock items more than one way in Kannada. E10-T8c made
the glossary the one store of an item's Kannada name (V161 marks a row as the machine's) and added a
fill endpoint that asks Google. Google's names were often wrong for a kitchen: Rice came back as
ಭತ್ತ (paddy), "beaten rice" as cooked rice, toor dal as English sounds in Kannada script. Rajeev
decided on 2026-10-04 that the names come from Claude instead, through a local script for now, and
stay marked as machine names for a person to check on /glossary.

What it does, and the rules it keeps (the same ones StockNameFillService keeps)
-------------------------------------------------------------------------------
1. Lists the temple's stock items (inventory_items joined to ingredients) with no Kannada glossary
   entry, compared case-insensitively the way the glossary's unique key compares. Same SQL as the
   service, so "missing" means the same thing here and there.
2. Turns each filed name into its read-aloud form, a faithful port of IngredientNames.readable:
   "Rice, broken" is asked about as "Broken rice"; "Ghee, for finishing" is left alone because its
   tail is a purpose, not an adjective.
3. Asks Claude for every name in ONE `claude -p` call, locked down the way run-question.sh is:
   `env -i` with an allow-listed environment, an empty working directory, no tools, no settings, no
   MCP, no session kept, and the answer forced into a JSON schema. One call, so the model names
   related items side by side and keeps them consistent with each other.
4. Inserts the names with ON CONFLICT DO NOTHING, machine_provider = 'claude' and
   machine_translated_at = now(). It never updates a row. Running it twice adds nothing.

It connects as kms_app, the application's unprivileged role (no BYPASSRLS, not a superuser), with
app.tenant_id set for the transaction, so the table's row-level security decides what it can read and
write, exactly as it would for the API. The tenant is the one you name; nothing here crosses temples.

Two store items that are the same thing ("Broken rice" and "Rice, broken") may get the same name. That
is reported, never merged: whether they should be one item is a catalogue question for a person.
"""
import json, os, re, shutil, subprocess, sys, tempfile, unicodedata

TENANT = os.environ.get("KMS_DEV_TENANT") or sys.exit("set KMS_DEV_TENANT to the temple's tenants.id")
if not re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", TENANT):
    sys.exit("KMS_DEV_TENANT is not a uuid")
DB = os.environ.get("DB_NAME", "kms_t8d")
CONTAINER = os.environ.get("PG_CONTAINER", "kms-postgres")
APP_ROLE = "kms_app"
LANGUAGE = "kn"
PROVIDER = "claude"
MODEL = os.environ.get("MODEL", "opus")
DRY = "--dry-run" in sys.argv
FROM_FILE = sys.argv[sys.argv.index("--from-file") + 1] if "--from-file" in sys.argv else None
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.environ.get("NAMES_OUT", os.path.join(HERE, "results", "2026-10-04-claude-kn-names.json"))


def psql(sql):
    """Runs SQL as the app role inside the container. -v ON_ERROR_STOP so a failure is a failure."""
    p = subprocess.run(["docker", "exec", "-i", CONTAINER, "psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1",
                        "-U", APP_ROLE, "-d", DB], input=sql, capture_output=True, text=True)
    if p.returncode:
        sys.exit("psql failed: " + p.stderr)
    return p.stdout


def literal(s):
    # Dollar quoting with a tag that cannot occur in the payload, so nothing in a name can end it.
    tag = "$kmsfill$"
    if tag in s:
        sys.exit("payload contains the quote tag")
    return tag + s + tag


# --- 4. insert, never overwrite, under RLS (a function, so --from-file can reach it without step 2) --
def insert_names(names):
    payload = json.dumps([{"source": f, "target": kn} for f, kn in names.items()], ensure_ascii=False)
    out = psql(f"""
    BEGIN;
    SET LOCAL app.tenant_id = {literal(TENANT)};
    WITH ins AS (
      INSERT INTO translation_glossary (tenant_id, language, source_term, target_term,
                                        machine_translated_at, machine_provider)
      SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid, {literal(LANGUAGE)}, r.source, r.target,
             now(), {literal(PROVIDER)}
      FROM json_to_recordset({literal(payload)}::json) AS r(source text, target text)
      ON CONFLICT (tenant_id, language, lower(source_term)) DO NOTHING
      RETURNING 1)
    SELECT count(*) FROM ins;
    COMMIT;
    """).strip().splitlines()
    print(f"inserted {out[-1]} rows (machine_provider='{PROVIDER}')", file=sys.stderr)


# --- 1. the missing items, read under RLS -----------------------------------------------------------
PURPOSE = re.compile(r"^(for|to|as|if|with|in|on|from|per|at|by|about|without)\b", re.I)


def readable(name):
    """Port of org.iskcon.kms.ingredient.IngredientNames.readable, line for line."""
    if name is None:
        return None
    t = name.strip()
    if "," not in t:
        return t
    parts = t.split(",")
    # Java's String.split drops trailing empty strings; Python's does not.
    while parts and parts[-1] == "":
        parts.pop()
    head = parts[0].strip()
    if not head:
        return t
    quals = []
    for p in parts[1:]:
        tail = p.strip()
        if not tail or PURPOSE.search(tail):
            return t
        quals.append(tail)
    if not quals:
        return t
    out = [quals[0][:1].upper() + quals[0][1:]] + quals[1:] + [head[:1].lower() + head[1:]]
    return " ".join(out)


rows = psql(f"""
BEGIN;
SET LOCAL app.tenant_id = {literal(TENANT)};
SELECT coalesce(json_agg(name ORDER BY lower(name)), '[]') FROM (
  SELECT DISTINCT ON (lower(i.name)) i.name
  FROM inventory_items ii JOIN ingredients i ON i.id = ii.ingredient_id
  WHERE NOT EXISTS (SELECT 1 FROM translation_glossary g
                    WHERE g.language = {literal(LANGUAGE)} AND lower(g.source_term) = lower(i.name))
  ORDER BY lower(i.name), i.name) m;
COMMIT;
""").strip().splitlines()
missing = json.loads(rows[-1])
print(f"stock items with no Kannada entry: {len(missing)}", file=sys.stderr)
if not missing:
    print("nothing to fill", file=sys.stderr)
    sys.exit(0)

spoken = [readable(n) for n in missing]
KANNADA_ONLY = re.compile(r"^[\u0c80-\u0cff\u200c\u200d ]+$")

if FROM_FILE:
    # Names a previous run saved. Only items still missing here are taken, matched the way the
    # glossary's unique key matches (lower case); each name is checked as a fresh answer would be.
    with open(FROM_FILE, encoding="utf-8") as f:
        saved = {e["filed"].lower(): e["kannada"] for e in json.load(f)["names"] if e.get("kannada")}
    names, problems = {}, []
    for filed in missing:
        kn = saved.get(filed.lower())
        if kn is None:
            problems.append(f"{filed!r}: not in {os.path.basename(FROM_FILE)}")
        elif not KANNADA_ONLY.match(kn):
            problems.append(f"{filed!r}: not plain Kannada: {kn!r}")
        else:
            names[filed] = kn
    print(f"from file: {len(names)} of {len(missing)} names, problems {len(problems)}", file=sys.stderr)
    for pr in problems:
        print("  problem:", pr, file=sys.stderr)
    if DRY:
        print("dry run: nothing written", file=sys.stderr)
    else:
        insert_names(names)
    sys.exit(0)

# --- 2. one locked-down call to Claude --------------------------------------------------------------
SYSTEM = """You name store items for an ISKCON temple kitchen in Karnataka, in Kannada.
The names are written into the kitchen's glossary and shown to cooks every time they ask about stock, so
each one must be the word the cooks themselves use."""

INSTRUCTION = """For each English store item below, give the everyday name a cook in a Karnataka temple
kitchen uses for it, in Kannada script.

Rules:
- The cook's word, not a literal or dictionary translation. Rice is ಅಕ್ಕಿ (raw grain), never ಅನ್ನ
  (cooked rice) or ಭತ್ತ (paddy). Toor dal is ತೊಗರಿ ಬೇಳೆ, not the English sounds written in Kannada.
- No English transliteration, unless that English word is genuinely what Karnataka cooks say for it
  (a variety or brand name such as Sona Masoori is fine).
- Preparation words ("chopped", "grated", "soaked", "sour", "for finishing") are translated naturally
  the way a cook would say them, or dropped when the store item is simply the same thing.
- Words in brackets in the English are a hint about what the item is; include them only if a cook
  would say them.
- Use the plain name form: no case endings, no punctuation, no brackets, no English.
- Two items that are the same thing should get the same name.
- Keep it short: what would be written on the sack or tin in the store.

Return one entry per item, using its number. Items:
"""

listing = "\n".join(f"{i + 1}. {s}" for i, s in enumerate(spoken))
SCHEMA = {
    "type": "object",
    "properties": {"names": {"type": "array", "items": {
        "type": "object",
        "properties": {"n": {"type": "integer"}, "kannada": {"type": "string"}},
        "required": ["n", "kannada"], "additionalProperties": False}}},
    "required": ["names"], "additionalProperties": False,
}

claude = shutil.which("claude") or sys.exit("claude CLI not on PATH")
env = {"PATH": "/usr/bin:/bin:/usr/sbin:/sbin:" + os.path.dirname(claude), "HOME": os.environ["HOME"],
       "USER": os.environ.get("USER", ""), "LANG": "en_US.UTF-8"}
if os.environ.get("CLAUDE_CODE_OAUTH_TOKEN"):
    env["CLAUDE_CODE_OAUTH_TOKEN"] = os.environ["CLAUDE_CODE_OAUTH_TOKEN"]
work = tempfile.mkdtemp(prefix="kms-fill.")
try:
    cmd = [claude, "-p", "--setting-sources", "", "--disable-slash-commands", "--tools", "",
           "--strict-mcp-config", "--model", MODEL, "--no-session-persistence",
           "--system-prompt", SYSTEM, "--output-format", "json", "--json-schema", json.dumps(SCHEMA),
           INSTRUCTION + listing]
    print("claude -p --setting-sources '' --disable-slash-commands --tools '' --strict-mcp-config "
          f"--model {MODEL} --no-session-persistence --output-format json --json-schema <schema>  "
          f"(env -i, cwd {work}, {len(spoken)} items)", file=sys.stderr)
    p = subprocess.run(cmd, cwd=work, env=env, capture_output=True, text=True)
finally:
    shutil.rmtree(work, ignore_errors=True)
if p.returncode:
    sys.exit(f"claude exited {p.returncode}: {p.stderr[-1000:]}")
res = json.loads(p.stdout)
if res.get("is_error") or not isinstance(res.get("structured_output"), dict):
    sys.exit("claude returned no structured output: " + p.stdout[-1000:])

# --- 3. check every answer before anything is written -----------------------------------------------
got, problems = {}, []
for e in res["structured_output"]["names"]:
    n, kn = e["n"], unicodedata.normalize("NFC", re.sub(r"\s+", " ", e["kannada"]).strip())
    if not 1 <= n <= len(missing):
        problems.append(f"unknown number {n}")
    elif n in got:
        problems.append(f"{n} answered twice")
    elif not KANNADA_ONLY.match(kn):
        problems.append(f"{n} {spoken[n - 1]!r}: not plain Kannada: {kn!r}")
    else:
        got[n] = kn
unanswered = [spoken[i] for i in range(len(missing)) if i + 1 not in got]
names = {missing[n - 1]: kn for n, kn in sorted(got.items())}

same = {}
for filed, kn in names.items():
    same.setdefault(kn, []).append(filed)
shared = {kn: f for kn, f in same.items() if len(f) > 1}

model = next(iter(res.get("modelUsage", {})), "?")
report = {"model": model, "cost_usd": res.get("total_cost_usd"), "duration_ms": res.get("duration_ms"),
          "asked": len(missing), "named": len(names), "problems": problems, "unanswered": unanswered,
          "same_name": shared,
          "names": [{"filed": f, "spoken": readable(f), "kannada": names.get(f)} for f in missing]}
os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", encoding="utf-8") as f:
    json.dump(report, f, ensure_ascii=False, indent=1)
print(f"model {model}  cost ${res.get('total_cost_usd')}  {res.get('duration_ms')} ms  "
      f"named {len(names)} of {len(missing)}  problems {len(problems)}  -> {OUT}", file=sys.stderr)
for pr in problems:
    print("  problem:", pr, file=sys.stderr)
for kn, f in shared.items():
    print(f"  same name {kn}: {f}", file=sys.stderr)

if DRY:
    print("dry run: nothing written", file=sys.stderr)
    sys.exit(0)
insert_names(names)
