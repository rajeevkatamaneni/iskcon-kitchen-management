"use client";

import Link from "next/link";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { Badge } from "@/components/ds/Badge";
import { Button } from "@/components/ds/Button";
import { Form } from "@/components/ds/Form";
import { PageHeader } from "@/components/ds/PageHeader";
import { RequireRole } from "@/components/RequireRole";
import { AttachmentThumb } from "@/components/AttachmentThumb";
import { InvoiceTotals } from "@/components/InvoiceTotals";
import { PayInvoiceForm, PAYMENT_METHODS } from "@/components/PayInvoiceForm";
import {
  api,
  toApiError,
  type ApiError,
  type AttachmentView,
  type InvoiceLineView,
  type InvoicePaymentView,
  type VendorInvoiceDetailView,
  type VendorInvoiceView,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";
import { dateWithYear, money, moment, quantity, readablePackRate, readableRate, repeatsPack, shortDate } from "@/lib/format";
import {
  RULED_TABLE,
  THEAD,
  TR,
  TH_PRIMARY,
  TD_PRIMARY,
  TH_SECOND,
  TD_SECOND,
  TH_FIXED,
  TD_FIXED,
  TD_FIXED_NUM,
  TH_ACTIONS_FIXED,
  TD_ACTIONS_FIXED,
  withLongTokenBreaks,
} from "@/components/ds/table";

/**
 * One vendor invoice in full (A8; since stage 6 of the procurement build, R-INV-7 and R-PAY-1..3, the
 * `dev-invoices` mock's design D).
 *
 * <p>In order down the page: the summary with the copy of the bill, the items the vendor billed with
 * the bill's totals under them, "Invoiced vs received", and the payments, where the invoice is paid
 * from. The list can only carry six columns, so everything captured when the invoice was recorded
 * lives here.
 *
 * <p><b>"Invoiced vs received"</b> is the reason this page first existed. On the list a difference
 * reads "₹50" with nothing to be a difference *from*. Here it is the subtraction it is: the items
 * billed, against what was delivered at the order's own prices. Since stage 6 the server works both
 * from the invoice's lines (T-271) and sends null where it has no basis (a direct buy, an order line
 * with no price). A null is shown as "—", never as ₹0: ₹0 would say the bill matched when nobody can
 * know.
 *
 * <p><b>Invoices recorded before stage 6</b> have no lines, no totals and no uploaded bill, only a total
 * and perhaps a typed scan reference. They still read sensibly: the Grand total is their amount, the
 * Items section is left out rather than shown empty, the scan reference is shown where the bill would
 * be, and "Invoiced vs received" compares their total as it always did (the server keeps that reading
 * for them).
 *
 * <p><b>Who sees what.</b> The page is gated to the same roles as the list. Paying, reading payments,
 * voiding and crediting all sit behind MANAGE_VENDOR_PAYMENTS, which of the roles that can open this
 * page only a Temple Admin holds. For anyone else the Payments section, the Pay this invoice button and
 * both corrections are absent rather than empty: an empty payments table would tell a Kitchen Manager
 * the bill is unpaid when in truth they simply cannot see. R-PAY-1: who can pay must not widen, so the
 * button uses exactly the check the payment list always used.
 */
export default function InvoiceDetailPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      {/* useSearchParams, for the confirmation "Create an invoice" comes back with. */}
      <Suspense>
        <InvoiceDetailView />
      </Suspense>
    </RequireRole>
  );
}

const rs = (n: number) => money(n, "INR");
const round2 = (n: number) => Math.round(n * 100) / 100;

function InvoiceDetailView() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const { appUser } = useAuth();
  const router = useRouter();
  const search = useSearchParams();

  // MANAGE_VENDOR_PAYMENTS: the Temple Admin today (RolePermissions). One check, used for the
  // payments, the Pay button and the corrections alike, so none of them can drift from the others.
  const canPay = appUser?.role === "TEMPLE_ADMIN";

  const fetchInvoice = useCallback((token: string | undefined) => api.getInvoice(id, token), [id]);
  const { data: invoice, error, loading, reload } = useAuthedQuery(fetchInvoice);

  // The payments are read here rather than inside their section because the header's Pay button
  // needs what is still owed. For a reader who cannot pay, nothing is asked of the server at all.
  const fetchPayments = useCallback(
    (token: string | undefined) =>
      canPay ? api.listInvoicePayments(id, token) : Promise.resolve([] as InvoicePaymentView[]),
    [id, canPay]
  );
  const ledger = useAuthedQuery(fetchPayments);
  const reloadLedger = ledger.reload;

  // A payment, a reversal or a correction changes the invoice as well as the payment list, because
  // what is paid decides whether the bill is settled. Both are reloaded from one place so the two
  // halves of the screen can never disagree.
  const reloadEverything = useCallback(() => {
    reload();
    reloadLedger();
  }, [reload, reloadLedger]);

  // Which correction is being written, if any. One at a time: a void and a credit note are
  // different answers to the same question, and offering both half-open invites the wrong one.
  const [correcting, setCorrecting] = useState<"void" | "credit" | null>(null);
  const [paying, setPaying] = useState(false);
  const [done, setDone] = useState<string | null>(null);
  const payments = useRef<HTMLElement>(null);

  // "Create an invoice" ends here with ?recorded=<number>, and &duplicate=1 when another invoice from
  // the vendor already uses that number (conductor's ruling, T-273/T-274). Read once and the address
  // cleared, so a reload does not say it again. The ref guards the capture: setting state re-renders,
  // and a router object that is new each render would otherwise re-run this effect for ever (the
  // repo's known flash-capture loop, which runs a test out of memory).
  const recorded = search.get("recorded");
  const duplicate = search.get("duplicate") === "1";
  const [flash, setFlash] = useState<{ number: string; duplicate: boolean } | null>(null);
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current || !recorded) return;
    captured.current = true;
    setFlash({ number: recorded, duplicate });
    router.replace(`/invoices/${id}`);
  }, [recorded, duplicate, router, id]);

  const voided = invoice?.status === "VOIDED";
  const paidToDate = (ledger.data ?? []).reduce((sum, p) => sum + p.amount, 0);
  const owed = invoice ? round2(invoice.amount - invoice.creditedAmount - paidToDate) : 0;
  const ledgerReady = canPay && !ledger.loading && !ledger.error && ledger.data != null;

  function openPay() {
    setPaying(true);
    setDone(null);
    // The form opens in the Payments section, so the page is taken there with the cursor in Amount.
    setTimeout(() => {
      payments.current?.scrollIntoView?.({ behavior: "smooth", block: "start" });
      payments.current?.querySelector<HTMLInputElement>("form input")?.focus({ preventScroll: true });
    }, 0);
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/invoices" />
      <main className="min-w-0 flex-1 px-4 py-10 sm:px-8">
        <div className="mx-auto max-w-content">
          <Link href="/invoices" className="text-sm link">← All invoices</Link>

          {loading && !invoice ? (
            <Loading label="Loading invoice…" />
          ) : error ? (
            <div className="mt-6"><ErrorNotice error={error} /></div>
          ) : !invoice ? null : (
            <div className="mt-3 grid gap-6">
              <PageHeader
                title={invoice.invoiceNumber}
                subtitle={
                  <span className="flex flex-wrap items-center gap-2">
                    <Link href={`/vendors/${invoice.vendorId}`} className="link">
                      {invoice.vendorName}
                    </Link>
                    {voided ? (
                      <Badge>Voided</Badge>
                    ) : invoice.status === "PAID" ? (
                      // Neutral: a settled state, not a fresh result (T-227).
                      <Badge>Paid</Badge>
                    ) : (
                      // "Unpaid", never "Pending": the same word as the list (conductor, 2026-09-19).
                      <Badge tone="accent">Unpaid</Badge>
                    )}
                    {invoice.overdue && <Badge tone="danger">Overdue</Badge>}
                  </span>
                }
                // Up to three actions. On a phone they wrap inside the shared header: Pay and Record a
                // credit note side by side, Void on the line below (T-277 fixed PageHeader for this;
                // T-274 had to copy its markup).
                actions={
                  canPay && !voided ? (
                    <>
                      {ledgerReady && owed > 0.005 && !paying && (
                        <Button icon="cash" onClick={openPay}>
                          Pay this invoice
                        </Button>
                      )}
                      <Button variant="ghost" onClick={() => setCorrecting("credit")}>
                        Record a credit note
                      </Button>
                      <Button variant="danger" onClick={() => setCorrecting("void")}>
                        Void this bill
                      </Button>
                    </>
                  ) : undefined
                }
              />

              {flash &&
                (flash.duplicate ? (
                  <InlineNotice tone="warning" title={`Invoice ${flash.number} was recorded.`}>
                    Another invoice from this vendor already uses that number.
                  </InlineNotice>
                ) : (
                  <InlineNotice tone="success" autoDismiss title={`Invoice ${flash.number} was recorded.`} />
                ))}

              {done && (
                <InlineNotice tone="success" autoDismiss>
                  {done}
                </InlineNotice>
              )}

              {/* The void comes straight after the name, because every figure below it means
                  something different once a bill was never owed. Information: a settled state,
                  explained, not something to act on (T-227). */}
              {voided && (
                <InlineNotice tone="info" title="This bill was struck as never owed.">
                  {invoice.voidReason}
                  {invoice.voidedAt ? ` — ${moment(invoice.voidedAt)}` : ""}
                </InlineNotice>
              )}

              <Summary invoice={invoice} />

              {invoice.lines.length > 0 && <Items invoice={invoice} />}

              {/* A direct buy has no order to compare with, and a struck bill has no figures. */}
              {!invoice.direct && !voided && <InvoicedVsReceived invoice={invoice} />}

              {canPay && (
                <section
                  ref={payments}
                  className="card scroll-mt-4 px-6 py-5"
                  aria-labelledby="payments-heading"
                >
                  <h2 id="payments-heading" className="text-lg font-semibold text-ink">
                    Payments
                  </h2>
                  <p className="mt-1 max-w-prose text-sm text-ink-secondary">
                    Payments are made at the bank and recorded here. This app never pays anybody.
                  </p>

                  {ledger.loading && !ledger.data ? (
                    <Loading label="Loading payments…" />
                  ) : ledger.error ? (
                    <div className="mt-4"><ErrorNotice error={ledger.error} /></div>
                  ) : (
                    <>
                      <dl className="mt-4 flex flex-wrap gap-x-10 gap-y-4 text-sm">
                        <Detail label="Paid to date">
                          <span className="tabular-nums">{rs(paidToDate)}</span>
                        </Detail>
                        <Detail label="Still owed">
                          <span className="tabular-nums">{rs(owed)}</span>
                        </Detail>
                      </dl>

                      {paying && (
                        <PayInvoiceForm
                          invoiceId={id}
                          owed={owed}
                          onCancel={() => setPaying(false)}
                          onPaid={(amount) => {
                            setPaying(false);
                            const left = round2(owed - amount);
                            setDone(
                              `Payment of ${rs(amount)} recorded. ` +
                                (left > 0.005
                                  ? `${rs(left)} is still owed on this invoice.`
                                  : "This invoice is now paid in full.")
                            );
                            reloadEverything();
                          }}
                        />
                      )}

                      <PaymentTable
                        invoiceId={id}
                        payments={ledger.data ?? []}
                        onReversed={reloadEverything}
                      />
                    </>
                  )}
                </section>
              )}

              {correcting && (
                <CorrectionDialog
                  kind={correcting}
                  invoice={invoice}
                  onCancel={() => setCorrecting(null)}
                  onDone={() => {
                    setCorrecting(null);
                    reloadEverything();
                  }}
                />
              )}
            </div>
          )}
        </div>
      </main>
    </div>
  );
}

/**
 * The invoice's own facts, and the copy of the bill beside them (the mock's "The invoice" card).
 *
 * <p>Beyond the mock: the credit (shown with what is left owed beside it, because a credited bill whose
 * total still reads ₹1,400 is a bill somebody pays ₹1,400 against), the description a direct buy may
 * carry, the deliveries the bill covers in R-INV-3's own words, and an old invoice's typed scan
 * reference. Each appears only when there is something to say.
 */
function Summary({ invoice }: { invoice: VendorInvoiceDetailView }) {
  return (
    <section className="card px-6 py-5" aria-labelledby="invoice-heading">
      <h2 id="invoice-heading" className="text-lg font-semibold text-ink">
        The invoice
      </h2>
      <div className="mt-4 flex flex-wrap items-start gap-x-8 gap-y-4">
        <dl className="grid min-w-0 grow basis-72 grid-cols-2 gap-x-8 gap-y-4 text-sm">
          <Detail label="Grand total">
            {/* The invoice's amount is the grand total typed from the bill (T-271); an old invoice's
                amount means the same thing, what the vendor billed, all in. */}
            <span className="tabular-nums">{rs(invoice.grandTotal ?? invoice.amount)}</span>
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
          {invoice.creditedAmount > 0 && (
            <>
              <Detail label="Credited">
                <span className="tabular-nums">{rs(invoice.creditedAmount)}</span>
              </Detail>
              <Detail label="Owed after credit">
                <span className="tabular-nums">{rs(invoice.amount - invoice.creditedAmount)}</span>
              </Detail>
            </>
          )}
          {invoice.description && (
            <div className="col-span-2">
              <Detail label="Description">{invoice.description}</Detail>
            </div>
          )}
          {invoice.deliveries.length > 0 && (
            <div className="col-span-2">
              <Detail label={invoice.deliveries.length === 1 ? "Delivery billed" : "Deliveries billed"}>
                <ul className="grid gap-1">
                  {invoice.deliveries.map((d) => (
                    <li key={d.receiptId} className="tabular-nums">
                      <Link href={`/orders/${d.purchaseOrderId}`} className="link">
                        {d.poNumber}
                      </Link>
                      {` · delivered ${shortDate(d.receivedOn)}`}
                      {d.receivedByName ? ` · received by ${d.receivedByName}` : ""}
                    </li>
                  ))}
                </ul>
              </Detail>
            </div>
          )}
          {invoice.scanRef && (
            <div className="col-span-2">
              <Detail label="Scan reference">
                <span className="[overflow-wrap:anywhere]">{invoice.scanRef}</span>
              </Detail>
            </div>
          )}
        </dl>
        <BillCopy invoiceId={invoice.id} bill={invoice.bill} />
      </div>
    </section>
  );
}

/**
 * The copy of the bill: its thumbnail, and its name as a second way in, both opening the file
 * (R-INV-7). The thumbnail owns the viewing layer, so the name presses it rather than keeping a
 * second viewer of its own.
 */
function BillCopy({ invoiceId, bill }: { invoiceId: string; bill: AttachmentView | null }) {
  const { getToken } = useAuth();
  const box = useRef<HTMLSpanElement>(null);
  const name = bill?.originalName ?? "Copy of the bill";
  return (
    <div className="grid gap-1 text-sm">
      <span className="text-ink-secondary">Copy of the bill</span>
      {bill ? (
        <span ref={box} className="flex items-center gap-3">
          <AttachmentThumb
            fileId={bill.id}
            name={name}
            contentType={bill.contentType}
            load={async () => api.invoiceBill(invoiceId, await getToken())}
          />
          <button
            type="button"
            // The accessible name is the file's own; the thumbnail beside it says "Open …".
            onClick={() => box.current?.querySelector<HTMLButtonElement>("button[data-attachment-thumb]")?.click()}
            className="link min-h-touch text-left [overflow-wrap:anywhere]"
          >
            {name}
          </button>
        </span>
      ) : (
        // An invoice recorded before the upload was required (stage 6) has none.
        <span className="text-ink-muted">Nothing uploaded</span>
      )}
    </div>
  );
}

/** "4 × Bag (25 Kg)": a count of packs as a person writes it. */
const count = (n: number) => new Intl.NumberFormat("en-IN", { maximumFractionDigits: 3 }).format(n);

/**
 * How much one pack of a line holds, in the line's own unit. The server checks packCount × the
 * pack's size = billedQty (T-271), so the size is the billed quantity over the count; it is not
 * restated from the pack's own unit here, because the line does not carry that unit.
 */
function packSize(l: InvoiceLineView): number | null {
  if (!l.packLabel || l.packCount == null || l.packCount <= 0 || l.billedQty <= 0) return null;
  return l.billedQty / l.packCount;
}

/**
 * The Rate cell: "₹100 / Kg", and on a pack line per pack and per unit, "₹1,500 / bag · ₹60 / Kg". A
 * dash when nothing was billed. Said through the shared {@link readableRate} (T-293), so a 500 gm
 * line billed at ₹250 reads "₹500 / Kg" as the vendor page and the order sheet say it — not "₹0.50 /
 * gm", which followed the unit the quantity happened to be printed in. The pack price is the one the
 * server worked out for the line (`ratePerPack`), printed as it stands rather than rebuilt from the
 * rate.
 */
function lineRate(l: InvoiceLineView): string {
  if (l.rate == null || l.billedQty <= 0) return "—";
  const perStock = l.amount / l.billedQty;
  const rate = packSize(l) && l.ratePerPack != null
    ? readablePackRate(l.ratePerPack, l.packLabel!, perStock, l.unit)
    : readableRate(perStock, l.unit);
  return rate ?? "—";
}

/** A quantity cell: "45 Kg", or on a pack line "4 × Bag (25 Kg)" over "100 Kg" (T-273's form). */
function LineQuantity({ value, line }: { value: number | null; line: InvoiceLineView }) {
  if (value == null) return <span className="text-ink-muted">—</span>;
  const size = packSize(line);
  if (!size) return <span className="tabular-nums">{quantity(value, line.unit)}</span>;
  const packs = Number((value / size).toFixed(3));
  // One pack with no name is its own size, so "500 gm" under "1 × 500 gm" is dropped (T-302). The
  // rule is `repeatsPack`, one copy for every view (T-303); `packs` is the count as written above.
  const unnamedSingle = repeatsPack(line.packLabel!, packs);
  return (
    <span className="inline-flex flex-col">
      <span className="tabular-nums text-ink">{`${count(packs)} × ${line.packLabel}`}</span>
      {!unnamedSingle && <span className="text-sm tabular-nums text-ink-secondary">{quantity(value, line.unit)}</span>}
    </span>
  );
}

/** The amber note for a line billed at more than came (R-INV-4): "Billed 50 Kg, 45 Kg delivered". */
function overBilled(l: InvoiceLineView): string | null {
  if (l.deliveredQty == null || l.billedQty <= l.deliveredQty + 1e-9) return null;
  return `Billed ${quantity(l.billedQty, l.unit)}, ${quantity(l.deliveredQty, l.unit)} delivered`;
}

/**
 * The items the vendor billed, read-only (R-INV-4's columns), and the bill's totals under them with
 * the labels "Create an invoice" typed them under (R-INV-5: these labels are used everywhere), in
 * design D's compact list rather than the form's boxes (`InvoiceTotals readOnly`, T-277).
 */
function Items({ invoice }: { invoice: VendorInvoiceDetailView }) {
  // A direct buy has nothing ordered or delivered to show, as on the form that made it.
  const delivered = invoice.lines.some((l) => l.orderedQty != null || l.deliveredQty != null);
  const totals = invoice.subTotal != null && invoice.grandTotal != null;
  return (
    <section className="card overflow-hidden" aria-labelledby="items-heading">
      <h2 id="items-heading" className="px-6 pt-5 text-lg font-semibold text-ink">
        Items
      </h2>
      <table className={`${RULED_TABLE} mt-3`}>
        <thead className={THEAD}>
          <tr>
            <th className={TH_PRIMARY}>Item</th>
            {delivered && <th className={TH_FIXED}>Ordered</th>}
            {delivered && <th className={TH_FIXED}>Delivered</th>}
            <th className={TH_FIXED}>Billed qty</th>
            <th className={TH_FIXED}>Amount</th>
            <th className={TH_FIXED}>Rate</th>
          </tr>
        </thead>
        <tbody>
          {invoice.lines.map((l) => {
            const warn = overBilled(l);
            return (
              <tr key={l.id} className={TR}>
                <td className={TD_PRIMARY}>
                  {l.itemName}
                  {warn && (
                    <span className="flex items-center gap-1 text-sm font-normal text-warning">
                      <i className="ti ti-alert-triangle" aria-hidden="true" />
                      {warn}
                    </span>
                  )}
                </td>
                {delivered && (
                  <td className={TD_FIXED_NUM} data-label="Ordered">
                    <LineQuantity value={l.orderedQty} line={l} />
                  </td>
                )}
                {delivered && (
                  <td className={TD_FIXED_NUM} data-label="Delivered">
                    <LineQuantity value={l.deliveredQty} line={l} />
                  </td>
                )}
                <td className={TD_FIXED_NUM} data-label="Billed qty">
                  <LineQuantity value={l.billedQty} line={l} />
                </td>
                <td className={TD_FIXED_NUM} data-label="Amount">
                  {rs(l.amount)}
                </td>
                <td className={`${TD_FIXED_NUM} text-ink-secondary`} data-label="Rate">
                  {lineRate(l)}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {/* The bill's foot, on the right as on the paper. Not a table, so the table rule's left
          alignment does not reach it (see InvoiceTotals). */}
      {totals && (
        <div className="flex border-t border-hairline px-6 py-4">
          <InvoiceTotals
            readOnly
            subTotal={invoice.subTotal!}
            gstAmount={invoice.gstAmount ?? 0}
            otherCharges={invoice.otherCharges ?? 0}
            otherChargesNote={invoice.otherChargesNote}
            discount={invoice.discount ?? 0}
            grandTotal={invoice.grandTotal!}
          />
        </div>
      )}
    </section>
  );
}

/**
 * "Invoiced vs received" (R-INV-7): the items billed against what was delivered at the order's own
 * prices, and the difference, all as the server works them out from the lines (T-271). The server's
 * difference also takes off any credit note, which is why it is shown as sent and not re-subtracted
 * here. An old invoice compares its whole total, as it always did.
 */
function InvoicedVsReceived({ invoice }: { invoice: VendorInvoiceDetailView }) {
  const itemised = invoice.subTotal != null;
  const variance = invoice.variance;
  const over = invoice.lines
    .map((l) => {
      const warn = overBilled(l);
      return warn ? `${l.itemName}: ${warn.replace("Billed", "billed")}` : null;
    })
    .filter((w): w is string => w != null);
  return (
    <section className="card px-6 py-5" aria-labelledby="variance-heading">
      <h2 id="variance-heading" className="text-lg font-semibold text-ink">
        Invoiced vs received
      </h2>
      <p className="mt-1 max-w-prose text-sm text-ink-secondary">
        The items billed, against what was delivered at the order’s own prices. A difference is worth
        a question, not a refusal.
      </p>
      <dl className="mt-4 flex flex-wrap gap-x-10 gap-y-4 text-sm">
        <Detail label={itemised ? "Items billed" : "Invoiced"}>
          <span className="tabular-nums">{rs(itemised ? invoice.subTotal! : invoice.amount)}</span>
        </Detail>
        <Detail label="Delivered, at the order’s prices">
          <span className="tabular-nums">
            {invoice.expectedValue == null ? "—" : rs(invoice.expectedValue)}
          </span>
        </Detail>
        <Detail label="Difference">
          <span className={`tabular-nums ${variance ? "text-warning" : ""}`}>
            {variance == null
              ? "—"
              : variance === 0
                ? "None"
                : `${rs(Math.abs(variance))} ${variance > 0 ? "more" : "less"} than received`}
          </span>
        </Detail>
      </dl>
      {over.length > 0 && (
        <ul className="mt-3 grid gap-1 text-sm text-warning">
          {over.map((w) => (
            <li key={w} className="flex items-center gap-1">
              <i className="ti ti-alert-triangle" aria-hidden="true" />
              {w}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** The name a proof file is shown under when the device gave it none. */
const PROOF_NAME: Record<string, string> = {
  PAYMENT_PROOF: "Proof of payment",
  CASH_SIGNED_NOTE: "Signed note",
  CASH_RECEIVER_PHOTO: "Photo of the person who took the cash",
};

/**
 * What has been paid against this invoice (R-PAY-3), with its proof.
 *
 * <p>A payment recorded in error is reversed rather than struck, because the ledger underneath is
 * append-only and nothing in it is ever edited. So a reversal shows as two rows: the payment, which
 * stays exactly as it was, and the correction of the opposite sign that names it. Both are dimmed,
 * because between them they are worth nothing, and the total above them already says so. The
 * Reversal column is there only when some row is part of a reversal: an always-empty column is a
 * dead block (table rule).
 *
 * <p>"Paid by" (R-PAY-3, was "Recorded by") is whoever recorded the payment. For cash, the Reference
 * place names who took the money, as the form asked for it in the same place. The proof is one
 * thumbnail, or two for cash (the signed note, then the photo), each opening its file; a payment made
 * before proof was asked for has none, and says so with a dash.
 */
/**
 * The Payments table's Reference column: a text column that keeps a short value's place on a phone.
 *
 * <p>It was a short value ({@link TD_FIXED}), which never wraps. But what it holds is free text of
 * no fixed length: a bank's UTR ("UTR SBIN0226019876543210"), or for cash the name of whoever took
 * the money ("Received by Manjunath Krishnamurthy Gowda"). Measured at 390 (T-300, defect N1 of the
 * second invoices verification): a 34-character reference was a 315-334px line that would not
 * give, it widened the Payments card, the page is a grid so every card went with it, and the page
 * scrolled 10px sideways (scrollWidth 400). At 1024 the same reference held its column at full
 * width and the fitter had to squeeze "Paid by" to 36px, one letter per line.
 *
 * <p>So it is a text column (§5's logic, Q-18: what wraps is decided by what the content is, not by
 * where the column sits). It wraps between words, and on a wide screen the fitter can narrow it like
 * "Paid by" beside it, never below its longest word. A single token wider than the whole card (a
 * pasted reference with no spaces) may break inside itself, from the text column's
 * `overflow-wrap: anywhere`, and only then: a broken reference is the lesser evil against a page
 * that scrolls sideways, and a reference is an identifier to be copied, not a word to be read.
 *
 * <p>On a phone it keeps the place the approved mock gives it: on the card's second line, between
 * the method and the proof, not moved up to the first line beside the payer's name, which is where
 * a text column would otherwise go. `!` because the card rule placing text columns is two classes
 * deep in globals.css.
 */
const REFERENCE = `${TD_SECOND} max-lg:!order-2`;

function PaymentTable({
  invoiceId,
  payments,
  onReversed,
}: {
  invoiceId: string;
  payments: InvoicePaymentView[];
  onReversed: () => void;
}) {
  const { getToken } = useAuth();
  const [reversing, setReversing] = useState<InvoicePaymentView | null>(null);
  const anyReversal = payments.some((p) => p.reverses != null || p.reversedBy != null);

  if (payments.length === 0) {
    return <p className="mt-4 text-sm text-ink-muted">Nothing paid yet.</p>;
  }

  return (
    <div className="-mx-6 mt-4">
      <table className={RULED_TABLE}>
        <thead className={THEAD}>
          <tr>
            <th className={TH_FIXED}>Paid on</th>
            <th className={TH_FIXED}>Amount</th>
            <th className={TH_FIXED}>Method</th>
            <th className={REFERENCE}>Reference</th>
            <th className={TH_SECOND}>Paid by</th>
            <th className={TH_FIXED}>Proof</th>
            {anyReversal && <th className={TH_SECOND}>Reversal</th>}
            <th className={TH_ACTIONS_FIXED}><span className="sr-only">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          {payments.map((p) => {
            const spent = p.reverses != null || p.reversedBy != null;
            return (
              <tr key={p.id} className={`${TR} ${spent ? "opacity-50" : ""}`}>
                <td className={TD_FIXED}>{dateWithYear(p.paidOn)}</td>
                <td className={TD_FIXED_NUM}>{rs(p.amount)}</td>
                <td className={TD_FIXED}>
                  {PAYMENT_METHODS.find((m) => m.value === p.method)?.label ?? p.method.replace(/_/g, " ").toLowerCase()}
                </td>
                <td className={`${REFERENCE} text-ink-secondary`} data-label="Reference">
                  {p.receivedByName ? `Received by ${p.receivedByName}` : p.reference || "—"}
                </td>
                <td className={`${TD_SECOND} text-ink-secondary`} data-label="Paid by">
                  {p.recordedByName ?? "—"}
                </td>
                <td className={TD_FIXED} data-label="Proof">
                  {p.attachments.length === 0 ? (
                    <span className="text-ink-muted">—</span>
                  ) : (
                    <span className="inline-flex gap-2 align-middle">
                      {p.attachments.map((file) => (
                        <AttachmentThumb
                          key={file.id}
                          small
                          fileId={file.id}
                          name={file.originalName ?? PROOF_NAME[file.kind] ?? "Proof of payment"}
                          contentType={file.contentType}
                          load={async () => api.paymentFile(invoiceId, p.id, file.id, await getToken())}
                        />
                      ))}
                    </span>
                  )}
                </td>
                {anyReversal && (
                  <td className={`${TD_SECOND} text-ink-secondary`} data-label="Reversal">
                    {p.reverses != null ? p.reverseReason : p.reversedBy != null ? "Reversed" : null}
                  </td>
                )}
                <td className={TD_ACTIONS_FIXED}>
                  {p.reverses == null && p.reversedBy == null && p.amount > 0 ? (
                    <Button variant="ghost" size="sm" onClick={() => setReversing(p)}>
                      Reverse
                    </Button>
                  ) : null}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>

      {reversing && (
        <ReverseDialog
          invoiceId={invoiceId}
          payment={reversing}
          onCancel={() => setReversing(null)}
          onDone={() => {
            setReversing(null);
            onReversed();
          }}
        />
      )}
    </div>
  );
}

/**
 * Striking a bill, or reducing it — one panel, because the two are the same shape of act and the
 * whole difficulty is telling them apart. The words say which is which rather than the button
 * colour: a void says the bill was never owed and is final, a credit note says it was owed and is
 * now owed less and leaves it payable.
 *
 * <p>The reason is required in both, and the commit stays refused until there are words in the box.
 * The server refuses a blank one too — this is the earlier, kinder half of the same rule, the way
 * dropping a vendor already works.
 */
function CorrectionDialog({
  kind,
  invoice,
  onCancel,
  onDone,
}: {
  kind: "void" | "credit";
  invoice: VendorInvoiceView;
  onCancel: () => void;
  onDone: () => void;
}) {
  const { getToken } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [reason, setReason] = useState("");
  const [amount, setAmount] = useState("");
  const amountField = useRef<HTMLInputElement>(null);
  const reasonField = useRef<HTMLTextAreaElement>(null);

  // The cursor belongs in the first thing this panel asks for, which is not the same field in both
  // shapes of it: a void asks only why, a credit note asks how much first.
  const striking = kind === "void";
  useEffect(() => {
    if (striking) {
      reasonField.current?.focus();
    } else {
      amountField.current?.focus();
    }
  }, [striking]);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onCancel();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onCancel]);

  const written = reason.trim();
  const credit = Number(amount);
  const ready = written !== "" && (striking || (amount !== "" && credit > 0));

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // `Form` has already named a blank box, a reason of only spaces (as blank), and a credit of 0 or
    // less ("must be more than 0", from the amount box's `data-more-than`) before this runs (T-203).
    // This check stays as the twin guard, so neither is sent even if the form's check were removed.
    if (!ready) return;
    setBusy(true);
    setError(null);
    try {
      const token = await getToken();
      if (striking) {
        await api.voidInvoice(invoice.id, written, token);
      } else {
        await api.creditInvoice(invoice.id, { amount: credit, reason: written }, token);
      }
      onDone();
    } catch (e) {
      setError(
        toApiError(
          e,
          striking ? "We couldn’t void that invoice." : "We couldn’t record that credit note."
        )
      );
      setBusy(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 px-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="correction-title"
    >
      <Form
        onSubmit={submit}
        aria-label={striking ? "Void this invoice" : "Record a credit note"}
        className="modal w-full max-w-prose px-8 py-7"
      >
        <h2 id="correction-title" className="text-lg">
          {striking ? `Void ${invoice.invoiceNumber}?` : `Credit note against ${invoice.invoiceNumber}`}
        </h2>
        <p className="mt-2 text-sm text-ink-secondary">
          {striking
            ? "The bill leaves the payment queue for good and nothing further can be paid against it. Payments already recorded stay exactly as they are."
            : "The bill stays payable and what is owed goes down by the amount you enter. Use this when the vendor has reduced a bill that was right when it was raised."}
        </p>

        {!striking && (
          <label className="mt-5 flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">How much is being credited?</span>
            <input
              ref={amountField}
              name="amount"
              type="number"
              min="0"
              // Exclusive: a credit of exactly 0 is refused in words, which `min` cannot do (T-203).
              data-more-than="0"
              step="any"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              required
              className="min-h-touch w-40 rounded-control border border-hairline px-3 text-right tabular-nums"
            />
            <span className="pl-field-inset text-sm text-ink-secondary">
              In rupees. It cannot take what is owed below what has already been paid.
            </span>
          </label>
        )}

        <label className="mt-5 flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">
            {striking ? "Why was this bill never owed?" : "What is the credit for?"}
          </span>
          <textarea
            ref={reasonField}
            name="reason"
            rows={3}
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            required
            className="rounded-control border border-hairline px-3 py-2"
          />
          <span className="pl-field-inset text-sm text-ink-secondary">
            Kept with your name and today’s date, and never overwritten. Whoever argues this with the
            vendor next year reads it.
          </span>
        </label>

        {error && (
          <div className="mt-4">
            <ErrorNotice error={error} />
          </div>
        )}

        <div className="mt-6 flex flex-wrap items-center justify-end gap-3">
          <Button type="button" variant="ghost" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" variant={striking ? "danger" : "primary"} busy={busy}>
            {striking ? "Void this bill" : "Record the credit note"}
          </Button>
        </div>
      </Form>
    </div>
  );
}

/**
 * Undoing a payment that did not happen — a bounced cheque, a mistyped amount, money that went
 * against the wrong bill.
 *
 * <p>The panel says what will actually be written, because a person who presses "Reverse" expecting
 * the row to disappear and then finds two rows will assume something went wrong. Nothing is removed
 * here, ever.
 */
function ReverseDialog({
  invoiceId,
  payment,
  onCancel,
  onDone,
}: {
  invoiceId: string;
  payment: InvoicePaymentView;
  onCancel: () => void;
  onDone: () => void;
}) {
  const { getToken } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [reason, setReason] = useState("");
  const field = useRef<HTMLTextAreaElement>(null);

  useEffect(() => field.current?.focus(), []);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onCancel();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onCancel]);

  const written = reason.trim();

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // `Form` counts a reason of only spaces as blank and says "What happened? is required" before this
    // runs (T-203). This check stays as the twin guard, so nothing is sent for spaces.
    if (written === "") return;
    setBusy(true);
    setError(null);
    try {
      await api.reverseInvoicePayment(invoiceId, payment.id, written, await getToken());
      onDone();
    } catch (e) {
      setError(toApiError(e, "We couldn’t reverse that payment."));
      setBusy(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 px-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="reverse-title"
    >
      <Form onSubmit={submit} aria-label="Reverse this payment" className="modal w-full max-w-prose px-8 py-7">
        <h2 id="reverse-title" className="text-lg">
          Reverse the {money(payment.amount, "INR")} paid on {dateWithYear(payment.paidOn)}?
        </h2>
        <p className="mt-2 text-sm text-ink-secondary">
          The payment stays on the record and an entry of the opposite amount is added beside it, so
          the two cancel out. What is owed goes back up, and the bill returns to the payment queue if
          this payment had settled it.
        </p>

        <label className="mt-5 flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">What happened?</span>
          <textarea
            ref={field}
            name="reason"
            rows={3}
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            required
            className="rounded-control border border-hairline px-3 py-2"
          />
          <span className="pl-field-inset text-sm text-ink-secondary">
            A bounced cheque, a mistyped amount, a payment against the wrong bill. It is kept with
            your name and today’s date.
          </span>
        </label>

        {error && (
          <div className="mt-4">
            <ErrorNotice error={error} />
          </div>
        )}

        <div className="mt-6 flex flex-wrap items-center justify-end gap-3">
          <Button type="button" variant="ghost" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" variant="danger" busy={busy}>
            Reverse this payment
          </Button>
        </div>
      </Form>
    </div>
  );
}

/** What this invoice was raised against: a purchase order to follow, or a cash-market buy. */
function against(invoice: VendorInvoiceView) {
  if (invoice.direct) {
    return <span className="rounded-control bg-sunken px-2 py-1 text-xs font-semibold">Direct — no purchase order</span>;
  }
  if (!invoice.purchaseOrderId) {
    return <span className="text-ink-muted">—</span>;
  }
  return (
    <Link href={`/orders/${invoice.purchaseOrderId}`} className="link tabular-nums">
      {invoice.poNumber ?? "Purchase order"}
    </Link>
  );
}

/**
 * One labelled value in the invoice's details.
 *
 * <p>A value with no spaces in it (a description or reference pasted from somewhere, an email
 * address) is one unbreakable word to a browser, and here it widened its card and, the page being a
 * grid, the whole page with it (T-304; T-300 measured the same in the Payments table: 1349px at
 * 1024). So a value may break inside itself, but only when it alone is wider than its place: first
 * after an "@", full stop, hyphen, slash or underscore, the same break points the tables give a long
 * token ({@link withLongTokenBreaks}, which leaves every word shorter than a pasted token alone, so
 * a PO number never splits), then anywhere. `min-w-0` lets the two-column grid of details give its
 * columns less than their longest word; without it a grid column never goes below that, whatever
 * the text is allowed to do.
 */
function Detail({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-ink-secondary">{label}</dt>
      <dd className="mt-1 [overflow-wrap:anywhere]">
        {typeof children === "string" ? withLongTokenBreaks(children, "separators") : children}
      </dd>
    </div>
  );
}
