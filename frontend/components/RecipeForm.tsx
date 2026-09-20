"use client";

import { useState } from "react";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Button } from "@/components/ds/Button";
import { Form } from "@/components/ds/Form";
import { HintedField } from "@/components/ds/InfoHint";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { api, type ApiError, type RecipeDetail, type RecipeInput } from "@/lib/api";
import { useAuthedQuery } from "@/lib/use-authed-query";
import {
  FOOD_UNITS,
  RECIPE_MEASURES,
  convertQuantity,
  cooksQuantity,
  portionUnitsFor,
  stepForUnit,
  unitLabel,
} from "@/lib/format";

const BADGES = ["Everyday", "Moderate", "Festival", "Sustainable", "Economical"];

/** A comma-separated box as a list, with the empties dropped and the spaces trimmed. */
function splitList(value: string): string[] {
  return value
    .split(",")
    .map((v) => v.trim())
    .filter(Boolean);
}

/**
 * Below this many people, and above this many, one batch is probably a slip of the unit (T-218).
 *
 * <p>Rajeev's figures. The recipe that prompted them was saved as 270 L with a portion of 350 ml —
 * 771 people, which is plausible — but the same form would have taken 270 ml and said nothing, and
 * that feeds fewer than one. A warning and not a refusal: a festival batch for 8,000 is real, and a
 * chutney for three is real, and the cook knows which one they are writing down.
 */
const FEWEST_PEOPLE = 5;
const MOST_PEOPLE = 5_000;

/**
 * How many people the recipe as typed would feed, or null while there is not enough to say.
 *
 * <p>The portion is converted into the recipe's own unit with `convertQuantity` — the one table of
 * what a litre is — before dividing, which is the whole point: 270 L ÷ 350 ml is 771 people, and
 * 270 ÷ 350 read as if both were litres is 0.77 of one. Null for a pair the table cannot convert,
 * which the family lock below makes unreachable on this form and a stale saved recipe could still
 * hold; saying nothing is better than a number built on a guess.
 *
 * <p>Rounded to a whole person, and the warning is judged on the rounded figure, so the sentence and
 * the amber box beneath it can never disagree about the number.
 */
function peopleFed(yieldQty: string, yieldUnit: string, portionQty: string, portionUnit: string): number | null {
  const made = Number(yieldQty);
  const each = Number(portionQty);
  if (!yieldQty.trim() || !portionQty.trim() || !yieldUnit || !portionUnit) return null;
  if (!Number.isFinite(made) || !Number.isFinite(each) || made <= 0 || each <= 0) return null;
  const eachInYieldUnit = convertQuantity(each, portionUnit, yieldUnit);
  if (eachInYieldUnit == null || eachInYieldUnit <= 0) return null;
  return Math.round(made / eachInYieldUnit);
}

interface Line {
  ingredientId: string;
  quantity: string;
  unit: string;
  /** How it is prepared for this recipe — "slit", "halved" (R-DUP-1). Empty means none. */
  preparationNote: string;
}

/**
 * The recipe create/edit form (E2). Presentational: it collects and validates enough to build a
 * RecipeInput, then hands it to the parent, which owns the API call, navigation, and error.
 */
export function RecipeForm({
  initial,
  formId,
  busy,
  error,
  onSubmit,
}: {
  initial?: RecipeDetail;
  /**
   * The id the screen's own commit button points at with `form={formId}`.
   *
   * <p>This form has no button of its own. Both screens that use it are focus screens, and rule 6
   * of that pattern is one place to commit — the sticky header, where the name of what is being
   * edited is still on screen. A second copy at the foot would be two answers to "where do I press".
   */
  formId: string;
  busy: boolean;
  error: ApiError | null;
  onSubmit: (input: RecipeInput) => void;
}) {
  const categories = useAuthedQuery(api.listRecipeCategories);
  const ingredients = useAuthedQuery(api.listIngredients);

  const [name, setName] = useState(initial?.name ?? "");
  const [categoryId, setCategoryId] = useState(initial?.categoryId ?? "");
  const [baseYieldQty, setBaseYieldQty] = useState(initial ? String(initial.baseYieldQty) : "100");
  const [baseYieldUnit, setBaseYieldUnit] = useState(initial?.baseYieldUnit ?? "KG");
  const [method, setMethod] = useState(initial?.method ?? "");
  const [notes, setNotes] = useState(initial?.notes ?? "");
  const [regionTag, setRegionTag] = useState(initial?.regionTag ?? "");
  const [yieldNote, setYieldNote] = useState(initial?.yieldNote ?? "");
  const [perHeadQty, setPerHeadQty] = useState(initial?.perHeadQty != null ? String(initial.perHeadQty) : "");
  const [perHeadUnit, setPerHeadUnit] = useState(initial?.perHeadUnit ?? "");
  const [subtitle, setSubtitle] = useState(initial?.subtitle ?? "");
  const [badge, setBadge] = useState(initial?.badge ?? "");
  const [indicativeCost, setIndicativeCost] = useState(
    initial?.indicativeCost != null ? String(initial.indicativeCost) : ""
  );
  const [why, setWhy] = useState(initial?.why ?? "");
  // Catering was taken out of the product (E4-S15), so the "Catering note" field went from this form
  // on 2026-09-18 at Rajeev's say. The column and the API field stay, and whatever a recipe already
  // holds is carried through a save untouched: the value is kept here, just no longer shown or edited.
  const [cateringNote] = useState(initial?.cateringNote ?? "");
  const [subRegion, setSubRegion] = useState(initial?.subRegion ?? "");
  const [noteStart, setNoteStart] = useState(initial?.noteStart ?? "");
  const [noteVessel, setNoteVessel] = useState(initial?.noteVessel ?? "");
  const [noteSeason, setNoteSeason] = useState(initial?.noteSeason ?? "");
  const [tags, setTags] = useState((initial?.tags ?? []).join(", "));
  const [serveWith, setServeWith] = useState((initial?.serveWith ?? []).join(", "));
  const [lines, setLines] = useState<Line[]>(
    initial
      ? initial.ingredients.map((l) => ({
          ingredientId: l.ingredientId,
          quantity: String(l.quantity),
          unit: l.unit,
          preparationNote: l.preparationNote ?? "",
        }))
      : [{ ingredientId: "", quantity: "", unit: "KG", preparationNote: "" }]
  );

  /*
    "Measured in" offers kilos, litres and pieces (T-218). A recipe saved in grams or millilitres
    before that rule keeps its own unit on the list, so opening it and pressing Save changes nothing
    it did not ask to change — the rule is about what a new recipe is written in, not a migration of
    old ones by the back door. None exist on staging or in the library today; this is for the one
    that might.
  */
  const measures = RECIPE_MEASURES.includes(baseYieldUnit) ? RECIPE_MEASURES : [...RECIPE_MEASURES, baseYieldUnit];
  const portionUnits = portionUnitsFor(baseYieldUnit);

  /*
    Changing what the recipe is measured in clears a portion unit that no longer fits it, and only
    the unit: the number the cook typed is still what one person eats, and they choose ml or L for it
    again. Leaving "ml" on a recipe now in kilos would be a pair the server refuses and the planner
    cannot convert — T-217's thousandfold error in a new costume.
  */
  function changeMeasure(next: string) {
    setBaseYieldUnit(next);
    if (perHeadUnit && !portionUnitsFor(next).includes(perHeadUnit)) setPerHeadUnit("");
  }

  const feeds = peopleFed(baseYieldQty, baseYieldUnit, perHeadQty, perHeadUnit);
  // The batch the rough cost is for, or null while "This recipe makes" is empty or not a size.
  const batchSize = Number(baseYieldQty) > 0 ? cooksQuantity(Number(baseYieldQty), baseYieldUnit) : null;

  function setLine(index: number, patch: Partial<Line>) {
    setLines((prev) => prev.map((l, i) => (i === index ? { ...l, ...patch } : l)));
  }
  function addLine() {
    setLines((prev) => [...prev, { ingredientId: "", quantity: "", unit: "KG", preparationNote: "" }]);
  }
  function removeLine(index: number) {
    setLines((prev) => prev.filter((_, i) => i !== index));
  }

  function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    onSubmit({
      name: name.trim(),
      categoryId,
      baseYieldQty: Number(baseYieldQty),
      baseYieldUnit,
      method: method.trim() || undefined,
      notes: notes.trim() || undefined,
      regionTag: regionTag.trim() || undefined,
      yieldNote: yieldNote.trim() || undefined,
      // Both or neither: a portion with no unit is a number nobody can act on, and the database
      // says so too.
      perHeadQty: perHeadQty.trim() && perHeadUnit ? Number(perHeadQty) : undefined,
      perHeadUnit: perHeadQty.trim() && perHeadUnit ? perHeadUnit : undefined,
      subtitle: subtitle.trim() || undefined,
      badge: badge || undefined,
      indicativeCost: indicativeCost.trim() ? Number(indicativeCost) : undefined,
      why: why.trim() || undefined,
      cateringNote: cateringNote.trim() || undefined,
      subRegion: subRegion.trim() || undefined,
      noteStart: noteStart.trim() || undefined,
      noteVessel: noteVessel.trim() || undefined,
      noteSeason: noteSeason.trim() || undefined,
      tags: splitList(tags),
      serveWith: splitList(serveWith),
      ingredients: lines
        .filter((l) => l.ingredientId && l.quantity)
        // A blank note is left out, which the server stores as null: "no note", never a note of spaces.
        .map((l) => ({
          ingredientId: l.ingredientId,
          quantity: Number(l.quantity),
          unit: l.unit,
          preparationNote: l.preparationNote.trim() || undefined,
        })),
    });
  }

  /*
    Supplies are not offered here, and this is the only picker in the application that filters.

    D-1 put LPG, leaf plates, dishwashing liquid and hand soap on the ingredient catalogue rather
    than in a table of their own, because they are bought, received, stored and used up exactly as
    food is. A recipe is the single place the two part company: a mop is not an ingredient of
    anything. Inventory, ingredient requests, purchase orders, in-kind donations and a vendor's
    supply list all keep showing them on purpose — filtering everywhere would undo the reason the
    flag exists.

    Filtered here rather than by a query parameter: `api.listIngredients` is passed as a bare
    function reference to `useAuthedQuery` at five call sites and wrapped at two more, and its only
    parameter is the token. A leading filter argument would silently bind the token to the wrong
    parameter at every one of them. The list is a temple's ingredient catalogue — hundreds of rows,
    not thousands — and it is already fetched whole for the units lookup below.

    The client is not the guard. `RecipeService.resolveIngredients` refuses a supply on a recipe
    line with KMS-400127, which is what answers a raw POST, an import, or a screen written later.
    That refusal also covers the one case this filter cannot: a recipe saved before an ingredient
    was flagged a supply still holds the line, its select renders with nothing chosen, and pressing
    Save asks the server, which says which one and why.
  */
  const ingredientOptions = (ingredients.data ?? []).filter((ing) => !ing.supply);

  return (
    <Form id={formId} onSubmit={handleSubmit} className="space-y-8">
      {error && <ErrorNotice error={error} />}

      <section className="space-y-5">
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Name</span>
          <input value={name} onChange={(e) => setName(e.target.value)} required
            className="min-h-touch rounded-control border border-hairline px-3 text-base text-ink" />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Subtitle</span>
          <input value={subtitle} onChange={(e) => setSubtitle(e.target.value)} placeholder="Spiced buttermilk"
            className="min-h-touch rounded-control border border-hairline px-3 text-base text-ink" />
        </label>

        <div className="grid grid-cols-1 gap-5 sm:grid-cols-3">
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Category</span>
            <select value={categoryId} onChange={(e) => setCategoryId(e.target.value)} required
              className="min-h-touch rounded-control border border-hairline px-3 text-base text-ink">
              <option value="">Choose…</option>
              {(categories.data ?? []).map((c) => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </select>
          </label>
          {/*
            "This recipe makes" rather than "Base yield" (T-218): the old label named the database
            column, and a recipe was saved as 270 L with a 350 ml portion with nothing on the form to
            say what those two numbers meant together.

            The box carries an aria-label of its own, "How much this recipe makes", because Form
            words a refusal from the field's name and "This recipe makes is required" is not a
            sentence. It still contains the visible words, so a screen reader and a voice user find
            it by what they see.
          */}
          <HintedField label="This recipe makes" hint="How much the ingredients below make. Planned amounts are scaled from this.">
            {(id) => (
              <input id={id} aria-label="How much this recipe makes" type="number" min="0"
                // A recipe measured in pieces makes whole ladoos (T-424). Follows "Measured in".
                step={stepForUnit(baseYieldUnit)}
                value={baseYieldQty} onChange={(e) => setBaseYieldQty(e.target.value)} required
                className="min-h-touch rounded-control border border-hairline px-3 text-base text-ink" />
            )}
          </HintedField>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Measured in</span>
            <select value={baseYieldUnit} onChange={(e) => changeMeasure(e.target.value)}
              className="min-h-touch rounded-control border border-hairline px-3 text-base text-ink">
              {measures.map((u) => <option key={u} value={u}>{unitLabel(u)}</option>)}
            </select>
          </label>
        </div>

        {/* The portion is what turns a head count into a quantity when this recipe is planned.
            Without it the planner has to ask, which is honest but slower. */}
        <div className="grid grid-cols-1 gap-5 sm:grid-cols-3">
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">One person eats</span>
            {/* A portion of a counted recipe is a whole thing: one person eats 2 ladoos, not 2.4.
                Follows "Portion unit" beside it, which may differ from the yield's (T-424). */}
            <input type="number" min="0" step={stepForUnit(perHeadUnit)} value={perHeadQty}
              onChange={(e) => setPerHeadQty(e.target.value)} placeholder="0.2"
              className="min-h-touch rounded-control border border-hairline px-3 text-base text-ink" />
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Portion unit</span>
            <select value={perHeadUnit} onChange={(e) => setPerHeadUnit(e.target.value)}
              className="min-h-touch rounded-control border border-hairline px-3 text-base text-ink">
              <option value="">—</option>
              {portionUnits.map((u) => <option key={u} value={u}>{unitLabel(u)}</option>)}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Makes (in words)</span>
            <input value={yieldNote} onChange={(e) => setYieldNote(e.target.value)}
              placeholder="300 idlis (3 per devotee)"
              className="min-h-touch rounded-control border border-hairline px-3 text-base text-ink" />
          </label>
        </div>

        {/*
          What the two pairs of boxes above mean together, said as the cook types (T-218). The
          portion goes through cooksQuantity, so 0.35 L reads "350 ml" the way it does everywhere
          else. The warning is amber and never blocks Save: it asks for a second look, and the
          cook may well be right.
        */}
        {feeds != null && (
          <div className="space-y-3">
            <p className="pl-field-inset text-sm text-ink-secondary" aria-live="polite">
              {feeds < 1
                ? `This recipe feeds fewer than one person at ${cooksQuantity(Number(perHeadQty), perHeadUnit)} each.`
                : `This recipe feeds about ${feeds.toLocaleString("en-IN")} ${feeds === 1 ? "person" : "people"} at ${cooksQuantity(Number(perHeadQty), perHeadUnit)} each.`}
            </p>
            {feeds < FEWEST_PEOPLE && (
              <InlineNotice tone="warning" title="Very few people for one batch. Check the amounts and units." />
            )}
            {feeds > MOST_PEOPLE && (
              <InlineNotice tone="warning" title="A lot of people for one batch. Check the amounts and units." />
            )}
          </div>
        )}
      </section>

      <section aria-labelledby="ingredients-heading" className="space-y-3">
        <h2 id="ingredients-heading" className="text-lg">Ingredients</h2>
        {lines.map((line, i) => (
          /*
            On a phone the ingredient takes its own line and the amount, unit and Remove sit under
            it. Held on one line, the select could not shrink below its longest option and pushed
            the whole form 200px past the right edge of the screen.

            The preparation note (R-DUP-1: "slit", "halved") sits right after the ingredient, so the
            line reads the way it prints — "Green chilli · slit". Where it goes is set by what fits
            without cutting a name short:
            - a phone: a row of its own under the ingredient; beside it, both boxes were narrower
              than "Coconut, dry grated (kopra)".
            - a tablet up to xl: beside the ingredient, with the amount, unit and Remove under them.
            - xl and up (1280, the width it was measured at): the whole line on one row, the
              ingredient getting the larger share because it is the longer text.
          */
          <div key={i} className="grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto] items-end gap-2 xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)_6rem_6rem_auto]">
            <select aria-label={`Ingredient ${i + 1}`} value={line.ingredientId}
              onChange={(e) => setLine(i, { ingredientId: e.target.value })}
              className="col-span-3 min-h-touch min-w-0 rounded-control border border-hairline px-3 text-base text-ink sm:col-span-1">
              <option value="">Choose ingredient…</option>
              {ingredientOptions.map((ing) => (
                <option key={ing.id} value={ing.id}>
                  {ing.name}
                </option>
              ))}
            </select>
            <input aria-label={`Preparation ${i + 1}`} value={line.preparationNote} maxLength={200}
              onChange={(e) => setLine(i, { preparationNote: e.target.value })} placeholder="Preparation, e.g. slit"
              className="col-span-3 min-h-touch min-w-0 rounded-control border border-hairline px-3 text-base text-ink sm:col-span-2 xl:col-span-1" />
            <input aria-label={`Quantity ${i + 1}`} type="number" min="0" step={stepForUnit(line.unit)} value={line.quantity}
              onChange={(e) => setLine(i, { quantity: e.target.value })} placeholder="Qty"
              className="min-h-touch rounded-control border border-hairline px-3 text-base text-ink" />
            <select aria-label={`Unit ${i + 1}`} value={line.unit} onChange={(e) => setLine(i, { unit: e.target.value })}
              className="min-h-touch rounded-control border border-hairline px-2 text-base text-ink">
              {FOOD_UNITS.map((u) => <option key={u} value={u}>{unitLabel(u)}</option>)}
            </select>
            <button type="button" onClick={() => removeLine(i)} aria-label={`Remove ingredient ${i + 1}`}
              className="btn btn-secondary min-h-touch px-3 text-sm">
              Remove
            </button>
          </div>
        ))}
        <Button variant="secondary" onClick={addLine}>
          + Add ingredient
        </Button>
      </section>

      <section className="space-y-5">
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Method (one step per line)</span>
          <textarea value={method} onChange={(e) => setMethod(e.target.value)} rows={6}
            className="rounded-control border border-hairline px-3 py-2 text-base text-ink" />
        </label>
        {/*
          Region tag kept its half of the row rather than growing into the whole width when D-18
          took the override-reason field off the other half. It is a one-word field — "Karnataka" —
          and a box four times longer than anything ever typed into it invites a sentence.
        */}
        <div className="grid grid-cols-1 gap-5 sm:grid-cols-2">
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">State or region</span>
            <input value={regionTag} onChange={(e) => setRegionTag(e.target.value)} placeholder="Karnataka"
              className="min-h-touch rounded-control border border-hairline px-3 text-base text-ink" />
          </label>
        </div>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Notes</span>
          <textarea value={notes} onChange={(e) => setNotes(e.target.value)} rows={2}
            className="rounded-control border border-hairline px-3 py-2 text-base text-ink" />
        </label>
      </section>

      {/* Everything a recipe book carries beyond the cooking itself. All optional — a two-line
          chutney must not have to walk past a wall of fields to get written down. */}
      <section aria-labelledby="about-heading" className="space-y-5">
        <h2 id="about-heading" className="text-lg">About this dish</h2>

        <div className="grid grid-cols-1 gap-5 sm:grid-cols-3">
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">How often it is cooked</span>
            <select value={badge} onChange={(e) => setBadge(e.target.value)}
              className="min-h-touch rounded-control border border-hairline px-3 text-base text-ink">
              <option value="">—</option>
              {BADGES.map((b) => <option key={b} value={b}>{b}</option>)}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            {/* The cost of one batch, the amount "This recipe makes" (T-231, Rajeev 2026-09-18). It
                was "Indicative cost", which never said of what, so the batch is named beside the box
                as it is typed, and in the same words the recipe page uses for it. Nothing is shown
                until there is a batch size to name: "for —" would be a question, not an answer. */}
            <span className="pl-field-inset font-medium text-ink">Rough cost of one batch (₹)</span>
            <span className="flex items-center gap-2">
              <input type="number" min="0" step="any" value={indicativeCost}
                onChange={(e) => setIndicativeCost(e.target.value)}
                className="min-h-touch min-w-0 flex-1 rounded-control border border-hairline px-3 text-base text-ink" />
              {batchSize && <span className="whitespace-nowrap text-ink">for {batchSize}</span>}
            </span>
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">District or town</span>
            <input value={subRegion} onChange={(e) => setSubRegion(e.target.value)} placeholder="Rohtak"
              className="min-h-touch rounded-control border border-hairline px-3 text-base text-ink" />
          </label>
        </div>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Why this dish</span>
          <textarea value={why} onChange={(e) => setWhy(e.target.value)} rows={2}
            className="rounded-control border border-hairline px-3 py-2 text-base text-ink" />
        </label>

        <div className="grid grid-cols-1 gap-5 sm:grid-cols-3">
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Before you start</span>
            <textarea value={noteStart} onChange={(e) => setNoteStart(e.target.value)} rows={2}
              placeholder="Soak the dal overnight."
              className="rounded-control border border-hairline px-3 py-2 text-base text-ink" />
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Vessel</span>
            <textarea value={noteVessel} onChange={(e) => setNoteVessel(e.target.value)} rows={2}
              placeholder="A 30 L drum with a lid. One cook."
              className="rounded-control border border-hairline px-3 py-2 text-base text-ink" />
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Season</span>
            <textarea value={noteSeason} onChange={(e) => setNoteSeason(e.target.value)} rows={2}
              placeholder="All year, doubled from April to July."
              className="rounded-control border border-hairline px-3 py-2 text-base text-ink" />
          </label>
        </div>

        <div className="grid grid-cols-1 gap-5 sm:grid-cols-2">
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Tags (comma separated)</span>
            <input value={tags} onChange={(e) => setTags(e.target.value)}
              placeholder="Jain-safe, Gluten-free, Travels well"
              className="min-h-touch rounded-control border border-hairline px-3 text-base text-ink" />
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Serve with (comma separated)</span>
            <input value={serveWith} onChange={(e) => setServeWith(e.target.value)}
              placeholder="Akki Rotti, Majjige"
              className="min-h-touch rounded-control border border-hairline px-3 text-base text-ink" />
          </label>
        </div>
      </section>

    </Form>
  );
}
