# The shared recipe library — source data

The two JSON files beside this one are the input to the recipe-library loader (E2-S9):
`karnataka.json` (42 recipes) and `andhra_pradesh.json` (2). **44 recipes, 454 ingredient lines.**

**They are generated. Never hand-edit them.**

## Where they come from

Rajeev approved each of these 44 recipes by hand on 2026-09-19, one at a time, after deciding the
mass import that preceded them had been a mistake:

> It was a BAD idea to mass import that many recipes without vetting each first.

His approvals are one file per recipe in `docs/work/reference/curated-recipes/` — the output of his
curation tool, and **gitignored**, so a fresh clone will not have them.
`tools/seed/02b-build-catalogue.mjs` turns those per-recipe files into the per-book files here.

So the editing source of truth is **his curated files plus that converter**, not this directory. An
edit made here is lost the next time the converter runs, and it is not reviewed by anybody.

## Regenerating them

```bash
node tools/seed/02b-build-catalogue.mjs
```

It rewrites this directory from `docs/work/reference/curated-recipes/`. Then run the loader: it
upserts on `(state_slug, recipe_slug)`, so a re-run updates in place and never duplicates. A
temple's own copies are untouched by a reload — they are independent rows, and an edit a temple has
made to one is theirs (design doc §4).

One recipe is deliberately absent: `karnataka__halubai.json` is marked `needs-work` and the
converter leaves it out until Rajeev says otherwise.

## What the files carry that the old ones did not

Two per-line fields, both added because his curation states them and no vendored book ever did:

- `prep` — what the cook does to that line. 83 lines have one, 26 distinct values, "Grated" 31
  times. The books used to bury this inside the ingredient name after a comma ("Green chilli,
  slit"); his recipes name the ingredient plainly and put the preparation in its own field.
- `not_bought` — the temple never buys this at all. 15 lines, every one of them water. An
  ingredient the import creates from such a line is created never-bought, which is what keeps water
  off every shopping list instead of being unticked on each one.

The local-language translations are still stripped **on the way into the database, by the loader**,
never here.

## History: where the recipes originally came from

Until 2026-09-19 this directory held **32 state books vendored from another repository** — 5,376
recipes, 168 per book, none of them vetted. That import is where the recipes Rajeev then curated
were originally drawn from, so the provenance is kept here rather than lost:

| | |
|---|---|
| Repository | `github.com/kranthimj23/ikms` — ours, written by a teammate |
| Branch | `ikms-rbac-role-based-access-control` |
| Commit | `41cf173ae8897f3697489cb22ce3444b74dd0229` (2026-08-19) |
| Path there | `ikms/data/recipe_books/` |
| Vendored | 2026-08-21, byte-for-byte |
| Deleted | 2026-09-19, replaced by the curated catalogue |

Those files were verbatim copies so they could be diffed against upstream. That reason is gone with
them: nothing here tracks that repository any more.

Every row in `master_recipes` also carries its own `source_ref`, so a single recipe can be traced
back to the file it came from without consulting this note.

The file schema and the parsing rules the loader depends on are in
`docs/stories/EPIC-2-recipe-library-DESIGN.md` §1. **Its counts are the vendored ones and are now
wrong** — it still says 5,376 recipes in 32 books — but the field-by-field description still
describes these files, because the converter writes the same shape.
