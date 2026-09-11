#!/usr/bin/env bash
#
# Refuse a terraform.tfvars.example that has drifted from variables.tf.
#
# The example file is what somebody stands a new environment up from. It is the only description
# of "what this environment needs" that a person reads before they have anything working, and it
# is the one file in infra/ that nothing validates: `terraform fmt` and `terraform validate`
# both refuse the `.example` extension outright, and the real `terraform.tfvars` is gitignored,
# so no Terraform command has ever looked at this file.
#
# So it drifted, and nobody found out for months. By 2026-09-10 it named `min_instances`, a
# variable deleted long before, and carried none of the five smtp_*/email_* entries the
# application had grown. Both halves of that hurt, and they hurt differently:
#
#   * A variable declared in variables.tf but missing from the example leaves a new environment
#     silently unconfigured. Email had no relay host and the app quietly did not send.
#   * A variable in the example that no longer exists makes `terraform` refuse at the first
#     step, with a message that reads like the operator mistyped something. The first thing a
#     new environment would have done is fail, and the person would have assumed it was them.
#
# Hence a set difference, both directions, on every push. It is the cheapest check in CI and it
# is the only thing standing between this file and the past.
#
# ---------------------------------------------------------------------------------------------
# What the parser does, and where it would be wrong
#
# Both files are HCL and this is awk, so be honest about the limits. A naive `grep 'variable "'`
# is right until somebody writes a comment containing that string, and a check that silently
# stops matching is worse than no check at all, because it reports success. This one is built so
# that everything it does not understand is an error rather than a shrug:
#
#   * Comments are stripped by a character scanner that tracks double-quoted strings, so a `#`
#     or `//` inside a string value is not treated as a comment and a `#` outside one is.
#   * Heredocs (`<<EOT`, `<<-EOT`) are tracked and their bodies skipped entirely, so a
#     description containing the literal text `variable "x" {` at column 0 is not counted.
#   * Brace/bracket/paren depth is counted outside strings and comments, and only lines at
#     depth 0 are considered declarations or assignments. A nested `smtp_host = ...` inside some
#     future block is therefore not mistaken for a top-level assignment.
#   * Anything at depth 0 that is not blank and not a declaration/assignment is a PARSE ERROR,
#     not a skipped line. If either file grows a construct this parser has never seen — a
#     `locals` block in variables.tf, a `terraform {` block, anything — CI fails loudly and
#     tells you to extend this script. That is the whole point: the failure mode of a drift
#     check must never be "quietly matched nothing".
#   * `/* ... */` block comments are a deliberate PARSE ERROR. Neither file uses them, handling
#     them properly needs real lexing, and handling them badly is how a check starts lying.
#   * Duplicate names on either side are an error too. Two assignments of the same variable in
#     the example make the set difference look clean while the file is wrong.
#
# Where it is still wrong: it does not understand single-quoted-anything (HCL has no single-quoted
# strings, so this is fine), it does not evaluate `for` expressions or interpolation, and it
# assumes a variable block opens on the same line as its name — `variable "x"` followed by `{` on
# the next line would be reported as a parse error rather than parsed. That last one is legal HCL
# and nobody writes it; if somebody does, CI will say so rather than miscount.
set -euo pipefail

cd "$(dirname "$0")/.."

readonly VARIABLES="infra/environment/variables.tf"
readonly EXAMPLE="infra/environment/terraform.tfvars.example"

for f in "$VARIABLES" "$EXAMPLE"; do
  if [[ ! -f "$f" ]]; then
    echo "$f does not exist. This check cannot run." >&2
    exit 1
  fi
done

# The shared scanner. MODE is "declarations" (variables.tf) or "assignments" (the example).
# Prints one name per line on stdout; prints a diagnosis and exits non-zero on anything it
# cannot account for.
# shellcheck disable=SC2016  # The single quotes are the point: this is an awk program, and $0,
# $1 and the backslashes in it belong to awk, not to the shell.
readonly PARSER='
function fail(msg) {
  printf("%s:%d: %s\n", FILENAME, FNR, msg) > "/dev/stderr"
  printf("  %s\n", $0) > "/dev/stderr"
  bad = 1
  exit 1
}

# Strip comments, count bracket depth, and spot a heredoc opener, all in one left-to-right pass
# that knows when it is inside a double-quoted string. Returns the line with comments removed.
function scan(line,   i, n, c, d, inq, out) {
  n = length(line); inq = 0; out = ""; heredoc_open = ""
  for (i = 1; i <= n; i++) {
    c = substr(line, i, 1)
    if (inq) {
      if (c == "\\") { out = out c substr(line, i + 1, 1); i++; continue }
      if (c == "\"") inq = 0
      out = out c
      continue
    }
    if (c == "\"") { inq = 1; out = out c; continue }
    if (c == "#") break
    if (c == "/" && substr(line, i + 1, 1) == "/") break
    if (c == "/" && substr(line, i + 1, 1) == "*")
      fail("/* */ block comment. This parser deliberately does not handle them — see the header of tools/check-tfvars-example.sh and either use # comments or extend the parser.")
    if (c == "{" || c == "[" || c == "(") depth++
    if (c == "}" || c == "]" || c == ")") depth--
    if (c == "<" && substr(line, i + 1, 1) == "<") {
      d = substr(line, i + 2)
      sub(/^[-~]/, "", d)
      sub(/^"/, "", d)
      if (match(d, /^[A-Za-z_][A-Za-z0-9_]*/)) heredoc_open = substr(d, 1, RLENGTH)
      i = n
    }
    out = out c
  }
  return out
}

function trim(s) { sub(/^[ \t]+/, "", s); sub(/[ \t]+$/, "", s); return s }

BEGIN { depth = 0; in_heredoc = 0; bad = 0 }

{
  if (in_heredoc) {
    if (trim($0) == heredoc_term) in_heredoc = 0
    next
  }

  opening_depth = depth
  code = trim(scan($0))
  if (heredoc_open != "") { heredoc_term = heredoc_open; in_heredoc = 1 }

  if (code == "") next
  if (opening_depth > 0) next

  if (MODE == "declarations") {
    if (match(code, /^variable[ \t]+"[^"]+"[ \t]*\{/)) {
      name = code
      sub(/^variable[ \t]+"/, "", name)
      sub(/".*$/, "", name)
    } else {
      fail("top-level line in variables.tf that is not a `variable \"name\" {` declaration. This parser only understands variable blocks; extend tools/check-tfvars-example.sh rather than letting it skip lines silently.")
    }
  } else {
    if (match(code, /^[A-Za-z_][A-Za-z0-9_-]*[ \t]*=/)) {
      name = code
      sub(/[ \t]*=.*$/, "", name)
    } else {
      fail("top-level line in the example that is not a `name = value` assignment. The example is assignments and # comments only; extend tools/check-tfvars-example.sh rather than letting it skip lines silently.")
    }
  }

  if (name in seen)
    fail(sprintf("%s is named twice (first at line %d). A duplicate makes the set difference below look clean while the file is wrong.", name, seen[name]))
  seen[name] = FNR
  print name
}

END {
  if (bad) exit 1
  if (in_heredoc)
    { printf("%s: file ended inside a heredoc opened for %s; the parser lost its place.\n", FILENAME, heredoc_term) > "/dev/stderr"; exit 1 }
  if (depth != 0)
    { printf("%s: unbalanced braces (depth %d at end of file); the parser lost its place.\n", FILENAME, depth) > "/dev/stderr"; exit 1 }
}
'

declared="$(awk -v MODE=declarations "$PARSER" "$VARIABLES" | LC_ALL=C sort)"
exampled="$(awk -v MODE=assignments "$PARSER" "$EXAMPLE" | LC_ALL=C sort)"

# A drift check that parses nothing reports success forever. Refuse to be that.
if [[ -z "$declared" ]]; then
  echo "Parsed zero variables out of $VARIABLES. That is a broken parser, not an empty file." >&2
  exit 1
fi
if [[ -z "$exampled" ]]; then
  echo "Parsed zero assignments out of $EXAMPLE. That is a broken parser, not an empty file." >&2
  exit 1
fi

missing="$(LC_ALL=C comm -23 <(printf '%s\n' "$declared") <(printf '%s\n' "$exampled"))"
extra="$(LC_ALL=C comm -13 <(printf '%s\n' "$declared") <(printf '%s\n' "$exampled"))"

if [[ -z "$missing" && -z "$extra" ]]; then
  echo "terraform.tfvars.example matches variables.tf: all $(printf '%s\n' "$declared" | wc -l | tr -d ' ') declared variables present, none extra."
  exit 0
fi

echo "terraform.tfvars.example has drifted from variables.tf." >&2
echo >&2

if [[ -n "$missing" ]]; then
  echo "Declared in $VARIABLES but missing from the example:" >&2
  while IFS= read -r v; do echo "  $v" >&2; done <<< "$missing"
  echo >&2
  echo "  A new environment stood up from this file would be silently unconfigured for these." >&2
  echo "  Add each one, with the line of explanation the neighbouring entries carry." >&2
  echo >&2
fi

if [[ -n "$extra" ]]; then
  echo "In the example but not declared in $VARIABLES:" >&2
  while IFS= read -r v; do echo "  $v" >&2; done <<< "$extra"
  echo >&2
  echo "  terraform will reject these at the first step, with a message that reads like the" >&2
  echo "  operator mistyped something. Delete them, or declare them." >&2
  echo >&2
fi

exit 1
