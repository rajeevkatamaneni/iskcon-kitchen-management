"use client";

import { useEffect, useRef, useState } from "react";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Form } from "@/components/ds/Form";
import { HintedField } from "@/components/ds/InfoHint";
import { stepForUnit, unitLabel, unitLabelFor } from "@/lib/format";
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

const FIELD = "min-h-touch rounded-control border border-hairline px-3";

/** What the screen needs in order to open an item and its first lot. */
export interface NewInventoryItem {
  ingredientId: string;
  name: string;
  unit: string;
  openingQuantity: number | null;
  storageLocation: string | null;
  reorderThreshold: number | null;
  notes: string | null;
  /**
   * "What it would cost to buy today", ₹ per the ingredient's own stock unit (R-ING-3), or null when
   * no count was typed. Sent with the opening count and nowhere else: it is only ever asked because
   * stock is being added, so with no count there is nothing for it to travel with.
   */
  pricePerUnit: number | null;
}

/**
 * Looks up what to pre-fill in the stock-value box for one ingredient: the preferred vendor's list
 * price, else its market rate, else null (R-ING-3). Owned by the screen, because it is an API call.
 */
export type LoadStockValue = (ingredientId: string) => Promise<number | null>;

/**
 * Adding a consumable to the inventory (E10-S12). Presentational: the screen around it owns the
 * API call (one: the item and its opening count are saved together, T-294), the navigation and the
 * error.
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
  loadStockValue,
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
  loadStockValue: LoadStockValue;
}) {
  const [ingredientId, setIngredientId] = useState("");
  const [levelUnit, setLevelUnit] = useState<string | null>(null);
  /** The count as typed, read as it changes because it decides whether the value box is required. */
  const [count, setCount] = useState("");

  const alreadyIn = new Set(tracked.map((i) => i.ingredientId));
  const available = ingredients.filter((i) => !alreadyIn.has(i.id));
  const chosen = available.find((i) => i.id === ingredientId);

  // The unit belongs to the ingredient, so until one is chosen there is no unit to show. It used to
  // default to kilograms, which asserted a unit for an ingredient nobody had named yet.
  const units = chosen ? (ENTRY_UNITS[chosen.unit] ?? [{ code: chosen.unit, per: 1 }]) : [];
  const typedIn = units.find((u) => u.code === levelUnit) ?? units[0] ?? null;

  const [stockValue, setStockValue] = usePrefilledStockValue(chosen?.id ?? null, loadStockValue);
  // Required only when the count adds stock (the conductor's ruling on R-ING-3, 2026-09-19): the
  // rule is "whenever a person adds stock", and a blank or 0 count adds none, so there is nothing
  // for a price to be the price of. The box stays on screen either way, so nothing jumps about as
  // the count is typed.
  const addsStock = Number(count) > 0;

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
      pricePerUnit: addsStock ? Number(stockValue) : null,
    });
  }

  return (
    <>
      {error && <ErrorNotice error={error} />}

      {available.length === 0 && ingredients.length > 0 && (
        <p className="text-sm text-ink-secondary">Every ingredient is already in your inventory.</p>
      )}

      {/* Two columns from `md` (768) up, one below it (VERIFY-A defect 4). At 390 wide two columns
          left each box 125px, and the ingredient select showed "VERIFY-A Rice — kept" of "VERIFY-A
          Rice — kept in Kg" — the one thing on this form that must be read in full, cut off. None of
          the six fields fits beside another on a phone (the count's box also carries its unit
          picker), so there each gets its own row.

          `md`, not the `sm` most two-column forms here use, and measured rather than assumed (T-294):
          at 640 two columns give each box 280px, the longest ingredient on the local data ("Fenugreek
          seeds, soaked overnight — kept in gm", 285px of text) was cut off in it, and the value
          box's long label wrapped to a third line so its row stood 18px taller than the ones around
          it. At 768 each box is 344px, that name fits, and all three rows are the same height. The
          cost is 640–767 wide showing one full-width column, which hides nothing; a squeezed select
          would. The narrowest two-column box is 332px, at 1024 where the sidebar opens (the name above
          still fits, with 5px to spare); 460px at 1280. A name longer than the box would still be
          cut off in the closed select at any layout; the open list shows it in full. */}
      <Form
        id={formId}
        className="grid grid-cols-1 gap-4 md:grid-cols-2"
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

        {/* Still one field though there are two boxes: the count is what the label names, and the
            unit beside it carries its own. So the id goes on the number, not on the pair. */}
        <HintedField
          label="How much is on the shelf now"
          hint="Counted today. Everything after this — deliveries, donations, meals cooked — moves on its own."
        >
          {(id) => (
            <div className="flex gap-2">
              <input
                id={id}
                name="opening"
                type="number"
                value={count}
                onChange={(e) => setCount(e.target.value)}
                min="0"
                // The unit picker sits in this same row, so the box follows whatever it is set
                // to rather than the ingredient's stock unit (T-424).
                step={stepForUnit(levelUnit)}
                placeholder={chosen ? "e.g. 40" : "Choose an ingredient first"}
                disabled={!chosen}
                className={`${FIELD} min-w-0 flex-1 disabled:opacity-60`}
              />
              <UnitControl units={units} typedIn={typedIn} onChange={setLevelUnit} />
            </div>
          )}
        </HintedField>

        {/* Beside the count's row rather than under Notes: it is a question about the same shelf, and
            with it the form is six fields in three full rows — no half-empty row anywhere. */}
        <StockValueField
          unit={chosen?.unit ?? null}
          value={stockValue}
          onChange={setStockValue}
          required={addsStock}
          disabled={!chosen}
        />

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Where it lives</span>
          <input name="storageLocation" placeholder="Main store, cold room…" className={FIELD} />
        </label>

        <HintedField
          label="Tell me when stock drops below"
          hint="Leave it blank if you’d rather not be warned. You can change it later."
        >
          {(id) => (
            <input
              id={id}
              name="reorderThreshold"
              type="number"
              min="0"
              // A threshold is compared against a stock level, so it is counted whenever the
              // level is: "tell me when aprons drop below 3.6" can never be true or false in a
              // way anybody could act on (T-424). Always the ingredient's stock unit — this box
              // has no picker of its own and the hint names no unit.
              step={stepForUnit(chosen?.unit)}
              placeholder={chosen ? "e.g. 5" : ""}
              disabled={!chosen}
              className={`${FIELD} disabled:opacity-60`}
            />
          )}
        </HintedField>

        {/* One column now, not two: the stock value made the fields even, and a full-width Notes
            would leave "Tell me when stock drops below" alone on its row with a blank beside it. */}
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Notes</span>
          <input name="notes" className={FIELD} />
        </label>
      </Form>
    </>
  );
}

/**
 * "What it would cost to buy today" (R-ING-3): the box asked whenever a person adds stock, here and
 * on the item page's adjustment form, so the two ask it in the same words.
 *
 * <p>The price is always per the ingredient's **stock** unit, whatever unit the count was typed in —
 * that is what the server stores as the market rate (T-254) — so the label names the stock unit and
 * never follows the count's unit picker. Before an ingredient is chosen there is no unit to name, and
 * the label says "(₹)" with the box disabled (the conductor's ruling, 2026-09-19). The box is on
 * screen in both states, so choosing an ingredient changes its words and never adds a row.
 *
 * <p>Required means `required` plus `data-more-than="0"`, so {@link Form} says "… is required" for a
 * blank box and "… must be more than 0" for a 0, in red beside it, like every other box. The server
 * refuses the same two (KMS-400161) as the second guard.
 */
export function StockValueField({
  unit,
  value,
  onChange,
  required,
  disabled = false,
}: {
  /** The ingredient's stock unit, or null before one is chosen. */
  unit: string | null;
  value: string;
  onChange: (value: string) => void;
  required: boolean;
  disabled?: boolean;
}) {
  // "piece", not "pieces": it is the price of one.
  const per = unit ? unitLabelFor(1, unit) : null;
  const label = per ? `What it would cost to buy today (₹ per ${per})` : "What it would cost to buy today (₹)";
  return (
    <HintedField
      label={label}
      hint={`The price of one ${per ?? "unit"} today. We fill in the preferred vendor’s list price, or else the market rate. Saving it updates the market rate.`}
    >
      {(id) => (
        <input
          id={id}
          name="pricePerUnit"
          type="number"
          inputMode="decimal"
          min="0"
          step="any"
          required={required}
          data-more-than={required ? "0" : undefined}
          disabled={disabled}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={disabled ? "Choose an ingredient first" : "e.g. 60"}
          className={`${FIELD} disabled:opacity-60`}
        />
      )}
    </HintedField>
  );
}

/**
 * The stock-value box's contents, pre-filled from {@link LoadStockValue} for the ingredient in hand.
 *
 * <p>A pre-fill only: it lands in an empty box and never over something a person has typed, even
 * when the lookup is slower than their typing. A failed lookup leaves the box empty and says nothing
 * — the box is still required, and a person can type the price; blocking the form over a suggestion
 * would be worse than no suggestion.
 *
 * <p>The loader is held in a ref and the effect keys on the ingredient only. A screen's loader
 * closes over `getToken`, which is not guaranteed to keep its identity between renders, and an
 * effect keyed on it would empty the box on every render.
 */
export function usePrefilledStockValue(ingredientId: string | null, load: LoadStockValue) {
  const [value, setValue] = useState("");
  const loadRef = useRef(load);
  loadRef.current = load;

  useEffect(() => {
    let live = true;
    setValue("");
    if (!ingredientId) return;
    loadRef
      .current(ingredientId)
      .then((price) => {
        if (live && price != null && price > 0) setValue((typed) => (typed === "" ? String(price) : typed));
      })
      .catch(() => {});
    return () => {
      live = false;
    };
  }, [ingredientId]);

  return [value, setValue] as const;
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
      <span className="flex min-h-touch items-center rounded-control border border-hairline bg-sunken px-3 text-ink-muted">
        —
      </span>
    );
  }
  if (units.length === 1) {
    // Grams convert to kilograms; nothing converts to a coconut.
    return (
      <span className="flex min-h-touch items-center rounded-control border border-hairline bg-sunken px-3 text-ink-secondary">
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
