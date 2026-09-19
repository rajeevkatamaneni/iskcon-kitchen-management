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
import { RULED_TABLE, THEAD, TR, TH_LEAD, TD_LEAD, TH_PRIMARY, TD_PRIMARY, TH_FIXED, TD_FIXED, TD_FIXED_NUM } from "@/components/ds/table";

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
      <main className="min-w-0 flex-1 px-4 py-10 sm:px-8">
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
              <select value={status} onChange={(e) => setStatus(e.target.value as InvoiceStatus | "")} className="ml-2 min-h-touch rounded-control border border-hairline px-3">
                <option value="">All</option>
                <option value="PENDING">Pending</option>
                <option value="PAID">Paid</option>
                <option value="VOIDED">Voided</option>
              </select>
            </label>
            <label className="inline-flex min-h-touch items-center text-sm text-ink-secondary">
              <input type="checkbox" checked={overdueOnly} onChange={(e) => setOverdueOnly(e.target.checked)} className="mr-2 accent-accent" />
              Overdue only
            </label>
          </div>

          {loading ? (
            <Loading label="Loading invoices…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : invoices.length === 0 ? (
            <div className="card px-6 py-14 text-center">
              <p className="text-lg">No invoices</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                Record a vendor’s invoice so Payments knows what is owed.
              </p>
            </div>
          ) : (
            <div className="table-wrap overflow-x-auto">
              {/* On the table rule since 2026-09-18 (T-233): the invoice number leads on the left,
                  fixed, because it is the row's link (the exception Rajeev accepted); the vendor is
                  the primary flexible column; everything after it is fixed (one line). Since T-236 every
                  column reads left and the spare width is shared evenly between them. */}
              <table className={RULED_TABLE}>
                <thead className={THEAD}>
                  <tr>
                    <th className={TH_LEAD}>Invoice</th>
                    <th className={TH_PRIMARY}>Vendor</th>
                    <th className={TH_FIXED}>Against</th>
                    <th className={TH_FIXED}>Amount</th>
                    <th className={TH_FIXED}>Due</th>
                    <th className={TH_FIXED}>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {invoices.map((inv) => (
                    <tr key={inv.id} className={TR}>
                      <td className={TD_LEAD}>
                        <Link href={`/invoices/${inv.id}`} className="font-medium text-accent-text hover:underline">
                          {inv.invoiceNumber}
                        </Link>
                      </td>
                      <td className={`${TD_PRIMARY} text-ink-secondary`}>{inv.vendorName}</td>
                      <td className={`${TD_FIXED} text-ink-secondary`}>
                        {inv.direct ? (
                          <span className="rounded-control bg-sunken px-2 py-1 text-xs font-semibold">Direct</span>
                        ) : (
                          <span className="tabular-nums">{inv.poNumber ?? "—"}</span>
                        )}
                        {inv.variance != null && inv.variance !== 0 && (
                          <span className="ml-2 rounded-control bg-warning-bg px-2 py-0.5 text-xs text-warning font-semibold">
                            Variance {money(inv.variance, "INR")}
                          </span>
                        )}
                      </td>
                      <td className={TD_FIXED_NUM}>
                        {money(inv.amount, "INR")}
                        {/* What is owed, where it differs from what was billed. A credit note leaves
                            the invoiced figure alone — that is what the vendor sent — so the row has
                            to say the rest out loud or it reads as money still to pay. */}
                        {inv.creditedAmount > 0 && (
                          <span className="ml-2 text-xs text-ink-secondary">
                            less {money(inv.creditedAmount, "INR")} credited
                          </span>
                        )}
                      </td>
                      <td className={`${TD_FIXED} text-ink-secondary`} data-label="Due">
                        {inv.dueDate ? dateWithYear(inv.dueDate) : "—"}
                        {inv.overdue && <span className="ml-2 rounded-control bg-danger-bg px-2 py-0.5 text-xs text-danger font-semibold">Overdue</span>}
                      </td>
                      <td className={TD_FIXED}>
                        {inv.status === "VOIDED" ? (
                          <span className="rounded-control bg-sunken px-2 py-1 text-xs text-ink-secondary font-semibold">Voided</span>
                        ) : inv.status === "PAID" ? (
                          // Neutral: paid is a settled state, and green is kept for the moment the
                          // reader's own action succeeds (Rajeev, 2026-09-18, T-227).
                          <span className="rounded-control bg-sunken px-2 py-1 text-xs text-ink-secondary font-semibold">Paid</span>
                        ) : (
                          <span className="rounded-control bg-accent-bg px-2 py-1 text-xs text-accent-text font-semibold">Pending</span>
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
