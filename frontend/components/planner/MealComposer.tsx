"use client";

import { useMemo, useState } from "react";
import { Badge } from "@/components/ds/Badge";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { Card } from "@/components/ds/Card";
import { EmptyState } from "@/components/ds/EmptyState";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { BusyPot } from "@/components/Loading";
import { ErrorNotice } from "@/components/ErrorNotice";
import { api, toApiError, type ApiError, type MealKindView, type RecipeSummary } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";

/**
 * Planning a meal, in the order a kitchen decides one: what kind of meal it is, who is expected,
 * what is being cooked, and anything the cooks should know.
 *
 * <p>The head count is the temple's own arithmetic — children eat about six tenths of a portion,
 * seniors about eight — and it sets each preparation's servings. But only until someone disagrees:
 * a planner knows the sweet always goes first and the buttermilk rarely does, so every dish carries
 * its own figure, and one that has been set by hand is never overwritten by a later change to the
 * count. That judgement is the most valuable thing on this screen.
 */

const CHILD_PORTION = 0.6;
const SENIOR_PORTION = 0.8;

interface Draft {
  recipeId: string;
  servings: number;
  /** Set once the planner types a number of their own; the head count stops driving it. */
  overridden: boolean;
}

export function MealComposer({
  date,
  recipes,
  mealKinds,
  isEkadashi,
  onClose,
  onPlanned,
}: {
  date: string;
  recipes: RecipeSummary[];
  mealKinds: MealKindView[];
  isEkadashi: boolean;
  onClose: () => void;
  onPlanned: () => void;
}) {
  const { getToken } = useAuth();

  const [kindName, setKindName] = useState(mealKinds[0]?.name ?? "");
  const kind = mealKinds.find((k) => k.name === kindName);

  const [readyBy, setReadyBy] = useState(kind?.defaultReadyTime?.slice(0, 5) ?? "");
  const [adults, setAdults] = useState(100);
  const [children, setChildren] = useState(0);
  const [seniors, setSeniors] = useState(0);
  const [picked, setPicked] = useState<Draft[]>([]);
  const [notes, setNotes] = useState("");
  const [clientName, setClientName] = useState("");
  const [clientContact, setClientContact] = useState("");
  const [venue, setVenue] = useState("");

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [confirmGrain, setConfirmGrain] = useState<{ recipeName: string; ingredients: string[] } | null>(null);

  const headCount = Math.round(adults + children * CHILD_PORTION + seniors * SENIOR_PORTION);

  const byId = useMemo(() => new Map(recipes.map((r) => [r.id, r])), [recipes]);

  function chooseKind(name: string) {
    setKindName(name);
    const next = mealKinds.find((k) => k.name === name);
    setReadyBy(next?.defaultReadyTime?.slice(0, 5) ?? "");
  }

  /** A head count everyone follows, except the dishes someone has deliberately set. */
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

  const needsClient = kind?.needsClient && !clientName.trim();
  const needsVenue = kind?.needsVenue && !venue.trim();
  const needsTime = !readyBy;
  const blocked = picked.length === 0 || needsTime || needsClient || needsVenue;

  async function save(acknowledge = false) {
    setBusy(true);
    setError(null);
    const token = await getToken();
    const done: string[] = [];

    try {
      for (const draft of picked) {
        try {
          await api.createMealPlan(
            {
              planDate: date,
              mealKind: kindName,
              recipeId: draft.recipeId,
              targetServings: draft.servings,
              readyBy: readyBy || null,
              clientName: clientName.trim() || null,
              clientContact: clientContact.trim() || null,
              venue: venue.trim() || null,
              adults,
              children,
              seniors,
              kitchenNotes: notes.trim() || null,
              ekadashiAcknowledged: acknowledge,
            },
            token
          );
          done.push(draft.recipeId);
        } catch (e) {
          const err = toApiError(e, "We couldn't plan that meal.");
          // A grain dish on a fasting day: name the dish and let the planner decide, rather than
          // refusing a whole meal because one preparation is questionable.
          if (err.code === "KMS-4917" && !acknowledge) {
            const check = await api.ekadashiCheck(date, draft.recipeId, token).catch(() => null);
            setConfirmGrain({
              recipeName: byId.get(draft.recipeId)?.name ?? "That preparation",
              ingredients: check?.offendingIngredients ?? [],
            });
            setPicked((list) => list.filter((d) => !done.includes(d.recipeId)));
            if (done.length > 0) onPlanned();
            return;
          }
          throw e;
        }
      }
      onPlanned();
      onClose();
    } catch (e) {
      setPicked((list) => list.filter((d) => !done.includes(d.recipeId)));
      if (done.length > 0) onPlanned();
      setError(toApiError(e, "We couldn't plan that meal."));
    } finally {
      setBusy(false);
    }
  }

  if (recipes.length === 0) {
    return (
      <Card tone="canvas">
        <EmptyState title="No recipes yet" action={<ButtonLink href="/recipes">Add a recipe</ButtonLink>}>
          A meal is a recipe cooked for a number of people, so the temple needs at least one recipe
          before anything can be planned.
        </EmptyState>
      </Card>
    );
  }

  return (
    <Card
      tone="canvas"
      title="Add a meal"
      meta="Head count scales every preparation you pick"
      action={
        <Button variant="ghost" size="sm" onClick={onClose} aria-label="Close">
          ✕
        </Button>
      }
    >
      <div className="grid gap-6">
        {error && <ErrorNotice error={error} />}

        {confirmGrain && (
          <InlineNotice
            tone="warning"
            title={`${confirmGrain.recipeName} has grains or beans, and this is a fasting day`}
            action={
              <span className="flex gap-3">
                <Button size="sm" disabled={busy} onClick={() => { setConfirmGrain(null); save(true); }}>
                  Plan it anyway
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setConfirmGrain(null)}>
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

          <div className="flex flex-wrap items-end gap-4">
            <label className="grid gap-1 text-sm text-ink-secondary">
              Ready by
              <input
                type="time"
                value={readyBy}
                onChange={(e) => setReadyBy(e.target.value)}
                className="min-h-touch w-40 rounded border border-hairline bg-canvas px-3"
              />
              <span className="text-xs text-ink-muted">Pick the time this must be ready</span>
            </label>

            {kind?.needsClient && (
              <label className="grid gap-1 text-sm text-ink-secondary">
                Who is it for?
                <input
                  value={clientName}
                  onChange={(e) => setClientName(e.target.value)}
                  className="min-h-touch rounded border border-hairline bg-canvas px-3"
                />
              </label>
            )}
            {kind?.needsClient && (
              <label className="grid gap-1 text-sm text-ink-secondary">
                Their contact
                <input
                  value={clientContact}
                  onChange={(e) => setClientContact(e.target.value)}
                  className="min-h-touch rounded border border-hairline bg-canvas px-3"
                />
              </label>
            )}
            {kind?.needsVenue && (
              <label className="grid gap-1 text-sm text-ink-secondary">
                Where is it going?
                <input
                  value={venue}
                  onChange={(e) => setVenue(e.target.value)}
                  className="min-h-touch rounded border border-hairline bg-canvas px-3"
                />
              </label>
            )}
          </div>
        </section>

        {/* 2 — who is expected */}
        <section className="grid gap-3 rounded-lg bg-raised p-5">
          <Step n={2} title="Who is expected" />
          {/* items-start, not items-end. Two of these three counters carry a hint line under them
              and Adults does not, so bottom-aligning them pushed Adults a whole line lower than its
              neighbours \u2014 which is exactly what Rajeev photographed. Aligning to the top puts every
              label on one line, and the reserved hint below keeps the boxes level too. */}
          <div className="flex flex-wrap items-start gap-4">
            <Counter label="Adults" hint="a full portion" value={adults} onChange={(v) => setCount("adults", v)} />
            <Counter
              label="Children"
              hint="0.6 of a portion"
              value={children}
              onChange={(v) => setCount("children", v)}
            />
            <Counter
              label="Seniors"
              hint="0.8 of a portion"
              value={seniors}
              onChange={(v) => setCount("seniors", v)}
            />
            <span className="ml-auto grid self-start rounded-lg bg-sunken px-4 py-2">
              <span className="text-xs text-ink-muted">Scales to</span>
              <span className="text-lg font-semibold tabular-nums text-ink">
                {headCount.toLocaleString("en-IN")} servings
              </span>
            </span>
          </div>
        </section>

        {/* 3 — preparations */}
        <section className="grid gap-3 rounded-lg bg-raised p-5">
          <Step
            n={3}
            title="Preparations"
            hint="Servings follow the head count. Raise the ones that always run out."
          />
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
                      <span className="text-xs text-ink-muted">{recipe.categoryName}</span>
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

        {/* Notes */}
        <label className="grid gap-1 text-sm text-ink-secondary">
          Notes for the kitchen
          <textarea
            rows={3}
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            placeholder="Cook the kheer thin — the seniors prefer it that way."
            className="rounded border border-hairline bg-canvas px-3 py-2 text-ink"
          />
        </label>

        <div className="flex flex-wrap items-center gap-3">
          <Button disabled={busy || blocked} onClick={() => save(false)}>
            {busy ? (
              <span className="inline-flex items-center gap-2">
                <BusyPot />
                Saving…
              </span>
            ) : (
              "Save this meal"
            )}
          </Button>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          {blocked && (
            <span className="text-sm text-ink-muted">
              {picked.length === 0
                ? "Pick at least one preparation"
                : needsTime
                  ? "Pick the time it must be ready"
                  : needsClient
                    ? "Say who it is for"
                    : "Say where it is going"}
            </span>
          )}
          {isEkadashi && (
            <Badge tone="warning">Fasting day — grain dishes will ask you to confirm</Badge>
          )}
        </div>
      </div>
    </Card>
  );
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

function Counter({
  label, hint, value, onChange,
}: {
  label: string;
  hint?: string;
  value: number;
  onChange: (value: number) => void;
}) {
  return (
    <span className="grid gap-1">
      <span className="text-sm text-ink-secondary">{label}</span>
      <span className="flex items-center gap-1 rounded-lg bg-sunken px-2 py-1">
        <button
          type="button"
          aria-label={`One fewer ${label.toLowerCase()}`}
          onClick={() => onChange(value - 1)}
          className="min-h-touch w-9 rounded text-lg text-ink-secondary transition-colors duration-state hover:bg-hairline"
        >
          −
        </button>
        <input
          type="number"
          min={0}
          aria-label={label}
          value={value}
          onChange={(e) => onChange(Number(e.target.value))}
          className="w-16 bg-transparent text-center text-base tabular-nums text-ink outline-none"
        />
        <button
          type="button"
          aria-label={`One more ${label.toLowerCase()}`}
          onClick={() => onChange(value + 1)}
          className="min-h-touch w-9 rounded text-lg text-ink-secondary transition-colors duration-state hover:bg-hairline"
        >
          +
        </button>
      </span>
      {hint && <span className="text-xs text-ink-muted">{hint}</span>}
    </span>
  );
}
