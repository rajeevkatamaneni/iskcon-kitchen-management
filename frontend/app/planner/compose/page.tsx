"use client";

import { Suspense, useCallback, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { EmptyState } from "@/components/ds/EmptyState";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { Loading } from "@/components/Loading";
import { RequireRole } from "@/components/RequireRole";
import { MealComposer, type ComposerStatus } from "@/components/planner/MealComposer";
import { api } from "@/lib/api";
import { longDate, todayIso } from "@/lib/format";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * Planning a meal — the same screen as editing one, which is the whole point of it existing.
 *
 * <p><strong>Why this is a page and not a panel.</strong> Adding a meal was an inline composer on the
 * day view while correcting one was a focus screen, so the identical form was built two ways and
 * drifted: the actions ended up at the foot of a two-thousand-pixel form in one and floating in the
 * header of the other. Rajeev, 2026-09-05: <em>"we dont want to have the same screen shown two
 * different ways depending on the action."</em> Making them look alike would have treated the
 * symptom; the {@code chrome} prop that branched the shell was the cause, and it is gone.
 *
 * <p><strong>What it costs, stated plainly.</strong> Adding a meal is now a navigation rather than an
 * expand, so planning a week from nothing is seven round trips instead of seven expansions. That is
 * the right trade because nobody plans a week from nothing: temples copy a previous week and adjust
 * it, which Rajeev confirms holds across temples whatever their buying cycle. Composing from scratch
 * is the rare, deliberate act — and the form is 2,700 pixels tall, so it was never meaningfully
 * "inline" anyway. You lost sight of the day the moment you opened it.
 *
 * <p><strong>Why {@code /planner/compose} and not {@code /planner/[date]/new}.</strong> A temple can
 * create its own meal kinds, and the edit route is {@code /planner/[date]/[kind]} — so a static
 * {@code new} segment would silently shadow a kind somebody named "new". A date can never be the
 * word "compose", so this shape has no such collision.
 */
export default function ComposeMealPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      {/* The date is read from the query string, and that needs a boundary. */}
      <Suspense>
        <ComposeMealScreen />
      </Suspense>
    </RequireRole>
  );
}

const FORM = "compose-meal";

function ComposeMealScreen() {
  const params = useSearchParams();
  const router = useRouter();
  const date = asDate(params.get("date")) ?? todayIso();
  const backToDay = `/planner?view=day&date=${date}`;

  const recipesQ = useAuthedQuery(useCallback((t?: string) => api.listRecipes({}, t), []));
  const { data: mealKinds } = useAuthedQuery(api.listMealKinds);
  const calQ = useAuthedQuery(
    useCallback((t?: string) => api.calendarRange(date, date, t), [date])
  );

  const [status, setStatus] = useState<ComposerStatus>({ busy: false, blocked: true, hint: null });
  const onStatus = useCallback((next: ComposerStatus) => setStatus(next), []);

  // A day that has gone is read but not changed, the same rule the day view and the edit screen
  // keep. Reached by typing a URL rather than by pressing anything, so it is refused rather than
  // hidden.
  if (date < todayIso()) {
    return (
      <FocusScreen
        task="Plan a meal"
        who={longDate(date)}
        activeHref="/planner"
        actions={<ButtonLink href={backToDay} variant="secondary">Back to the day</ButtonLink>}
      >
        <EmptyState title="That day has passed">
          Its plan can be read but not added to.
        </EmptyState>
      </FocusScreen>
    );
  }

  if (recipesQ.loading && !recipesQ.data) {
    return (
      <FocusScreen task="Plan a meal" who={longDate(date)} activeHref="/planner">
        <Loading label="Loading the recipes…" />
      </FocusScreen>
    );
  }

  return (
    <FocusScreen
      task="Plan a meal"
      who={longDate(date)}
      activeHref="/planner"
      actions={
        <>
          <ButtonLink href={backToDay} variant="secondary">
            Cancel
          </ButtonLink>
          {/* "Save this meal" against the edit screen's "Update this meal" — the same place, saying
              which of the two things this one is. */}
          <Button type="submit" form={FORM} disabled={status.busy || status.blocked}>
            {status.busy ? "Saving…" : "Save this meal"}
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
        formId={FORM}
        onStatus={onStatus}
        onPlanned={() => undefined}
        // Back to the day, with the confirmation waiting there rather than on a screen that is
        // about to close.
        onClose={() => router.push(`${backToDay}&saved=planned`)}
      />
    </FocusScreen>
  );
}

/** A date out of the query string, or null. Checked rather than trusted — every sum uses it. */
function asDate(raw: string | null): string | null {
  if (!raw || !/^\d{4}-\d{2}-\d{2}$/.test(raw)) return null;
  return Number.isNaN(new Date(raw + "T00:00:00").getTime()) ? null : raw;
}
