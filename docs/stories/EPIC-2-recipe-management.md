# EPIC 2 — Recipe Management

**Goal:** Ingredient master with sattvic enforcement, recipes with yield-based scaling (modeled on the temple's real RM 2019 workbook), PDF/print output, and Indian-language translation.
**Depends on:** Epic 1. **Blocks:** Epics 3–5 (ingredients/recipes are upstream of inventory consumption, meal planning, ordering).
**Labels:** `epic:recipes`

**Reference material:** `RM 2019_v2.xlsx` in the project folder — the temple's real recipe master. Its structure (per-recipe yield in litres/servings, ingredient rows with base and scaled quantity columns, category sheets incl. a dedicated Ekadashi category) is the validated model to follow, not a hypothetical.

---

## E2-S1 — Ingredient master

**Verified by:** [UAT-013](../uat/UAT-013-build-the-ingredient-list.md), [UAT-014](../uat/UAT-014-the-prohibited-flag-is-admin-only.md)

**As a** Kitchen Staff member, **I want** a single ingredient catalog with units and compliance flags, **so that** recipes, inventory, and orders all speak the same ingredient language.

**Assumptions:** Ingredients are tenant-scoped (each temple curates its own; no global shared catalog in release 1 — revisit if duplication hurts). Units: Kg, gm, L, ml, pieces (per RM 2019 and the prior proposal's data-cleanup guidance).

**Requirements:**
- CRUD: name, category, canonical unit, `is_sattvic_prohibited` flag (onion, garlic, mushroom, egg, and admin-extendable list), optional aliases (handles "Rice" vs "Raw Rice" dedup guidance from the prior proposal).
- Seed list: common prohibited items pre-flagged on tenant provisioning.
- Only TEMPLE_ADMIN can change the prohibited flag; change writes an audit event.
- Search/typeahead endpoint for recipe and inventory pickers.

**Acceptance criteria:**
- [ ] New tenant starts with prohibited seed list in place.
- [ ] KITCHEN_STAFF cannot alter the prohibited flag (403 + audit-relevant log); TEMPLE_ADMIN can, and the change is audited.
- [ ] Duplicate-name creation warns (alias suggestion) but can proceed with distinct name.
- [ ] Typeahead returns matches on name and alias within 300ms at 2k ingredients (seeded test).

---

## E2-S2 — Recipe CRUD

**Verified by:** [UAT-015](../uat/UAT-015-write-a-recipe.md), [UAT-016](../uat/UAT-016-find-a-recipe.md)

**As a** Kitchen Staff member, **I want** to create and organize recipes with ingredients and a base yield, **so that** institutional cooking knowledge lives in the system, not in one cook's head.

**Assumptions:** Recipe = name, category (Beverages, Breakfast, Dal, Rice, Sweets, Ekadashi, etc. — seeded from RM 2019's sheet list, admin-editable), base yield (numeric + unit: servings or litres, per RM 2019 both occur), ingredient lines (ingredient ref, quantity, unit), method steps (rich text), optional notes/region tag (RM 2019 tags e.g. "Gujarati").

**Requirements:**
- CRUD with validation: ≥1 ingredient line, base yield required, quantities positive.
- Category management (tenant-scoped list, seeded).
- Soft delete/archive (recipes referenced by meal plans must not hard-delete).
- List + search by name, category, ingredient ("what can we make with X").

**Acceptance criteria:**
- [ ] Full CRUD works for all roles with recipe permissions; VOLUNTEER/donor roles have no access.
- [ ] A recipe used by any meal plan archives instead of deleting, and stays renderable in history.
- [ ] Search by contained ingredient returns correct recipes.
- [ ] Ekadashi category exists out of the box and is flagged as fasting-compatible (consumed by E4).

---

## E2-S3 — Recipe scaling

**Verified by:** [UAT-017](../uat/UAT-017-scale-a-recipe.md)

**As a** cook, **I want** any recipe rescaled to a target yield, **so that** a 100-serving recipe becomes a 3,000-serving festival batch without manual arithmetic.

**Assumptions:** Linear scaling by ratio (target/base), matching exactly how RM 2019's scaled-quantity column works. Non-linear culinary adjustments (spice curves) are explicitly out of scope for release 1 — cooks adjust by judgment; a note field per recipe can carry guidance.

**Requirements:**
- Scaling endpoint + UI: enter target yield → all ingredient lines scaled, sensible unit presentation (24,000 gm → 24 Kg).
- No stored copy per scale: scaling is computed on demand; scaled views are inputs to sufficiency checks (E4) and order generation (E5).
- Rounding rules documented and consistent (e.g. 2 significant decimals, with raw value available to downstream calculations unrounded).
- No upper bound that would break festival scale (validated to 50,000 servings).

**Acceptance criteria:**
- [ ] Scaling a seeded RM 2019 recipe (e.g. Aam Ras: base 100 → target 40) reproduces the workbook's scaled quantities.
- [ ] Unit promotion works (gm→Kg, ml→L) at display layer while raw values flow to downstream consumers unrounded.
- [ ] 50,000-serving scale computes correctly with no overflow/precision loss.

---

## E2-S4 — Sattvic enforcement on recipes

**Verified by:** [UAT-014](../uat/UAT-014-the-prohibited-flag-is-admin-only.md), [UAT-018](../uat/UAT-018-sattvic-block-and-override.md)

**As a** Temple Admin, **I want** prohibited ingredients to hard-block recipe saves with an admin-only audited override, **so that** a religious compliance failure can't happen by accident.

**Assumptions:** Locked decision: hard-block; override = TEMPLE_ADMIN only, with mandatory reason, audited. (PO-side enforcement is E5's story, same rule.)

**Requirements:**
- Recipe create/update containing a flagged ingredient is rejected with a clear, non-technical message naming the ingredient.
- Override path: TEMPLE_ADMIN supplies reason → save proceeds → audit event (actor, recipe, ingredient, reason).
- Overridden recipes visibly badged in list and detail views.
- Enforcement lives in the service layer (one place), not UI-only.

**Acceptance criteria:**
- [ ] KITCHEN_STAFF cannot save a recipe containing garlic under any path, including direct API calls.
- [ ] TEMPLE_ADMIN override with reason succeeds; audit event contains all required fields; recipe shows override badge.
- [ ] Removing the prohibited ingredient clears the badge on next save.

---

## E2-S5 — Recipe PDF and print (English)

**Verified by:** [UAT-019](../uat/UAT-019-print-and-download-a-recipe.md)

**As a** cook, **I want** a clean printed recipe card at any scale, **so that** the kitchen works from paper, not a phone over a hot stove.

**Assumptions:** Playwright/Chromium PDF pipeline per TECH_STACK.md; server-rendered print template; generation runs as a background job with the file landing in GCS (signed URL), plus a direct browser print view for instant use.

**Requirements:**
- Print-view route (clean CSS, no navigation) usable with browser print directly.
- "Download PDF" queues a job → GCS → signed URL returned; UI shows pending → ready.
- Template: temple name/logo, recipe name, yield (base or scaled — user picks scale first), ingredient table, method, generated-on date.
- Noto fonts bundled in worker image (groundwork for E2-S6; verify English + Devanagari rendering now).

**Acceptance criteria:**
- [ ] PDF of a scaled recipe matches the on-screen scale and renders correctly (spot-check ingredient table integrity across page breaks).
- [ ] Signed URL expires (config, default 7 days); regeneration works.
- [ ] Browser print view produces a sane A4 layout without manual tweaks.
- [ ] A test string in Devanagari renders correctly in the generated PDF (font pipeline proven before E2-S6 builds on it).

---

## E2-S6 — Recipe translation + translated PDF/print

**Verified by:** [UAT-020](../uat/UAT-020-translate-a-recipe.md), [UAT-021](../uat/UAT-021-the-translation-glossary.md)

**As a** Kitchen Staff member, **I want** a recipe translated into a chosen Indian language and printed/downloaded, **so that** cooks who don't read English can work from it.

**Assumptions:** Bhashini primary behind an internal translation interface, Google Cloud Translation fallback, per TECH_STACK.md. Translations cached per (recipe version, language) per SYSTEM_DESIGN.md §6. Language list: the tenant configures which languages it needs (from Bhashini's supported set).

**Requirements:**
- "Translate" action: pick language → background job → translated recipe stored (structured: ingredient lines + steps translated; quantities/units untouched).
- Ingredient-name glossary: tenant-level override table (English term → preferred translation) consulted before MT, because culinary vocabulary is where MT fails (locked caution in REQUIREMENTS.md). Admin screen to maintain glossary.
- Translated PDF/print reuses E2-S5 pipeline with the correct Noto font per script.
- Cache invalidates when the recipe is edited (recipe version bump).

**Acceptance criteria:**
- [ ] Hindi and one Dravidian-script language (e.g. Telugu) translate end-to-end and render correct PDFs — verified visually by a reader of each script during UAT (flag for test plan).
- [ ] Glossary override beats MT output for a seeded term (e.g. "Toor Dal" stays "तूर दाल" not a mistranslation).
- [ ] Editing the recipe invalidates cached translations; stale PDFs are no longer offered.
- [ ] Bhashini outage → fallback provider used → provenance recorded on the translation record.

---

## E2-S7 — Recipe browse and search UX

**Verified by:** [UAT-016](../uat/UAT-016-find-a-recipe.md)

**As a** Kitchen Staff member, **I want** fast browse/search across recipes on a mid-range phone, **so that** finding "that Ekadashi kheer recipe" takes seconds during prep.

**Assumptions:** Consolidates list/search UX debt from E2-S2 into a deliberate screen per the approved wireframe's Recipes tab; respects <200KB JS budget.

**Requirements:**
- Filterable list: category chips, text search (name/ingredient/region tag), sort by name/recently used.
- Recipe detail: yield, scale control inline, badges (sattvic override, Ekadashi-compatible), actions (print, PDF, translate).
- Mobile-first layout; list virtualized if needed at 500+ recipes.

**Acceptance criteria:**
- [ ] Search-to-result under 1s on seeded 500-recipe tenant (staging, mid-tier device profile).
- [ ] All actions reachable and usable at 360px viewport width.
- [ ] Lighthouse performance ≥85 on the recipe list page under mobile throttling.

---

## E2-S8 — Removing a recipe

**Verified by:** automated cover in `RecipeIT` (`neverCookedIsDeletedOutright`,
`cookedRecipeCannotBeDeleted`, `archiveCanBeRestored`) and `recipe-detail.test.tsx`. UAT to be
written.

**As a** Kitchen Staff member, **I want** to get rid of a recipe I should not have created, **so
that** the list I plan meals from is the list the temple actually cooks.

**Assumptions:** An archive endpoint and its client function had existed since E2-S2 and were reached
by no screen at all, so in practice a recipe could not be removed by any route. `meal_plans.recipe_id`
is `ON DELETE RESTRICT`; `recipe_ingredients`, `recipe_translations` and `documents` all cascade.

**Requirements:**
- A recipe **no meal plan has ever named** is deleted outright, taking its ingredient lines, its
  translations and any generated cards with it.
- A recipe that **has been planned or cooked** is refused with `KMS-4967`, whose next step is to
  archive it. The screen carries that through to a button rather than leaving the person at a
  refusal.
- Archiving is reversible. An archived recipe says so, keeps its history, stays off the planner, and
  offers Restore in place of Delete.
- The recipe list can show archived recipes, so there is a route back to one.
- Deleting asks first, and says plainly that it cannot be undone.
- Every one of the three acts is audited: `RECIPE_DELETED` (with the recipe's shape as the
  before-state and a null after-state), `RECIPE_ARCHIVED`, `RECIPE_RESTORED`.

**Acceptance criteria:**
- [x] A recipe created and never planned is deleted, and its `recipe_ingredients` rows go with it.
- [x] A recipe on a meal plan is refused with `KMS-4967` and stays `ACTIVE`; archiving it then works.
- [x] An archived recipe is absent from the default list, present with `includeArchived`, still
      fetchable by id, and restorable to `ACTIVE`.
- [x] The delete confirmation names the recipe and warns it cannot be undone.

**Decisions:**
- **D1 — Two acts, not one, and the system chooses.** "Delete" means two different things: removing
  rubbish, and retiring something real. Asking the user which they meant would put the integrity of
  the temple's own history behind a dropdown. The presence of a meal plan decides it, because that is
  exactly the fact that makes the difference.
- **D2 — Cascading the recipe's own belongings is right.** Ingredient lines, translations and
  generated cards mean nothing without the recipe, and a card can be produced again from a recipe
  that still exists. Nothing that outlives the recipe is deleted with it.
- **D3 — `DELETE` now deletes.** The verb used to archive, which is the kind of mismatch that gets
  discovered by somebody expecting the other behaviour. Archiving moved to
  `POST /{id}/archive`, a URL that says what it is. Nothing called the old endpoint, so there was
  no cost to correcting it.
- **D4 — Archiving had to become reversible before delete could fall back to it.** Sending somebody
  from a refusal into a one-way door would be worse than the refusal.
