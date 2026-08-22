"use client";

import { useCallback, useState } from "react";
import { Badge } from "@/components/ds/Badge";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { Card } from "@/components/ds/Card";
import { EmptyState } from "@/components/ds/EmptyState";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { BusyPot } from "@/components/Loading";
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
import { languageLabel } from "@/lib/languages";

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
  onChanged: () => void;
  onError: (e: ApiError) => void;
}) {
  const [nonce, setNonce] = useState(0);
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

  const meals = (data ?? []).filter(
    (meal) => !meal.dishes.every((dish) => dish.status === "CANCELLED" && !dish.notMade)
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
        />
      ))}
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
}: {
  meal: MealServiceView;
  /** Who is rostered over this meal's ready-by, or null where nothing has been counted. */
  crew: MealCrewView | null;
  sufficiency: Map<string, MealSufficiency>;
  recipes: RecipeSummary[];
  readOnly: boolean;
  onChanged: () => void;
  onError: (e: ApiError) => void;
}) {
  const { getToken } = useAuth();
  const [recording, setRecording] = useState(false);
  const [editing, setEditing] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [preparingPdf, setPreparingPdf] = useState(false);

  // The card is two halves with two readers (build brief Q3). The worksheet is always English and
  // goes back to the office; the recipes are optional, and print in a language chosen here for the
  // cooks. The list is only the languages this meal's recipes are actually translated into — offering
  // one with nothing behind it would print an English appendix under a Kannada heading.
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

  const live = meal.dishes.filter((dish) => dish.status !== "CANCELLED" || dish.notMade);
  const open = meal.dishes.filter((dish) => dish.status === "PLANNED");

  async function act(fn: (t: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    try {
      await fn(await getToken());
      onChanged();
    } catch (e) {
      onError(toApiError(e, failure));
    } finally {
      setBusy(false);
    }
  }

  /**
   * Opens the card in a new window and prints it. It goes through fetch rather than a plain link
   * because the print endpoint wants an Authorization header — a temple's documents stay behind the
   * same access control as its data, so there is no URL that works without one.
   *
   * <p>It opens the print dialogue itself rather than leaving the window sitting there. A button
   * called <em>Print job card</em> that produces a page and stops has asked the person to do the
   * one thing they already told us they wanted, and on a shared kitchen machine the extra window
   * simply gets left open. Waiting for the document to lay out first matters: printing a page
   * mid-parse gives a half-drawn sheet.
   */
  async function print() {
    try {
      const res = await fetch(api.jobCardPrintUrl(meal.planDate, meal.mealKind, printLanguage), {
        headers: { Authorization: `Bearer ${await getToken()}` },
      });
      if (!res.ok) throw new Error("print failed");
      const html = await res.text();
      const w = window.open("", "_blank");
      if (w) {
        w.document.write(html);
        w.document.close();
        const go = () => {
          w.focus();
          w.print();
        };
        if (w.document.readyState === "complete") go();
        else w.addEventListener("load", go, { once: true });
      }
      onChanged();
    } catch {
      onError(toApiError(null, "We couldn’t open the job card. Download the PDF instead."));
    }
  }

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
      <header className="grid gap-3">
        <div className="flex flex-wrap items-baseline gap-x-4 gap-y-2">
          <span className="text-lg font-medium tabular-nums text-ink">{hhmm(meal.readyBy)}</span>
          <span className="text-lg font-semibold text-ink">{meal.mealKind}</span>
          <span className="text-sm text-ink-secondary">
            {meal.plates.toLocaleString("en-IN")} plates
            {meal.occasionName ? ` · ${meal.occasionName}` : ""}
            {headCount(meal) ? ` · ${headCount(meal)}` : ""}
            {meal.venue ? ` · ${meal.venue}` : ""}
            {meal.purpose ? ` · ${meal.purpose}` : ""}
          </span>
          <CrewPebble crew={crew} required={meal.crewRequired} />
          <span className="ml-auto flex flex-wrap items-center gap-2">
            {meal.cardNumber && <span className="text-xs tabular-nums text-ink-muted">{meal.cardNumber}</span>}
            {meal.recorded ? <Badge tone="success">Recorded</Badge> : <Badge>Not yet recorded</Badge>}
          </span>
        </div>

        {/* The three things you do to a whole meal, on the whole meal (item 16). They used to sit
            at the foot under its preparations, which read as though they belonged to the last one. */}
        <div className="flex flex-wrap items-center gap-2">
          {!readOnly && !meal.recorded && (
            <ButtonLink
              href={`/planner/${meal.planDate}/${encodeURIComponent(meal.mealKind)}`}
              size="sm"
              variant="secondary"
            >
              Edit
            </ButtonLink>
          )}
          <Button size="sm" variant="secondary" onClick={print}>
            Job card
          </Button>
          {!meal.recorded && open.length > 0 && !recording && (
            <Button size="sm" onClick={() => setRecording(true)}>
              Record what went out
            </Button>
          )}
        </div>
      </header>

      {meal.kitchenNotes && (
        <p className="mt-2 text-sm text-ink-secondary">{meal.kitchenNotes}</p>
      )}

      <div className="mt-4 grid">
        {live.map((dish) =>
          editing === dish.id ? (
            <EditDish
              key={dish.id}
              dish={dish}
              meal={meal}
              recipes={recipes}
              onCancel={() => setEditing(null)}
              onSaved={() => {
                setEditing(null);
                onChanged();
              }}
              onError={onError}
            />
          ) : (
            <div
              key={dish.id}
              className="flex flex-wrap items-center gap-3 border-t border-hairline py-3 first:border-t-0"
            >
              <span className="grid min-w-[14rem] flex-1">
                <span className="font-medium text-ink">{dish.recipeName}</span>
                <span className="text-xs text-ink-muted">
                  {Number(dish.targetYield).toLocaleString("en-IN")} planned
                  {dish.actualServings != null && !dish.notMade
                    ? ` · ${Number(dish.actualServings).toLocaleString("en-IN")} went out`
                    : ""}
                  {dish.ekadashiAcknowledged ? " · grains on a fasting day, acknowledged" : ""}
                </span>
              </span>

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

              {/* A dish can be swapped or edited right up until the meal is recorded, and never
                  after — what was cooked drew stock against a figure, and rewriting the figure
                  afterwards would leave the ledger describing a meal that never happened. */}
              {!readOnly && !meal.recorded && dish.status === "PLANNED" && (
                <span className="flex gap-2">
                  <Button size="sm" variant="secondary" onClick={() => setEditing(dish.id)}>
                    Swap or edit
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={busy}
                    onClick={() => act((t) => api.cancelMealPlan(dish.id, t), "We couldn’t cancel that preparation.")}
                  >
                    Cancel
                  </Button>
                </span>
              )}
            </div>
          )
        )}
      </div>

      {meal.recorded ? (
        <p className="mt-4 border-t border-hairline pt-3 text-sm text-ink-secondary">
          Recorded{meal.recordedByName ? ` by ${meal.recordedByName}` : ""}. What was cooked
          can’t be changed afterwards.
          {meal.recordingNote ? ` — ${meal.recordingNote}` : ""}
        </p>
      ) : (
        recording && (
          <RecordMeal
            meal={meal}
            dishes={open}
            onCancel={() => setRecording(false)}
            onSaved={() => {
              setRecording(false);
              onChanged();
            }}
            onError={onError}
          />
        )
      )}

      {/* How the card comes out, rather than whether it does: the acts are on the meal header, and
          these are the choices behind the one that prints. Marking off and signing are paper — the
          card carries the sign-off boxes, and the app carries no checklist, because a cook
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
            {(offered?.languages ?? ["en"]).map((code) => (
              <option key={code} value={code}>
                {languageLabel(code)}
              </option>
            ))}
          </select>
        )}
        <Button size="sm" variant="ghost" disabled={preparingPdf} onClick={downloadPdf}>
          {preparingPdf ? (
            <span className="inline-flex items-center gap-2">
              <BusyPot />
              Preparing PDF…
            </span>
          ) : (
            "Download PDF"
          )}
        </Button>
      </div>
    </Card>
  );
}

/**
 * The recording form: one meal, every dish on it, planned servings prefilled and editable to what
 * actually went out — which is the number the whole exercise is for.
 */
function RecordMeal({
  meal,
  dishes,
  onCancel,
  onSaved,
  onError,
}: {
  meal: MealServiceView;
  dishes: MealPlanView[];
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
      servings: Number(dish.targetYield),
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
            actualServings: e.notMade ? null : e.servings,
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
      <p className="text-sm text-ink-secondary">
        From the job card that came back. Change each figure to what actually went out.
      </p>

      {entries.map((entry) => (
        <div key={entry.mealPlanId} className="flex flex-wrap items-center gap-4">
          <span className="min-w-[12rem] flex-1 text-ink">{entry.recipeName}</span>
          <label className="flex items-center gap-2 text-sm text-ink-secondary">
            <span className="font-medium text-ink">Servings</span>
            <input
              type="number"
              min={1}
              step="any"
              aria-label={`Servings of ${entry.recipeName}`}
              value={entry.notMade ? "" : entry.servings}
              disabled={entry.notMade}
              onChange={(e) => set(entry.mealPlanId, { servings: Number(e.target.value) })}
              className="min-h-touch w-28 rounded border border-hairline bg-canvas px-3 tabular-nums disabled:opacity-50"
            />
          </label>
          <label className="flex cursor-pointer items-center gap-2 text-sm text-ink-secondary">
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
        Recording draws the ingredients from stock, against these figures. It can only be done once.
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

/** Swap the recipe or change the servings, in place (B4). The row keeps its identity and its past. */
function EditDish({
  dish,
  meal,
  recipes,
  onCancel,
  onSaved,
  onError,
}: {
  dish: MealPlanView;
  meal: MealServiceView;
  recipes: RecipeSummary[];
  onCancel: () => void;
  onSaved: () => void;
  onError: (e: ApiError) => void;
}) {
  const { getToken } = useAuth();
  const [recipeId, setRecipeId] = useState(dish.recipeId);
  // Held as a string, not a number, so an emptied box stays empty. `Number("")` is 0, and a 0 that
  // reaches the API is refused with a message about a field rather than about a dish — E2-S17.
  const [target, setTarget] = useState(String(dish.targetYield));
  const missingQuantity = target.trim() === "" || !(Number(target) > 0);
  const [busy, setBusy] = useState(false);

  async function save(acknowledge = false) {
    setBusy(true);
    try {
      await api.updateMealPlan(
        dish.id,
        {
          planDate: dish.planDate,
          mealKind: dish.mealKind,
          recipeId,
          targetYield: Number(target),
          readyBy: dish.readyBy.slice(0, 5),
          clientName: dish.clientName,
          clientContact: dish.clientContact,
          venue: dish.venue,
          purpose: dish.purpose,
          // The whole meal is sent on every update, so anything left out here is cleared. The
          // occasion and the crew are whole-meal facts carried on each preparation row, and a swap
          // of one recipe must not quietly forget which festival the meal is for or how many
          // people it takes.
          occasionName: dish.occasionName,
          adults: dish.adults,
          children: dish.children,
          seniors: dish.seniors,
          crewRequired: dish.crewRequired,
          kitchenNotes: dish.kitchenNotes,
          ekadashiAcknowledged: acknowledge,
        },
        await getToken()
      );
      onSaved();
    } catch (e) {
      const err = toApiError(e, "We couldn’t change that preparation.");
      // A grain preparation on a fasting day: the planner is asked, as when the meal was planned.
      if (err.code === "KMS-4917" && !acknowledge) {
        if (window.confirm("That recipe has grains or beans and this is a fasting day. Use it anyway?")) {
          await save(true);
          return;
        }
        setBusy(false);
        return;
      }
      onError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form
      aria-label={`Edit ${dish.recipeName}`}
      className="flex flex-wrap items-end gap-3 border-t border-hairline py-3 first:border-t-0"
      onSubmit={(e) => {
        e.preventDefault();
        save();
      }}
    >
      <label className="grid min-w-[14rem] flex-1 gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Preparation</span>
        <select
          aria-label={`Recipe for ${dish.recipeName}`}
          value={recipeId}
          onChange={(e) => setRecipeId(e.target.value)}
          className="min-h-touch rounded border border-hairline bg-canvas px-3"
        >
          {recipes.map((r) => (
            <option key={r.id} value={r.id}>
              {r.name}
            </option>
          ))}
        </select>
      </label>
      <label className="grid gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">How much to make</span>
        <input
          type="number"
          min={0}
          step="any"
          aria-label={`How much ${dish.recipeName} to make`}
          value={target}
          onChange={(e) => setTarget(e.target.value)}
          className={[
            "min-h-touch w-28 rounded border bg-canvas px-3 tabular-nums",
            missingQuantity ? "border-warning" : "border-hairline",
          ].join(" ")}
        />
      </label>
      <span className="text-xs text-ink-muted">
        {meal.mealKind} · {hhmm(meal.readyBy)}
      </span>
      <span className="flex gap-2">
        <Button type="submit" size="sm" disabled={busy || missingQuantity}>
          {busy ? "Saving…" : "Save"}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
      </span>
    </form>
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
function headCount(meal: MealServiceView): string {
  const parts: string[] = [];
  if (meal.adults) parts.push(`${meal.adults} adults`);
  if (meal.children) parts.push(`${meal.children} children`);
  if (meal.seniors) parts.push(`${meal.seniors} seniors`);
  return parts.join(", ");
}
