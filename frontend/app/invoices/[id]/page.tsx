"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, type VendorInvoiceView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";
import { dateWithYear, money } from "@/lib/format";
import { TABLE, THEAD, TR, TH_TEXT, TH_NUM, TD_TEXT, TD_NUM, TD_DATE, WRAP } from "@/components/ds/table";

/**
 * One vendor invoice in full (A8).
 *
 * <p>The list can only carry six columns, so several things captured at recording were being stored
 * and never shown again: the description that is the whole content of a direct cash-market invoice,
 * the date on the paper, and the reference of the scan somebody filed. They live here.
 *
 * <p>The variance is the other reason this page exists. On the list it reads "variance ₹50" with
 * nothing to be a difference *from*, which is a number a person cannot act on. Here it is shown as
 * the subtraction it actually is: what the vendor invoiced, against what the goods received would
 * cost at the purchase order's own prices.
 *
 * <p>Gated to the same roles as the list — the API is the boundary, and this reads no more than the
 * list already does. The payment history is the exception and is handled separately below.
 */
export default function InvoiceDetailPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <InvoiceDetailView />
    </RequireRole>
  );
}

function InvoiceDetailView() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const { appUser } = useAuth();

  const fetchInvoice = useCallback((token: string | undefined) => api.getInvoice(id, token), [id]);
  const { data: invoice, error, loading } = useAuthedQuery(fetchInvoice);

  // Recording and reading payments sit behind MANAGE_VENDOR_PAYMENTS, which of the roles that can
  // open this page only a Temple Admin holds. Showing a Kitchen Manager an empty payments table
  // would tell them the invoice is unpaid when in truth they simply cannot see; the section is
  // absent instead.
  const canSeePayments = appUser?.role === "TEMPLE_ADMIN";

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/invoices" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <Link href="/invoices" className="text-sm text-accent-text hover:underline">← All invoices</Link>

          {loading ? (
            <Loading label="Loading invoice…" />
          ) : error ? (
            <div className="mt-6"><ErrorNotice error={error} /></div>
          ) : !invoice ? null : (
            <>
              <header className="mb-6 mt-3">
                <h1>{invoice.invoiceNumber}</h1>
                <p className="mt-1 flex flex-wrap items-center gap-2 text-ink-secondary">
                  <Link href={`/vendors/${invoice.vendorId}`} className="text-accent-text hover:underline">
                    {invoice.vendorName}
                  </Link>
                  {invoice.status === "PAID" ? (
                    <span className="rounded-sm bg-success-bg px-2 py-1 text-xs text-success font-semibold">Paid</span>
                  ) : (
                    <span className="rounded-sm bg-accent-bg px-2 py-1 text-xs text-accent-text font-semibold">Pending</span>
                  )}
                  {invoice.overdue && (
                    <span className="rounded-sm bg-danger-bg px-2 py-1 text-xs text-danger font-semibold">Overdue</span>
                  )}
                </p>
              </header>

              <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="invoice-heading">
                <h2 id="invoice-heading" className="text-lg">The invoice</h2>
                <dl className="mt-4 grid grid-cols-2 gap-x-8 gap-y-4 text-sm">
                  <Detail label="Amount">
                    <span className="tabular-nums">{money(invoice.amount, "INR")}</span>
                  </Detail>
                  <Detail label="Against">{against(invoice)}</Detail>
                  <Detail label="Invoice date">
                    <span className="tabular-nums">{dateWithYear(invoice.invoiceDate)}</span>
                  </Detail>
                  <Detail label="Due">
                    <span className="tabular-nums">
                      {invoice.dueDate ? dateWithYear(invoice.dueDate) : "No due date"}
                    </span>
                  </Detail>
                  {invoice.description && <Detail label="Description">{invoice.description}</Detail>}
                  <Detail label="Scan reference">
                    {invoice.scanRef ?? <span className="text-ink-muted">Nothing filed</span>}
                  </Detail>
                </dl>
              </section>

              {invoice.expectedValue != null && (
                <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="variance-heading">
                  <h2 id="variance-heading" className="text-lg">Invoiced against received</h2>
                  <p className="mt-1 max-w-prose text-sm text-ink-secondary">
                    What was received, at this order’s own line prices. A difference is worth a
                    question, not a refusal.
                  </p>
                  <dl className="mt-4 grid grid-cols-3 gap-x-8 gap-y-4 text-sm">
                    <Detail label="Invoiced">
                      <span className="tabular-nums">{money(invoice.amount, "INR")}</span>
                    </Detail>
                    <Detail label="Value received">
                      <span className="tabular-nums">{money(invoice.expectedValue, "INR")}</span>
                    </Detail>
                    <Detail label="Difference">
                      <span className={`tabular-nums ${invoice.variance ? "text-warning" : ""}`}>
                        {invoice.variance == null || invoice.variance === 0
                          ? "None"
                          : `${money(Math.abs(invoice.variance), "INR")} ${invoice.variance > 0 ? "more" : "less"} than expected`}
                      </span>
                    </Detail>
                  </dl>
                </section>
              )}

              {canSeePayments && <PaymentHistory invoiceId={id} amount={invoice.amount} />}
            </>
          )}
        </div>
      </main>
    </div>
  );
}

/**
 * What has been paid against this invoice, and what is still owed.
 *
 * <p>Its own component so its query only ever runs for a reader who holds the permission — hooks
 * cannot be called conditionally, so the condition has to live at the component boundary.
 */
function PaymentHistory({ invoiceId, amount }: { invoiceId: string; amount: number }) {
  const fetchPayments = useCallback(
    (token: string | undefined) => api.listInvoicePayments(invoiceId, token),
    [invoiceId]
  );
  const { data, error, loading } = useAuthedQuery(fetchPayments);
  const payments = data ?? [];
  const paidToDate = payments.reduce((sum, p) => sum + p.amount, 0);
  const outstanding = amount - paidToDate;

  return (
    <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="payments-heading">
      <h2 id="payments-heading" className="text-lg">Payments</h2>
      <p className="mt-1 max-w-prose text-sm text-ink-secondary">
        Payments are made at the bank and recorded here. This app never pays anybody.
      </p>

      {loading ? (
        <Loading label="Loading payments…" />
      ) : error ? (
        <div className="mt-4"><ErrorNotice error={error} /></div>
      ) : (
        <>
          <dl className="mt-4 grid grid-cols-2 gap-x-8 gap-y-4 text-sm">
            <Detail label="Paid to date">
              <span className="tabular-nums">{money(paidToDate, "INR")}</span>
            </Detail>
            <Detail label="Outstanding">
              <span className="tabular-nums">{money(outstanding, "INR")}</span>
            </Detail>
          </dl>

          {payments.length === 0 ? (
            <p className="mt-4 text-sm text-ink-muted">Nothing paid yet.</p>
          ) : (
            <table className={`mt-4 ${TABLE} text-sm`}>
              <thead className={THEAD}>
                <tr>
                  <th className={TH_TEXT}>Paid on</th>
                  <th className={TH_NUM}>Amount</th>
                  <th className={TH_TEXT}>Method</th>
                  <th className={`${TH_TEXT} ${WRAP}`}>Reference</th>
                  <th className={TH_TEXT}>Recorded by</th>
                </tr>
              </thead>
              <tbody>
                {payments.map((p) => (
                  <tr key={p.id} className={TR}>
                    <td className={TD_DATE}>{dateWithYear(p.paidOn)}</td>
                    <td className={TD_NUM}>{money(p.amount, "INR")}</td>
                    <td className={TD_TEXT}>{p.method.replace(/_/g, " ").toLowerCase()}</td>
                    <td className={`${TD_TEXT} text-ink-secondary`}>{p.reference ?? "—"}</td>
                    <td className={`${TD_TEXT} ${WRAP} text-ink-secondary`}>{p.recordedByName ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </section>
  );
}

/** What this invoice was raised against: a purchase order to follow, or a cash-market buy. */
function against(invoice: VendorInvoiceView) {
  if (invoice.direct) {
    return <span className="rounded-sm bg-sunken px-2 py-1 text-xs font-semibold">Direct — no purchase order</span>;
  }
  if (!invoice.purchaseOrderId) {
    return <span className="text-ink-muted">—</span>;
  }
  return (
    <Link href={`/orders/${invoice.purchaseOrderId}`} className="text-accent-text hover:underline tabular-nums">
      {invoice.poNumber ?? "Purchase order"}
    </Link>
  );
}

function Detail({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-ink-secondary">{label}</dt>
      <dd className="mt-1">{children}</dd>
    </div>
  );
}
