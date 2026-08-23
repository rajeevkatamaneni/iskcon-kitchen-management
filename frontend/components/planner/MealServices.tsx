"use client";

import { useCallback, useState } from "react";
import { Badge } from "@/components/ds/Badge";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { Card } from "@/components/ds/Card";
import { EmptyState } from "@/components/ds/EmptyState";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { BusyPot } from "@/components/Loading";
import { RecipePeek, unitLabel } from "@/components/RecipePeek";
import {
  api,
  toApiError,
  type ApiError,
  type MealCrewView,
  type MealPlanView,
  type MealServiceView,
  type MealSufficiency,
  type RecipeSummary,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { generateAndDownload } from "@/lib/document-download";
import { hhmm } from "@/lib/format";
import { ALL_LANGUAGES } from "@/lib/languages";

/**
 * The day's meals, grouped the way a kitchen thinks of them: one block per meal kind, with its
 * dishes underneath.
 *
 * <p>Three things happen here and nowhere else. A dish can be swapped or re-scaled until the meal is
 * recorded (B4) — instead of cancel-and-re-add, which loses the row and its history. The job card
 * prints, on paper, in whichever language the temple wants (B5). And when the signed card comes back,
 * the whole meal is recorded in one form (§2): every dish listed, planned servings prefilled,
 * editable to what actually went out, with "not made" beside each.
 *
 * <p>What is deliberately absent is a <em>Mark cooked</em> button on each dish. Nobody with hot oil
 * in front of them touches a screen, and a tick tells the temple nothing; what actually went out
 * tells them, over a month, that their head counts are wrong and by how much.
 */
export function MealServices({
  date,
  sufficiency,
  recipes,
  readOnly,
  refreshKey = 0,
  only,
  onChanged,
  onError,
}: {
  date: string;
  sufficiency: Map<string, MealSufficiency>;
  recipes: RecipeSummary[];
  /** A day that has been and gone: it can still be recorded, but nothing about it can be re-planned. */
  readOnly: boolean;
  /**
   * Bumped by the screen around this one when it has changed the day — a meal planned in the
   * composer beneath, say. Re-reading on a prop rather than remounting keeps what the person had
   * already chosen here, such as the language their job card prints in.
   */
  refreshKey?: number;
  /**
   * Narrows the day to the meals still waiting to be written down — what the catching-up screen
   * shows. Absent, the day shows every meal on it, which is what the planner means by a day.
   */
  only?: "unrecorded";
  onChanged: () => void;
  onError: (e: ApiError) => void;
}) {
  const [nonce, setNonce] = useState(0);
  // Which recipe is being read over the planner, if any.
  const [peek, setPeek] = useState<{ recipeId: string; name: string } | null>(null);
  const { data, loading } = useAuthedQuery(
    useCallback(
      (t?: string) => {
        void nonce;
        void refreshKey;
        return api.mealServices(date, date, t);
      },
      [date, nonce, refreshKey]
    )
  );

  /**
   * How many hands each of the day's meals has against how many it needs (item 24). Read once for
   * the whole day rather than per block, and read from the same endpoint Today's workforce line
   * uses, so the two screens cannot disagree about the same lunch.
   */
  const { data: crew } = useAuthedQuery(
    useCallback(
      (t?: string) => {
        void nonce;
        void refreshKey;
        return api.mealCrew(date, date, t).catch(() => [] as MealCrewView[]);
      },
      [date, nonce, refreshKey]
    )
  );

  function changed() {
    setNonce((n) => n + 1);
    onChanged();
  }

  const meals = (data ?? [])
    .filter((meal) => !meal.dishes.every((dish) => dish.status === "CANCELLED" && !dish.notMade))
    // A meal is still waiting if it has never been recorded and something on it was cooked — the
    // same test the nudge on Today counts by, so the two screens cannot disagree about which ten.
    .filter(
      (meal) =>
        only !== "unrecorded" ||
        (!meal.recorded && meal.dishes.some((dish) => dish.status === "PLANNED"))
    );

  if (loading && meals.length === 0) {
    return null;
  }

  if (meals.length === 0) {
    return (
      <EmptyState title="Nothing planned for this day">
        {readOnly
          ? "No meals were planned for this day."
          : "Add the day’s meals below."}
      </EmptyState>
    );
  }

  return (
    <div className="grid gap-4">
      {meals.map((meal) => (
        <MealBlock
          key={meal.mealKind}
          meal={meal}
          crew={(crew ?? []).find((c) => c.mealKind === meal.mealKind) ?? null}
          sufficiency={sufficiency}
          recipes={recipes}
          readOnly={readOnly}
          onChanged={changed}
          onError={onError}
          onReadRecipe={(recipeId, name) => setPeek({ recipeId, name })}
        />
      ))}

      {/* One layer for the whole day rather than one per meal: only one recipe is ever being read. */}
      {peek && (
        <RecipePeek recipeId={peek.recipeId} name={peek.name} onClose={() => setPeek(null)} />
      )}
    </div>
  );
}

/** One meal: its dishes, its job card, and the record of what went out. */
function MealBlock({
  meal,
  crew,
  sufficiency,
  recipes,
  readOnly,
  onChanged,
  onError,
  onReadRecipe,
}: {
  meal: MealServiceView;
  /** Who is rostered over this meal's ready-by, or null where nothing has been counted. */
  crew: MealCrewView | null;
  sufficiency: Map<string, MealSufficiency>;
  recipes: RecipeSummary[];
  readOnly: boolean;
  onChanged: () => void;
  onError: (e: ApiError) => void;
  /** Opens one preparation's recipe over the planner. */
  onReadRecipe: (recipeId: string, name: string) => void;
}) {
  const { getToken } = useAuth();
  const [recording, setRecording] = useState(false);
  const [justRecorded, setJustRecorded] = useState(false);
  const [preparingPdf, setPreparingPdf] = useState(false);

  // The card is two halves with two readers (build brief Q3). The worksheet is always English and
  // goes back to the office; the recipes are optional, and print in a language chosen here for the
  // cooks — any of the 23, translated when the card is asked for. The list used to be narrowed to
  // what had already been translated, on the assumption that a temple's cooks read the language of
  // the state it stands in, which is not true of any kitchen this is for.
  const [includeRecipes, setIncludeRecipes] = useState(true);
  // Null until somebody picks: the server says which language the picker should open on, and that
  // answer arrives after the first render.
  const [language, setLanguage] = useState<string | null>(null);
  const { data: offered } = useAuthedQuery(
    useCallback(
      (t?: string) => api.jobCardLanguages(meal.planDate, meal.mealKind, t),
      [meal.planDate, meal.mealKind]
    )
  );
  const recipeLanguage = language ?? offered?.defaultLanguage ?? "en";
  // What the card is asked for, in one value: a language for the appendix, or the sentinel that
  // means the worksheet on its own.
  const printLanguage = includeRecipes ? recipeLanguage : "none";

  /** What a preparation's quantities are in — the yield unit of the recipe behind it. */
  const yieldUnit = (recipeId: string) =>
    unitLabel(recipes.find((r) => r.id === recipeId)?.baseYieldUnit);

  const live = meal.dishes.filter((dish) => dish.status !== "CANCELLED" || dish.notMade);
  const open = meal.dishes.filter((dish) => dish.status === "PLANNED");



  async function downloadPdf() {
    setPreparingPdf(true);
    try {
      const token = await getToken();
      await generateAndDownload({
        request: () => api.requestJobCard(meal.planDate, meal.mealKind, printLanguage, token),
        status: (documentId) => api.getJobCardDocument(documentId, token),
        download: (documentId) => api.downloadJobCardDocument(documentId, token),
        filename: `${meal.cardNumber ?? "job-card"}.pdf`,
      });
      onChanged();
    } catch (e) {
      onError(toApiError(e, "We couldn’t generate that job card."));
    } finally {
      setPreparingPdf(false);
    }
  }

  return (
    <Card padding="p-6">
      {/*
        The meal names itself first and says when it is wanted second — "Lunch, ready by 12:00",
        the way a kitchen says it. The bare "12:00 Lunch" it used to read left the one question a
        cook actually has unanswered: whether that is when the pots go on or when the plates go
        out. It is when the food must be ready, so it says so.

        Under the name, in the same weight as the day it belongs to: who is expected and how much
        that comes to. Below that, whatever else was said about this meal, left as it was.

        The acts sit top right with the state above them, so "not yet recorded" reads as a label on
        the button that answers it rather than as a badge floating off on its own.
      */}
      <header className="flex flex-wrap items-start gap-x-6 gap-y-3">
        <div className="grid min-w-[16rem] flex-1 gap-1">
          <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
            <span className="text-lg font-semibold text-ink">{meal.mealKind}</span>
            <span className="text-sm text-ink-secondary">
              Ready by <span className="font-medium tabular-nums text-ink">{hhmm(meal.readyBy)}</span>
            </span>
            <CrewPebble crew={crew} required={meal.crewRequired} />
          </div>

          <div className="flex flex-wrap items-baseline gap-x-2 text-ink">
            {headCount(meal) && <span>{headCount(meal)} expected</span>}
            {headCount(meal) && <span aria-hidden className="text-ink-muted">·</span>}
            <span>{meal.plates.toLocaleString("en-IN")} servings</span>
            {meal.occasionName && (
              <>
                <span aria-hidden className="text-ink-muted">·</span>
                <span>{meal.occasionName}</span>
              </>
            )}
            {meal.venue && (
              <>
                <span aria-hidden className="text-ink-muted">·</span>
                <span>{meal.venue}</span>
              </>
            )}
            {meal.purpose && (
              <>
                <span aria-hidden className="text-ink-muted">·</span>
                <span>{meal.purpose}</span>
              </>
            )}
          </div>

          {meal.kitchenNotes && <p className="text-sm text-ink-secondary">{meal.kitchenNotes}</p>}
        </div>

        <div className="grid justify-items-end gap-2">
          <span className="flex items-center gap-2">
            {meal.cardNumber && (
              <span className="text-xs tabular-nums text-ink-muted">{meal.cardNumber}</span>
            )}
            {meal.recorded ? <Badge tone="success">Recorded</Badge> : <Badge>Not yet recorded</Badge>}
          </span>
          <span className="flex flex-wrap items-center justify-end gap-2">
            {!meal.recorded && open.length > 0 && !recording && (
              <Button size="sm" onClick={() => setRecording(true)}>
                Record actuals
              </Button>
            )}
            {!readOnly && !meal.recorded && (
              <ButtonLink
                href={`/planner/${meal.planDate}/${encodeURIComponent(meal.mealKind)}`}
                size="sm"
                variant="secondary"
              >
                Edit
              </ButtonLink>
            )}
          </span>
        </div>
      </header>

      <div className="mt-4 grid">
        {live.map((dish) => (
            <div
              key={dish.id}
              className="flex flex-wrap items-center gap-3 border-t border-hairline py-3 first:border-t-0"
            >
              {/* The name, then what the name is doing — the state of the preparation sits beside
                  it rather than across the row, because "Kosu Palya, short of ingredients" is one
                  fact and reading it used to mean crossing an empty gap to find the second half.
                  Pressing the name opens the recipe over the planner: a preparation is worth
                  reading before it is committed to, and that was previously a trip off this screen
                  and back. */}
              <span className="flex min-w-[14rem] flex-1 flex-wrap items-center gap-x-3 gap-y-1">
                <button
                  type="button"
                  onClick={() => onReadRecipe(dish.recipeId, dish.recipeName)}
                  className="rounded-sm font-medium text-ink underline decoration-hairline-strong underline-offset-4 transition-colors duration-state hover:decoration-ink"
                >
                  {dish.recipeName}
                </button>

                {dish.notMade ? (
                  <Badge tone="warning">Not made</Badge>
                ) : dish.status === "COOKED" ? (
                  <Badge tone="success">Cooked</Badge>
                ) : sufficiency.get(dish.id)?.status === "SHORT" ? (
                  <Badge tone="danger">Short of ingredients</Badge>
                ) : sufficiency.get(dish.id)?.status === "SUFFICIENT" ? (
                  <Badge tone="success">Ingredients ready</Badge>
                ) : (
                  <Badge>Planned</Badge>
                )}

                {dish.ekadashiAcknowledged && (
                  <span className="text-xs text-ink-muted">
                    grains on a fasting day, acknowledged
                  </span>
                )}
              </span>

              {/* What this preparation is for, on the right, in the name's own size and colour:
                  the quantity is half of what the row says and was being whispered under it. */}
              <span className="text-right font-medium text-ink">
                {Number(dish.targetYield).toLocaleString("en-IN")} {yieldUnit(dish.recipeId)}
                {dish.actualServings != null && !dish.notMade && (
                  <span className="block text-xs font-normal text-ink-muted">
                    {Number(dish.actualServings).toLocaleString("en-IN")} cooked
                    {dish.consumedQuantity != null
                      ? ` · ${Number(dish.consumedQuantity).toLocaleString("en-IN")} eaten`
                      : ""}
                  </span>
                )}
              </span>
            </div>
        ))}
      </div>

      {/* Saying so, and then getting out of the way. The form closes itself on success — leaving it
          open over the figures it just saved asks the person to work out whether anything happened. */}
      {justRecorded && (
        <div className="mt-4">
          <InlineNotice tone="success" autoDismiss title={`${meal.mealKind} is recorded.`}>
            The ingredients have been drawn from stock against what was cooked.
          </InlineNotice>
        </div>
      )}

      {meal.recorded ? (
        <p className="mt-4 border-t border-hairline pt-3 text-sm text-ink-secondary">
          Recorded{meal.recordedByName ? ` by ${meal.recordedByName}` : ""}.
          {meal.recordingNote ? ` — ${meal.recordingNote}` : ""}
        </p>
      ) : (
        recording && (
          <RecordMeal
            meal={meal}
            dishes={open}
            unit={(mealPlanId) =>
              yieldUnit(meal.dishes.find((d) => d.id === mealPlanId)?.recipeId ?? "")
            }
            onCancel={() => setRecording(false)}
            onSaved={() => {
              setRecording(false);
              setJustRecorded(true);
              onChanged();
            }}
            onError={onError}
          />
        )
      )}

      {/* The job card, in one place. There were two of it: a "Job card" button on the header that
          opened a printable copy in a new tab, and a "Download PDF" link down here — two controls
          for one document, and the choices that shape it (the recipes, the language) attached to
          only one of them. This row is the whole of it now. Marking off and signing are paper —
          the card carries the sign-off boxes, and the app carries no checklist, because a cook
          mid-service will not use one. */}
      <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-hairline pt-4">
        <label className="flex min-h-touch cursor-pointer items-center gap-2 text-sm text-ink">
          <input
            type="checkbox"
            checked={includeRecipes}
            aria-label={`Include the recipes with the ${meal.mealKind} card`}
            onChange={(e) => setIncludeRecipes(e.target.checked)}
            className="h-5 w-5 rounded-sm border-hairline-strong"
          />
          Include the recipes
        </label>
        {includeRecipes && (
          <select
            aria-label={`Recipe language for ${meal.mealKind}`}
            value={recipeLanguage}
            onChange={(e) => setLanguage(e.target.value)}
            className="min-h-touch rounded border border-hairline bg-canvas px-3 text-sm"
          >
            {/* Every language, from the one list the application keeps. The server is asked only
                which one to open on — the offer itself does not depend on a round trip, so a slow
                or failed call cannot silently shrink a picker of 23 down to English. */}
            {ALL_LANGUAGES.map((language) => (
              <option key={language.code} value={language.code}>
                {language.label}
              </option>
            ))}
          </select>
        )}
        <Button size="sm" variant="secondary" disabled={preparingPdf} onClick={downloadPdf}>
          {preparingPdf ? (
            <span className="inline-flex items-center gap-2">
              <BusyPot />
              Preparing the card…
            </span>
          ) : (
            "Download job card"
          )}
        </Button>
      </div>
    </Card>
  );
}

/**
 * The recording form: one meal, every preparation on it, and the three figures that make the plan
 * answerable — what was planned, what was cooked, and what was eaten.
 */
function RecordMeal({
  meal,
  dishes,
  unit,
  onCancel,
  onSaved,
  onError,
}: {
  meal: MealServiceView;
  dishes: MealPlanView[];
  /** What a preparation is measured in, so every figure on the form carries its unit. */
  unit: (mealPlanId: string) => string;
  onCancel: () => void;
  onSaved: () => void;
  onError: (e: ApiError) => void;
}) {
  const { getToken } = useAuth();
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");
  const [entries, setEntries] = useState(() =>
    dishes.map((dish) => ({
      mealPlanId: dish.id,
      recipeName: dish.recipeName,
      planned: Number(dish.targetYield),
      // Both start at the plan, because the plan is what the kitchen was told to do and most days
      // it is very nearly what happened. Typing is then a correction rather than a transcription.
      cooked: Number(dish.targetYield),
      consumed: Number(dish.targetYield),
      notMade: false,
    }))
  );

  function set(id: string, patch: Partial<(typeof entries)[number]>) {
    setEntries((list) => list.map((e) => (e.mealPlanId === id ? { ...e, ...patch } : e)));
  }

  async function save() {
    setBusy(true);
    try {
      await api.recordMeal(
        {
          planDate: meal.planDate,
          mealKind: meal.mealKind,
          note: note.trim() || null,
          dishes: entries.map((e) => ({
            mealPlanId: e.mealPlanId,
            actualServings: e.notMade ? null : e.cooked,
            consumedQuantity: e.notMade ? null : e.consumed,
            notMade: e.notMade,
          })),
        },
        await getToken()
      );
      onSaved();
    } catch (e) {
      onError(toApiError(e, "We couldn’t record that meal."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section
      aria-label={`Record ${meal.mealKind}`}
      className="mt-4 grid gap-3 rounded-lg bg-raised p-5"
    >
      {/*
        Three figures, read across: what the plan asked for, what the kitchen made, and what people
        actually ate. The form used to collect one — "servings" — and folded the other two into it,
        which is why nobody could answer the question the job card was invented to ask. What came
        back is the difference between the last two, and it is worth more than either.
      */}
      <p className="text-sm text-ink-secondary">
        From the job card that came back. Both figures start at the plan — change what differed.
      </p>

      <div className="hidden gap-4 px-1 text-xs font-semibold uppercase tracking-wide text-ink-secondary sm:flex">
        <span className="min-w-[12rem] flex-1">Preparation</span>
        <span className="w-24 text-right">Planned</span>
        <span className="w-28 text-right">Cooked</span>
        <span className="w-28 text-right">Consumed</span>
        <span className="w-24" />
      </div>

      {entries.map((entry) => (
        <div key={entry.mealPlanId} className="flex flex-wrap items-center gap-4">
          <span className="min-w-[12rem] flex-1 text-ink">{entry.recipeName}</span>

          <span className="w-24 text-right tabular-nums text-ink-secondary">
            {entry.planned.toLocaleString("en-IN")}
            <span className="ml-1 text-xs">{unit(entry.mealPlanId)}</span>
          </span>

          <label className="flex items-center gap-2">
            <span className="text-sm text-ink-secondary sm:sr-only">Cooked</span>
            <input
              type="number"
              min={0}
              step="any"
              aria-label={`How much ${entry.recipeName} was cooked`}
              value={entry.notMade ? "" : entry.cooked}
              disabled={entry.notMade}
              onChange={(e) => {
                const cooked = Number(e.target.value);
                // Nothing can be eaten that was never made, so the figure below follows this one
                // down rather than being left describing an impossible meal.
                set(entry.mealPlanId, {
                  cooked,
                  consumed: Math.min(entry.consumed, cooked),
                });
              }}
              className="min-h-touch w-28 rounded border border-hairline bg-canvas px-3 text-right tabular-nums disabled:opacity-50"
            />
          </label>

          <label className="flex items-center gap-2">
            <span className="text-sm text-ink-secondary sm:sr-only">Consumed</span>
            <input
              type="number"
              min={0}
              max={entry.cooked}
              step="any"
              aria-label={`How much ${entry.recipeName} was eaten`}
              value={entry.notMade ? "" : entry.consumed}
              disabled={entry.notMade}
              onChange={(e) => set(entry.mealPlanId, { consumed: Number(e.target.value) })}
              className="min-h-touch w-28 rounded border border-hairline bg-canvas px-3 text-right tabular-nums disabled:opacity-50"
            />
          </label>

          <label className="flex w-24 cursor-pointer items-center gap-2 text-sm text-ink-secondary">
            <input
              type="checkbox"
              checked={entry.notMade}
              aria-label={`${entry.recipeName} was not made`}
              onChange={(e) => set(entry.mealPlanId, { notMade: e.target.checked })}
              className="h-5 w-5 rounded-sm border-hairline-strong"
            />
            Not made
          </label>
        </div>
      ))}

      {entries.some((e) => !e.notMade && e.consumed < e.cooked) && (
        <p className="text-sm text-ink-secondary">
          {leftovers(entries)
            .map((l) => `${l.name}: ${l.left.toLocaleString("en-IN")} ${unit(l.mealPlanId)} left over`)
            .join(" · ")}
        </p>
      )}

      <label className="grid gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Anything worth noting</span>
        <input
          value={note}
          onChange={(e) => setNote(e.target.value)}
          placeholder="Ran short, sent out at 220"
          className="min-h-touch rounded border border-hairline bg-canvas px-3"
        />
      </label>

      <InlineNotice tone="info">
        Recording draws the ingredients from stock, against what was cooked.
      </InlineNotice>

      <div className="flex items-center gap-3">
        <Button size="sm" disabled={busy} onClick={save}>
          {busy ? (
            <span className="inline-flex items-center gap-2">
              <BusyPot />
              Recording…
            </span>
          ) : (
            "Record this meal"
          )}
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
      </div>
    </section>
  );
}


/**
 * How many hands this meal has against how many it takes — "5 of 8" (item 24).
 *
 * <p>Absent where nobody has said how many it takes. Null is not zero, and a meal planned weeks
 * before anybody is rostered must not be drawn as short of a number it was never given. Short, it
 * takes the warning tone and nothing more: it is telling the kitchen something, not refusing it.
 */
function CrewPebble({ crew, required }: { crew: MealCrewView | null; required: number | null }) {
  if (required == null) return null;
  const rostered = crew?.rostered ?? 0;
  const short = rostered < required;

  return (
    <span
      title={`${crew?.staffIn ?? 0} staff and ${crew?.volunteers ?? 0} volunteers, of ${required} needed`}
      className={[
        "inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold tabular-nums",
        short ? "bg-warning-bg text-warning" : "bg-sunken text-ink",
      ].join(" ")}
    >
      <i aria-hidden="true" className="ti ti-users" />
      {rostered} of {required}
      <span className="sr-only"> people rostered of the number this meal takes</span>
    </span>
  );
}

/** "200 adults, 40 children, 30 seniors" — the count the servings were worked out from. */
/** What each preparation had left, for the line under the figures. */
function leftovers(
  entries: { mealPlanId: string; recipeName: string; cooked: number; consumed: number; notMade: boolean }[]
) {
  return entries
    .filter((e) => !e.notMade && e.consumed < e.cooked)
    .map((e) => ({ mealPlanId: e.mealPlanId, name: e.recipeName, left: e.cooked - e.consumed }));
}

function headCount(meal: MealServiceView): string {
  const parts: string[] = [];
  if (meal.adults) parts.push(`${meal.adults} adults`);
  if (meal.children) parts.push(`${meal.children} children`);
  if (meal.seniors) parts.push(`${meal.seniors} seniors`);
  return parts.join(", ");
}
