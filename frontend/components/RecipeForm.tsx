"use client";

import { useState } from "react";
import { ErrorNotice } from "@/components/ErrorNotice";
import { api, type ApiError, type RecipeDetail, type RecipeInput } from "@/lib/api";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * One vocabulary (E11-S2), shown as the part of it that can be true in each place.
 *
 * A yield may be counted in servings — a recipe that feeds a hundred people says so. What one
 * person eats may not: a portion is a quantity of food, and "0.5 servings per head" tells a cook
 * nothing they can weigh.
 */
const YIELD_UNITS = ["KG", "GM", "L", "ML", "PIECES", "SERVINGS"];
const PORTION_UNITS = ["KG", "GM", "L", "ML", "PIECES"];
const BADGES = ["Everyday", "Moderate", "Festival", "Sustainable", "Economical"];
const LINE_UNITS = ["KG", "GM", "L", "ML", "PIECES"];
const UNIT_LABEL: Record<string, string> = { KG: "Kg", GM: "gm", L: "L", ML: "ml", PIECES: "pieces", SERVINGS: "servings" };

/** A comma-separated box as a list, with the empties dropped and the spaces trimmed. */
function splitList(value: string): string[] {
  return value
    .split(",")
    .map((v) => v.trim())
    .filter(Boolean);
}

interface Line {
  ingredientId: string;
  quantity: string;
  unit: string;
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
  const [baseYieldUnit, setBaseYieldUnit] = useState(initial?.baseYieldUnit ?? "SERVINGS");
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
  const [cateringNote, setCateringNote] = useState(initial?.cateringNote ?? "");
  const [subRegion, setSubRegion] = useState(initial?.subRegion ?? "");
  const [noteStart, setNoteStart] = useState(initial?.noteStart ?? "");
  const [noteVessel, setNoteVessel] = useState(initial?.noteVessel ?? "");
  const [noteSeason, setNoteSeason] = useState(initial?.noteSeason ?? "");
  const [tags, setTags] = useState((initial?.tags ?? []).join(", "));
  const [serveWith, setServeWith] = useState((initial?.serveWith ?? []).join(", "));
  const [overrideReason, setOverrideReason] = useState(initial?.sattvicOverrideReason ?? "");
  const [lines, setLines] = useState<Line[]>(
    initial
      ? initial.ingredients.map((l) => ({ ingredientId: l.ingredientId, quantity: String(l.quantity), unit: l.unit }))
      : [{ ingredientId: "", quantity: "", unit: "KG" }]
  );

  function setLine(index: number, patch: Partial<Line>) {
    setLines((prev) => prev.map((l, i) => (i === index ? { ...l, ...patch } : l)));
  }
  function addLine() {
    setLines((prev) => [...prev, { ingredientId: "", quantity: "", unit: "KG" }]);
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
      sattvicOverrideReason: overrideReason.trim() || undefined,
      ingredients: lines
        .filter((l) => l.ingredientId && l.quantity)
        .map((l) => ({ ingredientId: l.ingredientId, quantity: Number(l.quantity), unit: l.unit })),
    });
  }

  const ingredientOptions = ingredients.data ?? [];

  return (
    <form id={formId} onSubmit={handleSubmit} className="space-y-8">
      {error && <ErrorNotice error={error} />}

      <section className="space-y-5">
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Name</span>
          <input value={name} onChange={(e) => setName(e.target.value)} required
            className="min-h-touch rounded border border-hairline bg-raised px-3" />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Subtitle</span>
          <input value={subtitle} onChange={(e) => setSubtitle(e.target.value)} placeholder="Spiced buttermilk"
            className="min-h-touch rounded border border-hairline bg-raised px-3" />
        </label>

        <div className="grid grid-cols-1 gap-5 sm:grid-cols-3">
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Category</span>
            <select value={categoryId} onChange={(e) => setCategoryId(e.target.value)} required
              className="min-h-touch rounded border border-hairline bg-raised px-3">
              <option value="">Choose…</option>
              {(categories.data ?? []).map((c) => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Base yield</span>
            <input type="number" min="0" step="any" value={baseYieldQty} onChange={(e) => setBaseYieldQty(e.target.value)} required
              className="min-h-touch rounded border border-hairline bg-raised px-3" />
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Yield unit</span>
            <select value={baseYieldUnit} onChange={(e) => setBaseYieldUnit(e.target.value)}
              className="min-h-touch rounded border border-hairline bg-raised px-3">
              {YIELD_UNITS.map((u) => <option key={u} value={u}>{u.toLowerCase()}</option>)}
            </select>
          </label>
        </div>

        {/* The portion is what turns a head count into a quantity when this recipe is planned.
            Without it the planner has to ask, which is honest but slower. */}
        <div className="grid grid-cols-1 gap-5 sm:grid-cols-3">
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">One person eats</span>
            <input type="number" min="0" step="any" value={perHeadQty}
              onChange={(e) => setPerHeadQty(e.target.value)} placeholder="0.2"
              className="min-h-touch rounded border border-hairline bg-raised px-3" />
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Portion unit</span>
            <select value={perHeadUnit} onChange={(e) => setPerHeadUnit(e.target.value)}
              className="min-h-touch rounded border border-hairline bg-raised px-3">
              <option value="">—</option>
              {PORTION_UNITS.map((u) => <option key={u} value={u}>{u.toLowerCase()}</option>)}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Yield note</span>
            <input value={yieldNote} onChange={(e) => setYieldNote(e.target.value)}
              placeholder="300 idlis (3 per devotee)"
              className="min-h-touch rounded border border-hairline bg-raised px-3" />
          </label>
        </div>
      </section>

      <section aria-labelledby="ingredients-heading" className="space-y-3">
        <h2 id="ingredients-heading" className="text-lg">Ingredients</h2>
        {lines.map((line, i) => (
          <div key={i} className="grid grid-cols-[1fr_6rem_6rem_auto] items-end gap-2">
            <select aria-label={`Ingredient ${i + 1}`} value={line.ingredientId}
              onChange={(e) => setLine(i, { ingredientId: e.target.value })}
              className="min-h-touch rounded border border-hairline bg-raised px-3">
              <option value="">Choose ingredient…</option>
              {ingredientOptions.map((ing) => (
                <option key={ing.id} value={ing.id}>
                  {ing.name}{ing.sattvicProhibited ? " (prohibited)" : ""}
                </option>
              ))}
            </select>
            <input aria-label={`Quantity ${i + 1}`} type="number" min="0" step="any" value={line.quantity}
              onChange={(e) => setLine(i, { quantity: e.target.value })} placeholder="Qty"
              className="min-h-touch rounded border border-hairline bg-raised px-3" />
            <select aria-label={`Unit ${i + 1}`} value={line.unit} onChange={(e) => setLine(i, { unit: e.target.value })}
              className="min-h-touch rounded border border-hairline bg-raised px-2">
              {LINE_UNITS.map((u) => <option key={u} value={u}>{UNIT_LABEL[u]}</option>)}
            </select>
            <button type="button" onClick={() => removeLine(i)} aria-label={`Remove ingredient ${i + 1}`}
              className="min-h-touch rounded border border-hairline-strong px-3 text-sm text-ink-secondary hover:bg-raised">
              Remove
            </button>
          </div>
        ))}
        <button type="button" onClick={addLine}
          className="min-h-touch rounded border border-hairline-strong px-4 text-sm hover:bg-raised">
          + Add ingredient
        </button>
      </section>

      <section className="space-y-5">
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Method (one step per line)</span>
          <textarea value={method} onChange={(e) => setMethod(e.target.value)} rows={6}
            className="rounded border border-hairline bg-raised px-3 py-2" />
        </label>
        <div className="grid grid-cols-1 gap-5 sm:grid-cols-2">
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Region tag</span>
            <input value={regionTag} onChange={(e) => setRegionTag(e.target.value)} placeholder="Karnataka"
              className="min-h-touch rounded border border-hairline bg-raised px-3" />
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Sattvic override reason (only if a prohibited ingredient is needed)</span>
            <input value={overrideReason} onChange={(e) => setOverrideReason(e.target.value)}
              className="min-h-touch rounded border border-hairline bg-raised px-3" />
          </label>
        </div>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Notes</span>
          <textarea value={notes} onChange={(e) => setNotes(e.target.value)} rows={2}
            className="rounded border border-hairline bg-raised px-3 py-2" />
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
              className="min-h-touch rounded border border-hairline bg-raised px-3">
              <option value="">—</option>
              {BADGES.map((b) => <option key={b} value={b}>{b}</option>)}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Indicative cost (₹)</span>
            <input type="number" min="0" step="any" value={indicativeCost}
              onChange={(e) => setIndicativeCost(e.target.value)}
              className="min-h-touch rounded border border-hairline bg-raised px-3" />
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">District or town</span>
            <input value={subRegion} onChange={(e) => setSubRegion(e.target.value)} placeholder="Rohtak"
              className="min-h-touch rounded border border-hairline bg-raised px-3" />
          </label>
        </div>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Why this dish</span>
          <textarea value={why} onChange={(e) => setWhy(e.target.value)} rows={2}
            className="rounded border border-hairline bg-raised px-3 py-2" />
        </label>

        <div className="grid grid-cols-1 gap-5 sm:grid-cols-3">
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Start</span>
            <textarea value={noteStart} onChange={(e) => setNoteStart(e.target.value)} rows={2}
              placeholder="Soak the dal overnight."
              className="rounded border border-hairline bg-raised px-3 py-2" />
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Vessel</span>
            <textarea value={noteVessel} onChange={(e) => setNoteVessel(e.target.value)} rows={2}
              placeholder="A 30 L drum with a lid. One cook."
              className="rounded border border-hairline bg-raised px-3 py-2" />
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Season</span>
            <textarea value={noteSeason} onChange={(e) => setNoteSeason(e.target.value)} rows={2}
              placeholder="All year, doubled from April to July."
              className="rounded border border-hairline bg-raised px-3 py-2" />
          </label>
        </div>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Catering note</span>
          <textarea value={cateringNote} onChange={(e) => setCateringNote(e.target.value)} rows={2}
            className="rounded border border-hairline bg-raised px-3 py-2" />
        </label>

        <div className="grid grid-cols-1 gap-5 sm:grid-cols-2">
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Tags (comma separated)</span>
            <input value={tags} onChange={(e) => setTags(e.target.value)}
              placeholder="Jain-safe, Gluten-free, Travels well"
              className="min-h-touch rounded border border-hairline bg-raised px-3" />
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Serve with (comma separated)</span>
            <input value={serveWith} onChange={(e) => setServeWith(e.target.value)}
              placeholder="Akki Rotti, Majjige"
              className="min-h-touch rounded border border-hairline bg-raised px-3" />
          </label>
        </div>
      </section>

    </form>
  );
}
