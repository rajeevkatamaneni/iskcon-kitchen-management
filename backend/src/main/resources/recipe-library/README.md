# The shared recipe library — source data

The 32 JSON files beside this one are the input to the recipe-library loader (E2-S9). They are
**vendored**: committed here rather than fetched at deploy time, because a loader that reaches
across to another repository during a deployment is a deployment that can fail on somebody else's
branch name, rename or outage.

## Provenance

| | |
|---|---|
| Repository | `github.com/kranthimj23/ikms` — ours, written by a teammate |
| Branch | `ikms-rbac-role-based-access-control` |
| Commit | `41cf173ae8897f3697489cb22ce3444b74dd0229` (2026-08-19) |
| Path there | `ikms/data/recipe_books/` |
| Vendored | 2026-08-21, byte-for-byte |

Each row in `master_recipes` also carries its own `source_ref`, so a single recipe can be traced
back to the file and commit it came from without consulting this note.

## Why the files are verbatim and not pre-stripped

The brief asks for the local-language translations to be stripped, and they are — **on the way into
the database, by the loader**, never here. A vendored copy that has already been transformed cannot
be diffed against upstream, so when the books are next updated there would be no way to see what
actually changed. Transformation belongs in code that can be read and tested; the checked-in copy
stays a faithful copy.

The cost is 23 MB rather than 9 MB, once.

## What is in them

32 state books, 168 recipes each — **5,376 recipes**, 21 categories x 8 dishes per state, in 16
languages. Every recipe carries: name, subtitle, category, badge, yield, per-head portion, indicative
cost, ingredients, method, and a "why", each with a translation alongside. 2,016 of them add region,
tags, serve-with and start/vessel/season notes.

Full schema, field coverage and the parsing rules the loader depends on are in
`docs/stories/EPIC-2-recipe-library-DESIGN.md` §1.

## Updating them

Re-copy from the same path upstream, update the commit above, and re-run the loader — it upserts on
`(state_slug, recipe_slug)`, so a re-run updates in place and never duplicates. A temple's own copies
are untouched by a reload: they are independent rows, and an edit a temple has made to one is theirs
(design doc §4).
