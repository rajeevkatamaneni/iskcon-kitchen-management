# Recipe reference data

Source artifacts for recipe/ingredient seed data and tests (Epic 2). These are
the temple's real working documents, kept here for provenance so the seed data
is traceable to a real source rather than invented.

Drop files here:

- **`RM 2019_v2.xlsx`** — the original recipe master the temple currently uses
  (from the Pune temple). The validated model for structure: per-recipe yield,
  ingredient rows with base + scaled quantities, category sheets incl. Ekadashi.
- **`Karnataka_Temple_Recipes.pdf`** — the Bangalore-localized recipe list
  derived from RM 2019 (Pune food differs from Bangalore food). **This is the
  authoritative source for seed data and tests**, per Rajeev: the app should
  ship menus tailored to the serving location, not a generic list.

Provenance note (2026-08-10): the Bangalore PDF and the scripts that generated
it currently live in the sibling folder `~/Workspace/recipes-experiment/`
(karnataka_recipes*.py, build_pdf.py). The `.py` files hold the recipe data as
structured Python and are the cleanest machine-readable source.
