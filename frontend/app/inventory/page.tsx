"use client";

import Link from "next/link";
import { useCallback, useMemo, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";

const UNITS = ["KG", "GM", "L", "ML", "PIECES"];
const UNIT_LABEL: Record<string, string> = { KG: "Kg", GM: "gm", L: "L", ML: "ml", PIECES: "pieces" };

export default function InventoryPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <InventoryView />
    </RequireRole>
  );
}

function InventoryView() {
  const { getToken } = useAuth();
  const fetchInventory = useCallback((token: string | undefined) => api.listInventory({}, token), []);
  const { data, error, loading, reload } = useAuthedQuery(fetchInventory);
  const { data: ingredientsData } = useAuthedQuery(api.listIngredients);
  const items = data ?? [];
  const ingredients = ingredientsData ?? [];

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [locationFilter, setLocationFilter] = useState("");
  const [onlyLow, setOnlyLow] = useState(false);
  const [showAdd, setShowAdd] = useState(false);

  const locations = useMemo(
    () => [...new Set(items.map((i) => i.storageLocation).filter(Boolean))] as string[],
    [items]
  );

  const visible = items.filter(
    (i) => (!locationFilter || i.storageLocation === locationFilter) && (!onlyLow || i.belowThreshold)
  );
  const lowCount = items.filter((i) => i.belowThreshold).length;
  const expiringCount = items.filter((i) => i.expiringSoon).length;

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
    const threshold = String(f.get("reorderThreshold") ?? "").trim();
    const ok = await run(
      (token) =>
        api.createInventoryItem(
          {
            ingredientId: String(f.get("ingredientId") ?? ""),
            storageLocation: emptyToNull(String(f.get("storageLocation") ?? "")),
            reorderThreshold: threshold === "" ? null : Number(threshold),
            notes: emptyToNull(String(f.get("notes") ?? "")),
          },
          token
        ),
      "We couldn't start tracking that item."
    );
    if (ok) {
      form.reset();
      setShowAdd(false);
    }
  }

  // Ingredients not yet tracked, so the add form only offers new ones.
  const trackedIds = new Set(items.map((i) => i.ingredientId));
  const untracked = ingredients.filter((i) => !trackedIds.has(i.id));

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/inventory" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Inventory</h1>
              <p className="mt-1 text-ink-secondary">
                What&rsquo;s on the shelf — computed from every receipt, donation, and meal cooked.
              </p>
            </div>
            <button
              type="button"
              onClick={() => setShowAdd((s) => !s)}
              className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover"
            >
              Track an item
            </button>
          </header>

          {(lowCount > 0 || expiringCount > 0) && (
            <div className="mb-6 flex flex-wrap gap-3">
              {lowCount > 0 && (
                <button
                  type="button"
                  onClick={() => setOnlyLow((s) => !s)}
                  className={`rounded-md px-4 py-2 text-sm ${onlyLow ? "bg-warning text-ink-inverse" : "bg-warning-bg text-warning"}`}
                >
                  {lowCount} below reorder level{onlyLow ? " — showing only these" : ""}
                </button>
              )}
              {expiringCount > 0 && (
                <span className="rounded-md bg-warning-bg px-4 py-2 text-sm text-warning">
                  {expiringCount} with stock expiring soon
                </span>
              )}
            </div>
          )}

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

          {showAdd && (
            <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="add-heading">
              <h2 id="add-heading" className="text-lg">Track a consumable</h2>
              <form className="mt-4 grid grid-cols-2 gap-4" aria-label="Track an item" onSubmit={add}>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Ingredient
                  <select name="ingredientId" required className="min-h-touch rounded border border-hairline bg-canvas px-3">
                    <option value="">Choose an ingredient…</option>
                    {untracked.map((i) => <option key={i.id} value={i.id}>{i.name}</option>)}
                  </select>
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Storage location
                  <input name="storageLocation" placeholder="Main store, cold room…" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Reorder threshold (canonical unit)
                  <input name="reorderThreshold" type="number" min="0" step="any" placeholder="e.g. 5" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Notes
                  <input name="notes" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <div className="col-span-2">
                  <button type="submit" disabled={busy || untracked.length === 0} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">
                    Start tracking
                  </button>
                  {untracked.length === 0 && (
                    <span className="ml-3 text-sm text-ink-muted">Every ingredient is already tracked.</span>
                  )}
                </div>
              </form>
            </section>
          )}

          {locations.length > 0 && (
            <div className="mb-4">
              <label className="text-sm text-ink-secondary">
                Location{" "}
                <select value={locationFilter} onChange={(e) => setLocationFilter(e.target.value)} className="ml-1 min-h-touch rounded border border-hairline bg-canvas px-3">
                  <option value="">All</option>
                  {locations.map((l) => <option key={l} value={l}>{l}</option>)}
                </select>
              </label>
            </div>
          )}

          {loading ? (
            <Loading label="Loading inventory…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : items.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">Nothing tracked yet</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                Track a consumable above, then stock moves as goods are received, donated, or cooked.
              </p>
            </div>
          ) : (
            <div className="overflow-hidden rounded-lg bg-raised">
              <table className="w-full text-left">
                <thead className="bg-sunken text-sm text-ink-secondary">
                  <tr>
                    <th className="px-5 py-3 font-medium">Item</th>
                    <th className="px-5 py-3 font-medium">Location</th>
                    <th className="px-5 py-3 font-medium text-right">On hand</th>
                    <th className="px-5 py-3 font-medium text-right">Reorder at</th>
                    <th className="px-5 py-3 font-medium">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {visible.map((i) => (
                    <tr key={i.itemId} className="border-t border-hairline align-middle">
                      <td className="px-5 py-3">
                        <Link href={`/inventory/${i.itemId}`} className="font-medium text-accent-text hover:underline">
                          {i.ingredientName}
                        </Link>
                        <span className="ml-2 text-xs text-ink-muted">{i.category}</span>
                      </td>
                      <td className="px-5 py-3 text-ink-secondary">{i.storageLocation ?? "—"}</td>
                      <td className="px-5 py-3 text-right tabular-nums">{i.onHand} {UNIT_LABEL[i.unit] ?? i.unit}</td>
                      <td className="px-5 py-3 text-right tabular-nums text-ink-secondary">
                        {i.reorderThreshold == null ? "—" : `${i.reorderThreshold} ${UNIT_LABEL[i.unit] ?? i.unit}`}
                      </td>
                      <td className="px-5 py-3">
                        <div className="flex flex-wrap gap-1.5">
                          {i.belowThreshold && <span className="rounded-sm bg-warning-bg px-2 py-1 text-xs text-warning">Low</span>}
                          {i.expiringSoon && <span className="rounded-sm bg-warning-bg px-2 py-1 text-xs text-warning">Expiring soon</span>}
                          {!i.belowThreshold && !i.expiringSoon && <span className="text-xs text-ink-muted">OK</span>}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </main>
    </div>
  );
}

function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
}
