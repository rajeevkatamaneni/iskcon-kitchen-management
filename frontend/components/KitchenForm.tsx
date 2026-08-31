"use client";

import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ds/Button";
import { ErrorNotice } from "@/components/ErrorNotice";
import { InlineNotice } from "@/components/ds/InlineNotice";
import {
  api,
  toApiError,
  type ApiError,
  type Kitchen,
  type KitchenInput,
  type MealPlannerImpact,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * Every kitchen the temple has, archived ones included.
 *
 * <p>Archived ones count here because the main-kitchen flag is one per temple whether or not the
 * kitchen holding it is still in use — the database's partial unique index does not exempt them.
 * A form that asked only about active kitchens could offer to move a flag it cannot see.
 */
const everyKitchen = (token?: string) => api.listKitchens(true, token);

/** Who could be put in charge. Wrapped because the fetcher takes the token and nothing else. */
const templeUsers = (token?: string) => api.listUsers(token);

/** "2 drafts will be deleted and 3 requests will be denied." Only the halves that are not zero. */
export function settlement(impact: MealPlannerImpact): string {
  const parts: string[] = [];
  if (impact.draftsDeleted > 0) {
    parts.push(`${impact.draftsDeleted} ${impact.draftsDeleted === 1 ? "draft" : "drafts"} will be deleted`);
  }
  if (impact.requestsDenied > 0) {
    parts.push(
      `${impact.requestsDenied} ${impact.requestsDenied === 1 ? "request" : "requests"} will be denied`
    );
  }
  return `${parts.join(" and ")}.`;
}

/** What still has to be agreed to before a save is allowed to go through. */
interface Pending {
  input: KitchenInput;
  /** The kitchen that stops being the main one, by name. Null when nothing moves. */
  losing: string | null;
  /** What turning the meal planner on would settle. Null when it would settle nothing. */
  impact: MealPlannerImpact | null;
}

/**
 * The kitchen create/edit form (E10-S3). Presentational in the same sense {@link RecipeForm} is:
 * it collects a {@link KitchenInput} and hands it to the parent, which owns the call, the
 * navigation and the error.
 *
 * <p>Two of its fields are not ordinary fields, and both are handled here rather than on either
 * screen, so neither screen can forget one:
 *
 * <ul>
 *   <li><b>The main kitchen.</b> At most one per temple. Ticking it where another kitchen holds it
 *       names that kitchen and waits — a flag that moves silently is a flag nobody can be told
 *       moved. On a temple's first kitchen there is nothing to take it from, so it is ticked and
 *       disabled with a line saying why.
 *   <li><b>The meal planner.</b> Turning it on settles every ingredient request in flight for this
 *       kitchen: drafts deleted permanently, anything awaiting or holding approval denied (D6). So
 *       it asks the server what that would cost and says the counts before it does it.
 * </ul>
 */
export function KitchenForm({
  initial,
  formId,
  busy,
  error,
  onSubmit,
}: {
  initial?: Kitchen;
  /** The id the screen's commit button reaches with `form={formId}`. This form has no button. */
  formId: string;
  busy: boolean;
  error: ApiError | null;
  onSubmit: (input: KitchenInput) => void;
}) {
  const { getToken } = useAuth();
  const kitchens = useAuthedQuery(everyKitchen);
  const users = useAuthedQuery(templeUsers);

  const [name, setName] = useState(initial?.name ?? "");
  const [description, setDescription] = useState(initial?.description ?? "");
  const [location, setLocation] = useState(initial?.location ?? "");
  const [inChargeUserId, setInChargeUserId] = useState(initial?.inChargeUserId ?? "");
  const [contactPhone, setContactPhone] = useState(initial?.contactPhone ?? "");
  const [isMain, setIsMain] = useState(initial?.isMain ?? false);
  const [usesMealPlanner, setUsesMealPlanner] = useState(initial?.usesMealPlanner ?? false);

  const [pending, setPending] = useState<Pending | null>(null);
  const [checking, setChecking] = useState(false);
  const [checkError, setCheckError] = useState<ApiError | null>(null);

  const all = kitchens.data;
  const others = (all ?? []).filter((k) => k.id !== initial?.id);
  const currentMain = others.find((k) => k.isMain) ?? null;
  // A temple's first kitchen: nothing exists yet, and we have actually heard back to know it.
  const firstKitchen = !initial && all !== null && all.length === 0;

  useEffect(() => {
    if (firstKitchen) setIsMain(true);
  }, [firstKitchen]);

  // Somebody else moved the main flag between this form opening and this save (KMS-4985). Read the
  // list again so the confirmation below names whichever kitchen holds it now. Guarded on the error
  // object itself, because reloading re-renders and an unguarded effect would fetch in a loop.
  const reloadKitchens = kitchens.reload;
  const handled = useRef<ApiError | null>(null);
  useEffect(() => {
    if (!error || error.code !== "KMS-4985" || handled.current === error) return;
    handled.current = error;
    reloadKitchens();
  }, [error, reloadKitchens]);

  function build(): KitchenInput {
    return {
      name: name.trim(),
      description: description.trim() || null,
      location: location.trim() || null,
      isMain,
      usesMealPlanner,
      inChargeUserId: inChargeUserId || null,
      contactPhone: contactPhone.trim() || null,
    };
  }

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (checking || busy) return;

    const input = build();
    const losing = input.isMain && !initial?.isMain && currentMain ? currentMain.name : null;

    // Only a kitchen that exists can have requests in flight, and only a flag that is going from
    // off to on settles them. A new kitchen has nothing to settle, so it is never asked.
    let impact: MealPlannerImpact | null = null;
    if (initial && input.usesMealPlanner && !initial.usesMealPlanner) {
      setChecking(true);
      setCheckError(null);
      try {
        const found = await api.mealPlannerImpact(initial.id, await getToken());
        impact = found.draftsDeleted > 0 || found.requestsDenied > 0 ? found : null;
      } catch (e) {
        setCheckError(toApiError(e, "We couldn’t work out what that would settle."));
        setChecking(false);
        return;
      }
      setChecking(false);
    }

    // Nothing moves and nothing is settled: save without asking. A confirmation nobody needs is a
    // confirmation everybody learns to click through.
    if (!losing && !impact) {
      onSubmit(input);
      return;
    }
    setPending({ input, losing, impact });
  }

  const people = (users.data ?? []).filter(
    (u) => u.status === "ACTIVE" || u.id === initial?.inChargeUserId
  );

  return (
    <form id={formId} onSubmit={handleSubmit} className="space-y-8">
      {error && <ErrorNotice error={error} />}
      {checkError && <ErrorNotice error={checkError} />}

      {error?.code === "KMS-4975" && (
        <InlineNotice tone="warning" title="This kitchen is archived.">
          Restore it on the kitchens list, then make your changes.
        </InlineNotice>
      )}

      {error?.code === "KMS-4985" && (
        <InlineNotice tone="warning" title="The main kitchen moved while this form was open.">
          {currentMain
            ? `${currentMain.name} holds it now. Save again to bring it here.`
            : "No kitchen holds it now. Save again to bring it here."}
        </InlineNotice>
      )}

      {checking && (
        <p className="text-sm text-ink-secondary">Checking what this would settle…</p>
      )}

      {pending && (
        <div role="alert" className="rounded border border-warning-bg bg-warning-bg p-4 text-warning">
          <p className="font-medium">Read this before it is saved</p>

          {pending.losing && (
            <p className="mt-2">
              {pending.losing} is the temple’s main kitchen at the moment. Saving moves that here, and{" "}
              {pending.losing} becomes an ordinary kitchen.
            </p>
          )}

          {pending.impact && (
            <>
              <p className="mt-2">{settlement(pending.impact)}</p>
              <p className="mt-1">
                A deleted draft cannot be brought back, and a denial is final. Anything this kitchen
                needed before today is left alone.
              </p>
            </>
          )}

          <div className="mt-4 flex flex-wrap gap-2">
            <Button type="button" variant="secondary" size="sm" onClick={() => setPending(null)}>
              Go back
            </Button>
            <Button
              type="button"
              size="sm"
              disabled={busy}
              onClick={() => {
                const agreed = pending;
                setPending(null);
                onSubmit(agreed.input);
              }}
            >
              Save these changes
            </Button>
          </div>
        </div>
      )}

      <section className="space-y-5">
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Name</span>
          <input value={name} onChange={(e) => setName(e.target.value)} required
            className="min-h-touch rounded border border-hairline bg-raised px-3" />
          {error?.code === "KMS-4972" && (
            <span className="pl-field-inset text-danger">
              Another kitchen here already goes by this name.
            </span>
          )}
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Description</span>
          <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={2}
            placeholder="Cooks for the deities every morning."
            className="rounded border border-hairline bg-raised px-3 py-2" />
        </label>

        <div className="grid grid-cols-1 gap-5 sm:grid-cols-3">
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Location</span>
            <input value={location} onChange={(e) => setLocation(e.target.value)}
              placeholder="Ground floor, east wing"
              className="min-h-touch rounded border border-hairline bg-raised px-3" />
          </label>

          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Who runs it</span>
            <select value={inChargeUserId} onChange={(e) => setInChargeUserId(e.target.value)}
              className="min-h-touch rounded border border-hairline bg-raised px-3">
              <option value="">Nobody yet</option>
              {people.map((u) => (
                <option key={u.id} value={u.id}>{u.fullName}</option>
              ))}
            </select>
          </label>

          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Contact phone</span>
            <input type="tel" value={contactPhone} onChange={(e) => setContactPhone(e.target.value)}
              placeholder="+91 98765 43210"
              className="min-h-touch rounded border border-hairline bg-raised px-3" />
          </label>
        </div>
      </section>

      <section aria-labelledby="kitchen-answers" className="space-y-4">
        <h2 id="kitchen-answers" className="text-lg">What this kitchen is</h2>

        <label className="flex items-start gap-3 rounded border border-hairline bg-raised p-4 text-sm text-ink-secondary">
          <input type="checkbox" checked={isMain} disabled={firstKitchen}
            onChange={(e) => setIsMain(e.target.checked)}
            className="mt-1 h-4 w-4 flex-none" />
          <span>
            <span className="block font-medium text-ink">This is the temple’s main kitchen</span>
            {firstKitchen ? (
              <span className="mt-1 block">
                A temple’s only kitchen is its main one, so this one is.
              </span>
            ) : currentMain ? (
              <span className="mt-1 block">
                {currentMain.name} holds this at the moment. Ticking it here asks first.
              </span>
            ) : (
              <span className="mt-1 block">
                A label for the principal kitchen. Only one kitchen can carry it.
              </span>
            )}
          </span>
        </label>

        <label className="flex items-start gap-3 rounded border border-hairline bg-raised p-4 text-sm text-ink-secondary">
          <input type="checkbox" checked={usesMealPlanner}
            onChange={(e) => setUsesMealPlanner(e.target.checked)}
            className="mt-1 h-4 w-4 flex-none" />
          <span>
            <span className="block font-medium text-ink">
              This kitchen plans its meals here, using recipes and the meal planner
            </span>
            <span className="mt-1 block">
              Its ingredients are drawn from the store as its meals are recorded, so it no longer
              asks the store for them. One kitchen, one door, which is what stops the same rice
              leaving the books twice.
            </span>
          </span>
        </label>
      </section>
    </form>
  );
}
