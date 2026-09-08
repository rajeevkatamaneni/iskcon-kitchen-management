"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { Button } from "@/components/ds/Button";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type InvoicePaymentView, type VendorInvoiceView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";
import { dateWithYear, money, moment } from "@/lib/format";
import { TABLE, THEAD, TR, TH_TEXT, TH_NUM, TH_ACTIONS, TD_TEXT, TD_NUM, TD_DATE, TD_ACTIONS, WRAP } from "@/components/ds/table";

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
 * <p>It is also where a bill is corrected (T-010) — struck as never owed, reduced by a credit note,
 * or relieved of a payment that did not happen. Those three acts sit behind the payments permission
 * and not the one that opens this page, so they appear for a Temple Admin alone.
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
  const { data: invoice, error, loading, reload } = useAuthedQuery(fetchInvoice);

  // Recording and reading payments sit behind MANAGE_VENDOR_PAYMENTS, which of the roles that can
  // open this page only a Temple Admin holds. Showing a Kitchen Manager an empty payments table
  // would tell them the invoice is unpaid when in truth they simply cannot see; the section is
  // absent instead. The same permission gates voiding and crediting.
  const canCorrect = appUser?.role === "TEMPLE_ADMIN";

  // Which correction is being written, if any. One at a time: a void and a credit note are
  // different answers to the same question, and offering both half-open invites the wrong one.
  const [correcting, setCorrecting] = useState<"void" | "credit" | null>(null);

  // A reversal changes the invoice as well as the payment list, because what is paid decides
  // whether the bill is settled. Both queries are reloaded from one place so the two halves of the
  // screen can never disagree.
  const [ledgerNonce, setLedgerNonce] = useState(0);
  const reloadEverything = useCallback(() => {
    reload();
    setLedgerNonce((n) => n + 1);
  }, [reload]);

  const voided = invoice?.status === "VOIDED";

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
              <header className="mb-6 mt-3 flex flex-wrap items-start justify-between gap-4">
                <div>
                  <h1>{invoice.invoiceNumber}</h1>
                  <p className="mt-1 flex flex-wrap items-center gap-2 text-ink-secondary">
                    <Link href={`/vendors/${invoice.vendorId}`} className="text-accent-text hover:underline">
                      {invoice.vendorName}
                    </Link>
                    {voided ? (
                      <span className="rounded-sm bg-sunken px-2 py-1 text-xs text-ink-secondary font-semibold">Voided</span>
                    ) : invoice.status === "PAID" ? (
                      <span className="rounded-sm bg-success-bg px-2 py-1 text-xs text-success font-semibold">Paid</span>
                    ) : (
                      <span className="rounded-sm bg-accent-bg px-2 py-1 text-xs text-accent-text font-semibold">Pending</span>
                    )}
                    {invoice.overdue && (
                      <span className="rounded-sm bg-danger-bg px-2 py-1 text-xs text-danger font-semibold">Overdue</span>
                    )}
                  </p>
                </div>
                {canCorrect && !voided && (
                  <div className="flex flex-wrap items-center gap-3">
                    <Button variant="secondary" onClick={() => setCorrecting("credit")}>
                      Record a credit note
                    </Button>
                    <Button variant="danger" onClick={() => setCorrecting("void")}>
                      Void this bill
                    </Button>
                  </div>
                )}
              </header>

              {/* The void is the first thing on the page after the name, because every figure below
                  it means something different once a bill was never owed. */}
              {voided && (
                <div className="mb-6">
                  <InlineNotice tone="warning" title="This bill was struck as never owed.">
                    {invoice.voidReason}
                    {invoice.voidedAt ? ` — ${moment(invoice.voidedAt)}` : ""}
                  </InlineNotice>
                </div>
              )}

              <section className="card mb-8 px-6 py-5" aria-labelledby="invoice-heading">
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
                  {/* Shown only once there is a credit note, and shown with what is left owed
                      beside it. A credited bill whose amount still reads ₹1,400 is a bill somebody
                      pays ₹1,400 against. */}
                  {invoice.creditedAmount > 0 && (
                    <>
                      <Detail label="Credited">
                        <span className="tabular-nums">{money(invoice.creditedAmount, "INR")}</span>
                      </Detail>
                      <Detail label="Owed after credit">
                        <span className="tabular-nums">
                          {money(invoice.amount - invoice.creditedAmount, "INR")}
                        </span>
                      </Detail>
                    </>
                  )}
                  {invoice.description && <Detail label="Description">{invoice.description}</Detail>}
                  <Detail label="Scan reference">
                    {invoice.scanRef ?? <span className="text-ink-muted">Nothing filed</span>}
                  </Detail>
                </dl>
              </section>

              {invoice.expectedValue != null && (
                <section className="card mb-8 px-6 py-5" aria-labelledby="variance-heading">
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

              {canCorrect && (
                <PaymentHistory
                  invoiceId={id}
                  owed={invoice.amount - invoice.creditedAmount}
                  nonce={ledgerNonce}
                  onReversed={reloadEverything}
                />
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
 *
 * <p>A payment recorded in error is reversed rather than struck, because the ledger underneath is
 * append-only and nothing in it is ever edited. So a reversal shows as two rows: the payment, which
 * stays exactly as it was, and the correction of the opposite sign that names it. Both are dimmed,
 * because between them they are worth nothing, and the total above them already says so.
 */
function PaymentHistory({
  invoiceId,
  owed,
  nonce,
  onReversed,
}: {
  invoiceId: string;
  /** The invoiced amount less any credit notes — what a full payment would have to reach. */
  owed: number;
  /** Bumped by the page when something outside this component changed the ledger. */
  nonce: number;
  onReversed: () => void;
}) {
  const fetchPayments = useCallback(
    (token: string | undefined) => api.listInvoicePayments(invoiceId, token),
    [invoiceId]
  );
  const { data, error, loading, reload } = useAuthedQuery(fetchPayments);
  const payments = data ?? [];
  const paidToDate = payments.reduce((sum, p) => sum + p.amount, 0);
  const outstanding = owed - paidToDate;

  // The invoice half of the screen reloads on its own clock; this keeps the ledger in step with it
  // without a second query hook. The ref guards the first render, which is not a change.
  const seen = useRef(nonce);
  useEffect(() => {
    if (seen.current === nonce) return;
    seen.current = nonce;
    reload();
  }, [nonce, reload]);

  const [reversing, setReversing] = useState<InvoicePaymentView | null>(null);

  return (
    <section className="card mb-8 px-6 py-5" aria-labelledby="payments-heading">
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
            <div className="table-wrap overflow-x-auto">
              <table className={`mt-4 ${TABLE} text-sm`}>
                <thead className={THEAD}>
                  <tr>
                    <th className={TH_TEXT}>Paid on</th>
                    <th className={TH_NUM}>Amount</th>
                    <th className={TH_TEXT}>Method</th>
                    <th className={`${TH_TEXT} ${WRAP}`}>Reference</th>
                    <th className={TH_TEXT}>Recorded by</th>
                    <th className={TH_ACTIONS}>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {payments.map((p) => {
                    const spent = p.reverses != null || p.reversedBy != null;
                    return (
                      <tr key={p.id} className={`${TR} ${spent ? "opacity-50" : ""}`}>
                        <td className={TD_DATE}>{dateWithYear(p.paidOn)}</td>
                        <td className={TD_NUM}>{money(p.amount, "INR")}</td>
                        <td className={TD_TEXT}>{p.method.replace(/_/g, " ").toLowerCase()}</td>
                        <td className={`${TD_TEXT} ${WRAP} text-ink-secondary`}>{p.reference ?? "—"}</td>
                        <td className={`${TD_TEXT} ${WRAP} text-ink-secondary`}>{p.recordedByName ?? "—"}</td>
                        <td className={TD_ACTIONS}>
                          {p.reverses != null ? (
                            <span className="text-ink-secondary">{p.reverseReason}</span>
                          ) : p.reversedBy != null ? (
                            <span className="text-ink-secondary">Reversed</span>
                          ) : p.amount > 0 ? (
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
            </div>
          )}
        </>
      )}

      {reversing && (
        <ReverseDialog
          invoiceId={invoiceId}
          payment={reversing}
          onCancel={() => setReversing(null)}
          onDone={() => {
            setReversing(null);
            reload();
            onReversed();
          }}
        />
      )}
    </section>
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
      <form
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
          <Button type="button" variant="secondary" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" variant={striking ? "danger" : "primary"} busy={busy} disabled={!ready}>
            {striking ? "Void this bill" : "Record the credit note"}
          </Button>
        </div>
      </form>
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
      <form onSubmit={submit} aria-label="Reverse this payment" className="modal w-full max-w-prose px-8 py-7">
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
          <Button type="button" variant="secondary" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" variant="danger" busy={busy} disabled={written === ""}>
            Reverse this payment
          </Button>
        </div>
      </form>
    </div>
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
