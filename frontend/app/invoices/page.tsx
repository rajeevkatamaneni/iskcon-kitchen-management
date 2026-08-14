"use client";

import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type InvoiceStatus } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

export default function InvoicesPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_STAFF"]}>
      <InvoicesView />
    </RequireRole>
  );
}

function InvoicesView() {
  const { getToken } = useAuth();
  const [status, setStatus] = useState<InvoiceStatus | "">("");
  const [overdueOnly, setOverdueOnly] = useState(false);

  const fetchInvoices = useCallback(
    (token: string | undefined) =>
      api.listInvoices({ status: status || undefined, overdue: overdueOnly }, token),
    [status, overdueOnly]
  );
  const { data, error, loading, reload } = useAuthedQuery(fetchInvoices);
  const { data: vendorsData } = useAuthedQuery((t) => api.listVendors(true, t));
  const invoices = data ?? [];
  const vendors = vendorsData ?? [];

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [showAdd, setShowAdd] = useState(false);
  const [isDirect, setIsDirect] = useState(false);

  async function record(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const f = new FormData(form);
    setBusy(true);
    setActionError(null);
    setNotice(null);
    try {
      const res = await api.recordInvoice(
        {
          vendorId: String(f.get("vendorId") ?? ""),
          purchaseOrderId: isDirect ? null : emptyToNull(String(f.get("purchaseOrderId") ?? "")),
          description: isDirect ? emptyToNull(String(f.get("description") ?? "")) : null,
          invoiceNumber: String(f.get("invoiceNumber") ?? "").trim(),
          invoiceDate: String(f.get("invoiceDate") ?? ""),
          amount: Number(f.get("amount") ?? 0),
          dueDate: emptyToNull(String(f.get("dueDate") ?? "")),
          scanRef: emptyToNull(String(f.get("scanRef") ?? "")),
        },
        await getToken()
      );
      form.reset();
      setShowAdd(false);
      setNotice(res.duplicateWarning
        ? "Recorded — note another invoice already uses this number for this vendor."
        : "Invoice recorded.");
      reload();
    } catch (e) {
      setActionError(toApiError(e, "We couldn't record that invoice."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/invoices" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Invoices</h1>
              <p className="mt-1 text-ink-secondary">
                What the temple owes — captured against a purchase order, or direct for a cash-market buy.
              </p>
            </div>
            <button type="button" onClick={() => setShowAdd((s) => !s)} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover">
              Record an invoice
            </button>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}
          {notice && <div className="mb-6 rounded border border-hairline bg-success-bg px-4 py-3 text-sm text-success">{notice}</div>}

          {showAdd && (
            <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="add-heading">
              <h2 id="add-heading" className="text-lg">New invoice</h2>
              <label className="mt-3 flex items-center gap-2 text-sm text-ink-secondary">
                <input type="checkbox" checked={isDirect} onChange={(e) => setIsDirect(e.target.checked)} />
                Direct (no purchase order)
              </label>
              <form className="mt-4 grid grid-cols-2 gap-4" aria-label="Record an invoice" onSubmit={record}>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Vendor
                  <select name="vendorId" required className="min-h-touch rounded border border-hairline bg-canvas px-3">
                    <option value="">Choose a vendor…</option>
                    {vendors.map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}
                  </select>
                </label>
                {isDirect ? (
                  <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                    Description
                    <input name="description" required placeholder="Cash market vegetables…" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                  </label>
                ) : (
                  <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                    Purchase order id
                    <input name="purchaseOrderId" required placeholder="PO uuid" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                  </label>
                )}
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Invoice number
                  <input name="invoiceNumber" required className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Amount (₹)
                  <input name="amount" type="number" min="0" step="any" required className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Invoice date
                  <input name="invoiceDate" type="date" required className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Due date
                  <input name="dueDate" type="date" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
                  Scan reference (optional)
                  <input name="scanRef" placeholder="Uploaded scan id / link" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <div className="col-span-2">
                  <button type="submit" disabled={busy} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">
                    Record invoice
                  </button>
                </div>
              </form>
            </section>
          )}

          <div className="mb-4 flex flex-wrap items-center gap-4">
            <label className="text-sm text-ink-secondary">
              Status{" "}
              <select value={status} onChange={(e) => setStatus(e.target.value as InvoiceStatus | "")} className="ml-1 min-h-touch rounded border border-hairline bg-canvas px-3">
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
            <p className="text-ink-secondary">Loading invoices…</p>
          ) : error ? (
            <ErrorNotice error={error} />
          ) : invoices.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">No invoices</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                Record a vendor&rsquo;s invoice above so Payments has a clean queue of what the temple owes.
              </p>
            </div>
          ) : (
            <div className="overflow-hidden rounded-lg bg-raised">
              <table className="w-full text-left">
                <thead className="bg-sunken text-sm text-ink-secondary">
                  <tr>
                    <th className="px-5 py-3 font-medium">Invoice</th>
                    <th className="px-5 py-3 font-medium">Vendor</th>
                    <th className="px-5 py-3 font-medium">Against</th>
                    <th className="px-5 py-3 font-medium text-right">Amount</th>
                    <th className="px-5 py-3 font-medium">Due</th>
                    <th className="px-5 py-3 font-medium">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {invoices.map((inv) => (
                    <tr key={inv.id} className="border-t border-hairline align-middle">
                      <td className="px-5 py-3 font-medium">{inv.invoiceNumber}</td>
                      <td className="px-5 py-3 text-ink-secondary">{inv.vendorName}</td>
                      <td className="px-5 py-3 text-ink-secondary">
                        {inv.direct ? (
                          <span className="rounded-sm bg-sunken px-2 py-1 text-xs">Direct</span>
                        ) : (
                          <span className="tabular-nums">{inv.poNumber ?? "—"}</span>
                        )}
                        {inv.variance != null && inv.variance !== 0 && (
                          <span className="ml-2 rounded-sm bg-warning-bg px-2 py-0.5 text-xs text-warning">
                            variance ₹{inv.variance}
                          </span>
                        )}
                      </td>
                      <td className="px-5 py-3 text-right tabular-nums">₹{inv.amount}</td>
                      <td className="px-5 py-3 tabular-nums text-ink-secondary">
                        {inv.dueDate ?? "—"}
                        {inv.overdue && <span className="ml-2 rounded-sm bg-danger-bg px-2 py-0.5 text-xs text-danger">Overdue</span>}
                      </td>
                      <td className="px-5 py-3">
                        {inv.status === "PAID" ? (
                          <span className="rounded-sm bg-success-bg px-2 py-1 text-xs text-success">Paid</span>
                        ) : (
                          <span className="rounded-sm bg-accent-bg px-2 py-1 text-xs text-accent-text">Pending</span>
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

function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
}
