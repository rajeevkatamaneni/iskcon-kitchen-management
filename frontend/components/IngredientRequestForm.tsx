"use client";

import { useState } from "react";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { ErrorNotice } from "@/components/ErrorNotice";
import { BusyPot } from "@/components/Loading";
import {
  api,
  type ApiError,
  type IngredientRequestDetail,
  type IngredientRequestInput,
  type IngredientRequestStatus,
} from "@/lib/api";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { FOOD_UNITS, YIELD_UNITS, unitLabel } from "@/lib/format";

/**
 * Asking the store for ingredients (E10-S9), on the focus-screen shape recipes set.
 *
 * <p>Two commits from one form. A draft is allowed to be rough — somebody writing down what a
 * festival needs does it over a morning — so **Save as draft** asks only for the kitchen and the
 * date, which are the two things the request cannot exist without. **Submit for review** asks for
 * the rest, and it asks here rather than letting the server answer, because a person who has just
 * typed twenty lines should not have them bounced back with a reference code.
 *
 * <p>The dish list is the point of this screen and not paperwork: writing down what you are cooking
 * is what stops "let me get that too, just in case", and it is the other half of the comparison an
 * approver and later an auditor read the request against.
 */

/**
 * Which units belong together, so a line offers only the ones that can be true of its ingredient.
 *
 * <p>Half a kilo of a rice the catalogue holds in kilograms is 500 gm and is the same rice. Three
 * litres of it is not a quantity of rice at all, and the API refuses it — so it is never offered.
 * The vocabulary itself stays in `lib/format`; this only says which of it goes with which.
 */
const UNIT_FAMILY: Record<string, string> = {
  KG: "WEIGHT",
  GM: "WEIGHT",
  L: "VOLUME",
  ML: "VOLUME",
  PIECES: "COUNT",
};

/** Every food unit where the ingredient's own is unknown, and its family's where it is not. */
function unitsFor(ingredientUnit: string | undefined): readonly string[] {
  const family = ingredientUnit ? UNIT_FAMILY[ingredientUnit] : undefined;
  return family ? FOOD_UNITS.filter((u) => UNIT_FAMILY[u] === family) : FOOD_UNITS;
}

const DEFAULT_LINE_UNIT = FOOD_UNITS[0];
/** A dish genuinely is counted this way — "200 servings of khichdi" is how a kitchen says it. */
const DEFAULT_DISH_UNIT = "SERVINGS";

interface LineDraft {
  ingredientId: string;
  quantity: string;
  unit: string;
}

interface DishDraft {
  dishName: string;
  quantity: string;
  unit: string;
}

/** What the commit means: save it where it is, or save it and send it for review. */
export type CommitIntent = "SAVE" | "SUBMIT";

const fetchKitchens = (token: string | undefined) => api.listKitchens(false, token);
const fetchIngredients = (token: string | undefined) => api.listIngredients(token);

export function IngredientRequestForm({
  formId,
  task,
  cancelHref,
  status,
  initial,
  busy,
  error,
  onCommit,
}: {
  formId: string;
  /** The h1 — "New request", "Edit request". */
  task: string;
  cancelHref: string;
  /** Where the request stands. A new one is a draft that does not exist yet. */
  status: IngredientRequestStatus;
  initial?: IngredientRequestDetail;
  busy: boolean;
  error: ApiError | null;
  onCommit: (input: IngredientRequestInput, intent: CommitIntent) => void;
}) {
  const kitchens = useAuthedQuery(fetchKitchens);
  const ingredients = useAuthedQuery(fetchIngredients);

  const [kitchenId, setKitchenId] = useState(initial?.request.kitchenId ?? "");
  const [neededOn, setNeededOn] = useState(initial?.request.neededOn ?? "");
  const [purpose, setPurpose] = useState(initial?.request.purpose ?? "");
  const [lines, setLines] = useState<LineDraft[]>(
    initial && initial.lines.length > 0
      ? initial.lines.map((l) => ({
          ingredientId: l.ingredientId,
          quantity: String(l.quantity),
          unit: l.unit,
        }))
      : [{ ingredientId: "", quantity: "", unit: DEFAULT_LINE_UNIT }]
  );
  const [dishes, setDishes] = useState<DishDraft[]>(
    initial && initial.dishes.length > 0
      ? initial.dishes.map((d) => ({
          dishName: d.dishName,
          quantity: String(d.quantity),
          unit: d.unit,
        }))
      : [{ dishName: "", quantity: "", unit: DEFAULT_DISH_UNIT }]
  );

  // What this form refused to send, and why. Said here rather than left to the server, so the
  // answer arrives beside the field that needs filling in and nothing is lost on the way.
  const [problem, setProblem] = useState<string | null>(null);

  const ingredientOptions = ingredients.data ?? [];
  // A kitchen that plans its own meals draws its stock when a meal is recorded. Letting it also
  // ask the store would issue the same food twice, once through each door — so it is not offered,
  // and the API refuses it too.
  const kitchenOptions = (kitchens.data ?? []).filter((k) => !k.usesMealPlanner);

  function unitsForLine(line: LineDraft): readonly string[] {
    return unitsFor(ingredientOptions.find((i) => i.id === line.ingredientId)?.unit);
  }

  function setLine(index: number, patch: Partial<LineDraft>) {
    setLines((prev) => prev.map((l, i) => (i === index ? { ...l, ...patch } : l)));
  }

  /** Changing the ingredient can strand the unit in another family, so it moves with it. */
  function chooseIngredient(index: number, ingredientId: string) {
    const own = ingredientOptions.find((i) => i.id === ingredientId)?.unit;
    const allowed = unitsFor(own);
    setLines((prev) =>
      prev.map((l, i) =>
        i === index
          ? { ...l, ingredientId, unit: allowed.includes(l.unit) ? l.unit : own ?? allowed[0] }
          : l
      )
    );
  }

  function setDish(index: number, patch: Partial<DishDraft>) {
    setDishes((prev) => prev.map((d, i) => (i === index ? { ...d, ...patch } : d)));
  }

  function collect(): IngredientRequestInput {
    return {
      kitchenId,
      neededOn,
      purpose: purpose.trim() || null,
      lines: lines
        .filter((l) => l.ingredientId && l.quantity.trim())
        .map((l) => ({ ingredientId: l.ingredientId, quantity: Number(l.quantity), unit: l.unit })),
      dishes: dishes
        .filter((d) => d.dishName.trim() && d.quantity.trim())
        .map((d) => ({ dishName: d.dishName.trim(), quantity: Number(d.quantity), unit: d.unit })),
    };
  }

  function commit(intent: CommitIntent) {
    const input = collect();
    if (!input.kitchenId) {
      setProblem("Choose which kitchen this is for.");
      return;
    }
    if (!input.neededOn) {
      setProblem("Say when the kitchen needs it.");
      return;
    }
    if (intent === "SUBMIT") {
      if (input.lines.length === 0) {
        setProblem("Add at least one ingredient. A request that asks for nothing has nothing to answer.");
        return;
      }
      if (input.dishes.length === 0) {
        setProblem(
          "Say what you are cooking before you send this for review. An approver reads what you are asking for against what it is for, and cannot judge one without the other."
        );
        return;
      }
    }
    setProblem(null);
    onCommit(input, intent);
  }

  const awaitingReview = status === "SUBMITTED";
  const primaryLabel = awaitingReview ? "Save changes" : "Submit for review";

  return (
    <FocusScreen
      task={task}
      who={initial ? initial.request.reference : "For one of this temple’s kitchens"}
      activeHref="/ingredient-requests"
      actions={
        <>
          <ButtonLink href={cancelHref} variant="secondary">
            Cancel
          </ButtonLink>
          {!awaitingReview && (
            // Not a submit control, and deliberately: a draft is allowed to be incomplete, and a
            // form that validated it would be enforcing a rule the server does not have.
            <Button type="button" variant="secondary" disabled={busy} onClick={() => commit("SAVE")}>
              Save as draft
            </Button>
          )}
          <Button type="submit" form={formId} disabled={busy}>
            {busy ? (
              <span className="inline-flex items-center gap-2">
                <BusyPot />
                Saving…
              </span>
            ) : (
              primaryLabel
            )}
          </Button>
        </>
      }
    >
      <form
        id={formId}
        onSubmit={(event) => {
          event.preventDefault();
          commit(awaitingReview ? "SAVE" : "SUBMIT");
        }}
        className="space-y-8"
      >
        {error && <ErrorNotice error={error} />}
        {problem && <InlineNotice tone="warning" title={problem} />}

        <section className="space-y-5">
          <div className="grid grid-cols-1 gap-5 sm:grid-cols-2">
            {/* The hint is a sibling of the label rather than inside it: a label's accessible name
                is everything it contains, and a whole sentence of explanation would become the
                name of the field. */}
            <div className="flex flex-col gap-1">
              <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                <span className="pl-field-inset font-medium text-ink">Kitchen</span>
                <select
                  value={kitchenId}
                  onChange={(e) => setKitchenId(e.target.value)}
                  className="min-h-touch rounded border border-hairline bg-raised px-3"
                >
                  <option value="">Choose…</option>
                  {kitchenOptions.map((k) => (
                    <option key={k.id} value={k.id}>
                      {k.name}
                    </option>
                  ))}
                </select>
              </label>
              <p className="pl-field-inset text-sm text-ink-secondary">
                A kitchen that plans its own meals draws stock through the planner instead, so it is
                not on this list.
              </p>
            </div>

            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Needed on</span>
              <input
                type="date"
                value={neededOn}
                onChange={(e) => setNeededOn(e.target.value)}
                className="min-h-touch rounded border border-hairline bg-raised px-3"
              />
            </label>
          </div>

          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Reason</span>
            <textarea
              value={purpose}
              onChange={(e) => setPurpose(e.target.value)}
              rows={2}
              placeholder="Janmashtami feast — Sunday lunch for 400"
              className="rounded border border-hairline bg-raised px-3 py-2"
            />
          </label>
        </section>

        <section aria-labelledby="request-lines-heading" className="space-y-3">
          <h2 id="request-lines-heading" className="text-lg">
            What you need from the store
          </h2>
          {lines.map((line, i) => (
            <div key={i} className="grid grid-cols-[1fr_6rem_6rem_auto] items-end gap-2">
              <select
                aria-label={`Ingredient ${i + 1}`}
                value={line.ingredientId}
                onChange={(e) => chooseIngredient(i, e.target.value)}
                className="min-h-touch rounded border border-hairline bg-raised px-3"
              >
                <option value="">Choose ingredient…</option>
                {ingredientOptions.map((ing) => (
                  <option key={ing.id} value={ing.id}>
                    {ing.name}
                  </option>
                ))}
              </select>
              <input
                aria-label={`Quantity ${i + 1}`}
                type="number"
                min="0"
                step="any"
                value={line.quantity}
                onChange={(e) => setLine(i, { quantity: e.target.value })}
                placeholder="Qty"
                className="min-h-touch rounded border border-hairline bg-raised px-3"
              />
              <select
                aria-label={`Unit ${i + 1}`}
                value={line.unit}
                onChange={(e) => setLine(i, { unit: e.target.value })}
                className="min-h-touch rounded border border-hairline bg-raised px-2"
              >
                {unitsForLine(line).map((u) => (
                  <option key={u} value={u}>
                    {unitLabel(u)}
                  </option>
                ))}
              </select>
              <button
                type="button"
                onClick={() => setLines((prev) => prev.filter((_, at) => at !== i))}
                aria-label={`Remove ingredient ${i + 1}`}
                className="min-h-touch rounded border border-hairline-strong px-3 text-sm text-ink-secondary hover:bg-raised"
              >
                Remove
              </button>
            </div>
          ))}
          <button
            type="button"
            onClick={() =>
              setLines((prev) => [...prev, { ingredientId: "", quantity: "", unit: DEFAULT_LINE_UNIT }])
            }
            className="min-h-touch rounded border border-hairline-strong px-4 text-sm hover:bg-raised"
          >
            + Add ingredient
          </button>
        </section>

        <section aria-labelledby="request-dishes-heading" className="space-y-3">
          <h2 id="request-dishes-heading" className="text-lg">
            What you are cooking
          </h2>
          <p className="max-w-prose text-sm text-ink-secondary">
            Needed before this can go for review. It is what the approver reads your list against.
          </p>
          {dishes.map((dish, i) => (
            <div key={i} className="grid grid-cols-[1fr_6rem_7rem_auto] items-end gap-2">
              <input
                aria-label={`Dish ${i + 1}`}
                value={dish.dishName}
                onChange={(e) => setDish(i, { dishName: e.target.value })}
                placeholder="Khichdi"
                className="min-h-touch rounded border border-hairline bg-raised px-3"
              />
              <input
                aria-label={`Dish quantity ${i + 1}`}
                type="number"
                min="0"
                step="any"
                value={dish.quantity}
                onChange={(e) => setDish(i, { quantity: e.target.value })}
                placeholder="Qty"
                className="min-h-touch rounded border border-hairline bg-raised px-3"
              />
              <select
                aria-label={`Dish unit ${i + 1}`}
                value={dish.unit}
                onChange={(e) => setDish(i, { unit: e.target.value })}
                className="min-h-touch rounded border border-hairline bg-raised px-2"
              >
                {YIELD_UNITS.map((u) => (
                  <option key={u} value={u}>
                    {unitLabel(u)}
                  </option>
                ))}
              </select>
              <button
                type="button"
                onClick={() => setDishes((prev) => prev.filter((_, at) => at !== i))}
                aria-label={`Remove dish ${i + 1}`}
                className="min-h-touch rounded border border-hairline-strong px-3 text-sm text-ink-secondary hover:bg-raised"
              >
                Remove
              </button>
            </div>
          ))}
          <button
            type="button"
            onClick={() =>
              setDishes((prev) => [...prev, { dishName: "", quantity: "", unit: DEFAULT_DISH_UNIT }])
            }
            className="min-h-touch rounded border border-hairline-strong px-4 text-sm hover:bg-raised"
          >
            + Add dish
          </button>
        </section>
      </form>
    </FocusScreen>
  );
}
