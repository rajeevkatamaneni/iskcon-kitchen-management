"use client";

import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { Button } from "@/components/ds/Button";
import { countedBox } from "@/components/ds/formMessages";
import { FieldRow } from "@/components/ds/FieldRow";
import { InfoHint } from "@/components/ds/InfoHint";
import { FIELD_LABEL } from "@/components/Field";
import type { RecipeSummary } from "@/lib/api";
import { stepForUnit, unitLabel } from "@/lib/format";

/**
 * Step 3 of the meal composer since Epic 12: "What each kitchen cooks", one band per kitchen.
 *
 * <p>Built from the approved mock (`app/dev-kitchen-meal/page.tsx`, its "Building the meal" tab, which
 * Rajeev approved on 2026-09-19), class for class. What the mock settled, and why each piece is here:
 *
 * <ul>
 *   <li>The composer's old step 3 (Preparations) and step 4 (Who will run it) meet in one card,
 *       because splitting a kitchen's dishes and its people across two cards would put the main
 *       kitchen in two places.</li>
 *   <li>Each kitchen searches for its dishes rather than ticking them from a list of every recipe:
 *       two kitchens with two full lists would show every recipe twice. A recipe already on the meal,
 *       in any band, is not offered again — a dish belongs to exactly one kitchen.</li>
 *   <li>Dishes never move between kitchens. A dish is taken off one band and added in another; there
 *       is no move action anywhere.</li>
 *   <li>A kitchen is added from a searchable list, not a row of buttons (Rajeev, 2026-09-19: a row of
 *       kitchen buttons becomes a wall at five to ten kitchens).</li>
 * </ul>
 *
 * <p>This file owns the band, the add-a-kitchen combobox, and the two figure boxes (Counter and
 * Readout) the band shares with the rest of the composer. They moved here from `MealComposer.tsx`
 * rather than being copied, because the band needs their `onBand` variant and two copies of one box
 * would be two places to keep one rule.
 */

/** The label and its "i", in the row's first track. One shape for a field, a counter and a readout. */
export const ROW_LABEL = `${FIELD_LABEL} flex items-center gap-1.5`;

/**
 * A row of three 16rem fields is 50rem, and the card has that much room only from `xl` (1280px):
 * below it — a tablet, or a laptop narrower than 1184px beside the sidebar — the row ran past the
 * card and took the page sideways with it. Below `xl` the row goes two to a line instead, and
 * `FieldRow`'s own rule still stacks it to one below `sm`.
 */
export const NARROW_TWO_UP =
  "max-xl:grid-flow-row max-xl:gap-y-4 max-xl:![grid-template-columns:repeat(2,minmax(0,1fr))]";

const LINK_BUTTON =
  "w-fit rounded-sm text-sm font-medium text-ink underline decoration-hairline-strong underline-offset-4 transition-colors duration-state hover:decoration-ink";

/** A kitchen as its band needs it: which one, what it is called. */
export interface BandKitchen {
  kitchenId: string;
  kitchenName: string;
}

/** One dish in a band, already resolved to what the row draws. */
export interface BandDish {
  recipeId: string;
  name: string;
  category: string;
  /** The recipe's yield unit, as the server spells it ("KG", "L", "PIECES"). */
  unit: string;
  /** Null where nobody has said yet — drawn empty, never as a nought. */
  target: number | null;
  /** The server's ceiling on one dish's amount, carried to the box as `max` (T-217). */
  max: number;
}

/** A kitchen the combobox can offer. */
export interface KitchenOption {
  id: string;
  name: string;
  staffCount: number;
}

/**
 * One kitchen's band: its heading, its dishes, a search to add one, and its own People needed and
 * Rostered. Edge to edge in the card (the section's `-mx-5` wrapper takes back the card's padding),
 * and sunken, so each kitchen reads as its own place inside the one meal.
 */
export function KitchenBand({
  kitchen,
  dishes,
  offered,
  needed,
  confirming,
  readout,
  names,
  action,
  footer,
  onNeeded,
  onAdd,
  onAmount,
  onDrop,
  onRemove,
  onConfirmRemove,
  onKeep,
}: {
  kitchen: BandKitchen;
  dishes: BandDish[];
  /** The recipes this band may add: already filtered for the day, and with every dish on the meal taken out. */
  offered: RecipeSummary[];
  needed: number | null;
  /** The inline "Remove … and its N dishes?" question is showing in this band. */
  confirming: boolean;
  readout: { value: string; tone: "neutral" | "warning" };
  /** The rostered staff by name, or null when there is nobody to name or nothing counted yet. */
  names: string | null;
  /** The third cell of the People needed row: *Ask for volunteers* or *View volunteer shift*, or nothing. */
  action: ReactNode;
  /** A line under the names: where the meal's volunteer shift stands, in the band that carries it. */
  footer: ReactNode;
  onNeeded: (v: number | null) => void;
  onAdd: (recipeId: string) => void;
  /** The box's raw text: an emptied box stays empty rather than becoming a number. */
  onAmount: (recipeId: string, raw: string) => void;
  onDrop: (recipeId: string) => void;
  /** Null for the last band: a meal nobody cooks is not a plan. */
  onRemove: (() => void) | null;
  onConfirmRemove: () => void;
  onKeep: () => void;
}) {
  const [query, setQuery] = useState("");
  const searchId = useId();
  const q = query.trim().toLowerCase();
  const matches = q
    ? offered
        .filter((r) => r.name.toLowerCase().includes(q) || (r.categoryName ?? "").toLowerCase().includes(q))
        .slice(0, 6)
    : [];
  const n = dishes.length;

  function pick(r: RecipeSummary) {
    onAdd(r.id);
    setQuery("");
  }

  return (
    <section aria-label={kitchen.kitchenName} className="border-y border-hairline bg-sunken px-5 pb-5 pt-3">
      <h3 className="flex flex-wrap items-center gap-x-3 border-b border-hairline-strong pb-2">
        <span className="text-base font-semibold text-ink">{kitchen.kitchenName}</span>
        <span className="text-sm text-ink-secondary">
          {n} {n === 1 ? "preparation" : "preparations"}
        </span>
        {onRemove && (
          <button
            type="button"
            aria-label={`Remove ${kitchen.kitchenName}`}
            onClick={onRemove}
            className="ml-auto flex min-h-touch w-9 items-center justify-center rounded-control text-ink-secondary transition-colors duration-state hover:bg-hairline"
          >
            <i aria-hidden="true" className="ti ti-x" />
          </button>
        )}
      </h3>

      {/* Asked in place, never in a browser dialog, and only when there is something to lose. */}
      {confirming && (
        <div
          role="group"
          aria-label={`Remove ${kitchen.kitchenName}`}
          className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-2 rounded-control border border-hairline-strong bg-raised p-4"
        >
          <p className="text-sm font-medium text-ink">
            Remove {kitchen.kitchenName} and its {n} {n === 1 ? "dish" : "dishes"}?
          </p>
          <span className="flex flex-wrap gap-2">
            <Button type="button" size="sm" variant="danger" onClick={onConfirmRemove}>
              Remove
            </Button>
            <Button type="button" size="sm" variant="ghost" onClick={onKeep}>
              Keep it
            </Button>
          </span>
        </div>
      )}

      <div className="mt-2 grid">
        {n === 0 && <p className="py-3 text-sm text-ink-secondary">No dishes yet. Add one below.</p>}
        {dishes.map((d) => (
          <div
            key={d.recipeId}
            className="flex flex-wrap items-center gap-x-3 gap-y-2 border-t border-hairline py-2 first:border-t-0"
          >
            <span className="grid min-w-[9rem] flex-1">
              <span className="text-sm text-ink">{d.name}</span>
              <span className="text-xs text-ink-muted">{d.category}</span>
            </span>
            {/* The mock's box-and-unit pair. The only class added to it is the rule for `Form`'s error
                slot, which `Form` places straight after a refused box (an amount over the 50,000
                ceiling, T-217): without it the red sentence would be a third item on this line,
                between the box and its unit. With it the pair wraps and the sentence takes a line of
                its own under them. Nothing changes while no sentence is showing. */}
            <span className="flex flex-wrap items-center gap-2 [&>[data-form-error-slot]]:order-last [&>[data-form-error-slot]]:basis-full">
              <input
                type="number"
                min={0}
                max={d.max}
                // The recipe's own unit, never chosen here: a dish measured in pieces is planned
                // as whole pieces (T-424). Rendered inside MealComposer's `<Form>`, so the
                // refusal lands in the error slot this row already styles.
                step={stepForUnit(d.unit)}
                // "Amount of Ladoo must be a whole number" becomes "Ladoo is counted in whole
                // pieces" (T-431). The row is already tight at 390, and the sentence takes a line
                // of its own in the error slot the wrapper above styles, so it has the width.
                {...countedBox(d.name, d.unit)}
                aria-label={`Amount of ${d.name}`}
                value={d.target ?? ""}
                onChange={(e) => onAmount(d.recipeId, e.target.value)}
                className={[
                  "min-h-touch w-20 rounded-control border bg-raised px-2 text-sm tabular-nums",
                  d.target === null || !(d.target > 0) || d.target > d.max ? "border-warning" : "border-hairline",
                ].join(" ")}
              />
              {/* The unit is the recipe's, never chosen here — nobody can plan ten litres of a dry
                  podi. Written the way it is said: a litre is "L", never a lower-cased "l". */}
              <span className="w-6 text-xs text-ink-muted">{unitLabel(d.unit)}</span>
            </span>
            <button
              type="button"
              aria-label={`Take ${d.name} off ${kitchen.kitchenName}`}
              onClick={() => onDrop(d.recipeId)}
              className="flex min-h-touch w-9 items-center justify-center rounded-control text-ink-secondary transition-colors duration-state hover:bg-hairline"
            >
              <i aria-hidden="true" className="ti ti-x" />
            </button>
          </div>
        ))}
      </div>

      {/* Add a dish: a search per kitchen. */}
      <div className="mt-2 grid gap-1">
        {/* The words in a span of their own: the design-system guard's shape for every field label
            ("a bare text node cannot carry padding"). The mock had the bare text; the design system
            wins, and what renders is the same, since FIELD_LABEL is on the label either way. */}
        <label htmlFor={searchId} className={FIELD_LABEL}>
          <span>Add a dish to {kitchen.kitchenName}</span>
        </label>
        <input
          id={searchId}
          type="search"
          value={query}
          placeholder="Search your recipes"
          onChange={(e) => {
            // Searching is not a change to the meal, so it does not reach the form's own change
            // listener — typing a word and leaving must not ask "Leave without saving?".
            e.stopPropagation();
            setQuery(e.target.value);
          }}
          onKeyDown={(e) => {
            // Always held, not only when there is a match: this box is inside the meal's form, and
            // Enter in a text box submits its form — which here would save the meal.
            if (e.key === "Enter") {
              e.preventDefault();
              if (matches[0]) pick(matches[0]);
            }
          }}
          className="min-h-touch w-full max-w-[26rem] rounded-control border border-hairline bg-raised px-3 text-sm"
        />
        {q && (
          <ul className="grid max-w-[26rem] rounded-control border border-hairline bg-raised py-1">
            {matches.length === 0 && (
              <li className="px-3 py-2 text-sm text-ink-secondary">No recipe matches “{query}”.</li>
            )}
            {matches.map((r) => (
              <li key={r.id}>
                <button
                  type="button"
                  onClick={() => pick(r)}
                  className="flex min-h-touch w-full items-center justify-between gap-3 px-3 text-left text-sm text-ink hover:bg-sunken"
                >
                  <span>{r.name}</span>
                  <span className="text-xs text-ink-muted">{r.categoryName}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      {/* The composer's old step 4, per kitchen: its own People needed, and its own staff counted. */}
      <FieldRow className={`mt-5 [grid-template-columns:repeat(3,minmax(12rem,1fr))] ${NARROW_TWO_UP}`}>
        <Counter onBand label="People needed" hint="Leave it empty until you know" value={needed} onChange={onNeeded} />
        <Readout onBand label={`Rostered in ${kitchen.kitchenName}`} value={readout.value} tone={readout.tone} />
        {action && (
          // The empty first cell sits in the shared label track, so the button lines up with the two
          // boxes, not their labels.
          <span className="contents">
            <span aria-hidden="true" />
            {action}
          </span>
        )}
      </FieldRow>
      {names && <p className="mt-2 text-sm text-ink-secondary">{names}</p>}
      {footer}
    </section>
  );
}

/**
 * "+ Add another kitchen": a link that opens a searchable list of the kitchens that plan meals here
 * and are not on this meal yet. An ARIA 1.2 combobox: focus stays in the text box, the arrow keys
 * move the highlighted option (announced through aria-activedescendant), Enter picks it and Escape
 * closes the list. The mouse picks by pressing an option.
 */
export function AddKitchen({ options, onPick }: { options: KitchenOption[]; onPick: (id: string) => void }) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const boxRef = useRef<HTMLDivElement>(null);
  const listId = useId();
  const labelId = useId();

  const q = query.trim().toLowerCase();
  const matches = options.filter((k) => k.name.toLowerCase().includes(q));
  const current = Math.min(active, Math.max(0, matches.length - 1));

  useEffect(() => {
    if (open) inputRef.current?.focus();
  }, [open]);

  // Pressing anywhere outside closes the list, as a native select does.
  useEffect(() => {
    if (!open) return;
    const away = (e: PointerEvent) => {
      if (!boxRef.current?.contains(e.target as Node)) close();
    };
    document.addEventListener("pointerdown", away);
    return () => document.removeEventListener("pointerdown", away);
  }, [open]);

  function close() {
    setOpen(false);
    setQuery("");
    setActive(0);
  }
  function pick(id: string) {
    onPick(id);
    close();
  }

  if (!open) {
    return (
      <button type="button" onClick={() => setOpen(true)} className={LINK_BUTTON}>
        + Add another kitchen
      </button>
    );
  }

  return (
    <div ref={boxRef} className="relative grid max-w-[26rem] gap-1">
      {/* In its own span for the same design-system rule as the dish search's label above. */}
      <label id={labelId} htmlFor={`${listId}-input`} className={FIELD_LABEL}>
        <span>Add another kitchen</span>
      </label>
      <input
        ref={inputRef}
        id={`${listId}-input`}
        role="combobox"
        aria-expanded="true"
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={matches.length ? `${listId}-${current}` : undefined}
        value={query}
        placeholder="Search kitchens"
        onChange={(e) => {
          // Looking for a kitchen changes nothing on the meal until one is picked.
          e.stopPropagation();
          setQuery(e.target.value);
          setActive(0);
        }}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown") {
            e.preventDefault();
            setActive((current + 1) % Math.max(1, matches.length));
          } else if (e.key === "ArrowUp") {
            e.preventDefault();
            setActive((current - 1 + matches.length) % Math.max(1, matches.length));
          } else if (e.key === "Enter") {
            // Held whether or not anything matches: Enter in a box inside the meal's form saves the meal.
            e.preventDefault();
            if (matches[current]) pick(matches[current].id);
          } else if (e.key === "Escape") {
            e.preventDefault();
            close();
          }
        }}
        className="min-h-touch w-full rounded-control border border-hairline px-3 text-sm"
      />
      <ul
        id={listId}
        role="listbox"
        aria-labelledby={labelId}
        className="absolute left-0 right-0 top-full z-20 mt-1 grid max-h-72 overflow-y-auto rounded-control border border-hairline bg-raised py-1 shadow-lift"
      >
        {matches.length === 0 && (
          <li role="presentation" className="px-3 py-2 text-sm text-ink-secondary">
            No kitchen matches “{query}”.
          </li>
        )}
        {matches.map((k, i) => (
          <li
            key={k.id}
            id={`${listId}-${i}`}
            role="option"
            aria-selected={i === current}
            // pointerdown, not click, and default prevented, so the text box keeps focus.
            onPointerDown={(e) => {
              e.preventDefault();
              pick(k.id);
            }}
            onPointerMove={() => setActive(i)}
            className={[
              "flex min-h-touch cursor-pointer items-center justify-between gap-3 px-3 text-sm text-ink",
              i === current ? "bg-sunken" : "",
            ].join(" ")}
          >
            <span>{k.name}</span>
            <span className="text-xs text-ink-muted">{k.staffCount} staff</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * A figure the form worked out rather than asked for.
 *
 * <p>Same three parts as every other field in the row — the label above the box, not inside it —
 * so it is a peer of the counters beside it and not a shape of its own. Its label being inside its
 * box is what made this pill impossible to align and what two previous fixes were aimed at.
 *
 * <p>A readout takes a warning tone when the figure is short of what the form was told it needs.
 * Quiet, and never a block: it is telling the planner something, not refusing them.
 *
 * <p>`onBand`: the box is drawn in `bg-sunken`, and inside a kitchen's band, which is itself sunken,
 * it would vanish into it — so there it takes the raised surface instead (the mock's rule).
 */
export function Readout({
  label,
  hint,
  value,
  tone = "neutral",
  onBand = false,
}: {
  label: string;
  /** The arithmetic behind the figure, on the figure rather than on the boxes that feed it. */
  hint?: string;
  value: string;
  tone?: "neutral" | "warning";
  onBand?: boolean;
}) {
  return (
    <span className="contents">
      <span className={ROW_LABEL}>
        <span>{label}</span>
        {hint && <InfoHint text={hint} label={label} />}
      </span>
      <span
        className={[
          "flex items-center rounded-control px-5 py-2 text-lg font-semibold leading-snug tabular-nums",
          tone === "warning" ? "bg-warning-bg text-warning" : onBand ? "bg-raised text-ink" : "bg-sunken text-ink",
        ].join(" ")}
      >
        {value}
      </span>
    </span>
  );
}

export function Counter({
  label,
  hint,
  value,
  onChange,
  onBand = false,
}: {
  label: string;
  hint?: string;
  /** Null draws an empty box — an honest answer where nobody has said, and not a nought. */
  value: number | null;
  onChange: (value: number | null) => void;
  /** Inside a kitchen's sunken band: the raised surface, as {@link Readout} explains. */
  onBand?: boolean;
}) {
  return (
    <span className="contents">
      {/* Not a `<label>`: the counter is three controls in one box — a minus, a figure and a plus —
          each carrying its own name. The "i" sits beside the word the way it does on a field. */}
      <span className={ROW_LABEL}>
        <span>{label}</span>
        {hint && <InfoHint text={hint} label={label} />}
      </span>
      <span className={`flex items-center justify-center gap-2 rounded-control px-3 py-1 ${onBand ? "bg-raised" : "bg-sunken"}`}>
        <button
          type="button"
          aria-label={`One fewer ${label.toLowerCase()}`}
          onClick={() => onChange((value ?? 0) - 1)}
          className="min-h-touch w-9 rounded-control text-lg text-ink-secondary transition-colors duration-state hover:bg-hairline"
        >
          −
        </button>
        <input
          type="number"
          min={0}
          aria-label={label}
          value={value ?? ""}
          onChange={(e) => onChange(e.target.value === "" ? null : Number(e.target.value))}
          className="w-16 bg-transparent text-center text-base tabular-nums text-ink outline-none"
        />
        <button
          type="button"
          aria-label={`One more ${label.toLowerCase()}`}
          onClick={() => onChange((value ?? 0) + 1)}
          className="min-h-touch w-9 rounded-control text-lg text-ink-secondary transition-colors duration-state hover:bg-hairline"
        >
          +
        </button>
      </span>
    </span>
  );
}
