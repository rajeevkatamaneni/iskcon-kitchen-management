"use client";

import { useCallback, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { EmptyState } from "@/components/ds/EmptyState";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Loading } from "@/components/Loading";
import { RequireRole } from "@/components/RequireRole";
import { MealComposer, type ComposerStatus } from "@/components/planner/MealComposer";
import { api } from "@/lib/api";
import { hhmm, longDate, todayIso } from "@/lib/format";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * Editing one meal (item 16) — that meal and nothing else.
 *
 * <p>A meal is planned as one act and it is corrected as one act: change a preparation's servings,
 * swap one, remove one, add one, move the ready-by, redo the head count, say how many people it
 * takes. Until 2026-08-21 the only way to change a planned meal was one row of it at a time, from a
 * strip under its preparations, which could not touch anything the meal held as a whole.
 *
 * <p>It is the composer inside the focus screen: its own URL, the sidebar still there, the task as
 * the heading, and one pair of buttons top right.
 */

const FORM = "edit-meal";

export default function EditMealPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <EditMealScreen />
    </RequireRole>
  );
}

function EditMealScreen() {
  const params = useParams<{ date: string; kind: string }>();
  const router = useRouter();

  const date = asDate(one(params?.date));
  const kind = one(params?.kind) ? decodeURIComponent(one(params?.kind) as string) : null;

  const mealsQ = useAuthedQuery(
    useCallback(
      (t?: string) => (date ? api.mealServices(date, date, t) : Promise.resolve([])),
      [date]
    )
  );
  const recipesQ = useAuthedQuery(useCallback((t?: string) => api.listRecipes({}, t), []));
  const { data: mealKinds } = useAuthedQuery(api.listMealKinds);
  const calQ = useAuthedQuery(
    useCallback((t?: string) => (date ? api.calendarRange(date, date, t) : Promise.resolve([])), [date])
  );

  const meal = (mealsQ.data ?? []).find((m) => m.mealKind === kind);

  // The composer owns the form; the focus screen owns the button that commits it, so it has to be
  // told what the form knows. Compared before it is stored, because setting an object that has not
  // changed re-renders, and a re-render that reports again is a loop.
  const [status, setStatus] = useState<ComposerStatus>({ busy: false, blocked: true, hint: null });
  const onStatus = useCallback((next: ComposerStatus) => {
    setStatus((prev) =>
      prev.busy === next.busy && prev.blocked === next.blocked && prev.hint === next.hint ? prev : next
    );
  }, []);

  if (!date || !kind) {
    return (
      <FocusScreen task="Edit a meal" activeHref="/planner">
        <EmptyState title="That is not a meal" action={<ButtonLink href="/planner">Open the planner</ButtonLink>}>
          A meal is addressed by its date and its kind, as in 2026-08-21 and Lunch.
        </EmptyState>
      </FocusScreen>
    );
  }

  const backToDay = `/planner/${date}`;

  // The whole screen is the composer, and a composer with no recipes says "no recipes yet". Waiting
  // for them is the difference between that sentence being the truth and being a flash of it.
  if (mealsQ.loading || recipesQ.loading) {
    return (
      <FocusScreen task={`Edit ${kind}`} who={longDate(date)} activeHref="/planner">
        <Loading label="Loading the meal…" />
      </FocusScreen>
    );
  }

  if (mealsQ.error) {
    return (
      <FocusScreen task={`Edit ${kind}`} who={longDate(date)} activeHref="/planner">
        <ErrorNotice error={mealsQ.error} />
      </FocusScreen>
    );
  }

  if (!meal) {
    return (
      <FocusScreen task={`Edit ${kind}`} who={longDate(date)} activeHref="/planner">
        <EmptyState title="Nothing of that kind is planned for this day" action={<ButtonLink href={backToDay}>Open the day</ButtonLink>}>
          It may have been cancelled, or planned on another date.
        </EmptyState>
      </FocusScreen>
    );
  }

  // What was cooked drew stock against a figure, and rewriting the figure afterwards would leave the
  // ledger describing a meal that never happened.
  if (meal.recorded || date < todayIso()) {
    return (
      <FocusScreen
        task={`Edit ${kind}`}
        who={`${longDate(date)} · ${hhmm(meal.readyBy)}`}
        activeHref="/planner"
        actions={<ButtonLink href={backToDay} variant="secondary">Open the day</ButtonLink>}
      >
        <InlineNotice tone="info">
          {meal.recorded
            ? "This meal has been recorded, so what it was can no longer be changed."
            : "This day has passed, so its plan can be read but not changed."}
        </InlineNotice>
      </FocusScreen>
    );
  }

  return (
    <FocusScreen
      task={`Edit ${kind}`}
      who={`${longDate(date)} · ${hhmm(meal.readyBy)} · ${meal.plates.toLocaleString("en-IN")} servings`}
      activeHref="/planner"
      actions={
        <>
          <ButtonLink href={backToDay} variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={FORM} disabled={status.busy || status.blocked}>
            {status.busy ? "Saving…" : "Save changes"}
          </Button>
        </>
      }
    >
      {status.hint && <p className="text-sm text-ink-muted">{status.hint}</p>}

      <MealComposer
        date={date}
        recipes={recipesQ.data ?? []}
        mealKinds={mealKinds ?? []}
        isEkadashi={Boolean(calQ.data?.[0]?.isEkadashi)}
        ekadashiName={calQ.data?.[0]?.ekadashiName}
        existing={meal}
        formId={FORM}
        chrome={false}
        onStatus={onStatus}
        onPlanned={() => undefined}
        // Back to the day, with the confirmation waiting there rather than on a screen that is
        // about to close.
        onClose={() => router.push(`${backToDay}?saved=${encodeURIComponent(kind)}`)}
      />
    </FocusScreen>
  );
}

function one(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

/** A date out of the path, or null. Checked rather than trusted — every sum on the screen uses it. */
function asDate(raw: string | undefined): string | null {
  if (!raw || !/^\d{4}-\d{2}-\d{2}$/.test(raw)) return null;
  return Number.isNaN(new Date(raw + "T00:00:00").getTime()) ? null : raw;
}
