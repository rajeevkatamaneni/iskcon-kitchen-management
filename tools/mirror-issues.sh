#!/usr/bin/env bash
#
# Copy every issue from one GitHub repository to another, with its labels, milestone and state.
#
#   tools/mirror-issues.sh <source-owner/repo> <target-owner/repo> [--dry-run]
#
# Written 2026-09-22, when the code was synced to the company org and the stories and UAT tests did
# not arrive with it. They never could have: **GitHub Issues are not part of a git repository.** A
# sync, a mirror, a fork or `git push --mirror` copies commits, branches and tags and nothing else —
# not issues, labels, milestones, projects or the wiki. The 120 issues (59 `story`, 61 `uat`) stayed
# on the personal repo while all 168 files under docs/stories/ and docs/uat/ travelled with the code,
# which is why the markdown was there and the tracker was empty.
#
# Why this rather than docs/stories/github-import/import.sh: that script creates issues from body
# files it holds, and it holds 55 of them — the first 55 stories, nothing since E1-S12, and no UAT
# at all. It would rebuild a third of the tracker and silently drop the rest. This reads the source
# repository itself, so whatever is there is what arrives.
#
# What it does NOT copy, deliberately:
#   - comments. They carry @mentions and cross-references that would resolve to the wrong place in
#     a new org, and notify people about work they cannot see. Say so if you need them.
#   - assignees. The accounts may not exist in the target org.
#   - issue numbers. GitHub assigns its own. A closed issue is created then closed, so the numbers
#     stay dense and in order — but #64 here may not be #64 there, and docs/uat/TRACEABILITY.md
#     states an issue-number rule ("63 + the test number") that will need re-checking afterwards.
#
# It is safe to re-run: an issue whose exact title already exists in the target is skipped, so a run
# interrupted half way can simply be run again.

set -euo pipefail

SOURCE="${1:-}"
TARGET="${2:-}"
DRY_RUN="${3:-}"

if [[ -z "$SOURCE" || -z "$TARGET" ]]; then
	echo "Usage: tools/mirror-issues.sh <source-owner/repo> <target-owner/repo> [--dry-run]" >&2
	echo "   eg: tools/mirror-issues.sh rajeevkatamaneni/iskcon-kitchen-management SuperPi-Labs/iskcon-kitchen-management" >&2
	exit 2
fi

command -v gh >/dev/null || { echo "The GitHub CLI (gh) is not installed." >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is not installed: brew install jq" >&2; exit 1; }

# Fail here rather than half way through, with the reason spelled out: a private repo in an org with
# SSO needs the token authorised for that org, which is not the same as being a member of it.
for repo in "$SOURCE" "$TARGET"; do
	if ! gh repo view "$repo" --json name >/dev/null 2>&1; then
		echo "Cannot see $repo." >&2
		echo "  If it exists and is private, the token needs authorising for its organisation:" >&2
		echo "    gh auth refresh -h github.com -s repo" >&2
		echo "  and then 'Configure SSO' against the token at https://github.com/settings/tokens" >&2
		exit 1
	fi
done

say() { printf '%s\n' "$*"; }
run() { if [[ "$DRY_RUN" == "--dry-run" ]]; then say "    would: $*"; else "$@" >/dev/null; fi; }

# ---------------------------------------------------------------------------------------------
# 1. Labels. Created first, because an issue cannot be given a label that does not exist, and
#    `gh issue create --label` fails the whole call rather than skipping the unknown one.
# ---------------------------------------------------------------------------------------------
say "== Labels =="
gh label list --repo "$SOURCE" --limit 200 --json name,color,description \
	| jq -c '.[]' \
	| while read -r label; do
		name=$(jq -r '.name' <<<"$label")
		color=$(jq -r '.color' <<<"$label")
		desc=$(jq -r '.description // ""' <<<"$label")
		say "  $name"
		run gh label create "$name" --repo "$TARGET" --color "$color" --description "$desc" --force
	done

# ---------------------------------------------------------------------------------------------
# 2. Milestones. One per epic in this repository. Matched by title afterwards, because the target's
#    numbering is its own.
# ---------------------------------------------------------------------------------------------
say "== Milestones =="
gh api "repos/$SOURCE/milestones?state=all&per_page=100" --jq '.[] | @base64' \
	| while read -r row; do
		milestone=$(base64 --decode <<<"$row")
		title=$(jq -r '.title' <<<"$milestone")
		desc=$(jq -r '.description // ""' <<<"$milestone")
		state=$(jq -r '.state' <<<"$milestone")
		if gh api "repos/$TARGET/milestones?state=all&per_page=100" --jq '.[].title' 2>/dev/null | grep -qxF "$title"; then
			say "  $title (already there)"
			continue
		fi
		say "  $title"
		run gh api "repos/$TARGET/milestones" -f title="$title" -f description="$desc" -f state="$state"
	done

# ---------------------------------------------------------------------------------------------
# 3. Issues, oldest first so the numbering comes out in the same order as the source.
# ---------------------------------------------------------------------------------------------
say "== Issues =="

existing=$(gh issue list --repo "$TARGET" --limit 1000 --state all --json title --jq '.[].title' 2>/dev/null || true)

created=0
skipped=0
closed=0

# `--limit 1000` rather than the default 30, and `--state all` so closed work comes across too: a
# closed story is part of the record of what was built.
gh issue list --repo "$SOURCE" --limit 1000 --state all \
	--json number,title,body,labels,milestone,state \
	| jq -c 'sort_by(.number) | .[]' \
	| while read -r issue; do
		number=$(jq -r '.number' <<<"$issue")
		title=$(jq -r '.title' <<<"$issue")
		body=$(jq -r '.body // ""' <<<"$issue")
		state=$(jq -r '.state' <<<"$issue")
		milestone=$(jq -r '.milestone.title // ""' <<<"$issue")

		if grep -qxF "$title" <<<"$existing"; then
			say "  #$number  $title  — already there, skipped"
			skipped=$((skipped + 1))
			continue
		fi

		args=(--repo "$TARGET" --title "$title" --body "$body")
		while read -r label; do
			[[ -n "$label" ]] && args+=(--label "$label")
		done < <(jq -r '.labels[].name' <<<"$issue")
		[[ -n "$milestone" ]] && args+=(--milestone "$milestone")

		say "  #$number  $title  [$state]"
		if [[ "$DRY_RUN" == "--dry-run" ]]; then
			say "    would: gh issue create ${args[*]}"
			continue
		fi

		url=$(gh issue create "${args[@]}")
		created=$((created + 1))

		# Closed in the source, so closed here. Created open first because GitHub has no way to
		# create an issue closed, and the pair of calls is what keeps the record honest.
		if [[ "$state" == "CLOSED" ]]; then
			gh issue close "$url" >/dev/null
			closed=$((closed + 1))
		fi

		# GitHub's secondary rate limit trips on rapid content creation and answers 403, which reads
		# like a permission problem and is not one. A second between writes keeps well clear.
		sleep 1
	done

say ""
say "Done. Check the target's tracker, then re-read docs/uat/TRACEABILITY.md §1 — it states that a"
say "UAT test's issue number is 63 + the test number, and that will not hold on a fresh repository."
