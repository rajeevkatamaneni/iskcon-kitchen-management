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
import { dateWithYear } from "@/lib/format";
import { TABLE, THEAD, TR, TH_TEXT, TD_TEXT, TD_DATE, WRAP } from "@/components/ds/table";

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
              Generate orders from the shopping list.
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
                Generate orders from the <Link href="/shopping-list" className="text-accent-text hover:underline">shopping list</Link>, or create one directly.
              </p>
            </div>
          ) : (
            <div className="overflow-x-auto rounded-lg bg-raised">
              <table className={TABLE}>
                <thead className={THEAD}>
                  <tr>
                    <th className={TH_TEXT}>PO</th>
                    <th className={`${TH_TEXT} ${WRAP}`}>Vendor</th>
                    <th className={TH_TEXT}>Status</th>
                    <th className={TH_TEXT}>Needed by</th>
                    <th className={TH_TEXT}>Ordered</th>
                  </tr>
                </thead>
                <tbody>
                  {orders.map((po) => (
                    <tr key={po.id} className={TR}>
                      <td className={TD_TEXT}>
                        <Link href={`/orders/${po.id}`} className="font-medium text-accent-text hover:underline tabular-nums">
                          {po.poNumber}
                        </Link>
                      </td>
                      <td className={`${TD_TEXT} ${WRAP} text-ink-secondary`}>{po.vendorName}</td>
                      <td className={TD_TEXT}>{statusChip(po.status)}</td>
                      <td className={`${TD_DATE} text-ink-secondary`}>{po.neededBy ? dateWithYear(po.neededBy) : "—"}</td>
                      <td className={`${TD_DATE} text-ink-secondary`}>{dateWithYear(po.orderDate)}</td>
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
