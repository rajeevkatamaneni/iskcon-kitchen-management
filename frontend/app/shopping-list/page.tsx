"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type ShoppingListLineView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { cooksQuantity, dateWithYear, unitLabel } from "@/lib/format";
import { Loading } from "@/components/Loading";
import { TABLE, THEAD, TR, TH_TEXT, TH_NUM, TD_TEXT, TD_NUM, TD_DATE, WRAP } from "@/components/ds/table";

export default function ShoppingListPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <ShoppingListView />
    </RequireRole>
  );
}

function ShoppingListView() {
  const { getToken } = useAuth();
  const router = useRouter();
  const { data, error, loading, reload } = useAuthedQuery(api.listShoppingList);
  const lines = data ?? [];

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);

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

  async function setIncluded(line: ShoppingListLineView, included: boolean) {
    await run(
      (t) => api.updateShoppingListLine(line.ingredientId, { suggestedQty: line.suggestedQty, included }, t),
      "We couldn’t update that line."
    );
  }

  async function setQty(line: ShoppingListLineView, qty: number) {
    await run(
      (t) => api.updateShoppingListLine(line.ingredientId, { suggestedQty: qty, included: line.included }, t),
      "We couldn’t update that quantity."
    );
  }

  async function generate() {
    const ids = lines.filter((l) => l.included && l.suggestedVendorId).map((l) => l.ingredientId);
    setBusy(true);
    setActionError(null);
    try {
      await api.generatePurchaseOrders(ids.length > 0 ? ids : null, await getToken());
      router.push("/orders");
    } catch (e) {
      setActionError(toApiError(e, "We couldn’t generate purchase orders."));
      setBusy(false);
    }
  }

  const withVendor = lines.filter((l) => l.included && l.suggestedVendorId).length;

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/shopping-list" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Shopping list</h1>
              <p className="mt-1 text-ink-secondary">
                Edit or uncheck a line before generating orders.
              </p>
            </div>
            <div className="flex gap-3">
              <button type="button" disabled={busy} onClick={() => run((t) => api.regenerateShoppingList(t), "We couldn’t regenerate the list.")} className="min-h-touch rounded border border-hairline px-5 transition-colors duration-state hover:bg-sunken disabled:opacity-60">
                Regenerate
              </button>
              <button type="button" disabled={busy || withVendor === 0} onClick={generate} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">
                Generate purchase orders
              </button>
            </div>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

          {loading ? (
            <Loading label="Loading the shopping list…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : lines.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">Nothing to order</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                Regenerate to check for shortfalls now.
              </p>
            </div>
          ) : (
            <div className="overflow-x-auto rounded-lg bg-raised">
              <table className={TABLE}>
                <thead className={THEAD}>
                  <tr>
                    <th className={TH_TEXT}>Include</th>
                    <th className={`${TH_TEXT} ${WRAP}`}>Ingredient</th>
                    <th className={TH_NUM}>On hand</th>
                    <th className={TH_NUM}>Suggested</th>
                    {/* The only column allowed to grow downwards, so it is the only one that may
                        take the width the others give up. */}
                    <th className={`${TH_TEXT} ${WRAP}`}>Why</th>
                    <th className={`${TH_TEXT} ${WRAP}`}>Vendor</th>
                    <th className={TH_TEXT}>Needed by</th>
                  </tr>
                </thead>
                <tbody>
                  {lines.map((l) => (
                    <tr key={l.ingredientId} className={`${TR} ${l.included ? "" : "opacity-50"}`}>
                      <td className={TD_TEXT}>
                        <input type="checkbox" aria-label={`Include ${l.ingredientName}`} checked={l.included} disabled={busy} onChange={(e) => setIncluded(l, e.target.checked)} />
                      </td>
                      {/* The second unbounded value in this table, after the chips. An ingredient
                          somebody typed has no maximum length, and refusing it a second line would
                          carry the columns beyond it off the edge of the page. */}
                      <td className={`${TD_TEXT} ${WRAP} font-medium`}>
                        {l.ingredientName}
                        {l.edited && <span className="ml-2 text-xs text-ink-muted">edited</span>}
                      </td>
                      <td className={`${TD_NUM} text-ink-secondary`}>{cooksQuantity(l.currentStock, l.unit)}</td>
                      {/* A quantity and its unit are one reading — "55 Kg", never a 55 with a Kg
                          somewhere under it — so the cell refuses to break between them. */}
                      <td className={TD_NUM}>
                        <input
                          type="number" min="0" step="any" defaultValue={l.suggestedQty} disabled={busy}
                          aria-label={`Quantity for ${l.ingredientName}`}
                          onBlur={(e) => { const n = Number(e.target.value); if (n !== l.suggestedQty) setQty(l, n); }}
                          className="w-16 rounded border border-hairline bg-canvas px-2 py-1 tabular-nums"
                        />{" "}
                        {/* The bare label, never a promoted one: the box beside it holds and submits
                            the ingredient's own stored unit, so calling it "gm" beside a figure in
                            kilograms would invite a thousandfold error. */}
                        <span className="text-xs text-ink-muted">{unitLabel(l.unit)}</span>
                      </td>
                      <td className={`${TD_TEXT} ${WRAP}`}>
                        {/* No cap: this is the column that absorbs the table's slack, so the
                            chips reflow across whatever width is going. Capping it as well would
                            leave the cell wide and its contents short — the gap Rajeev saw. */}
                        <div className="flex flex-wrap gap-1">
                          {l.shortfall > 0 && <span className="rounded-sm bg-warning-bg px-2 py-0.5 text-xs text-warning font-semibold">shortfall {cooksQuantity(l.shortfall, l.unit)}</span>}
                          {l.thresholdTopUp > 0 && <span className="rounded-sm bg-sunken px-2 py-0.5 text-xs text-ink-secondary font-semibold">Top-up {cooksQuantity(l.thresholdTopUp, l.unit)}</span>}
                          {l.poOutstanding > 0 && <span className="rounded-sm bg-accent-bg px-2 py-0.5 text-xs text-accent-text font-semibold">PO short {cooksQuantity(l.poOutstanding, l.unit)}</span>}
                          {l.shortPurchaseOrders.map((po) => <span key={po} className="rounded-sm bg-accent-bg px-2 py-0.5 text-xs text-accent-text font-semibold">{po}</span>)}
                        </div>
                      </td>
                      <td className={`${TD_TEXT} ${WRAP} text-ink-secondary`}>
                        {l.suggestedVendorName ?? <span className="text-warning">No vendor</span>}
                      </td>
                      {/* Written the way the rest of the application writes a date, and kept whole:
                          "2026-09-" on one line and "01" on the next is not a date. */}
                      <td className={`${TD_DATE} text-ink-secondary`}>
                        {l.neededBy ? dateWithYear(l.neededBy) : "—"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {lines.length > 0 && withVendor === 0 && (
            <p className="mt-4 text-sm text-ink-muted">
              Set a preferred vendor for these ingredients before generating orders.
            </p>
          )}
        </div>
      </main>
    </div>
  );
}
