"use client";

import { useState } from "react";
import { ErrorNotice } from "@/components/ErrorNotice";
import { api, type ApiError, type RecipeDetail, type RecipeInput } from "@/lib/api";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { BusyPot } from "@/components/Loading";

const YIELD_UNITS = ["SERVINGS", "LITRES"];
const LINE_UNITS = ["KG", "GM", "L", "ML", "PIECES"];
const UNIT_LABEL: Record<string, string> = { KG: "Kg", GM: "gm", L: "L", ML: "ml", PIECES: "pieces" };

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
  submitLabel,
  busy,
  error,
  onSubmit,
}: {
  initial?: RecipeDetail;
  submitLabel: string;
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
      sattvicOverrideReason: overrideReason.trim() || undefined,
      ingredients: lines
        .filter((l) => l.ingredientId && l.quantity)
        .map((l) => ({ ingredientId: l.ingredientId, quantity: Number(l.quantity), unit: l.unit })),
    });
  }

  const ingredientOptions = ingredients.data ?? [];

  return (
    <form onSubmit={handleSubmit} className="space-y-8">
      {error && <ErrorNotice error={error} />}

      <section className="space-y-5">
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Name</span>
          <input value={name} onChange={(e) => setName(e.target.value)} required
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

      <div className="border-t border-hairline pt-6">
        <button type="submit" disabled={busy}
          className="min-h-touch rounded bg-accent px-6 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">
          {busy ? (<span className="inline-flex items-center gap-2"><BusyPot />Saving…</span>) : submitLabel}
        </button>
      </div>
    </form>
  );
}
