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
import { dateWithYear, leadTimeWarning, money, quantity, unitLabel } from "@/lib/format";
import { ALL_LANGUAGES } from "@/lib/languages";
import { statusChip } from "../po-status";
import { BusyPot, Loading } from "@/components/Loading";
import { TABLE, THEAD, TR, TH_TEXT, TH_NUM, TH_ACTIONS, TD_TEXT, TD_NUM, TD_DATE, TD_ACTIONS, WRAP } from "@/components/ds/table";
import { Button } from "@/components/ds/Button";

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
  // The working copy of the needed-by date, as the text in the box: "" is a date deliberately
  // cleared, which is a legitimate order with nothing to meet, not a missing answer.
  const [draftNeededBy, setDraftNeededBy] = useState("");

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
  // Advisory only, and recomputed as the date is typed. A date inside the vendor's usual notice is
  // a thing worth saying out loud and not a thing worth refusing — see leadTimeWarning.
  const neededByWarning = draftNeededBy === "" ? null : leadTimeWarning(draftNeededBy);

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
        // Blank stays blank. Number("") is 0, and a 0 here would be written back as the vendor's
        // price — "the bill hasn't come yet" turned into "this costs nothing" by a coercion.
        const priceText = String(f.get(`price_${l.id}`) ?? "").trim();
        const unitPrice = priceText === "" ? null : Number(priceText);
        return { poLineId: l.id, receivedQty: received, rejectedQty: rejected, rejectReason: reason as never, expiryDate: expiry, unitPrice };
      })
      .filter((l) => l.receivedQty > 0 || l.rejectedQty > 0);
    if (receiptLines.length === 0) {
      setActionError(toApiError(null, "Enter what arrived on at least one line."));
      return;
    }
    if (receiptLines.some((l) => l.unitPrice != null && (!Number.isFinite(l.unitPrice) || l.unitPrice < 0))) {
      setActionError(toApiError(null, "A price is an amount in rupees. Leave it blank if the bill hasn’t arrived."));
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
    setDraftNeededBy(po?.neededBy ?? "");
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

    // The one thing about this date that is refused rather than warned about, mirrored from the
    // server's KMS-4014 so the refusal arrives before the round trip rather than after it. The
    // server is still the guard; this only saves a wasted submit.
    if (draftNeededBy !== "" && draftNeededBy < po.orderDate) {
      setActionError(toApiError(null, "That date is before the order was raised. Choose a day on or after it."));
      return;
    }

    // The endpoint replaces a draft wholesale, so the header fields travel back with the lines —
    // otherwise correcting a quantity would quietly erase the delivery address somebody typed last
    // week. If the order was sent from another screen in the meantime the server refuses with
    // KMS-4919, and that refusal is shown as it arrives rather than swallowed.
    const ok = await run(
      (t) => api.updatePurchaseOrder(id, {
        neededBy: draftNeededBy === "" ? null : draftNeededBy,
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
                  {/* The date the temple asked for, on every order and in every state. On a draft
                      it is editable below; once the order has gone to the vendor it is a readout
                      and nothing else — that date is what they were asked for, and what the vendor
                      scorecard measures their delivery against. */}
                  <p className="mt-1 text-sm tabular-nums text-ink-secondary">
                    {po.neededBy ? `Needed by ${dateWithYear(po.neededBy)}` : "No needed-by date"}
                  </p>
                  {po.sentAt && <p className="text-sm text-ink-muted">Fixed when the order was sent</p>}
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

              {actionError && (
                <div className="mb-6 grid gap-3">
                  <ErrorNotice error={actionError} />
                  {/* A few refusals name the lines they are about — a unit the ingredient cannot be
                      measured in (KMS-4013) is one. An order can run to twenty lines, and being told
                      that one of them is wrong without being told which is not much of a refusal. */}
                  {actionError.fieldErrors.length > 0 && (
                    <ul className="grid gap-1 rounded border border-hairline bg-raised px-5 py-4 text-sm">
                      {actionError.fieldErrors.map((f) => (
                        <li key={f.field}>
                          <span className="font-medium">{f.field}</span>: {f.message}
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              )}

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
                    <label className="mb-5 flex max-w-xs flex-col gap-1 text-sm text-ink-secondary">
                      <span className="pl-field-inset font-medium text-ink">Needed by</span>
                      {/* min is the order's own date, so the picker itself will not offer a day
                          behind the order. The server refuses it regardless (KMS-4014): a browser
                          attribute is a courtesy, not a guard. */}
                      <input
                        type="date"
                        aria-label="Needed by"
                        value={draftNeededBy}
                        min={po.orderDate}
                        onChange={(e) => setDraftNeededBy(e.target.value)}
                        className="min-h-touch rounded border border-hairline bg-canvas px-3"
                      />
                      <span className={`pl-field-inset text-sm ${neededByWarning ? "text-warning" : "text-ink-secondary"}`}>
                        {neededByWarning ?? "Leave it blank if there is no date to meet"}
                      </span>
                    </label>
                    <table className={`${TABLE} text-sm`}>
                      <thead className={THEAD}>
                        <tr>
                          <th className={`${TH_TEXT} ${WRAP}`}>Item</th>
                          <th className={TH_NUM}>Quantity</th>
                          <th className={TH_ACTIONS}>Remove</th>
                        </tr>
                      </thead>
                      <tbody>
                        {draftLines.map((l, i) => (
                          <tr key={l.ingredientId} className={TR}>
                            <td className={`${TD_TEXT} ${WRAP}`}>{l.ingredientName}</td>
                            <td className={TD_NUM}>
                              <input
                                type="number"
                                min="0"
                                step="any"
                                value={l.quantity}
                                aria-label={`Quantity of ${l.ingredientName}`}
                                onChange={(e) => setDraftLines((cur) => cur && cur.map((x, j) => (j === i ? { ...x, quantity: e.target.value } : x)))}
                                className="w-28 rounded border border-hairline bg-canvas px-2 py-1 tabular-nums"
                              />{" "}
                              {/* The bare label, never a promoted one: the box beside it holds and
                                  submits the line's own stored unit, so a readout that said "gm"
                                  over a figure in kilograms would invite a thousandfold error. */}
                              <span className="text-ink-secondary">{unitLabel(l.unit)}</span>
                            </td>
                            <td className={TD_ACTIONS}>
                              {/* An order with nothing on it is not an empty order, it is a cancelled
                                  one — so the last line stays and Cancel is the way out. */}
                              <Button
                                variant="danger"
                                size="sm"
                                disabled={busy || draftLines.length === 1}
                                onClick={() => setDraftLines((cur) => cur && cur.filter((_, j) => j !== i))}
                              >
                                Remove
                              </Button>
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
                  <p className="mt-1 text-sm text-ink-secondary">Rejected goods need a reason and never enter stock. The price is what the bill says — correct it if it differs, or leave it blank for a delivery that came without one.</p>
                  <form className="mt-4" aria-label="Record a delivery" onSubmit={receive}>
                    <div className="overflow-x-auto">
                    <table className={`${TABLE} text-sm`}>
                      <thead className={THEAD}>
                        <tr>
                          {/* A floor, not a width. Seven of these eight columns hold a fixed-width
                              control or a two-word heading and sit at their minimum whatever the
                              card is, so a full-width table has nothing to share out and the one
                              column that may wrap is handed whatever is left — measured at 142px,
                              which broke a 69-character ingredient name over five lines and made a
                              125px-tall row nobody can read. `min-w` gives it a floor of 13rem
                              (three lines, and the knee of the curve: 16rem buys one more line for
                              twice the scroll) and lets the table run past the card, which now
                              scrolls rather than clipping. Deliberately a `min-w` and never a
                              `max-w` — see the note on WRAP in ds/table.ts. */}
                          <th className={`${TH_TEXT} ${WRAP} min-w-[13rem]`}>Item</th>
                          <th className={TH_NUM}>Ordered</th>
                          <th className={TH_NUM}>Received so far</th>
                          <th className={TH_NUM}>Received now</th>
                          <th className={TH_NUM}>Rejected</th>
                          <th className={TH_TEXT}>Reason</th>
                          <th className={TH_TEXT}>Expiry</th>
                          <th className={TH_NUM}>Price paid</th>
                        </tr>
                      </thead>
                      <tbody>
                        {lines.map((l) => (
                          <tr key={l.id} className={TR}>
                            <td className={`${TD_TEXT} ${WRAP} min-w-[13rem]`}>{l.ingredientName}</td>
                            {/* Ledger form on both, and for one reason: this row exists so a
                                store-keeper can see what is still owed. Round the ordered figure
                                and not the receipts against it and a fully delivered line reads as
                                over-delivered. "Received so far" was printing a bare number with no
                                unit at all, which is the same defect one step further on. */}
                            <td className={TD_NUM}>{quantity(l.quantity, l.unit)}</td>
                            <td className={`${TD_NUM} text-ink-secondary`}>{quantity(receivedByLine.get(l.id) ?? 0, l.unit)}</td>
                            <td className={TD_NUM}><input name={`received_${l.id}`} type="number" min="0" step="any" aria-label={`Received ${l.ingredientName}`} className="w-24 rounded border border-hairline bg-canvas px-2 py-1 tabular-nums" /></td>
                            <td className={TD_NUM}><input name={`rejected_${l.id}`} type="number" min="0" step="any" aria-label={`Rejected ${l.ingredientName}`} className="w-20 rounded border border-hairline bg-canvas px-2 py-1 tabular-nums" /></td>
                            <td className={TD_TEXT}>
                              <select name={`reason_${l.id}`} className="rounded border border-hairline bg-canvas px-2 py-1">
                                <option value="">—</option>
                                {REJECT_REASONS.map((r) => <option key={r} value={r}>{r.replace("_", " ").toLowerCase()}</option>)}
                              </select>
                            </td>
                            <td className={TD_DATE}><input name={`expiry_${l.id}`} type="date" className="rounded border border-hairline bg-canvas px-2 py-1" /></td>
                            {/* Pre-filled from the order and editable, because the bill that arrived
                                with the lorry is the truth and the order was only ever a guess. The
                                expected figure stays visible underneath rather than being replaced,
                                so a storekeeper can see that ₹80 is not the ₹45 that was budgeted —
                                as information, not as a gate. Whatever is typed here becomes the
                                vendor's last-known price for this ingredient. */}
                            <td className={`${TD_NUM} align-top`}>
                              <input
                                name={`price_${l.id}`}
                                type="number"
                                min="0"
                                step="0.01"
                                defaultValue={l.expectedPrice ?? ""}
                                aria-label={`Price paid per ${unitLabel(l.unit)} of ${l.ingredientName}, optional`}
                                className="w-24 rounded border border-hairline bg-canvas px-2 py-1 tabular-nums"
                              />
                              <span className="mt-1 block pl-field-inset text-xs text-ink-muted">
                                {l.expectedPrice == null
                                  ? `optional, per ${unitLabel(l.unit)}`
                                  : `expected ${money(l.expectedPrice, "INR")} / ${unitLabel(l.unit)}`}
                              </span>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    </div>
                    <button type="submit" disabled={busy} className="mt-4 min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">Record delivery</button>
                  </form>
                </section>
              )}

              <section className="mb-8 overflow-x-auto rounded-lg bg-raised">
                <table className={TABLE}>
                  <thead className={THEAD}>
                    <tr>
                      <th className={`${TH_TEXT} ${WRAP}`}>Item</th>
                      <th className={TH_NUM}>Quantity</th>
                      {showPrices && <th className={TH_NUM}>Price</th>}
                    </tr>
                  </thead>
                  <tbody>
                    {lines.map((l: PurchaseOrderLineView) => (
                      <tr key={l.id} className={TR}>
                        <td className={`${TD_TEXT} ${WRAP}`}>{l.ingredientName}</td>
                        {/* The order as issued, beside what it is expected to cost — the figure
                            the delivery above and the vendor's invoice are both checked against, so
                            it is exact and agrees line for line with the receiving table. */}
                        <td className={TD_NUM}>{quantity(l.quantity, l.unit)}</td>
                        {showPrices && <td className={TD_NUM}>{money(l.expectedPrice, "INR")}</td>}
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
