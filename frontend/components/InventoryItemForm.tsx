"use client";

import { useState } from "react";
import { ErrorNotice } from "@/components/ErrorNotice";
import { unitLabel } from "@/lib/format";
import type { ApiError, IngredientView, StockItemView } from "@/lib/api";

/**
 * The units a level may be typed in, for the unit the ingredient is kept in.
 *
 * <p>Not a picker's copy of the vocabulary and so not fed from `FOOD_UNITS` (E11-S6): it is a
 * conversion table, and each entry carries the factor that turns what was typed into what is
 * stored. The keys are the five physical units because those are the units stock is kept in — an
 * ingredient can never be counted in servings.
 */
const ENTRY_UNITS: Record<string, { code: string; per: number }[]> = {
  KG: [{ code: "KG", per: 1 }, { code: "GM", per: 0.001 }],
  GM: [{ code: "GM", per: 1 }, { code: "KG", per: 1000 }],
  L: [{ code: "L", per: 1 }, { code: "ML", per: 0.001 }],
  ML: [{ code: "ML", per: 1 }, { code: "L", per: 1000 }],
  PIECES: [{ code: "PIECES", per: 1 }],
};

const FIELD = "min-h-touch rounded border border-hairline bg-canvas px-3";

/** What the screen needs in order to open an item and its first lot. */
export interface NewInventoryItem {
  ingredientId: string;
  name: string;
  unit: string;
  openingQuantity: number | null;
  storageLocation: string | null;
  reorderThreshold: number | null;
  notes: string | null;
}

/**
 * Adding a consumable to the inventory (E10-S12). Presentational: the screen around it owns the
 * two API calls, the navigation and the error.
 *
 * <p>It asks the things a storekeeper knows standing in front of the shelf: what it is, how much is
 * there, and where it lives. The count is the one that used to be missing — an item could be added
 * and had no way of being told what was on the shelf, so it sat at zero, badged "below reorder
 * level", with nothing on any screen able to answer it.
 *
 * <p>Five fields, so a screen of its own rather than a panel over the list, and it has no button:
 * the focus screen's header commits it by name with `form={formId}`.
 */
export function InventoryItemForm({
  formId,
  ingredients,
  tracked,
  busy,
  error,
  onSubmit,
}: {
  /** The id the screen's own commit button points at with `form={formId}`. */
  formId: string;
  /** Every ingredient this temple knows. */
  ingredients: IngredientView[];
  /** What is already in the inventory, so nothing can be added to it twice. */
  tracked: StockItemView[];
  busy: boolean;
  error: ApiError | null;
  onSubmit: (input: NewInventoryItem) => void;
}) {
  const [ingredientId, setIngredientId] = useState("");
  const [levelUnit, setLevelUnit] = useState<string | null>(null);

  const alreadyIn = new Set(tracked.map((i) => i.ingredientId));
  const available = ingredients.filter((i) => !alreadyIn.has(i.id));
  const chosen = available.find((i) => i.id === ingredientId);

  // The unit belongs to the ingredient, so until one is chosen there is no unit to show. It used to
  // default to kilograms, which asserted a unit for an ingredient nobody had named yet.
  const units = chosen ? (ENTRY_UNITS[chosen.unit] ?? [{ code: chosen.unit, per: 1 }]) : [];
  const typedIn = units.find((u) => u.code === levelUnit) ?? units[0] ?? null;

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    if (!chosen || !typedIn) return;

    const opening = String(f.get("opening") ?? "").trim();
    const level = String(f.get("reorderThreshold") ?? "").trim();

    onSubmit({
      ingredientId: chosen.id,
      name: chosen.name,
      unit: typedIn.code,
      openingQuantity: opening === "" ? null : Number(opening),
      storageLocation: emptyToNull(String(f.get("storageLocation") ?? "")),
      // Stored in the ingredient's own unit, whichever one it was typed in.
      reorderThreshold: level === "" ? null : Number(level) * typedIn.per,
      notes: emptyToNull(String(f.get("notes") ?? "")),
    });
  }

  return (
    <>
      {error && <ErrorNotice error={error} />}

      {available.length === 0 && ingredients.length > 0 && (
        <p className="text-sm text-ink-secondary">Every ingredient is already in your inventory.</p>
      )}

      <form
        id={formId}
        className="grid grid-cols-2 gap-4"
        aria-label="Add to inventory"
        aria-busy={busy}
        onSubmit={submit}
      >
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Ingredient</span>
          <select
            name="ingredientId"
            required
            className={FIELD}
            value={ingredientId}
            onChange={(e) => {
              setIngredientId(e.target.value);
              setLevelUnit(null);
            }}
          >
            <option value="">Choose an ingredient…</option>
            {available.map((i) => (
              <option key={i.id} value={i.id}>
                {i.name} — kept in {unitLabel(i.unit)}
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">How much is on the shelf now</span>
          <div className="flex gap-2">
            <input
              name="opening"
              type="number"
              min="0"
              step="any"
              placeholder={chosen ? "e.g. 40" : "Choose an ingredient first"}
              disabled={!chosen}
              className={`${FIELD} min-w-0 flex-1 disabled:opacity-60`}
            />
            <UnitControl units={units} typedIn={typedIn} onChange={setLevelUnit} />
          </div>
          <span className="pl-field-inset text-sm text-ink-secondary">
            Counted today. Everything after this — deliveries, donations, meals cooked — moves on its own.
          </span>
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Where it lives</span>
          <input name="storageLocation" placeholder="Main store, cold room…" className={FIELD} />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Tell me when stock drops below</span>
          <input
            name="reorderThreshold"
            type="number"
            min="0"
            step="any"
            placeholder={chosen ? "e.g. 5" : ""}
            disabled={!chosen}
            className={`${FIELD} disabled:opacity-60`}
          />
          <span className="pl-field-inset text-sm text-ink-secondary">
            Leave it blank if you’d rather not be warned. You can change it later.
          </span>
        </label>

        <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Notes</span>
          <input name="notes" className={FIELD} />
        </label>
      </form>
    </>
  );
}

/** The unit a level is typed in: a choice where the family has two, a plain label where it has one. */
function UnitControl({
  units,
  typedIn,
  onChange,
}: {
  units: { code: string; per: number }[];
  typedIn: { code: string; per: number } | null;
  onChange: (code: string) => void;
}) {
  if (!typedIn) {
    return (
      <span className="flex min-h-touch items-center rounded border border-hairline bg-sunken px-3 text-ink-muted">
        —
      </span>
    );
  }
  if (units.length === 1) {
    // Grams convert to kilograms; nothing converts to a coconut.
    return (
      <span className="flex min-h-touch items-center rounded border border-hairline bg-sunken px-3 text-ink-secondary">
        {unitLabel(typedIn.code)}
      </span>
    );
  }
  return (
    <select aria-label="Unit" className={FIELD} value={typedIn.code} onChange={(e) => onChange(e.target.value)}>
      {units.map((u) => (
        <option key={u.code} value={u.code}>
          {unitLabel(u.code)}
        </option>
      ))}
    </select>
  );
}

function emptyToNull(value: string): string | null {
  const trimmed = value.trim();
  return trimmed === "" ? null : trimmed;
}
