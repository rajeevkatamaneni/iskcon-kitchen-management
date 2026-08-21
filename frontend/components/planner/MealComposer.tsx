"use client";

import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { Badge } from "@/components/ds/Badge";
import { FieldRow } from "@/components/ds/FieldRow";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { Card } from "@/components/ds/Card";
import { EmptyState } from "@/components/ds/EmptyState";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { BusyPot } from "@/components/Loading";
import { ErrorNotice } from "@/components/ErrorNotice";
import {
  api,
  toApiError,
  type ApiError,
  type MealCrewView,
  type MealKindView,
  type MealServiceView,
  type MenuHistoryView,
  type RecipeSummary,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { longDate } from "@/lib/format";
import { FIELD_LABEL } from "@/components/Field";

/**
 * Planning a meal, in the order a kitchen decides one: what kind of meal it is, who is expected,
 * what is being cooked, who will run it, and anything the cooks should know.
 *
 * <p>The head count is the temple's own arithmetic — children eat about six tenths of a portion,
 * seniors about eight — and it sets each preparation's servings. But only until someone disagrees:
 * a planner knows the sweet always goes first and the buttermilk rarely does, so every preparation
 * carries its own figure, and one that has been set by hand is never overwritten by a later change
 * to the count. That judgement is the most valuable thing on this screen.
 *
 * <p>Since 2026-08-21 the same component also <em>edits</em> a meal, given one in `existing` (item
 * 16). A meal is planned as one thing and it is corrected as one thing: change a preparation's
 * servings, swap one, remove one, add one, move the ready-by, redo the head count. Two forms for
 * the one act would be two places to keep a rule.
 */

const CHILD_PORTION = 0.6;
const SENIOR_PORTION = 0.8;

interface Draft {
  recipeId: string;
  servings: number;
  /** Set once the planner types a number of their own; the head count stops driving it. */
  overridden: boolean;
  /**
   * The `meal_plans` row this draft already is, when a meal is being edited. Absent on a
   * preparation added during this edit — that one is created rather than updated, and a row that
   * disappears from the list is cancelled rather than deleted, so its history survives.
   */
  planId?: string;
}

/** What the screen around the composer needs to know to draw its own commit button. */
export interface ComposerStatus {
  busy: boolean;
  blocked: boolean;
  /** Why it is blocked, in one clause, or null when it is not. */
  hint: string | null;
}

export function MealComposer({
  date,
  recipes,
  mealKinds,
  isEkadashi,
  existing,
  formId,
  chrome = true,
  onClose,
  onPlanned,
  onStatus,
}: {
  date: string;
  recipes: RecipeSummary[];
  mealKinds: MealKindView[];
  isEkadashi: boolean;
  /** The meal being corrected. Absent when a new one is being planned. */
  existing?: MealServiceView;
  /** The id an outside commit button targets with `form=`. Only meaningful with `chrome` off. */
  formId?: string;
  /**
   * Whether the composer draws its own card and its own buttons. Off inside a focus screen, which
   * carries the heading and the `[Cancel] [Primary]` pair itself — one place to commit, not two.
   */
  chrome?: boolean;
  onClose: () => void;
  onPlanned: () => void;
  /** Lets a focus screen keep its own button in step with the form it commits. */
  onStatus?: (status: ComposerStatus) => void;
}) {
  const { getToken } = useAuth();
  // Held in a ref rather than read in the effects below. `getToken` is a fresh closure on most
  // renders, so an effect that depends on it re-runs on every render — and an effect that also
  // sets state then never stops.
  const tokenRef = useRef(getToken);
  tokenRef.current = getToken;

  const editing = Boolean(existing);

  const [kindName, setKindName] = useState(existing?.mealKind ?? mealKinds[0]?.name ?? "");
  const kind = mealKinds.find((k) => k.name === kindName);

  const [readyBy, setReadyBy] = useState(
    existing ? existing.readyBy.slice(0, 5) : (kind?.defaultReadyTime?.slice(0, 5) ?? "")
  );
  const [adults, setAdults] = useState(existing?.adults ?? 100);
  const [children, setChildren] = useState(existing?.children ?? 0);
  const [seniors, setSeniors] = useState(existing?.seniors ?? 0);
  const [picked, setPicked] = useState<Draft[]>(() => openDrafts(existing));
  const [notes, setNotes] = useState(existing?.kitchenNotes ?? "");
  const [clientName, setClientName] = useState(existing?.clientName ?? "");
  const [clientContact, setClientContact] = useState(existing?.clientContact ?? "");
  const [venue, setVenue] = useState(existing?.venue ?? "");
  const [purpose, setPurpose] = useState(existing?.purpose ?? "");
  const [occasionName, setOccasionName] = useState(existing?.occasionName ?? "");

  /**
   * How many people it takes to cook this meal (item 24). One counter, not two: at execution time
   * the number can be met by any mix of staff and volunteers, and splitting it would invent a
   * constraint the temple does not have.
   *
   * <p>Null, not zero, until somebody says. Null is the honest answer for a meal nobody has thought
   * about the hands for yet, and it is drawn as an empty box rather than a nought.
   */
  const [crewRequired, setCrewRequired] = useState<number | null>(existing?.crewRequired ?? null);
  // Once the planner has touched the counter it is theirs, and the suggestion stops arriving over
  // the top of it when they change the kind of meal.
  const crewTouched = useRef(editing);

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [confirmGrain, setConfirmGrain] = useState<{ recipeName: string; ingredients: string[] } | null>(null);

  const headCount = Math.round(adults + children * CHILD_PORTION + seniors * SENIOR_PORTION);

  const byId = useMemo(() => new Map(recipes.map((r) => [r.id, r])), [recipes]);

  // --- what the form asks the server for, rather than the planner ------------

  /** Who is actually rostered over this meal's ready-by, for the readout in step 4. */
  const [crew, setCrew] = useState<MealCrewView | null>(null);
  useEffect(() => {
    let live = true;
    tokenRef
      .current()
      .then((t) => api.mealCrew(date, date, t))
      .then((rows) => {
        if (live) setCrew(rows.find((r) => r.mealKind === kindName) ?? null);
      })
      .catch(() => {
        if (live) setCrew(null);
      });
    return () => {
      live = false;
    };
  }, [date, kindName]);

  /** The median of the last three ordinary meals of this kind (Q11), or null where there are none. */
  useEffect(() => {
    if (crewTouched.current || !kindName) return;
    let live = true;
    tokenRef
      .current()
      .then((t) => api.suggestedCrew(kindName, t))
      .then((s) => {
        if (live && !crewTouched.current) setCrewRequired(s.crewRequired);
      })
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [kindName]);

  /**
   * The temple's named occasions, for the picker a feast carries. A list to choose from and not a
   * closed one: a temple anniversary, or a local festival the calendar does not carry, is typed in.
   */
  const [occasions, setOccasions] = useState<string[]>([]);
  useEffect(() => {
    if (!kind?.needsOccasion) return;
    let live = true;
    tokenRef
      .current()
      .then((t) => api.listOccasions(t))
      .then((list) => {
        if (live) setOccasions(list.map((o) => o.name));
      })
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [kind?.needsOccasion]);

  // What the calendar says this date is for, which is what the occasion opens on. Only ever a
  // default: the planner may be cooking a feast the calendar has never heard of.
  const occasionTouched = useRef(Boolean(existing?.occasionName));
  useEffect(() => {
    if (!kind?.needsOccasion || occasionTouched.current) return;
    let live = true;
    tokenRef
      .current()
      .then((t) => api.mealDayContext(date, t))
      .then((ctx) => {
        if (live && !occasionTouched.current && ctx.occasionName) setOccasionName(ctx.occasionName);
      })
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [date, kind?.needsOccasion]);

  /**
   * What was cooked for this occasion last time (item 26b). Offered, never applied — the whole
   * point is that one press saves an hour of reassembling a menu, not that the app decides one.
   */
  const [history, setHistory] = useState<MenuHistoryView | null>(null);
  const [menuUsed, setMenuUsed] = useState(false);
  const occasionQuery = kind?.needsOccasion ? occasionName.trim() : "";
  useEffect(() => {
    if (!occasionQuery) {
      setHistory(null);
      return;
    }
    let live = true;
    setMenuUsed(false);
    tokenRef
      .current()
      .then((t) => api.menuHistory(occasionQuery, date, t))
      .then((h) => {
        if (live) setHistory(h.lastCookedOn ? h : null);
      })
      .catch(() => {
        if (live) setHistory(null);
      });
    return () => {
      live = false;
    };
  }, [occasionQuery, date]);

  // --- the form itself -------------------------------------------------------

  function chooseKind(name: string) {
    setKindName(name);
    const next = mealKinds.find((k) => k.name === name);
    setReadyBy(next?.defaultReadyTime?.slice(0, 5) ?? "");
  }

  /** A head count everyone follows, except the preparations someone has deliberately set. */
  function setCount(which: "adults" | "children" | "seniors", value: number) {
    const v = Math.max(0, value);
    const next = {
      adults: which === "adults" ? v : adults,
      children: which === "children" ? v : children,
      seniors: which === "seniors" ? v : seniors,
    };
    if (which === "adults") setAdults(v);
    if (which === "children") setChildren(v);
    if (which === "seniors") setSeniors(v);

    const total = Math.round(next.adults + next.children * CHILD_PORTION + next.seniors * SENIOR_PORTION);
    setPicked((list) => list.map((d) => (d.overridden ? d : { ...d, servings: total })));
  }

  function toggle(recipeId: string) {
    setPicked((list) =>
      list.some((d) => d.recipeId === recipeId)
        ? list.filter((d) => d.recipeId !== recipeId)
        : [...list, { recipeId, servings: headCount, overridden: false }]
    );
  }

  function setServings(recipeId: string, servings: number) {
    setPicked((list) =>
      list.map((d) =>
        d.recipeId === recipeId ? { ...d, servings: Math.max(1, servings), overridden: true } : d
      )
    );
  }

  /**
   * Last time's menu, put in with one press.
   *
   * <p>The preparation list carries and nothing else does. Servings follow this year's head count,
   * and last year's per-preparation overrides are deliberately dropped: an override was a judgement
   * about last year's crowd, and re-applying it against a different head count would be wrong in a
   * way nobody would notice. Everything stays editable afterwards.
   */
  function useLastMenu() {
    if (!history) return;
    setPicked((list) => {
      const already = new Set(list.map((d) => d.recipeId));
      const added = history.preparations
        .filter((p) => byId.has(p.recipeId) && !already.has(p.recipeId))
        .map((p) => ({ recipeId: p.recipeId, servings: headCount, overridden: false }));
      return [...list, ...added];
    });
    setMenuUsed(true);
  }

  const needsClient = Boolean(kind?.needsClient) && !clientName.trim();
  const needsVenue = Boolean(kind?.needsVenue) && !venue.trim();
  const needsPurpose = Boolean(kind?.needsPurpose) && !purpose.trim();
  const needsOccasion = Boolean(kind?.needsOccasion) && !occasionName.trim();
  const needsTime = !readyBy;
  const blocked = picked.length === 0 || needsTime || needsClient || needsVenue || needsPurpose || needsOccasion;

  const blockedHint = !blocked
    ? null
    : picked.length === 0
      ? "Pick at least one preparation"
      : needsTime
        ? "Pick the time it must be ready"
        : needsClient
          ? "Say who it is for"
          : needsVenue
            ? "Say where it is going"
            : needsOccasion
              ? "Name the occasion this feast is for"
              : "Say what it is for";

  // The focus screen draws the commit button, so it has to know what the form knows. Every value
  // here is a primitive and `onStatus` is expected to be stable, so this settles rather than loops.
  useEffect(() => {
    onStatus?.({ busy, blocked, hint: blockedHint });
  }, [busy, blocked, blockedHint, onStatus]);

  /** Everything about the meal that every one of its preparation rows carries. */
  function mealFacts() {
    return {
      planDate: date,
      mealKind: kindName,
      readyBy: readyBy || null,
      clientName: clientName.trim() || null,
      clientContact: clientContact.trim() || null,
      venue: venue.trim() || null,
      purpose: purpose.trim() || null,
      occasionName: kind?.needsOccasion ? occasionName.trim() || null : null,
      adults,
      children,
      seniors,
      crewRequired,
      kitchenNotes: notes.trim() || null,
    };
  }

  async function save(acknowledge = false) {
    setBusy(true);
    setError(null);
    const token = await tokenRef.current();
    const facts = mealFacts();
    const done: string[] = [];

    try {
      // Preparations dropped during an edit go first, so a meal never briefly holds both the old
      // list and the new one if something later in the run fails.
      if (existing) {
        const kept = new Set(picked.map((d) => d.planId).filter(Boolean));
        for (const dish of existing.dishes) {
          if (dish.status === "PLANNED" && !kept.has(dish.id)) {
            await api.cancelMealPlan(dish.id, token);
          }
        }
      }

      for (const draft of picked) {
        try {
          if (draft.planId) {
            await api.updateMealPlan(
              draft.planId,
              {
                ...facts,
                recipeId: draft.recipeId,
                targetServings: draft.servings,
                ekadashiAcknowledged: acknowledge,
              },
              token
            );
          } else {
            await api.createMealPlan(
              {
                ...facts,
                recipeId: draft.recipeId,
                targetServings: draft.servings,
                ekadashiAcknowledged: acknowledge,
              },
              token
            );
          }
          done.push(draft.recipeId);
        } catch (e) {
          const err = toApiError(e, editing ? "We couldn't save that meal." : "We couldn't plan that meal.");
          // A grain preparation on a fasting day: name it and let the planner decide, rather than
          // refusing a whole meal because one preparation is questionable.
          if (err.code === "KMS-4917" && !acknowledge) {
            const check = await api.ekadashiCheck(date, draft.recipeId, token).catch(() => null);
            setConfirmGrain({
              recipeName: byId.get(draft.recipeId)?.name ?? "That preparation",
              ingredients: check?.offendingIngredients ?? [],
            });
            if (!editing) setPicked((list) => list.filter((d) => !done.includes(d.recipeId)));
            if (done.length > 0) onPlanned();
            return;
          }
          throw e;
        }
      }
      onPlanned();
      onClose();
    } catch (e) {
      if (!editing) setPicked((list) => list.filter((d) => !done.includes(d.recipeId)));
      if (done.length > 0) onPlanned();
      setError(toApiError(e, editing ? "We couldn't save that meal." : "We couldn't plan that meal."));
    } finally {
      setBusy(false);
    }
  }

  if (recipes.length === 0) {
    const empty = (
      <EmptyState title="No recipes yet" action={<ButtonLink href="/recipes">Add a recipe</ButtonLink>}>
        A meal is a recipe cooked for a number of people, so the temple needs at least one recipe
        before anything can be planned.
      </EmptyState>
    );
    return chrome ? <Card tone="canvas">{empty}</Card> : empty;
  }

  const body = (
    <div className="grid gap-6">
      {error && <ErrorNotice error={error} />}

      {confirmGrain && (
        <InlineNotice
          tone="warning"
          title={`${confirmGrain.recipeName} has grains or beans, and this is a fasting day`}
          action={
            <span className="flex gap-3">
              <Button type="button" size="sm" disabled={busy} onClick={() => { setConfirmGrain(null); save(true); }}>
                Plan it anyway
              </Button>
              <Button type="button" size="sm" variant="ghost" onClick={() => setConfirmGrain(null)}>
                Leave it out
              </Button>
            </span>
          }
        >
          {confirmGrain.ingredients.length > 0 && <>Contains {confirmGrain.ingredients.join(", ")}.</>}
        </InlineNotice>
      )}

      {/* 1 — what kind of meal */}
      <section className="grid gap-3 rounded-lg bg-raised p-5">
        <Step n={1} title="What kind of meal" />
        {editing ? (
          // A meal is its date and its kind, so the kind is what identifies the thing being edited.
          // Changing it here would not correct this meal — it would move its preparations into a
          // different one.
          <div className="flex flex-wrap items-center gap-3">
            <Badge tone="accent">{kindName}</Badge>
            <span className="text-xs text-ink-muted">
              A meal is its date and its kind
            </span>
          </div>
        ) : (
          <div className="flex flex-wrap gap-2">
            {mealKinds.map((k) => (
              <button
                key={k.id}
                type="button"
                onClick={() => chooseKind(k.name)}
                aria-pressed={k.name === kindName}
                className={[
                  "min-h-touch rounded-full border px-4 text-sm transition-colors duration-state",
                  k.name === kindName
                    ? "border-accent bg-accent text-ink-inverse"
                    : "border-hairline-strong bg-canvas text-ink hover:bg-raised",
                ].join(" ")}
              >
                {k.name}
              </button>
            ))}
          </div>
        )}

        <FieldRow>
          <RowField label="Ready by" hint="Pick the time this must be ready">
            <input
              type="time"
              aria-label="Ready by"
              value={readyBy}
              onChange={(e) => setReadyBy(e.target.value)}
              className="min-h-touch w-40 rounded border border-hairline bg-canvas px-3"
            />
          </RowField>

          {/* A feast names the festival it is for (item 26). The calendar fills it in, and it is
              still a box: a temple anniversary, or a local festival the calendar does not carry, is
              a feast the temple takes just as much pride in. */}
          {kind?.needsOccasion && (
            <RowField label="What is the occasion?" hint="The calendar's answer, or your own">
              <input
                list="meal-occasions"
                value={occasionName}
                onChange={(e) => {
                  occasionTouched.current = true;
                  setOccasionName(e.target.value);
                }}
                className="min-h-touch rounded border border-hairline bg-canvas px-3"
              />
            </RowField>
          )}

          {kind?.needsClient && (
            <RowField label="Who is it for?">
              <input
                value={clientName}
                onChange={(e) => setClientName(e.target.value)}
                className="min-h-touch rounded border border-hairline bg-canvas px-3"
              />
            </RowField>
          )}
          {kind?.needsClient && (
            <RowField label="Their contact">
              <input
                value={clientContact}
                onChange={(e) => setClientContact(e.target.value)}
                className="min-h-touch rounded border border-hairline bg-canvas px-3"
              />
            </RowField>
          )}
          {kind?.needsVenue && (
            <RowField label="Where is it going?">
              <input
                value={venue}
                onChange={(e) => setVenue(e.target.value)}
                className="min-h-touch rounded border border-hairline bg-canvas px-3"
              />
            </RowField>
          )}
          {/* Free text, and deliberately not a list. The reasons a temple cooks for an outside
              event are open-ended — a Bhagavad-gita reading, book distribution, a school event —
              and a list of five would be wrong by the sixth. Nothing in the system reasons about
              it: it is a label for the kitchen and for the job card. */}
          {kind?.needsPurpose && (
            <RowField label="What is it for?" hint="A reading, book distribution, a school event">
              <input
                value={purpose}
                onChange={(e) => setPurpose(e.target.value)}
                className="min-h-touch rounded border border-hairline bg-canvas px-3"
              />
            </RowField>
          )}
        </FieldRow>

        {/* Outside the row on purpose: a datalist is invisible, but a fourth child inside a
            three-track field would be a fourth cell for the row to reason about. */}
        {kind?.needsOccasion && (
          <datalist id="meal-occasions">
            {occasions.map((name) => (
              <option key={name} value={name} />
            ))}
          </datalist>
        )}
      </section>

      {/* 2 — who is expected */}
      <section className="grid gap-3 rounded-lg bg-raised p-5">
        <Step n={2} title="Who is expected" />
        <FieldRow>
          <Counter label="Adults" hint="A full portion" value={adults} onChange={(v) => setCount("adults", v ?? 0)} />
          <Counter
            label="Children"
            hint="0.6 of a portion"
            value={children}
            onChange={(v) => setCount("children", v ?? 0)}
          />
          <Counter
            label="Seniors"
            hint="0.8 of a portion"
            value={seniors}
            onChange={(v) => setCount("seniors", v ?? 0)}
          />
          <Readout label="Scales to" value={`${headCount.toLocaleString("en-IN")} servings`} />
        </FieldRow>
      </section>

      {/* 3 — preparations */}
      <section className="grid gap-3 rounded-lg bg-raised p-5">
        <Step
          n={3}
          title="Preparations"
          hint="Servings follow the head count. Raise the ones that always run out."
        />

        {history && (
          <InlineNotice
            tone={menuUsed ? "success" : "info"}
            autoDismiss={menuUsed}
            title={
              menuUsed
                ? `Last ${history.occasionName}'s menu has been added.`
                : `Last ${history.occasionName}, ${longDate(history.lastCookedOn ?? "")} — ${
                    history.preparationCount
                  } ${history.preparationCount === 1 ? "preparation" : "preparations"}.`
            }
            action={
              !menuUsed && history.preparations.length > 0 ? (
                <Button type="button" size="sm" variant="secondary" onClick={useLastMenu}>
                  Use this menu
                </Button>
              ) : undefined
            }
          >
            {history.missingCount > 0 && (
              <>
                {history.missingCount} of last year&rsquo;s {history.preparationCount} preparations
                are no longer in your recipes.
              </>
            )}
          </InlineNotice>
        )}

        <div className="grid gap-x-6 gap-y-2 sm:grid-cols-2 xl:grid-cols-3">
          {recipes.map((recipe) => {
            const draft = picked.find((d) => d.recipeId === recipe.id);
            return (
              <div key={recipe.id} className="grid gap-1 border-t border-hairline py-2 first:border-t-0 sm:border-t-0">
                <label className="flex cursor-pointer items-start gap-2">
                  <input
                    type="checkbox"
                    checked={Boolean(draft)}
                    onChange={() => toggle(recipe.id)}
                    className="mt-1 h-4 w-4 flex-none accent-accent"
                  />
                  <span className="grid">
                    <span className="text-sm text-ink">{recipe.name}</span>
                    <span className="pl-field-inset text-xs text-ink-muted">{recipe.categoryName}</span>
                  </span>
                </label>

                {draft && (
                  <span className="ml-6 flex items-center gap-2">
                    <input
                      type="number"
                      min={1}
                      step={1}
                      aria-label={`Servings of ${recipe.name}`}
                      value={draft.servings}
                      onChange={(e) => setServings(recipe.id, Number(e.target.value))}
                      className="min-h-touch w-24 rounded border border-hairline bg-canvas px-2 text-sm tabular-nums"
                    />
                    <span className="text-xs text-ink-muted">
                      servings
                      {draft.overridden && draft.servings !== headCount && (
                        <> · set by hand</>
                      )}
                    </span>
                  </span>
                )}
              </div>
            );
          })}
        </div>
      </section>

      {/* 4 — who will run it. After the preparations and not before (Q10): the crew a meal takes
          depends on what is being cooked as much as on how many are eating, and three preparations
          for 133 and eight for 133 are not the same morning's work. */}
      <section className="grid gap-3 rounded-lg bg-raised p-5">
        <Step n={4} title="Who will run it" hint="Any mix of staff and volunteers" />
        <FieldRow>
          <Counter
            label="People needed"
            hint="Leave it empty until you know"
            value={crewRequired}
            onChange={(v) => {
              crewTouched.current = true;
              setCrewRequired(v === null ? null : Math.max(0, v));
            }}
          />
          <Readout
            label="Rostered"
            value={rosterReadout(crew, crewRequired)}
            // Quiet, and only a warning. A meal is planned weeks before anybody is rostered, so
            // being short of hands today says nothing about the plan and never blocks saving it.
            tone={crewRequired != null && crew != null && crew.rostered < crewRequired ? "warning" : "neutral"}
          />
        </FieldRow>
      </section>

      {/* Notes */}
      <label className="grid gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Notes for the kitchen</span>
        <textarea
          rows={3}
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          placeholder="Cook the kheer thin — the seniors prefer it that way."
          className="rounded border border-hairline bg-canvas px-3 py-2 text-ink"
        />
      </label>

      {chrome && (
        <div className="flex flex-wrap items-center gap-3">
          <Button type="button" disabled={busy || blocked} onClick={() => save(false)}>
            {busy ? (
              <span className="inline-flex items-center gap-2">
                <BusyPot />
                Saving…
              </span>
            ) : editing ? (
              "Save changes"
            ) : (
              "Save this meal"
            )}
          </Button>
          <Button type="button" variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          {blockedHint && <span className="text-sm text-ink-muted">{blockedHint}</span>}
          {isEkadashi && (
            <Badge tone="warning">Fasting day — grain dishes will ask you to confirm</Badge>
          )}
        </div>
      )}

      {!chrome && isEkadashi && (
        <div>
          <Badge tone="warning">Fasting day — grain dishes will ask you to confirm</Badge>
        </div>
      )}
    </div>
  );

  if (!chrome) {
    return (
      <form
        id={formId}
        aria-label={editing ? `Edit ${kindName}` : "Plan a meal"}
        onSubmit={(e) => {
          e.preventDefault();
          if (!blocked && !busy) save(false);
        }}
      >
        {body}
      </form>
    );
  }

  return (
    <Card
      tone="canvas"
      title={editing ? `Edit ${kindName}` : "Add a meal"}
      meta="Head count scales every preparation you pick"
      action={
        <Button variant="ghost" size="sm" onClick={onClose} aria-label="Close">
          ✕
        </Button>
      }
    >
      {body}
    </Card>
  );
}

/**
 * The preparations of a meal that are still open, as drafts.
 *
 * <p>A cooked or cancelled row is not offered for editing: what was cooked drew stock against a
 * figure, and rewriting the figure afterwards would leave the ledger describing a meal that never
 * happened. A servings figure that does not match the meal's own head count was set by hand, so it
 * is marked as such and a later change to the count leaves it alone.
 */
function openDrafts(meal: MealServiceView | undefined): Draft[] {
  if (!meal) return [];
  const drafts: Draft[] = [];
  const seen = new Set<string>();
  for (const dish of meal.dishes) {
    if (dish.status !== "PLANNED" || seen.has(dish.recipeId)) continue;
    seen.add(dish.recipeId);
    drafts.push({
      recipeId: dish.recipeId,
      servings: Number(dish.targetServings),
      overridden: Number(dish.targetServings) !== meal.plates,
      planId: dish.id,
    });
  }
  return drafts;
}

/** "3 staff · 2 volunteers · 5 of 8" — who is rostered over this meal, against what it takes. */
function rosterReadout(crew: MealCrewView | null, required: number | null): string {
  if (!crew) return required == null ? "Not counted yet" : `0 of ${required}`;
  const parts = [
    `${crew.staffIn} staff`,
    `${crew.volunteers} ${crew.volunteers === 1 ? "volunteer" : "volunteers"}`,
  ];
  if (required != null) parts.push(`${crew.rostered} of ${required}`);
  return parts.join(" · ");
}

/** The numbered heading. The order is the order a kitchen decides a meal in, so it is a sequence. */
function Step({ n, title, hint }: { n: number; title: string; hint?: string }) {
  return (
    <span className="flex flex-wrap items-center gap-3">
      <span className="flex h-6 w-6 flex-none items-center justify-center rounded-full bg-ink text-xs font-semibold text-ink-inverse">
        {n}
      </span>
      <span className="text-base font-medium text-ink">{title}</span>
      {hint && <span className="text-xs text-ink-muted">{hint}</span>}
    </span>
  );
}

/**
 * One field in a {@link FieldRow}: label, control, hint, each in the row's own track.
 *
 * <p>The hint is optional and nothing stands in for a missing one. The row reserves the track, so a
 * field without a hint leaves an empty cell and its box still lines up with its neighbours'.
 */
function RowField({
  label, hint, children,
}: {
  label: string;
  hint?: string;
  children: ReactNode;
}) {
  return (
    <label className="contents text-sm text-ink-secondary">
      <span className={FIELD_LABEL}>{label}</span>
      {children}
      <span className="pl-field-inset text-xs text-ink-muted">{hint}</span>
    </label>
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
 */
function Readout({
  label,
  value,
  tone = "neutral",
}: {
  label: string;
  value: string;
  tone?: "neutral" | "warning";
}) {
  return (
    <span className="contents">
      <span className={FIELD_LABEL}>{label}</span>
      <span
        className={[
          "flex items-center rounded-lg px-4 text-lg font-semibold tabular-nums",
          tone === "warning" ? "bg-warning-bg text-warning" : "bg-sunken text-ink",
        ].join(" ")}
      >
        {value}
      </span>
      <span />
    </span>
  );
}

function Counter({
  label, hint, value, onChange,
}: {
  label: string;
  hint?: string;
  /** Null draws an empty box — an honest answer where nobody has said, and not a nought. */
  value: number | null;
  onChange: (value: number | null) => void;
}) {
  return (
    <span className="contents">
      <span className={FIELD_LABEL}>{label}</span>
      <span className="flex items-center gap-1 rounded-lg bg-sunken px-2 py-1">
        <button
          type="button"
          aria-label={`One fewer ${label.toLowerCase()}`}
          onClick={() => onChange((value ?? 0) - 1)}
          className="min-h-touch w-9 rounded text-lg text-ink-secondary transition-colors duration-state hover:bg-hairline"
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
          className="min-h-touch w-9 rounded text-lg text-ink-secondary transition-colors duration-state hover:bg-hairline"
        >
          +
        </button>
      </span>
      <span className="pl-field-inset text-xs text-ink-muted">{hint}</span>
    </span>
  );
}
