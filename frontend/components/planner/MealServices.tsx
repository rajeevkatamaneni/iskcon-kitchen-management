"use client";

import { useCallback, useState } from "react";
import { Badge } from "@/components/ds/Badge";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { Card } from "@/components/ds/Card";
import { EmptyState } from "@/components/ds/EmptyState";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { InfoHint } from "@/components/ds/InfoHint";
import { BusyPot } from "@/components/Loading";
import { RecipePeek } from "@/components/RecipePeek";
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
import { cooksQuantity, hhmm, unitLabel } from "@/lib/format";
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
  // Unchecked by default since 2026-09-05. The recipes are pages a cook works from and throws away,
  // and most prints are the worksheet alone — a default that quietly attaches five pages of
  // ingredients to every card is a default that wastes paper on most of them.
  const [includeRecipes, setIncludeRecipes] = useState(false);
  // Null until somebody picks: the server says which language the picker should open on, and that
  // answer arrives after the first render.
  const [language, setLanguage] = useState<string | null>(null);
  const { data: offered } = useAuthedQuery(
    useCallback(
      (t?: string) => api.jobCardLanguages(meal.planDate, meal.mealKind, meal.eventName, t),
      [meal.planDate, meal.mealKind]
    )
  );
  const recipeLanguage = language ?? offered?.defaultLanguage ?? "en";
  // What the card is asked for, in one value: a language for the appendix, or the sentinel that
  // means the worksheet on its own.
  const printLanguage = includeRecipes ? recipeLanguage : "none";

  /**
   * What a preparation's quantities are in — the stored yield unit of the recipe behind it.
   *
   * <p>The code rather than the label, because what comes back from here is handed to the shared
   * formatter, which needs the unit to decide whether a figure should be said in the larger or the
   * smaller unit of its family.
   */
  const yieldUnit = (recipeId: string) =>
    recipes.find((r) => r.id === recipeId)?.baseYieldUnit ?? "";

  const live = meal.dishes.filter((dish) => dish.status !== "CANCELLED" || dish.notMade);
  const open = meal.dishes.filter((dish) => dish.status === "PLANNED");



  async function downloadPdf() {
    setPreparingPdf(true);
    try {
      const token = await getToken();
      await generateAndDownload({
        request: () =>
          api.requestJobCard(meal.planDate, meal.mealKind, meal.eventName, printLanguage, token),
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
        cook actually has unanswered: whether that is when the pots go on or when the food goes
        out. It is when the food must be ready, so it says so.

        Under the name, in the same weight as the day it belongs to: who is expected and how much
        that comes to. Below that, whatever else was said about this meal, left as it was.

        The acts sit top right with the state above them, so "not yet recorded" reads as a label on
        the button that answers it rather than as a badge floating off on its own.
      */}
      <header className="flex flex-wrap items-start gap-x-6 gap-y-3">
        <div className="grid min-w-[16rem] flex-1 gap-1">
          <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
            {/* An event is read by its own name (E4-S15 D1). The Saturday reading appears as
                "Children's Bhagavad-gita Reading", with the kind kept beside it in the smaller
                weight — a day of six meals all headed "Event" would be a list of one word. */}
            <span className="text-lg font-semibold text-ink">{meal.eventName || meal.mealKind}</span>
            {meal.eventName && <span className="text-sm text-ink-secondary">{meal.mealKind}</span>}
            <span className="text-sm text-ink-secondary">
              Ready by <span className="font-medium tabular-nums text-ink">{hhmm(meal.readyBy)}</span>
            </span>
            {/* Beside the time rather than over the buttons: whether this meal has been written
                down yet is part of what the meal is, and it reads with the name and the hour. */}
            {meal.recorded ? <Badge tone="success">Recorded</Badge> : <Badge>Not yet recorded</Badge>}
            <CrewPebble crew={crew} required={meal.crewRequired} />
          </div>

          {/* One line of facts, dot-separated. Built as a list rather than as a chain of
              `{x && <>·{x}</>}` fragments, because every one of those carries its own leading dot
              and the first fact present must not have one. An event with no head count made that
              visible: it dropped the servings and left the line opening on a stray dot.

              An event is planned by how much to make and not by how many people (E4-S15 D2), so its
              head count is routinely nobody. "0 servings" beside a name reads as a mistake somebody
              made rather than a question nobody was asked — an event's quantity lives on the
              preparations below. A main meal always has a head count, so it always says one. */}
          <div className="flex flex-wrap items-baseline gap-x-2 text-ink">
            {[
              headCount(meal) ? `${headCount(meal)} expected` : null,
              meal.plates > 0 ? `${meal.plates.toLocaleString("en-IN")} servings` : null,
              meal.occasionName,
              meal.deliveryAddress,
              // Who to ring, beside where it is going. Food that has left the building is the one
              // case where the person to call is part of what the meal is.
              meal.contactName
                ? `${meal.contactName}${meal.contactPhone ? ` · ${meal.contactPhone}` : ""}`
                : null,
              meal.purpose,
            ]
              .filter((fact): fact is string => Boolean(fact))
              .map((fact, i) => (
                <span key={fact} className="flex items-baseline gap-x-2">
                  {i > 0 && (
                    <span aria-hidden className="text-ink-muted">
                      ·
                    </span>
                  )}
                  <span>{fact}</span>
                </span>
              ))}
          </div>

          <TravelLine meal={meal} />

          {meal.kitchenNotes && <p className="text-sm text-ink-secondary">{meal.kitchenNotes}</p>}
        </div>

        <div className="grid justify-items-end gap-2">
          {meal.cardNumber && (
            <span className="text-xs tabular-nums text-ink-muted">{meal.cardNumber}</span>
          )}
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

      {/* Only an event repeats, and only one that is still to be cooked. There is nothing to say
          about repeating a Lunch: the temple cooks one every day of the year already. */}
      {!readOnly && !meal.recorded && meal.eventName && open.length > 0 && (
        <RepeatForward meal={meal} planId={open[0].id} onChanged={onChanged} onError={onError} />
      )}

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
                {cooksQuantity(dish.targetYield, yieldUnit(dish.recipeId))}
                {dish.actualServings != null && !dish.notMade && (
                  <span className="block text-xs font-normal text-ink-muted">
                    {/* Cooked and eaten used to be bare numbers — "248 cooked" against a target
                        that carried a unit, so the two figures on one row did not read as the same
                        kind of thing. */}
                    {cooksQuantity(dish.actualServings, yieldUnit(dish.recipeId))} cooked
                    {dish.consumedQuantity != null
                      ? ` · ${cooksQuantity(dish.consumedQuantity, yieldUnit(dish.recipeId))} eaten`
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
            // Matched to the composer's list of preparations (2026-09-05): `rounded-sm` and a border
            // do nothing to a native checkbox, and without accent-color a ticked one is browser blue.
            className="h-4 w-4 flex-none accent-accent"
          />
          Include the recipes
        </label>
        {includeRecipes && (
          <select
            aria-label={`Recipe language for ${meal.mealKind}`}
            value={recipeLanguage}
            onChange={(e) => setLanguage(e.target.value)}
            className="min-h-touch rounded-control border border-hairline px-3 text-sm"
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
        <Button size="sm" variant="secondary" disabled={preparingPdf} onClick={downloadPdf} busy={preparingPdf}>
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
      className="card mt-4 grid gap-3 p-5"
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

          {/*
            The stored unit and the plain figure, deliberately — not the cook's form the rest of the
            screen uses. This row is being edited: the two boxes to the right hold and submit the
            raw value in the recipe's own unit, and a Planned column reading "600 gm" beside boxes
            holding "0.6" is an invitation to type 600 into one of them. Where a readout sits beside
            the inputs it describes, it agrees with them (E11-S3 D5).
          */}
          <span className="w-24 text-right tabular-nums text-ink-secondary">
            {entry.planned.toLocaleString("en-IN")} {unitLabel(unit(entry.mealPlanId))}
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
              className="min-h-touch w-28 rounded-control border border-hairline px-3 text-right tabular-nums disabled:opacity-50"
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
              className="min-h-touch w-28 rounded-control border border-hairline px-3 text-right tabular-nums disabled:opacity-50"
            />
          </label>

          <label className="flex w-24 cursor-pointer items-center gap-2 text-sm text-ink-secondary">
            <input
              type="checkbox"
              checked={entry.notMade}
              aria-label={`${entry.recipeName} was not made`}
              onChange={(e) => set(entry.mealPlanId, { notMade: e.target.checked })}
              className="h-5 w-5 rounded-sm border-hairline-strong accent-accent"
            />
            Not made
          </label>
        </div>
      ))}

      {entries.some((e) => !e.notMade && e.consumed < e.cooked) && (
        <p className="text-sm text-ink-secondary">
          {leftovers(entries)
            .map((l) => `${l.name}: ${cooksQuantity(l.left, unit(l.mealPlanId))} left over`)
            .join(" · ")}
        </p>
      )}

      <label className="grid gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Anything worth noting</span>
        <input
          value={note}
          onChange={(e) => setNote(e.target.value)}
          placeholder="Ran short, sent out at 220"
          className="min-h-touch rounded-control border border-hairline px-3"
        />
      </label>

      <InlineNotice tone="info">
        Recording draws the ingredients from stock, against what was cooked.
      </InlineNotice>

      <div className="flex items-center gap-3">
        <Button size="sm" disabled={busy} onClick={save} busy={busy}>
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

/**
 * When to leave the temple for this delivery (E4-S16 D1).
 *
 * <p><strong>It says when to leave, not how long it takes.</strong> A driver can act on *leave the
 * temple by 11:15*; nobody can act on *37 minutes*, and everybody would have to do the subtraction
 * themselves, in their head, against a serving time they would have to go and look up. The range is
 * the working shown beside the answer, and it is worked backwards from the hour the guests sit down
 * rather than forwards from the ready-by.
 *
 * <p>Nothing at all on a pickup or an in-house event: there is no drive to describe, and a line
 * saying so on every meal in the temple would be noise on ninety-nine plans out of a hundred.
 *
 * <p><strong>Unavailable is one quiet sentence in the ordinary text colour.</strong> Not a red
 * error, not a spinner that never stops, and not a blank where something clearly should be. A
 * temple with no map service configured is the normal case, not a fault, and a map service must
 * never stand between a cook and a meal plan.
 */
function TravelLine({ meal }: { meal: MealServiceView }) {
  // The whole-meal facts live on every one of its rows, so the first preparation answers for the
  // meal. The estimate is asked for by plan id because that is what the endpoint takes.
  const delivery = meal.dishes.find(
    (dish) => dish.handover === "DELIVERY" && dish.status !== "CANCELLED"
  );
  const { data, error } = useAuthedQuery(
    useCallback(
      (t?: string) => (delivery ? api.travelEstimate(delivery.id, t) : Promise.resolve(null)),
      [delivery?.id]
    )
  );

  if (!delivery) return null;

  // A failed request is the same sentence as an unavailable estimate. The reader does not care
  // which of the two happened and neither does the meal plan.
  if (error || (data && !data.available)) {
    return (
      <p className="text-sm text-ink-secondary">{unavailableLine(error ? null : data?.reason ?? null)}</p>
    );
  }
  if (!data?.leaveBy) return null;

  const weekday = new Date(`${meal.planDate}T00:00:00`).toLocaleDateString("en-GB", {
    weekday: "long",
  });
  return (
    <p className="text-sm text-ink">
      Leave the temple by{" "}
      <span className="font-semibold tabular-nums">{hhmm(data.leaveBy)}</span>
      {data.optimisticMinutes != null && data.pessimisticMinutes != null && (
        <>
          {" "}
          — {data.optimisticMinutes} to {data.pessimisticMinutes} minutes in {weekday} traffic
        </>
      )}
      {data.guestsEatAt && (
        <span className="text-ink-secondary">, to be there before {hhmm(data.guestsEatAt)}</span>
      )}
    </p>
  );
}

/** Why there is no estimate, in the reader's terms rather than the reason code's. */
function unavailableLine(reason: string | null): string {
  switch (reason) {
    case "NO_SERVING_TIME":
      return "No travel estimate: nobody has said when the guests eat.";
    case "ADDRESS_NOT_FOUND":
      return "No travel estimate: we couldn’t find that address on the map.";
    case "NO_ROUTE":
      return "No travel estimate: we couldn’t work out a route today.";
    default:
      // NO_MAP_SERVICE, and anything a later provider invents. The temple has not been promised a
      // map service and is not being told off for the absence of one.
      return "No travel estimate for this delivery.";
  }
}

/**
 * Repeating an event forward for a number of weeks (E4-S15 D8).
 *
 * <p>What it makes is <strong>copies, not a series.</strong> Each one is a plan in its own right:
 * edit the third and the other five are untouched, cancel the fifth and nothing asks *this one or
 * all of them?* A true recurrence rule with per-occurrence exceptions was considered and deferred —
 * it is a feature that grows teeth, and the temple's actual problem is not wanting to type the same
 * Saturday reading fifty-two times.
 */
function RepeatForward({
  meal,
  planId,
  onChanged,
  onError,
}: {
  meal: MealServiceView;
  /** Any still-open preparation of the meal: the endpoint copies the whole event from one of them. */
  planId: string;
  onChanged: () => void;
  onError: (e: ApiError) => void;
}) {
  const { getToken } = useAuth();
  const [open, setOpen] = useState(false);
  const [weeks, setWeeks] = useState(6);
  const [busy, setBusy] = useState(false);
  const [outcome, setOutcome] = useState<string | null>(null);

  async function repeat() {
    setBusy(true);
    setOutcome(null);
    try {
      const result = await api.repeatEvent(planId, weeks, await getToken());
      // What it declined to do, said out loud. A planner who asked for six weeks and got four has
      // to know which two are missing, or they will find out on the day.
      const parts = [
        `${result.weeksCopied} ${result.weeksCopied === 1 ? "week" : "weeks"} copied`,
        `${result.copied} ${result.copied === 1 ? "preparation" : "preparations"}`,
      ];
      if (result.refusedOnFast > 0) {
        parts.push(
          `${result.refusedOnFast} skipped — a fast falls there that these preparations don’t suit`
        );
      }
      setOutcome(parts.join(" · ") + ".");
      onChanged();
    } catch (e) {
      onError(toApiError(e, "We couldn’t repeat that event."));
    } finally {
      setBusy(false);
    }
  }

  if (!open) {
    return (
      <div className="mt-3">
        <Button size="sm" variant="secondary" onClick={() => setOpen(true)}>
          Repeat it forward
        </Button>
        {outcome && <span className="ml-3 text-sm text-ink-secondary">{outcome}</span>}
      </div>
    );
  }

  return (
    <div className="mt-3 grid gap-2 rounded-lg bg-sunken p-4">
      <span className="flex flex-wrap items-center gap-3 text-sm text-ink">
        <label className="flex items-center gap-2">
          <span>Repeat {meal.eventName} every week for</span>
          <input
            type="number"
            min={1}
            max={52}
            aria-label="How many weeks"
            value={weeks}
            onChange={(e) => setWeeks(Math.max(1, Number(e.target.value) || 1))}
            className="min-h-touch w-20 rounded-control border border-hairline px-2 tabular-nums"
          />
          <span>weeks</span>
        </label>
        {/* What repeating actually makes, in the "i" beside the control that does it. Outside the
            `<label>` rather than in it: the "i" is a button, and a button inside a label can become
            the labelled thing in place of the box. */}
        <InfoHint
          text="Each week is a copy you can edit or cancel on its own — nothing links them together."
          label="Repeating it forward"
        />
        <Button size="sm" disabled={busy} onClick={repeat} busy={busy}>
          {busy ? "Copying…" : "Copy it forward"}
        </Button>
        <Button size="sm" variant="ghost" onClick={() => setOpen(false)}>
          Close
        </Button>
      </span>
      {/* Said here rather than as a banner: copies are plans, and the ones that landed are already
          on the days they landed on. */}
      {outcome && <span className="text-sm text-ink-secondary">{outcome}</span>}
    </div>
  );
}
