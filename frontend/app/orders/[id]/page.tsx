"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type PurchaseOrderLineView } from "@/lib/api";
import { generateAndDownload } from "@/lib/document-download";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { ALL_LANGUAGES } from "@/lib/languages";
import { statusChip } from "../po-status";
import { BusyPot, Loading } from "@/components/Loading";

const REJECT_REASONS = ["DAMAGED", "SPOILED", "WRONG_ITEM", "OTHER"];

export default function PurchaseOrderDetailPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <PurchaseOrderDetailView />
    </RequireRole>
  );
}

function PurchaseOrderDetailView() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const { getToken } = useAuth();

  const fetchPo = useCallback((token: string | undefined) => api.getPurchaseOrder(id, token), [id]);
  const { data, error, loading, reload } = useAuthedQuery(fetchPo);
  const fetchReceipts = useCallback((token: string | undefined) => api.listReceipts(id, token), [id]);
  const { data: receiptsData, reload: reloadReceipts } = useAuthedQuery(fetchReceipts);

  const [busy, setBusy] = useState(false);
  const [preparingPdf, setPreparingPdf] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [showReceive, setShowReceive] = useState(false);
  const [showCancel, setShowCancel] = useState(false);
  // "" means the vendor's own preferred language; otherwise an explicit override for print / PDF.
  const [docLanguage, setDocLanguage] = useState("");

  async function run(mutation: (token: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    setActionError(null);
    try {
      await mutation(await getToken());
      reload();
      reloadReceipts();
      return true;
    } catch (e) {
      setActionError(toApiError(e, failure));
      return false;
    } finally {
      setBusy(false);
    }
  }

  // Generating a sheet ends the same way it does on a recipe: with the file itself. Every version is
  // still kept server-side for the record; this screen just hands over the one that was asked for.
  async function generatePdf() {
    setBusy(true);
    setPreparingPdf(true);
    setActionError(null);
    try {
      const token = await getToken();
      await generateAndDownload({
        request: () => api.requestPurchaseOrderPdf(id, docLanguage || undefined, token),
        status: (documentId) => api.getPurchaseOrderDocument(id, documentId, token),
        download: (documentId) => api.downloadPurchaseOrderDocument(id, documentId, token),
        filename: `${po?.poNumber ?? "purchase-order"}.pdf`,
      });
    } catch (e) {
      setActionError(toApiError(e, "We couldn't generate that PDF."));
    } finally {
      setPreparingPdf(false);
      setBusy(false);
    }
  }

  async function print() {
    setActionError(null);
    try {
      const res = await fetch(api.purchaseOrderPrintUrl(id, docLanguage || undefined), {
        headers: { Authorization: `Bearer ${await getToken()}` },
      });
      if (!res.ok) throw new Error("print failed");
      const html = await res.text();
      const w = window.open("", "_blank");
      if (w) {
        w.document.write(html);
        w.document.close();
      }
    } catch {
      setActionError(toApiError(null, "We couldn't open the print view. Download the PDF instead."));
    }
  }

  const po = data?.order;
  const lines = data?.lines ?? [];
  const receipts = receiptsData ?? [];
  const showPrices = lines.some((l) => l.expectedPrice != null);
  const canSend = po?.status === "DRAFT";
  const canReceive = po?.status === "SENT" || po?.status === "PARTIALLY_RECEIVED";
  const canCancel = po?.status === "DRAFT" || po?.status === "SENT" || po?.status === "PARTIALLY_RECEIVED";
  const canWhatsApp = po?.status === "DRAFT" || po?.status === "SENT" || po?.status === "PARTIALLY_RECEIVED";

  const receivedByLine = new Map<string, number>();
  for (const r of receipts) {
    for (const l of r.lines) {
      receivedByLine.set(l.poLineId, (receivedByLine.get(l.poLineId) ?? 0) + l.receivedQty);
    }
  }

  async function receive(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const f = new FormData(form);
    const receiptLines = lines
      .map((l) => {
        const received = Number(f.get(`received_${l.id}`) ?? 0) || 0;
        const rejected = Number(f.get(`rejected_${l.id}`) ?? 0) || 0;
        const reason = String(f.get(`reason_${l.id}`) ?? "") || null;
        const expiry = String(f.get(`expiry_${l.id}`) ?? "") || null;
        return { poLineId: l.id, receivedQty: received, rejectedQty: rejected, rejectReason: reason as never, expiryDate: expiry };
      })
      .filter((l) => l.receivedQty > 0 || l.rejectedQty > 0);
    if (receiptLines.length === 0) {
      setActionError(toApiError(null, "Enter what arrived on at least one line."));
      return;
    }
    const ok = await run(
      (t) => api.receiveDelivery(id, { idempotencyKey: crypto.randomUUID(), lines: receiptLines }, t),
      "We couldn't record that delivery."
    );
    if (ok) {
      form.reset();
      setShowReceive(false);
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/orders" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <Link href="/orders" className="text-sm text-accent-text hover:underline">← All purchase orders</Link>

          {loading ? (
            <Loading label="Loading purchase order…" />
          ) : error ? (
            <div className="mt-6"><ErrorNotice error={error} /></div>
          ) : !po ? null : (
            <>
              <header className="mb-6 mt-3 flex flex-wrap items-start justify-between gap-4">
                <div>
                  <h1 className="tabular-nums">{po.poNumber}</h1>
                  <p className="mt-1 flex items-center gap-2 text-ink-secondary">
                    {po.vendorName} {statusChip(po.status)}
                  </p>
                  {po.cancelReason && <p className="mt-1 text-sm text-ink-muted">Cancelled: {po.cancelReason}</p>}
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  <select
                    aria-label="Document language"
                    value={docLanguage}
                    onChange={(e) => setDocLanguage(e.target.value)}
                    className="min-h-touch rounded border border-hairline bg-canvas px-3 text-sm"
                  >
                    <option value="">Vendor&rsquo;s language</option>
                    {ALL_LANGUAGES.map((l) => <option key={l.code} value={l.code}>{l.label}</option>)}
                  </select>
                  <button type="button" disabled={busy} onClick={print} className="min-h-touch rounded border border-hairline px-4 transition-colors duration-state hover:bg-sunken disabled:opacity-60">Print</button>
                  <button type="button" disabled={busy} onClick={generatePdf} className="min-h-touch rounded border border-hairline px-4 transition-colors duration-state hover:bg-sunken disabled:opacity-60">{preparingPdf ? (<span className="inline-flex items-center gap-2"><BusyPot />Preparing PDF…</span>) : "Generate PDF"}</button>
                  {canSend && <button type="button" disabled={busy} onClick={() => run((t) => api.sendPurchaseOrder(id, t), "We couldn't send that order.")} className="min-h-touch rounded bg-accent px-4 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">Mark sent</button>}
                  {canWhatsApp && <button type="button" disabled={busy} onClick={() => run((t) => api.sendPurchaseOrderWhatsApp(id, t), "We couldn't send it on WhatsApp.")} className="min-h-touch rounded bg-accent px-4 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">Send on WhatsApp</button>}
                  {canReceive && <button type="button" disabled={busy} onClick={() => setShowReceive((s) => !s)} className="min-h-touch rounded border border-hairline px-4 transition-colors duration-state hover:bg-sunken disabled:opacity-60">Receive delivery</button>}
                  {canCancel && <button type="button" disabled={busy} onClick={() => setShowCancel((s) => !s)} className="min-h-touch rounded border border-hairline px-4 text-danger transition-colors duration-state hover:bg-sunken disabled:opacity-60">Cancel</button>}
                </div>
              </header>

              {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

              {showCancel && (
                <section className="mb-6 rounded-lg bg-raised px-6 py-5">
                  <h2 className="text-lg">Cancel this purchase order</h2>
                  <form className="mt-3 flex flex-wrap items-end gap-3" onSubmit={async (e) => {
                    e.preventDefault();
                    const reason = String(new FormData(e.currentTarget).get("reason") ?? "").trim();
                    const ok = await run((t) => api.cancelPurchaseOrder(id, reason, t), "We couldn't cancel that order.");
                    if (ok) setShowCancel(false);
                  }}>
                    <label className="flex flex-1 flex-col gap-1 text-sm text-ink-secondary">
                      Reason
                      <input name="reason" required className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                    </label>
                    <button type="submit" disabled={busy} className="min-h-touch rounded bg-danger px-5 text-ink-inverse disabled:opacity-60">Cancel order</button>
                  </form>
                </section>
              )}

              {showReceive && canReceive && (
                <section className="mb-6 rounded-lg bg-raised px-6 py-5" aria-labelledby="receive-heading">
                  <h2 id="receive-heading" className="text-lg">Record a delivery</h2>
                  <p className="mt-1 text-sm text-ink-secondary">Enter what actually arrived. Rejected goods need a reason and never enter stock.</p>
                  <form className="mt-4" aria-label="Record a delivery" onSubmit={receive}>
                    <table className="w-full text-left text-sm">
                      <thead className="text-ink-secondary">
                        <tr>
                          <th className="py-2 font-medium">Item</th>
                          <th className="py-2 font-medium text-right">Ordered</th>
                          <th className="py-2 font-medium text-right">Received so far</th>
                          <th className="py-2 font-medium">Received now</th>
                          <th className="py-2 font-medium">Rejected</th>
                          <th className="py-2 font-medium">Reason</th>
                          <th className="py-2 font-medium">Expiry</th>
                        </tr>
                      </thead>
                      <tbody>
                        {lines.map((l) => (
                          <tr key={l.id} className="border-t border-hairline">
                            <td className="py-2">{l.ingredientName}</td>
                            <td className="py-2 text-right tabular-nums">{l.quantity} {l.unit}</td>
                            <td className="py-2 text-right tabular-nums text-ink-secondary">{receivedByLine.get(l.id) ?? 0}</td>
                            <td className="py-2"><input name={`received_${l.id}`} type="number" min="0" step="any" aria-label={`Received ${l.ingredientName}`} className="w-24 rounded border border-hairline bg-canvas px-2 py-1 text-right tabular-nums" /></td>
                            <td className="py-2"><input name={`rejected_${l.id}`} type="number" min="0" step="any" aria-label={`Rejected ${l.ingredientName}`} className="w-20 rounded border border-hairline bg-canvas px-2 py-1 text-right tabular-nums" /></td>
                            <td className="py-2">
                              <select name={`reason_${l.id}`} className="rounded border border-hairline bg-canvas px-2 py-1">
                                <option value="">—</option>
                                {REJECT_REASONS.map((r) => <option key={r} value={r}>{r.replace("_", " ").toLowerCase()}</option>)}
                              </select>
                            </td>
                            <td className="py-2"><input name={`expiry_${l.id}`} type="date" className="rounded border border-hairline bg-canvas px-2 py-1" /></td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    <button type="submit" disabled={busy} className="mt-4 min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">Record delivery</button>
                  </form>
                </section>
              )}

              <section className="mb-8 overflow-hidden rounded-lg bg-raised">
                <table className="w-full text-left">
                  <thead className="bg-sunken text-sm text-ink-secondary">
                    <tr>
                      <th className="px-5 py-3 font-medium">Item</th>
                      <th className="px-5 py-3 font-medium text-right">Quantity</th>
                      {showPrices && <th className="px-5 py-3 font-medium text-right">Price</th>}
                    </tr>
                  </thead>
                  <tbody>
                    {lines.map((l: PurchaseOrderLineView) => (
                      <tr key={l.id} className="border-t border-hairline">
                        <td className="px-5 py-3">{l.ingredientName}</td>
                        <td className="px-5 py-3 text-right tabular-nums">{l.quantity} {l.unit}</td>
                        {showPrices && <td className="px-5 py-3 text-right tabular-nums">{l.expectedPrice == null ? "—" : `₹${l.expectedPrice}`}</td>}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </section>

            </>
          )}
        </div>
      </main>
    </div>
  );
}
