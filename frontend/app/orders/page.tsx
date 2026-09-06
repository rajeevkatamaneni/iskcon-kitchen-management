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
import { dateWithYear, templeDay } from "@/lib/format";
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
                    {/* Beside the status because the pair answers one question — how far along is
                        this, and since when. It was the last column and called "Ordered", which is
                        the one thing it is not: order_date is stamped when the PO is generated, and
                        the order is a DRAFT at that moment. Nothing on this screen said "generated"
                        (Rajeev, 2026-09-05). */}
                    {/* Three dates, and the order they happen in. Rajeev, 2026-09-05: "There cannot
                        be any confusion IF an order was sent late or if the merchant send the items
                        late." Generated → Sent is our lateness; Sent → Needed by is theirs, and one
                        column cannot answer both. */}
                    <th className={TH_TEXT}>Generated</th>
                    <th className={TH_TEXT}>Sent</th>
                    <th className={TH_TEXT}>Needed by</th>
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
                      <td className={`${TD_DATE} text-ink-secondary`}>{dateWithYear(po.orderDate)}</td>
                      {/* An em dash rather than a blank: a draft has not been sent, and saying so is
                          different from having nothing to say. */}
                      {/* templeDay, not dateWithYear: sentAt is an Instant and dateWithYear appends
                          "T00:00:00" to a date-only string, which on a timestamp produces Invalid
                          Date. It also has to be read in the temple's zone — an order sent at 02:00
                          in Bengaluru is the previous evening in UTC, and would sit on the wrong day
                          in the very column that exists to say which day it was sent. */}
                      <td className={`${TD_DATE} text-ink-secondary`}>{po.sentAt ? templeDay(po.sentAt) : "—"}</td>
                      <td className={`${TD_DATE} text-ink-secondary`}>{po.neededBy ? dateWithYear(po.neededBy) : "—"}</td>
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
