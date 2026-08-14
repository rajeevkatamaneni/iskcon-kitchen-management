"use client";

import { useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type IngredientView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

const UNITS = ["KG", "GM", "L", "ML", "PIECES"];
const UNIT_LABEL: Record<string, string> = { KG: "Kg", GM: "gm", L: "L", ML: "ml", PIECES: "pieces" };

export default function IngredientsPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_STAFF"]}>
      <IngredientsView />
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

  async function add(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const f = new FormData(form);
    const ok = await run(
      (token) =>
        api.createIngredient(
          {
            name: String(f.get("name") ?? ""),
            category: String(f.get("category") ?? ""),
            unit: String(f.get("unit") ?? "KG"),
            sattvicProhibited: f.get("sattvicProhibited") === "on",
            aliases: splitAliases(String(f.get("aliases") ?? "")),
          },
          token
        ),
      "We couldn't add that ingredient."
    );
    if (ok) form.reset();
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/ingredients" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-8">
            <h1>Ingredients</h1>
            <p className="mt-1 text-ink-secondary">
              Your temple&rsquo;s catalogue — the shared vocabulary for recipes, inventory, and orders.
            </p>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

          <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="add-heading">
            <h2 id="add-heading" className="text-lg">Add an ingredient</h2>
            <form className="mt-4 grid grid-cols-2 gap-4" aria-label="Add an ingredient" onSubmit={add}>
              <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                Name
                <input name="name" required className="min-h-touch rounded border border-hairline bg-canvas px-3" />
              </label>
              <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                Category
                <input name="category" required placeholder="Grains, Pulses, Spices…" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
              </label>
              <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                Unit
                <select name="unit" className="min-h-touch rounded border border-hairline bg-canvas px-3">
                  {UNITS.map((u) => <option key={u} value={u}>{UNIT_LABEL[u]}</option>)}
                </select>
              </label>
              <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                Aliases (comma-separated)
                <input name="aliases" placeholder="Arhar Dal" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
              </label>
              {isAdmin && (
                <label className="col-span-2 flex items-center gap-2 text-sm">
                  <input name="sattvicProhibited" type="checkbox" className="h-5 w-5 rounded-sm border-hairline-strong" />
                  <span>Sattvic-prohibited (onion, garlic, mushroom, egg…)</span>
                </label>
              )}
              <div className="col-span-2">
                <button type="submit" disabled={busy} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">
                  Add ingredient
                </button>
              </div>
            </form>
          </section>

          {loading ? (
            <p className="text-ink-secondary">Loading ingredients…</p>
          ) : error ? (
            <ErrorNotice error={error} />
          ) : ingredients.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">No ingredients yet</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">Add your first ingredient above.</p>
            </div>
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
                          const ok = await run((t) => api.updateIngredient(ing.id, input, t), "We couldn't save that.");
                          if (ok) setEditing(null);
                        }}
                      />
                    ) : (
                      <tr key={ing.id} className="border-t border-hairline align-middle">
                        <td className="px-5 py-3">{ing.name}</td>
                        <td className="px-5 py-3 text-ink-secondary">{ing.category}</td>
                        <td className="px-5 py-3 text-ink-secondary">{UNIT_LABEL[ing.unit] ?? ing.unit}</td>
                        <td className="px-5 py-3">
                          {isAdmin ? (
                            <button
                              type="button"
                              disabled={busy}
                              onClick={() => run((t) => api.setIngredientSattvicFlag(ing.id, !ing.sattvicProhibited, t), "We couldn't change that flag.")}
                              className={`rounded-sm px-2 py-1 text-xs ${ing.sattvicProhibited ? "bg-warning-bg text-warning" : "bg-sunken text-ink-secondary"}`}
                            >
                              {ing.sattvicProhibited ? "Prohibited" : "Allowed"}
                            </button>
                          ) : ing.sattvicProhibited ? (
                            <span className="rounded-sm bg-warning-bg px-2 py-1 text-xs text-warning">Prohibited</span>
                          ) : (
                            <span className="text-xs text-ink-muted">Allowed</span>
                          )}
                        </td>
                        <td className="px-5 py-3 text-sm">
                          <button type="button" onClick={() => setEditing(ing.id)} className="text-accent-text hover:underline">Edit</button>
                          <span className="mx-2 text-ink-muted">·</span>
                          <button type="button" disabled={busy} onClick={() => run((t) => api.deleteIngredient(ing.id, t), "That ingredient is in use, or couldn't be removed.")} className="text-danger hover:underline">Delete</button>
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
          {UNITS.map((u) => <option key={u} value={u}>{UNIT_LABEL[u]}</option>)}
        </select>
      </td>
      <td className="px-5 py-3"><input aria-label="Aliases" value={aliases} onChange={(e) => setAliases(e.target.value)} placeholder="aliases" className="min-h-touch w-full rounded border border-hairline bg-canvas px-2" /></td>
      <td className="px-5 py-3 text-sm">
        <button type="button" disabled={busy} onClick={() => onSave({ name, category, unit, aliases: splitAliases(aliases) })} className="text-accent-text hover:underline">Save</button>
        <span className="mx-2 text-ink-muted">·</span>
        <button type="button" onClick={onCancel} className="text-ink-secondary hover:underline">Cancel</button>
      </td>
    </tr>
  );
}

function splitAliases(raw: string): string[] {
  return raw.split(",").map((s) => s.trim()).filter(Boolean);
}
