"use client";

import { useState } from "react";
import { money, shortDate } from "@/lib/format";
import type { StaffPaymentMode, StaffPayView } from "@/lib/api";

/**
 * What one member of staff is paid, and the two forms that add to it (B8).
 *
 * <p>Lifted out of the staff register on 2026-08-20 and given a page of its own at
 * `/staff/[id]/pay`. It sat inline beneath the register, which meant paying somebody happened with
 * the whole payroll of the temple still on screen beneath it — and the history, which is the longest
 * thing here, pushed the register out of sight anyway. Paying a person is one person's business.
 *
 * <p>Everything here is a record of what happened: the app never works out what is owed, and the
 * only figure it computes is the advance balance, which is genuinely arithmetic — advances given
 * minus what has been docked back — and is therefore stated without hedging.
 *
 * <p>There is no Close and no Cancel. The page it lives on is reached deliberately and left by the
 * back link at its top left; a button that dismissed the only thing on the screen would leave the
 * reader looking at nothing.
 */

/** Cash first: it is what a temple kitchen reaches for most often. */
export const PAYMENT_MODES = [
  { value: "CASH", label: "Cash" },
  { value: "CHEQUE", label: "Cheque" },
  { value: "PAYROLL", label: "Payroll" },
] as const;

/** An advance is handed over, so a payroll run is the one thing it cannot come through. */
export const ADVANCE_MODES = PAYMENT_MODES.filter((m) => m.value !== "PAYROLL");

const FIELD = "min-h-touch rounded border border-hairline bg-canvas px-3";

export function PayPanel({
  pay,
  busy,
  onSubmitPayment,
  onSubmitAdvance,
  onVoidPayment,
  onVoidAdvance,
}: {
  /**
   * Never null: the page above decides what to show while the request is in flight and what to say
   * if it fails, so this component only ever renders a record that has arrived.
   */
  pay: StaffPayView;
  busy: boolean;
  onSubmitPayment: (e: React.FormEvent<HTMLFormElement>) => void;
  onSubmitAdvance: (e: React.FormEvent<HTMLFormElement>) => void;
  onVoidPayment: (paymentId: string) => void;
  onVoidAdvance: (advanceId: string) => void;
}) {
  // Which mode is chosen decides whether the reference is required, so it is held rather than left
  // to the browser — a cheque number asked for only after the server refuses is asked for too late.
  const [mode, setMode] = useState<StaffPaymentMode>("CASH");
  const [advanceMode, setAdvanceMode] = useState<StaffPaymentMode>("CASH");

  const recoverable = pay.advances.filter((a) => !a.voidedAt && a.outstanding > 0);

  return (
    // Named rather than headed: the page's own title says whose pay this is, and repeating it here
    // would put the same sentence on the screen twice. The name is kept for anyone navigating by
    // region, who arrives at this block without the heading above it in view.
    <section className="rounded-lg bg-raised px-6 py-5" aria-label={`${pay.fullName}’s pay`}>
      <dl className="grid grid-cols-3 gap-4 text-sm">
        <div className="rounded border border-hairline px-4 py-3">
          <dt className="text-ink-secondary">Monthly salary</dt>
          <dd className="mt-1 text-lg tabular-nums">
            {pay.monthlySalary !== null ? (
              money(pay.monthlySalary, pay.currency)
            ) : (
              <span className="text-base text-ink-muted">No salary recorded</span>
            )}
          </dd>
        </div>
        <div className="rounded border border-hairline px-4 py-3">
          <dt className="text-ink-secondary">Cash advances outstanding</dt>
          <dd className="mt-1 text-lg tabular-nums">{money(pay.advanceBalance, pay.currency)}</dd>
        </div>
        <div className="rounded border border-hairline px-4 py-3">
          <dt className="text-ink-secondary">Last salary payment</dt>
          <dd className="mt-1 text-lg tabular-nums">
            {pay.lastSalaryPayment ? (
              `${money(pay.lastSalaryPayment.net, pay.currency)} on ${shortDate(pay.lastSalaryPayment.paidOn)}`
            ) : (
              <span className="text-base text-ink-muted">None recorded</span>
            )}
          </dd>
        </div>
      </dl>

      {/* ---- Recording a payment ---- */}
      <form className="mt-6 grid grid-cols-4 gap-4" aria-label="Record a payment" onSubmit={onSubmitPayment}>
        <h2 className="col-span-4 text-base">Record a payment</h2>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Date</span>
          <input name="paidOn" type="date" required className={FIELD} />
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Amount before deductions</span>
          <input
            name="amount"
            type="number"
            min="1"
            step="0.01"
            inputMode="decimal"
            required
            className={FIELD}
          />
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Paid by</span>
          <select
            name="mode"
            value={mode}
            onChange={(e) => setMode(e.target.value as StaffPaymentMode)}
            className={FIELD}
          >
            {PAYMENT_MODES.map((m) => (
              <option key={m.value} value={m.value}>
                {m.label}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Reference</span>
          <input
            name="reference"
            required={mode !== "CASH"}
            placeholder={mode === "CHEQUE" ? "Cheque number" : mode === "PAYROLL" ? "Payroll run" : "—"}
            className={FIELD}
          />
          {mode !== "CASH" && (
            <span className="pl-field-inset text-xs text-ink-muted">
              So this payment can be found again on a statement.
            </span>
          )}
        </label>

        {recoverable.length > 0 && (
          <fieldset className="col-span-4 rounded border border-hairline px-4 py-3">
            <legend className="px-1 text-sm text-ink-secondary">
              Recover from an advance — leave blank to recover nothing this time
            </legend>
            <div className="grid grid-cols-2 gap-4">
              {recoverable.map((a) => (
                <label key={a.id} className="flex items-center gap-3 text-sm text-ink-secondary">
                  {/* The outstanding figure is the number this whole control exists for — how much
                      of this advance is still owed — so it is the one thing here set in the ink
                      colour at the body size and a medium weight. It was grey, extra-small and
                      below the fold of the eye; an admin deciding what to recover was reading the
                      *history* louder than the amount. */}
                  <span className="min-w-0 flex-1">
                    <span className="block font-medium tabular-nums text-ink">
                      {money(a.outstanding, pay.currency)} still outstanding
                    </span>
                    <span className="block text-xs text-ink-muted">
                      from an advance of {money(a.amount, pay.currency)} on {shortDate(a.paidOn)}
                    </span>
                  </span>
                  <input
                    name={`deduct-${a.id}`}
                    type="number"
                    min="0"
                    max={a.outstanding}
                    step="0.01"
                    inputMode="decimal"
                    className={`${FIELD} w-32`}
                  />
                </label>
              ))}
            </div>
          </fieldset>
        )}

        <label className="col-span-3 flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Note</span>
          <input name="note" className={FIELD} />
        </label>
        <div className="flex items-end">
          <button
            type="submit"
            disabled={busy}
            className="min-h-touch w-full rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
          >
            Record payment
          </button>
        </div>
      </form>

      {/* ---- Recording an advance ---- */}
      <form className="mt-6 grid grid-cols-4 gap-4" aria-label="Record an advance" onSubmit={onSubmitAdvance}>
        <h2 className="col-span-4 text-base">Give an advance</h2>
        <p className="col-span-4 -mt-2 text-sm text-ink-secondary">
          Money paid ahead of the work. It comes back by being docked from later payments, and the
          balance above follows on its own.
        </p>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Date</span>
          <input name="advancePaidOn" type="date" required className={FIELD} />
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Amount</span>
          <input
            name="advanceAmount"
            type="number"
            min="1"
            step="0.01"
            inputMode="decimal"
            required
            className={FIELD}
          />
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Paid by</span>
          <select
            name="advanceMode"
            value={advanceMode}
            onChange={(e) => setAdvanceMode(e.target.value as StaffPaymentMode)}
            className={FIELD}
          >
            {ADVANCE_MODES.map((m) => (
              <option key={m.value} value={m.value}>
                {m.label}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Reference</span>
          <input
            name="advanceReference"
            required={advanceMode !== "CASH"}
            placeholder={advanceMode === "CHEQUE" ? "Cheque number" : "—"}
            className={FIELD}
          />
        </label>

        <label className="col-span-3 flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Note</span>
          <input name="advanceNote" className={FIELD} />
        </label>
        <div className="flex items-end">
          <button
            type="submit"
            disabled={busy}
            className="min-h-touch w-full rounded border border-hairline px-5 hover:bg-sunken disabled:opacity-60"
          >
            Record advance
          </button>
        </div>
      </form>

      {/* ---- What has been paid ---- */}
      <section className="mt-8" aria-labelledby="payment-history-heading">
        <h2 id="payment-history-heading" className="mb-2 text-base">
          Payments
        </h2>
        {pay.payments.length === 0 ? (
          <p className="text-sm text-ink-secondary">Nothing has been paid to {pay.fullName} yet.</p>
        ) : (
          <div className="overflow-x-auto rounded-lg bg-sunken">
            <table className="w-full text-left text-sm">
              <thead className="text-ink-secondary">
                <tr>
                  <th className="px-4 py-2 font-medium">Date</th>
                  <th className="px-4 py-2 font-medium">For</th>
                  <th className="px-4 py-2 font-medium text-right">Gross</th>
                  <th className="px-4 py-2 font-medium text-right">Docked</th>
                  <th className="px-4 py-2 font-medium text-right">Paid</th>
                  <th className="px-4 py-2 font-medium">How</th>
                  <th className="px-4 py-2 font-medium" />
                </tr>
              </thead>
              <tbody>
                {pay.payments.map((p) => (
                  <tr key={p.id} className="border-t border-hairline hover:bg-raised/60">
                    <td className="px-4 py-2 tabular-nums">{shortDate(p.paidOn)}</td>
                    <td className="px-4 py-2">
                      {p.purposeLabel}
                      {p.voidedAt && <span className="ml-2 text-xs text-ink-muted">struck out</span>}
                    </td>
                    <td className="px-4 py-2 text-right tabular-nums">{money(p.gross, pay.currency)}</td>
                    <td className="px-4 py-2 text-right tabular-nums">
                      {p.deducted > 0 ? money(p.deducted, pay.currency) : "—"}
                    </td>
                    <td className="px-4 py-2 text-right tabular-nums">{money(p.net, pay.currency)}</td>
                    <td className="px-4 py-2 text-ink-secondary">
                      {p.modeLabel}
                      {p.reference ? ` · ${p.reference}` : ""}
                    </td>
                    <td className="px-4 py-2 text-right">
                      {/* Only an entry nothing depends on can be struck. One that docked an advance
                          would hand the balance back silently, so the API refuses it. Deliberately
                          left as quiet text: correcting a mistake is not one of this screen's
                          actions, it is what you do to a line you should not have written. */}
                      {!p.voidedAt && p.deducted === 0 && (
                        <button
                          type="button"
                          disabled={busy}
                          onClick={() => onVoidPayment(p.id)}
                          className="text-danger hover:underline disabled:opacity-60"
                        >
                          Strike out
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {/* ---- What is still owed ---- */}
      {pay.advances.length > 0 && (
        <section className="mt-6" aria-labelledby="advance-history-heading">
          <h2 id="advance-history-heading" className="mb-2 text-base">
            Advances
          </h2>
          <div className="overflow-x-auto rounded-lg bg-sunken">
            <table className="w-full text-left text-sm">
              <thead className="text-ink-secondary">
                <tr>
                  <th className="px-4 py-2 font-medium">Date</th>
                  <th className="px-4 py-2 font-medium text-right">Given</th>
                  <th className="px-4 py-2 font-medium text-right">Recovered</th>
                  <th className="px-4 py-2 font-medium text-right">Outstanding</th>
                  <th className="px-4 py-2 font-medium">How</th>
                  <th className="px-4 py-2 font-medium" />
                </tr>
              </thead>
              <tbody>
                {pay.advances.map((a) => (
                  <tr key={a.id} className="border-t border-hairline hover:bg-raised/60">
                    <td className="px-4 py-2 tabular-nums">
                      {shortDate(a.paidOn)}
                      {a.voidedAt && <span className="ml-2 text-xs text-ink-muted">struck out</span>}
                    </td>
                    <td className="px-4 py-2 text-right tabular-nums">{money(a.amount, pay.currency)}</td>
                    <td className="px-4 py-2 text-right tabular-nums">
                      {a.recovered > 0 ? money(a.recovered, pay.currency) : "—"}
                    </td>
                    <td className="px-4 py-2 text-right tabular-nums">
                      {a.voidedAt ? "—" : money(a.outstanding, pay.currency)}
                    </td>
                    <td className="px-4 py-2 text-ink-secondary">
                      {a.modeLabel}
                      {a.reference ? ` · ${a.reference}` : ""}
                    </td>
                    <td className="px-4 py-2 text-right">
                      {!a.voidedAt && a.recovered === 0 && (
                        <button
                          type="button"
                          disabled={busy}
                          onClick={() => onVoidAdvance(a.id)}
                          className="text-danger hover:underline disabled:opacity-60"
                        >
                          Strike out
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </section>
  );
}
