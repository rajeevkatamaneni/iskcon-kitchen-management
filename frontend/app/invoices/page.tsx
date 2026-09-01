"use client";

import Link from "next/link";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, type InvoiceStatus } from "@/lib/api";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";
import { dateWithYear, money } from "@/lib/format";
import { TABLE, THEAD, TR, TH_TEXT, TH_NUM, TD_TEXT, TD_NUM, TD_DATE, WRAP } from "@/components/ds/table";

export default function InvoicesPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      {/* useSearchParams — for the confirmation a recorded invoice comes back with. */}
      <Suspense>
        <InvoicesView />
      </Suspense>
    </RequireRole>
  );
}

function InvoicesView() {
  const [status, setStatus] = useState<InvoiceStatus | "">("");
  const [overdueOnly, setOverdueOnly] = useState(false);

  const fetchInvoices = useCallback(
    (token: string | undefined) =>
      api.listInvoices({ status: status || undefined, overdue: overdueOnly }, token),
    [status, overdueOnly]
  );
  const { data, error, loading } = useAuthedQuery(fetchInvoices);
  const invoices = data ?? [];

  // Recording happens on /invoices/new and ends here, so the confirmation travels in the URL. The
  // ref guards the capture: setting state re-renders, and a router object that is new each render
  // would otherwise re-run this effect for ever.
  const router = useRouter();
  const params = useSearchParams();
  const recorded = params.get("recorded");
  const duplicate = params.get("duplicate") === "1";
  const [flash, setFlash] = useState<{ number: string; duplicate: boolean } | null>(null);
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current || !recorded) return;
    captured.current = true;
    setFlash({ number: recorded, duplicate });
    router.replace("/invoices");
  }, [recorded, duplicate, router]);

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/invoices" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Invoices</h1>
              <p className="mt-1 text-ink-secondary">
                Captured against a purchase order, or direct for a cash-market buy.
              </p>
            </div>
            <ButtonLink href="/invoices/new">Record an invoice</ButtonLink>
          </header>

          {flash && (
            <div className="mb-6">
              {flash.duplicate ? (
                <InlineNotice tone="warning" title={`Invoice ${flash.number} was recorded.`}>
                  Another invoice from this vendor already uses that number.
                </InlineNotice>
              ) : (
                <InlineNotice tone="success" autoDismiss title={`Invoice ${flash.number} was recorded.`} />
              )}
            </div>
          )}

          <div className="mb-4 flex flex-wrap items-center gap-4">
            <label className="text-sm text-ink-secondary">
              <span className="font-medium text-ink">Status</span>
              <select value={status} onChange={(e) => setStatus(e.target.value as InvoiceStatus | "")} className="ml-2 min-h-touch rounded border border-hairline bg-canvas px-3">
                <option value="">All</option>
                <option value="PENDING">Pending</option>
                <option value="PAID">Paid</option>
              </select>
            </label>
            <label className="text-sm text-ink-secondary">
              <input type="checkbox" checked={overdueOnly} onChange={(e) => setOverdueOnly(e.target.checked)} className="mr-2 align-middle" />
              Overdue only
            </label>
          </div>

          {loading ? (
            <Loading label="Loading invoices…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : invoices.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">No invoices</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                Record a vendor’s invoice so Payments knows what is owed.
              </p>
            </div>
          ) : (
            <div className="overflow-x-auto rounded-lg bg-raised">
              <table className={TABLE}>
                <thead className={THEAD}>
                  <tr>
                    <th className={TH_TEXT}>Invoice</th>
                    <th className={`${TH_TEXT} ${WRAP}`}>Vendor</th>
                    <th className={TH_TEXT}>Against</th>
                    <th className={TH_NUM}>Amount</th>
                    <th className={TH_TEXT}>Due</th>
                    <th className={TH_TEXT}>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {invoices.map((inv) => (
                    <tr key={inv.id} className={TR}>
                      <td className={`${TD_TEXT}`}>
                        <Link href={`/invoices/${inv.id}`} className="font-medium text-accent-text hover:underline">
                          {inv.invoiceNumber}
                        </Link>
                      </td>
                      <td className={`${TD_TEXT} ${WRAP} text-ink-secondary`}>{inv.vendorName}</td>
                      <td className={`${TD_TEXT} text-ink-secondary`}>
                        {inv.direct ? (
                          <span className="rounded-sm bg-sunken px-2 py-1 text-xs font-semibold">Direct</span>
                        ) : (
                          <span className="tabular-nums">{inv.poNumber ?? "—"}</span>
                        )}
                        {inv.variance != null && inv.variance !== 0 && (
                          <span className="ml-2 rounded-sm bg-warning-bg px-2 py-0.5 text-xs text-warning font-semibold">
                            Variance {money(inv.variance, "INR")}
                          </span>
                        )}
                      </td>
                      <td className={TD_NUM}>{money(inv.amount, "INR")}</td>
                      <td className={`${TD_DATE} text-ink-secondary`}>
                        {inv.dueDate ? dateWithYear(inv.dueDate) : "—"}
                        {inv.overdue && <span className="ml-2 rounded-sm bg-danger-bg px-2 py-0.5 text-xs text-danger font-semibold">Overdue</span>}
                      </td>
                      <td className={TD_TEXT}>
                        {inv.status === "PAID" ? (
                          <span className="rounded-sm bg-success-bg px-2 py-1 text-xs text-success font-semibold">Paid</span>
                        ) : (
                          <span className="rounded-sm bg-accent-bg px-2 py-1 text-xs text-accent-text font-semibold">Pending</span>
                        )}
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
