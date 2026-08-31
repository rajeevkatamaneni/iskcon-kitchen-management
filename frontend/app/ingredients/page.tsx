"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { EmptyState } from "@/components/ds/EmptyState";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { splitAliases } from "@/components/IngredientForm";
import { api, toApiError, type ApiError, type IngredientView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";
import { FOOD_UNITS, unitLabel } from "@/lib/format";

export default function IngredientsPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      {/* useSearchParams — for the confirmation a new ingredient comes back with — needs a boundary. */}
      <Suspense>
        <IngredientsView />
      </Suspense>
    </RequireRole>
  );
}

function IngredientsView() {
  const { appUser, getToken } = useAuth();
  const isAdmin = appUser?.role === "TEMPLE_ADMIN";
  const { data, error, loading, reload } = useAuthedQuery(api.listIngredients);
  const ingredients = data ?? [];

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [editing, setEditing] = useState<string | null>(null);

  // Adding an ingredient happens on /ingredients/new and ends back here, so the confirmation has to
  // travel in the URL. Captured behind a ref because setting it re-renders, and a router object
  // that is new on each render would otherwise turn this effect into a loop.
  const router = useRouter();
  const added = useSearchParams().get("added");
  const [flash, setFlash] = useState<string | null>(null);
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current || !added) return;
    captured.current = true;
    setFlash(added);
    router.replace("/ingredients");
  }, [added, router]);

  // Let the banner stand, then clear itself. Keyed on `flash` so stripping the param above does not
  // cut the timer short.
  useEffect(() => {
    if (!flash) return;
    const timer = setTimeout(() => setFlash(null), 6000);
    return () => clearTimeout(timer);
  }, [flash]);

  async function run(mutation: (token: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    setActionError(null);
    try {
      await mutation(await getToken());
      reload();
      return true;
    } catch (e) {
      setActionError(toApiError(e, failure));
      return false;
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/ingredients" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-8 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Ingredients</h1>
              <p className="mt-1 text-ink-secondary">
                The shared vocabulary for recipes, inventory and orders.
              </p>
            </div>
            <ButtonLink href="/ingredients/new">Add an ingredient</ButtonLink>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

          {flash && (
            <div className="mb-6">
              <InlineNotice tone="success" autoDismiss title={`${flash} was added.`}>
                It can go into a recipe now, and be tracked in your inventory.
              </InlineNotice>
            </div>
          )}

          {loading ? (
            <Loading label="Loading ingredients…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : ingredients.length === 0 ? (
            <EmptyState
              title="No ingredients yet"
              action={<ButtonLink href="/ingredients/new">Add an ingredient</ButtonLink>}
            >
              Every recipe, stock item and order is written in these words, so this is the list they
              all start from.
            </EmptyState>
          ) : (
            <div className="overflow-hidden rounded-lg bg-raised">
              <table className="w-full text-left">
                <thead className="bg-sunken text-sm text-ink-secondary">
                  <tr>
                    <th className="px-5 py-3 font-medium">Name</th>
                    <th className="px-5 py-3 font-medium">Category</th>
                    <th className="px-5 py-3 font-medium">Unit</th>
                    <th className="px-5 py-3 font-medium">Sattvic</th>
                    <th className="px-5 py-3 font-medium">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {ingredients.map((ing) =>
                    editing === ing.id ? (
                      <EditRow
                        key={ing.id}
                        ingredient={ing}
                        busy={busy}
                        onCancel={() => setEditing(null)}
                        onSave={async (input) => {
                          const ok = await run((t) => api.updateIngredient(ing.id, input, t), "We couldn’t save that.");
                          if (ok) setEditing(null);
                        }}
                      />
                    ) : (
                      <tr key={ing.id} className="border-t border-hairline align-middle hover:bg-sunken">
                        <td className="px-5 py-3">{ing.name}</td>
                        <td className="px-5 py-3 text-ink-secondary">{ing.category}</td>
                        <td className="px-5 py-3 text-ink-secondary">{unitLabel(ing.unit)}</td>
                        <td className="px-5 py-3">
                          {isAdmin ? (
                            <button
                              type="button"
                              disabled={busy}
                              onClick={() => run((t) => api.setIngredientSattvicFlag(ing.id, !ing.sattvicProhibited, t), "We couldn’t change that flag.")}
                              className={`rounded-sm px-2 py-1 text-xs ${ing.sattvicProhibited ? "bg-warning-bg text-warning" : "bg-sunken text-ink-secondary"}`}
                            >
                              {ing.sattvicProhibited ? "Prohibited" : "Allowed"}
                            </button>
                          ) : ing.sattvicProhibited ? (
                            <span className="rounded-sm bg-warning-bg px-2 py-1 text-xs text-warning font-semibold">Prohibited</span>
                          ) : (
                            <span className="text-xs text-ink-muted">Allowed</span>
                          )}
                        </td>
                        <td className="px-5 py-3 text-sm">
                          <button type="button" onClick={() => setEditing(ing.id)} className="text-accent-text hover:underline">Edit</button>
                          <span className="mx-2 text-ink-muted">·</span>
                          <button type="button" disabled={busy} onClick={() => run((t) => api.deleteIngredient(ing.id, t), "That ingredient is in use, or couldn’t be removed.")} className="text-danger hover:underline">Delete</button>
                        </td>
                      </tr>
                    )
                  )}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </main>
    </div>
  );
}

function EditRow({
  ingredient,
  busy,
  onSave,
  onCancel,
}: {
  ingredient: IngredientView;
  busy: boolean;
  onSave: (input: { name: string; category: string; unit: string; aliases: string[] }) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState(ingredient.name);
  const [category, setCategory] = useState(ingredient.category);
  const [unit, setUnit] = useState(ingredient.unit);
  const [aliases, setAliases] = useState(ingredient.aliases.join(", "));

  return (
    <tr className="border-t border-hairline bg-sunken/40 align-middle">
      <td className="px-5 py-3"><input aria-label="Name" value={name} onChange={(e) => setName(e.target.value)} className="min-h-touch w-full rounded border border-hairline bg-canvas px-2" /></td>
      <td className="px-5 py-3"><input aria-label="Category" value={category} onChange={(e) => setCategory(e.target.value)} className="min-h-touch w-full rounded border border-hairline bg-canvas px-2" /></td>
      <td className="px-5 py-3">
        <select aria-label="Unit" value={unit} onChange={(e) => setUnit(e.target.value)} className="min-h-touch rounded border border-hairline bg-canvas px-2">
          {FOOD_UNITS.map((u) => <option key={u} value={u}>{unitLabel(u)}</option>)}
        </select>
      </td>
      <td className="px-5 py-3"><input aria-label="Aliases" value={aliases} onChange={(e) => setAliases(e.target.value)} placeholder="Aliases" className="min-h-touch w-full rounded border border-hairline bg-canvas px-2" /></td>
      <td className="px-5 py-3 text-sm">
        <button type="button" disabled={busy} onClick={() => onSave({ name, category, unit, aliases: splitAliases(aliases) })} className="text-accent-text hover:underline">Save</button>
        <span className="mx-2 text-ink-muted">·</span>
        <button type="button" onClick={onCancel} className="text-ink-secondary hover:underline">Cancel</button>
      </td>
    </tr>
  );
}
