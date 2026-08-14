"use client";

import { Fragment, useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type PayableView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

const BUCKET_LABEL: Record<string, string> = {
  CURRENT: "Current",
  DUE_1_30: "1–30 days overdue",
  OVERDUE_31_PLUS: "31+ days overdue",
};

export default function PayablesPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <PayablesView />
    </RequireRole>
  );
}

function PayablesView() {
  const { getToken } = useAuth();
  const { data, error, loading, reload } = useAuthedQuery(useCallback((t: string | undefined) => api.payables(t), []));
  const payables = data ?? [];

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [paying, setPaying] = useState<string | null>(null);

  async function pay(event: React.FormEvent<HTMLFormElement>, p: PayableView) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    setBusy(true);
    setActionError(null);
    try {
      await api.recordInvoicePayment(p.invoiceId, {
        paidOn: String(f.get("paidOn") ?? ""),
        amount: Number(f.get("amount") ?? 0),
        method: String(f.get("method") ?? "BANK_TRANSFER"),
        reference: String(f.get("reference") ?? "") || undefined,
      }, await getToken());
      setPaying(null);
      reload();
    } catch (e) {
      setActionError(toApiError(e, "We couldn't record that payment."));
    } finally {
      setBusy(false);
    }
  }

  const total = payables.reduce((sum, p) => sum + p.outstanding, 0);
  const today = new Date().toISOString().slice(0, 10);

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/money" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6">
            <h1>Payments</h1>
            <p className="mt-1 text-ink-secondary">
              Outstanding vendor invoices. Record payments made outside the app to track payables to zero.
            </p>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

          {loading ? (
            <p className="text-ink-secondary">Loading payables…</p>
          ) : error ? (
            <ErrorNotice error={error} />
          ) : payables.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">Nothing outstanding</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">All vendor invoices are paid. 🙏</p>
            </div>
          ) : (
            <>
              <p className="mb-4 text-sm text-ink-secondary">
                Total outstanding: <span className="font-medium tabular-nums text-ink">₹{total}</span>
              </p>
              <div className="overflow-hidden rounded-lg bg-raised">
                <table className="w-full text-left">
                  <thead className="bg-sunken text-sm text-ink-secondary">
                    <tr>
                      <th className="px-5 py-3 font-medium">Invoice</th>
                      <th className="px-5 py-3 font-medium">Vendor</th>
                      <th className="px-5 py-3 font-medium text-right">Outstanding</th>
                      <th className="px-5 py-3 font-medium">Aging</th>
                      <th className="px-5 py-3 font-medium text-right">Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {payables.map((p) => (
                      <Fragment key={p.invoiceId}>
                        <tr className="border-t border-hairline align-middle">
                          <td className="px-5 py-3 font-medium">{p.invoiceNumber}</td>
                          <td className="px-5 py-3 text-ink-secondary">{p.vendorName}</td>
                          <td className="px-5 py-3 text-right tabular-nums">₹{p.outstanding}</td>
                          <td className="px-5 py-3">
                            <span className={`rounded-sm px-2 py-1 text-xs ${p.agingBucket === "CURRENT" ? "bg-sunken text-ink-secondary" : "bg-warning-bg text-warning"}`}>
                              {BUCKET_LABEL[p.agingBucket] ?? p.agingBucket}
                            </span>
                          </td>
                          <td className="px-5 py-3 text-right">
                            <button type="button" onClick={() => setPaying(paying === p.invoiceId ? null : p.invoiceId)} className="text-sm text-accent-text hover:underline">
                              Record payment
                            </button>
                          </td>
                        </tr>
                        {paying === p.invoiceId && (
                          <tr className="border-t border-hairline bg-sunken/40">
                            <td colSpan={5} className="px-5 py-4">
                              <form className="flex flex-wrap items-end gap-3" aria-label={`Record payment for ${p.invoiceNumber}`} onSubmit={(e) => pay(e, p)}>
                                <label className="flex flex-col gap-1 text-sm text-ink-secondary">Date
                                  <input name="paidOn" type="date" defaultValue={today} required className="min-h-touch rounded border border-hairline bg-canvas px-2" />
                                </label>
                                <label className="flex flex-col gap-1 text-sm text-ink-secondary">Amount (₹)
                                  <input name="amount" type="number" min="0" step="any" defaultValue={p.outstanding} required className="min-h-touch w-32 rounded border border-hairline bg-canvas px-2 text-right tabular-nums" />
                                </label>
                                <label className="flex flex-col gap-1 text-sm text-ink-secondary">Method
                                  <select name="method" className="min-h-touch rounded border border-hairline bg-canvas px-2">
                                    <option value="BANK_TRANSFER">Bank transfer</option>
                                    <option value="UPI">UPI</option>
                                    <option value="CHEQUE">Cheque</option>
                                    <option value="CASH">Cash</option>
                                  </select>
                                </label>
                                <label className="flex flex-col gap-1 text-sm text-ink-secondary">Reference
                                  <input name="reference" className="min-h-touch rounded border border-hairline bg-canvas px-2" />
                                </label>
                                <button type="submit" disabled={busy} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">Save</button>
                              </form>
                            </td>
                          </tr>
                        )}
                      </Fragment>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </div>
      </main>
    </div>
  );
}
