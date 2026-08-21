"use client";

import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";

export default function WishlistAdminPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <WishlistAdminView />
    </RequireRole>
  );
}

function WishlistAdminView() {
  const { getToken } = useAuth();
  const { data, error, loading, reload } = useAuthedQuery(
    useCallback((t: string | undefined) => api.listWishlist(false, t), [])
  );
  const items = data ?? [];

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [showAdd, setShowAdd] = useState(false);

  async function run(mutation: (t: string | undefined) => Promise<unknown>, failure: string) {
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
      (t) => api.createWishlistItem({
        title: String(f.get("title") ?? "").trim(),
        description: emptyToNull(String(f.get("description") ?? "")),
        priceInr: Number(f.get("priceInr") ?? 0),
        category: String(f.get("category") ?? "OTHER"),
        quantityWanted: Number(f.get("quantityWanted") ?? 1),
        note: emptyToNull(String(f.get("note") ?? "")),
      }, t),
      "We couldn't add that item."
    );
    if (ok) {
      form.reset();
      setShowAdd(false);
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/wishlist" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Wish list</h1>
              <p className="mt-1 text-ink-secondary">Concrete needs devotees can fund; fulfilled items retire automatically.</p>
            </div>
            <button type="button" onClick={() => setShowAdd((s) => !s)} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover">
              Add an item
            </button>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

          {showAdd && (
            <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="add-heading">
              <h2 id="add-heading" className="text-lg">New wish-list item</h2>
              <form className="mt-4 grid grid-cols-2 gap-4" aria-label="Add wish-list item" onSubmit={add}>
                <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary"><span className="pl-field-inset font-medium text-ink">Title</span>
                  <input name="title" required className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary"><span className="pl-field-inset font-medium text-ink">Price (₹)</span>
                  <input name="priceInr" type="number" min="1" step="any" required className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary"><span className="pl-field-inset font-medium text-ink">Quantity wanted</span>
                  <input name="quantityWanted" type="number" min="1" defaultValue="1" required className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary"><span className="pl-field-inset font-medium text-ink">Category</span>
                  <select name="category" className="min-h-touch rounded border border-hairline bg-canvas px-3">
                    <option value="CONSUMABLE">Consumable</option>
                    <option value="EQUIPMENT">Equipment</option>
                    <option value="OTHER">Other</option>
                  </select>
                </label>
                <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary"><span className="pl-field-inset font-medium text-ink">Description</span>
                  <input name="description" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <div className="col-span-2">
                  <button type="submit" disabled={busy} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">Add item</button>
                </div>
              </form>
            </section>
          )}

          {loading ? (
            <Loading label="Loading wish list…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : items.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">Nothing on the wish list</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">Add an item above so devotees can sponsor it.</p>
            </div>
          ) : (
            <div className="overflow-hidden rounded-lg bg-raised">
              <table className="w-full text-left">
                <thead className="bg-sunken text-sm text-ink-secondary">
                  <tr>
                    <th className="px-5 py-3 font-medium">Item</th>
                    <th className="px-5 py-3 font-medium text-right">Price</th>
                    <th className="px-5 py-3 font-medium text-right">Sponsored</th>
                    <th className="px-5 py-3 font-medium">Status</th>
                    <th className="px-5 py-3 font-medium text-right">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((i) => (
                    <tr key={i.id} className="border-t border-hairline align-middle hover:bg-raised/60">
                      <td className="px-5 py-3 font-medium">{i.title}<span className="ml-2 text-xs text-ink-muted">{i.category.toLowerCase()}</span></td>
                      <td className="px-5 py-3 text-right tabular-nums">₹{i.priceInr}</td>
                      <td className="px-5 py-3 text-right tabular-nums">{i.sponsoredQuantity}/{i.quantityWanted}</td>
                      <td className="px-5 py-3">
                        <span className={`rounded-sm px-2 py-1 text-xs ${i.status === "FULFILLED" ? "bg-success-bg text-success" : i.status === "ARCHIVED" ? "bg-sunken text-ink-muted" : "bg-accent-bg text-accent-text"}`}>
                          {i.status.toLowerCase()}
                        </span>
                      </td>
                      <td className="px-5 py-3 text-right">
                        <button type="button" disabled={busy} onClick={() => run((t) => api.archiveWishlistItem(i.id, t), "We couldn't archive that item.")} className="text-sm text-ink-secondary hover:underline disabled:opacity-60">
                          Archive
                        </button>
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
