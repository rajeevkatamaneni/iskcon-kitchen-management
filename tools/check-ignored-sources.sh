#!/usr/bin/env bash
#
# Refuse a source file that git has been told to ignore.
#
# An ignore rule over source fails in the worst possible way: silently on the machine that wrote
# the file, and fatally on every other checkout. It happened here on 2026-08-19. `.gitignore`
# carries `**/aadhaar*` to keep a real Aadhaar card out of the repo, and the pattern cannot tell a
# document from a class, so it swallowed `AadhaarIdentity.java`. The backend compiled locally for
# two days and could not compile anywhere else; CI only said so once the commits were pushed.
#
# So this is the check that would have caught it on the first commit rather than the nineteenth.
# It looks only where source lives, and it reports the rule that did the swallowing, because
# "a file is missing" is a much harder thing to act on than "line 86 ate it".
set -euo pipefail

cd "$(dirname "$0")/.."

# Where source lives. Build output, dependencies and generated files are ignored on purpose and
# are not source, so they are not looked at.
readonly TREES=(
  "backend/src"
  "frontend/app"
  "frontend/components"
  "frontend/lib"
  "frontend/__tests__"
  "infra"
)

offenders="$(git ls-files --others --ignored --exclude-standard -- "${TREES[@]}" \
  | grep -E '\.(java|kt|ts|tsx|js|jsx|sql|tf|sh)$' || true)"

if [[ -z "$offenders" ]]; then
  echo "No ignored source files. Every source file under ${#TREES[@]} trees is in git."
  exit 0
fi

echo "Source files that git has been told to ignore:" >&2
echo >&2
while IFS= read -r file; do
  echo "  $file" >&2
  echo "      ignored by $(git check-ignore -v -- "$file" | cut -f1)" >&2
done <<< "$offenders"
echo >&2
echo "These compile on the machine that wrote them and nowhere else. Either commit them, or" >&2
echo "narrow the rule so it stops matching source — see the identity-document block in" >&2
echo ".gitignore for how that was done last time." >&2
exit 1
