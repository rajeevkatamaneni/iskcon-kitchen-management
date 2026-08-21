"use client";

import Link from "next/link";
import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, type PoStatus } from "@/lib/api";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { STATUSES, STATUS_LABEL, statusChip } from "./po-status";
import { Loading } from "@/components/Loading";

export default function PurchaseOrdersPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <PurchaseOrdersView />
    </RequireRole>
  );
}

function PurchaseOrdersView() {
  const [status, setStatus] = useState<PoStatus | "">("");
  const fetchPos = useCallback(
    (token: string | undefined) => api.listPurchaseOrders(status || undefined, token),
    [status]
  );
  const { data, error, loading } = useAuthedQuery(fetchPos);
  const orders = data ?? [];

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/orders" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6">
            <h1>Purchase orders</h1>
            <p className="mt-1 text-ink-secondary">
              Generate orders from the order list.
            </p>
          </header>

          <div className="mb-4">
            <label className="text-sm text-ink-secondary">
              <span className="font-medium text-ink">Status</span>
              <select value={status} onChange={(e) => setStatus(e.target.value as PoStatus | "")} className="ml-2 min-h-touch rounded border border-hairline bg-canvas px-3">
                <option value="">All</option>
                {STATUSES.map((s) => <option key={s} value={s}>{STATUS_LABEL[s]}</option>)}
              </select>
            </label>
          </div>

          {loading ? (
            <Loading label="Loading purchase orders…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : orders.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">No purchase orders</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                Generate orders from the <Link href="/order-list" className="text-accent-text hover:underline">order list</Link>, or create one directly.
              </p>
            </div>
          ) : (
            <div className="overflow-hidden rounded-lg bg-raised">
              <table className="w-full text-left">
                <thead className="bg-sunken text-sm text-ink-secondary">
                  <tr>
                    <th className="px-5 py-3 font-medium">PO</th>
                    <th className="px-5 py-3 font-medium">Vendor</th>
                    <th className="px-5 py-3 font-medium">Status</th>
                    <th className="px-5 py-3 font-medium">Needed by</th>
                    <th className="px-5 py-3 font-medium">Ordered</th>
                  </tr>
                </thead>
                <tbody>
                  {orders.map((po) => (
                    <tr key={po.id} className="border-t border-hairline align-middle hover:bg-raised/60">
                      <td className="px-5 py-3">
                        <Link href={`/orders/${po.id}`} className="font-medium text-accent-text hover:underline tabular-nums">
                          {po.poNumber}
                        </Link>
                      </td>
                      <td className="px-5 py-3 text-ink-secondary">{po.vendorName}</td>
                      <td className="px-5 py-3">{statusChip(po.status)}</td>
                      <td className="px-5 py-3 text-ink-secondary tabular-nums">{po.neededBy ?? "—"}</td>
                      <td className="px-5 py-3 text-ink-secondary tabular-nums">{po.orderDate}</td>
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
