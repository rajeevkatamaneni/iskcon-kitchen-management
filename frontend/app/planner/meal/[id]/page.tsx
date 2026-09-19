"use client";

import { Suspense, useCallback, useState } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Loading } from "@/components/Loading";
import { RequireRole } from "@/components/RequireRole";
import { MealComposer, type ComposerStatus } from "@/components/planner/MealComposer";
import { SeriesLine } from "@/components/planner/MealServices";
import { FROM, plannerUrl, safeReturn, withParam } from "@/components/planner/plannerAddress";
import { api } from "@/lib/api";
import { hhmm, longDate, todayIso } from "@/lib/format";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * Editing one meal (item 16), addressed by the meal's own id — `/planner/meal/<id>` (D-27).
 *
 * <p>It was `/planner/[date]/[kind]`, and that address was the whole problem D-27 set out to remove:
 * a meal named by its date and its kind's name. Two events on one Saturday share a kind, so the kind
 * could not say which one was meant, and a renamed kind broke every link anybody had kept. Rajeev,
 * 2026-09-13: *"identifying things by text is a terrible idea and one that WILL fail eventually."*
 * The id is what a meal is now, and it is what a volunteer shift points at too.
 *
 * <p>Why `meal/[id]` and not `[id]` at the top of the planner: `/planner/[date]` already takes the
 * first segment, and a static `meal` beside it is matched first and can never be read as a date.
 *
 * <p>It is the composer inside the focus screen: its own URL, the sidebar still there, the task as
 * the heading, and one pair of buttons top right. Leaving it with changes nothing has saved asks
 * first — that lives in the composer, which knows what changed.
 *
 * <p><b>Where every way out leads</b> (T-219). Back to the screen that opened it — the planner in the
 * view and on the date it was showing, carried here as `?from=` — or, when nothing opened it, to the
 * planner's day view on this meal's date. Cancel, the save and "Leave without saving?" all go to the
 * same place, because the last of those simply follows whichever link was pressed. See
 * `components/planner/plannerAddress.ts` for why `from` is checked before it is followed.
 */

const FORM = "edit-meal";

export default function EditMealPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      {/* The way back is read from the query string, and that needs a boundary. */}
      <Suspense>
        <EditMealScreen />
      </Suspense>
    </RequireRole>
  );
}

function EditMealScreen() {
  const params = useParams<{ id: string }>();
  const router = useRouter();
  const search = useSearchParams();
  const origin = safeReturn(search.get(FROM));
  const raw = params?.id;
  const id = (Array.isArray(raw) ? raw[0] : raw) ?? null;

  const mealQ = useAuthedQuery(
    useCallback((t?: string) => (id ? api.getMeal(id, t) : Promise.resolve(null)), [id])
  );
  const meal = mealQ.data;
  const date = meal?.planDate ?? null;

  const recipesQ = useAuthedQuery(useCallback((t?: string) => api.listRecipes({}, t), []));
  const { data: mealKinds } = useAuthedQuery(api.listMealKinds);
  const calQ = useAuthedQuery(
    useCallback((t?: string) => (date ? api.calendarRange(date, date, t) : Promise.resolve([])), [date])
  );

  // The composer owns the form; the focus screen owns the button that commits it, so it has to be
  // told what the form knows. Compared before it is stored, because setting an object that has not
  // changed re-renders, and a re-render that reports again is a loop.
  const [status, setStatus] = useState<ComposerStatus>({ busy: false, blocked: true, hint: null });
  const onStatus = useCallback((next: ComposerStatus) => {
    setStatus((prev) =>
      prev.busy === next.busy && prev.blocked === next.blocked && prev.hint === next.hint ? prev : next
    );
  }, []);

  // Not found and not this temple's are the same answer from the server (KMS-400030), and the error
  // notice says it in the server's own words with the code to quote.
  if (mealQ.error) {
    return (
      <FocusScreen
        task="Edit a meal"
        activeHref="/planner"
        actions={<ButtonLink href={origin ?? "/planner"} variant="secondary">Open the planner</ButtonLink>}
      >
        <ErrorNotice error={mealQ.error} />
      </FocusScreen>
    );
  }

  // The whole screen is the composer, and a composer with no recipes says "no recipes yet". Waiting
  // for them is the difference between that sentence being the truth and being a flash of it.
  if (!meal || recipesQ.loading) {
    return (
      <FocusScreen task="Edit a meal" activeHref="/planner">
        <Loading label="Loading the meal…" />
      </FocusScreen>
    );
  }

  const name = meal.eventName || meal.mealKind;
  // Where the person came from, or the planner's own day view on this meal's date. It used to be
  // `/planner/<date>`, a separate page for one day without the planner's tabs or date stepper, so
  // Cancel on a meal opened from the week left the person somewhere they had never been (T-219).
  const backToDay = origin ?? plannerUrl("day", meal.planDate);

  // What was cooked drew stock against a figure, so this screen — which re-plans a meal — stops at the
  // moment the meal was recorded. That is not the same as saying the figures are permanent (T-007): a
  // Temple Admin can correct them from the day, as a compensating entry that moves the stock with it,
  // which is why the notice points there. A cancelled meal is read, not re-planned; planning it again
  // is planning a meal, from the day.
  const closed = meal.recorded || meal.planDate < todayIso() || meal.status === "CANCELLED";
  if (closed) {
    return (
      <FocusScreen
        task={`Edit ${name}`}
        who={`${longDate(meal.planDate)} · ${hhmm(meal.readyBy)}`}
        activeHref="/planner"
        actions={<ButtonLink href={backToDay} variant="secondary">Open the day</ButtonLink>}
      >
        <InlineNotice tone="info">
          {meal.recorded
            ? "This meal has been recorded, so its plan can no longer be changed. If the figures " +
              "are wrong, a Temple Admin can correct them from the day."
            : meal.status === "CANCELLED"
              ? "This meal was cancelled, so its plan can be read but not changed."
              : "This day has passed, so its plan can be read but not changed."}
        </InlineNotice>
      </FocusScreen>
    );
  }

  return (
    <FocusScreen
      task={`Edit ${name}`}
      who={`${longDate(meal.planDate)} · ${hhmm(meal.readyBy)} · ${meal.plates.toLocaleString("en-IN")} servings`}
      activeHref="/planner"
      actions={
        <>
          <ButtonLink href={backToDay} variant="secondary">
            Cancel
          </ButtonLink>
          {/* "Update this meal" against the composer's "Save this meal", so the two screens say
              which of the two things they are doing while sitting in the same place (Rajeev,
              2026-09-05). */}
          <Button type="submit" form={FORM} disabled={status.busy || status.blocked}>
            {status.busy ? "Saving…" : "Update this meal"}
          </Button>
        </>
      }
    >
      {/* The series this date belongs to, under the heading (T-308). Said with what editing here
          does, because a planner who sees "repeats every week" is right to wonder whether changing
          this Saturday changes every Saturday. It does not: each date is its own meal. */}
      {meal.series && <SeriesLine series={meal.series} note="Changes here apply to this date only." />}
      {status.hint && <p className="text-sm text-ink-secondary">{status.hint}</p>}

      <MealComposer
        date={meal.planDate}
        recipes={recipesQ.data ?? []}
        mealKinds={mealKinds ?? []}
        isEkadashi={Boolean(calQ.data?.[0]?.isEkadashi)}
        ekadashiName={calQ.data?.[0]?.ekadashiName}
        existing={meal}
        formId={FORM}
        onStatus={onStatus}
        onPlanned={() => undefined}
        // Back to the day, with the confirmation waiting there rather than on a screen that is
        // about to close.
        onClose={() => router.push(withParam(backToDay, "saved", name))}
      />
    </FocusScreen>
  );
}
