"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type IngredientView, type PurchaseOrderLineView } from "@/lib/api";
import { generateAndDownload } from "@/lib/document-download";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { quantity, unitLabel } from "@/lib/format";
import { ALL_LANGUAGES } from "@/lib/languages";
import { statusChip } from "../po-status";
import { BusyPot, Loading } from "@/components/Loading";

const REJECT_REASONS = ["DAMAGED", "SPOILED", "WRONG_ITEM", "OTHER"];

/**
 * A line as it is being edited. The quantity is held as the text in the box rather than a number so
 * a person can clear the field and retype it; it becomes a number once, on save.
 */
interface DraftLine {
  ingredientId: string;
  ingredientName: string;
  quantity: string;
  unit: string;
  expectedPrice: number | null;
}

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
  // The ingredient catalogue is for the picker that adds a line to a draft. Fetched with the page
  // rather than when the edit form opens: it is a small, cacheable read, and a screen whose set of
  // queries changes as panels open is the harder thing to reason about.
  const fetchIngredients = useCallback((token: string | undefined) => api.listIngredients(token), []);
  const { data: ingredientsData } = useAuthedQuery(fetchIngredients);

  const [busy, setBusy] = useState(false);
  const [preparingPdf, setPreparingPdf] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [showReceive, setShowReceive] = useState(false);
  const [showCancel, setShowCancel] = useState(false);
  // "" means the vendor's own preferred language; otherwise an explicit override for print / PDF.
  const [docLanguage, setDocLanguage] = useState("");
  // Null while nobody is editing. Non-null holds the working copy of the lines, which is only
  // written back to the server when Save is pressed — so abandoning an edit costs nothing.
  const [draftLines, setDraftLines] = useState<DraftLine[] | null>(null);

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
      setActionError(toApiError(e, "We couldn’t generate that PDF."));
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
      setActionError(toApiError(null, "We couldn’t open the print view. Download the PDF instead."));
    }
  }

  const po = data?.order;
  const lines = data?.lines ?? [];
  const receipts = receiptsData ?? [];
  const showPrices = lines.some((l) => l.expectedPrice != null);
  const canSend = po?.status === "DRAFT";
  // Only a draft can be changed, and only in its quantities and its lines — never its vendor. An
  // order addressed to somebody else is a different order, so "change the vendor" would be
  // cancel-and-regenerate wearing a disguise; the server refuses it by not accepting a vendor at all.
  const canEdit = po?.status === "DRAFT";
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
      "We couldn’t record that delivery."
    );
    if (ok) {
      form.reset();
      setShowReceive(false);
    }
  }

  function startEditing() {
    setActionError(null);
    setDraftLines(lines.map((l) => ({
      ingredientId: l.ingredientId,
      ingredientName: l.ingredientName,
      quantity: String(l.quantity),
      unit: l.unit,
      expectedPrice: l.expectedPrice,
    })));
  }

  async function saveLines(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!po || !draftLines) return;

    const quantities = draftLines.map((l) => Number(l.quantity));
    if (quantities.some((q) => !Number.isFinite(q) || q <= 0)) {
      setActionError(toApiError(null, "Every line needs a quantity above zero. Remove a line you no longer want."));
      return;
    }

    // The endpoint replaces a draft wholesale, so the header fields travel back with the lines —
    // otherwise correcting a quantity would quietly erase the delivery address somebody typed last
    // week. If the order was sent from another screen in the meantime the server refuses with
    // KMS-4919, and that refusal is shown as it arrives rather than swallowed.
    const ok = await run(
      (t) => api.updatePurchaseOrder(id, {
        neededBy: po.neededBy,
        deliveryLocation: po.deliveryLocation,
        notes: po.notes,
        lines: draftLines.map((l, i) => ({
          ingredientId: l.ingredientId,
          quantity: quantities[i],
          unit: l.unit,
          expectedPrice: l.expectedPrice,
        })),
      }, t),
      "We couldn’t save those changes."
    );
    if (ok) setDraftLines(null);
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
                    <option value="">Vendor’s language</option>
                    {ALL_LANGUAGES.map((l) => <option key={l.code} value={l.code}>{l.label}</option>)}
                  </select>
                  <button type="button" disabled={busy} onClick={print} className="min-h-touch rounded border border-hairline px-4 transition-colors duration-state hover:bg-sunken disabled:opacity-60">Print</button>
                  <button type="button" disabled={busy} onClick={generatePdf} className="min-h-touch rounded border border-hairline px-4 transition-colors duration-state hover:bg-sunken disabled:opacity-60">{preparingPdf ? (<span className="inline-flex items-center gap-2"><BusyPot />Preparing PDF…</span>) : "Generate PDF"}</button>
                  {canEdit && <button type="button" disabled={busy} onClick={() => (draftLines ? setDraftLines(null) : startEditing())} className="min-h-touch rounded border border-hairline px-4 transition-colors duration-state hover:bg-sunken disabled:opacity-60">{draftLines ? "Stop editing" : "Edit lines"}</button>}
                  {canSend && <button type="button" disabled={busy} onClick={() => run((t) => api.sendPurchaseOrder(id, t), "We couldn’t send that order.")} className="min-h-touch rounded bg-accent px-4 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">Mark sent</button>}
                  {canWhatsApp && <button type="button" disabled={busy} onClick={() => run((t) => api.sendPurchaseOrderWhatsApp(id, t), "We couldn’t send it on WhatsApp.")} className="min-h-touch rounded bg-accent px-4 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">Send on WhatsApp</button>}
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
                    const ok = await run((t) => api.cancelPurchaseOrder(id, reason, t), "We couldn’t cancel that order.");
                    if (ok) setShowCancel(false);
                  }}>
                    <label className="flex flex-1 flex-col gap-1 text-sm text-ink-secondary">
                      <span className="pl-field-inset font-medium text-ink">Reason</span>
                      <input name="reason" required className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                    </label>
                    <button type="submit" disabled={busy} className="min-h-touch rounded bg-danger px-5 text-ink-inverse disabled:opacity-60">Cancel order</button>
                  </form>
                </section>
              )}

              {draftLines && canEdit && (
                <section className="mb-6 rounded-lg bg-raised px-6 py-5" aria-labelledby="edit-heading">
                  <h2 id="edit-heading" className="text-lg">Edit this draft</h2>
                  <p className="mt-1 max-w-prose text-sm text-ink-secondary">
                    The vendor cannot be changed. Cancel this order and raise it against the right
                    one. Once it is sent, nothing here can be changed at all.
                  </p>
                  <form className="mt-4" aria-label="Edit the draft order" onSubmit={saveLines}>
                    <table className="w-full text-left text-sm">
                      <thead className="text-ink-secondary">
                        <tr>
                          <th className="py-2 font-medium">Item</th>
                          <th className="py-2 font-medium">Quantity</th>
                          <th className="py-2 font-medium text-right">Remove</th>
                        </tr>
                      </thead>
                      <tbody>
                        {draftLines.map((l, i) => (
                          <tr key={l.ingredientId} className="border-t border-hairline hover:bg-sunken">
                            <td className="py-2">{l.ingredientName}</td>
                            <td className="py-2">
                              <input
                                type="number"
                                min="0"
                                step="any"
                                value={l.quantity}
                                aria-label={`Quantity of ${l.ingredientName}`}
                                onChange={(e) => setDraftLines((cur) => cur && cur.map((x, j) => (j === i ? { ...x, quantity: e.target.value } : x)))}
                                className="w-28 rounded border border-hairline bg-canvas px-2 py-1 text-right tabular-nums"
                              />{" "}
                              {/* The bare label, never a promoted one: the box beside it holds and
                                  submits the line's own stored unit, so a readout that said "gm"
                                  over a figure in kilograms would invite a thousandfold error. */}
                              <span className="text-ink-secondary">{unitLabel(l.unit)}</span>
                            </td>
                            <td className="py-2 text-right">
                              {/* An order with nothing on it is not an empty order, it is a cancelled
                                  one — so the last line stays and Cancel is the way out. */}
                              <button
                                type="button"
                                disabled={busy || draftLines.length === 1}
                                onClick={() => setDraftLines((cur) => cur && cur.filter((_, j) => j !== i))}
                                className="text-sm text-ink-secondary hover:underline disabled:opacity-40"
                              >
                                Remove
                              </button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>

                    <AddLine
                      busy={busy}
                      ingredients={ingredientsData ?? []}
                      alreadyOnOrder={draftLines.map((l) => l.ingredientId)}
                      onAdd={(line) => setDraftLines((cur) => (cur ? [...cur, line] : cur))}
                    />

                    <div className="mt-5 flex items-center gap-3">
                      <button type="submit" disabled={busy} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">Save changes</button>
                      <button type="button" disabled={busy} onClick={() => setDraftLines(null)} className="text-sm text-ink-secondary hover:underline disabled:opacity-60">Discard</button>
                    </div>
                  </form>
                </section>
              )}

              {showReceive && canReceive && (
                <section className="mb-6 rounded-lg bg-raised px-6 py-5" aria-labelledby="receive-heading">
                  <h2 id="receive-heading" className="text-lg">Record a delivery</h2>
                  <p className="mt-1 text-sm text-ink-secondary">Rejected goods need a reason and never enter stock.</p>
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
                          <tr key={l.id} className="border-t border-hairline hover:bg-sunken">
                            <td className="py-2">{l.ingredientName}</td>
                            {/* Ledger form on both, and for one reason: this row exists so a
                                store-keeper can see what is still owed. Round the ordered figure
                                and not the receipts against it and a fully delivered line reads as
                                over-delivered. "Received so far" was printing a bare number with no
                                unit at all, which is the same defect one step further on. */}
                            <td className="py-2 text-right tabular-nums">{quantity(l.quantity, l.unit)}</td>
                            <td className="py-2 text-right tabular-nums text-ink-secondary">{quantity(receivedByLine.get(l.id) ?? 0, l.unit)}</td>
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
                      <tr key={l.id} className="border-t border-hairline hover:bg-sunken">
                        <td className="px-5 py-3">{l.ingredientName}</td>
                        {/* The order as issued, beside what it is expected to cost — the figure
                            the delivery above and the vendor's invoice are both checked against, so
                            it is exact and agrees line for line with the receiving table. */}
                        <td className="px-5 py-3 text-right tabular-nums">{quantity(l.quantity, l.unit)}</td>
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

/**
 * Adding an ingredient to a draft.
 *
 * <p>A picker rather than a box to paste an identifier into: nobody knows an ingredient by its id,
 * and the vendor page and the invoice form both choose one this way already.
 */
function AddLine({
  busy, ingredients, alreadyOnOrder, onAdd,
}: {
  busy: boolean;
  ingredients: IngredientView[];
  alreadyOnOrder: string[];
  onAdd: (line: DraftLine) => void;
}) {
  const [chosen, setChosen] = useState("");

  // An ingredient already on the order is edited on its own row; offering it twice would produce
  // two lines for one thing and leave the vendor to work out which is meant.
  const available = ingredients.filter((i) => !alreadyOnOrder.includes(i.id));

  function add() {
    const ingredient = available.find((i) => i.id === chosen);
    if (!ingredient) return;
    // No expected price: that figure is a snapshot of the vendor's last-known price taken when the
    // order was raised, and there is nothing honest to put here for a line added by hand. The sheet
    // prints a dash, which is truthful, where an invented number would not be.
    onAdd({
      ingredientId: ingredient.id,
      ingredientName: ingredient.name,
      quantity: "",
      unit: ingredient.unit,
      expectedPrice: null,
    });
    setChosen("");
  }

  return (
    <div className="mt-4 flex flex-wrap items-end gap-3 border-t border-hairline pt-4">
      <label className="flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Add an ingredient</span>
        <select
          value={chosen}
          onChange={(e) => setChosen(e.target.value)}
          className="min-h-touch rounded border border-hairline bg-canvas px-3"
        >
          <option value="">Choose…</option>
          {available.map((i) => <option key={i.id} value={i.id}>{i.name}</option>)}
        </select>
      </label>
      <button
        type="button"
        disabled={busy || chosen === ""}
        onClick={add}
        className="min-h-touch rounded border border-hairline px-4 transition-colors duration-state hover:bg-sunken disabled:opacity-60"
      >
        Add line
      </button>
    </div>
  );
}
