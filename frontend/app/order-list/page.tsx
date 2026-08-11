"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type OrderListLineView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

export default function OrderListPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_STAFF"]}>
      <OrderListView />
    </RequireRole>
  );
}

function OrderListView() {
  const { getToken } = useAuth();
  const router = useRouter();
  const { data, error, loading, reload } = useAuthedQuery(api.listOrderList);
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

  async function setIncluded(line: OrderListLineView, included: boolean) {
    await run(
      (t) => api.updateOrderLine(line.ingredientId, { suggestedQty: line.suggestedQty, included }, t),
      "We couldn't update that line."
    );
  }

  async function setQty(line: OrderListLineView, qty: number) {
    await run(
      (t) => api.updateOrderLine(line.ingredientId, { suggestedQty: qty, included: line.included }, t),
      "We couldn't update that quantity."
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
      setActionError(toApiError(e, "We couldn't generate purchase orders."));
      setBusy(false);
    }
  }

  const withVendor = lines.filter((l) => l.included && l.suggestedVendorId).length;

  return (
    <div className="flex min-h-screen">
      <Sidebar templeName="Your temple" activeHref="/order-list" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Order list</h1>
              <p className="mt-1 text-ink-secondary">
                What to buy — merged from meal-plan shortfalls, low stock, and short deliveries. Edit or uncheck a line before generating orders.
              </p>
            </div>
            <div className="flex gap-3">
              <button type="button" disabled={busy} onClick={() => run((t) => api.regenerateOrderList(t), "We couldn't regenerate the list.")} className="min-h-touch rounded border border-hairline px-5 transition-colors duration-state hover:bg-sunken disabled:opacity-60">
                Regenerate
              </button>
              <button type="button" disabled={busy || withVendor === 0} onClick={generate} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">
                Generate purchase orders
              </button>
            </div>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

          {loading ? (
            <p className="text-ink-secondary">Loading the order list…</p>
          ) : error ? (
            <ErrorNotice error={error} />
          ) : lines.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">Nothing to order</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                When stock runs low or a meal plan needs more than is on hand, suggestions appear here. Regenerate to check now.
              </p>
            </div>
          ) : (
            <div className="overflow-hidden rounded-lg bg-raised">
              <table className="w-full text-left">
                <thead className="bg-sunken text-sm text-ink-secondary">
                  <tr>
                    <th className="px-4 py-3 font-medium">Include</th>
                    <th className="px-4 py-3 font-medium">Ingredient</th>
                    <th className="px-4 py-3 font-medium text-right">On hand</th>
                    <th className="px-4 py-3 font-medium text-right">Suggested</th>
                    <th className="px-4 py-3 font-medium">Why</th>
                    <th className="px-4 py-3 font-medium">Vendor</th>
                    <th className="px-4 py-3 font-medium">Needed by</th>
                  </tr>
                </thead>
                <tbody>
                  {lines.map((l) => (
                    <tr key={l.ingredientId} className={`border-t border-hairline align-middle ${l.included ? "" : "opacity-50"}`}>
                      <td className="px-4 py-3">
                        <input type="checkbox" aria-label={`Include ${l.ingredientName}`} checked={l.included} disabled={busy} onChange={(e) => setIncluded(l, e.target.checked)} />
                      </td>
                      <td className="px-4 py-3 font-medium">
                        {l.ingredientName}
                        {l.edited && <span className="ml-2 text-xs text-ink-muted">edited</span>}
                      </td>
                      <td className="px-4 py-3 text-right tabular-nums text-ink-secondary">{l.currentStock} {l.unit}</td>
                      <td className="px-4 py-3 text-right">
                        <input
                          type="number" min="0" step="any" defaultValue={l.suggestedQty} disabled={busy}
                          aria-label={`Quantity for ${l.ingredientName}`}
                          onBlur={(e) => { const n = Number(e.target.value); if (n !== l.suggestedQty) setQty(l, n); }}
                          className="w-24 rounded border border-hairline bg-canvas px-2 py-1 text-right tabular-nums"
                        /> <span className="text-xs text-ink-muted">{l.unit}</span>
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex flex-wrap gap-1">
                          {l.shortfall > 0 && <span className="rounded-sm bg-warning-bg px-2 py-0.5 text-xs text-warning">shortfall {l.shortfall}</span>}
                          {l.thresholdTopUp > 0 && <span className="rounded-sm bg-sunken px-2 py-0.5 text-xs text-ink-secondary">top-up {l.thresholdTopUp}</span>}
                          {l.poOutstanding > 0 && <span className="rounded-sm bg-accent-bg px-2 py-0.5 text-xs text-accent-text">PO short {l.poOutstanding}</span>}
                          {l.shortPurchaseOrders.map((po) => <span key={po} className="rounded-sm bg-accent-bg px-2 py-0.5 text-xs text-accent-text">{po}</span>)}
                        </div>
                      </td>
                      <td className="px-4 py-3 text-ink-secondary">
                        {l.suggestedVendorName ?? <span className="text-warning">no vendor</span>}
                      </td>
                      <td className="px-4 py-3 text-ink-secondary tabular-nums">{l.neededBy ?? "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {lines.length > 0 && withVendor === 0 && (
            <p className="mt-4 text-sm text-ink-muted">
              No included line has a vendor yet. Set a preferred vendor for these ingredients before generating orders.
            </p>
          )}
        </div>
      </main>
    </div>
  );
}
