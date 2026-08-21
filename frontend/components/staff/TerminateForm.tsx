"use client";

import { useState } from "react";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { money, shortDate } from "@/lib/format";
import { BanOnTermination } from "./Ban";
import { PAYMENT_MODES } from "./PayPanel";
import type { BanCategoryOption, EmploymentStatus, StaffPayView, StaffProfileView } from "@/lib/api";

/**
 * Ending somebody's employment (E6-S8), and the record it may raise (B9).
 *
 * <p>The screen this sits in is the reason the pattern exists at all. Measured on live while it was
 * still a panel over the register: the heading sat at 180px, the Terminate button ended at 1232px,
 * and the window was 836px tall — there was no scroll position where the name of the person and the
 * button that ends their employment were both visible. The commit button is now in a sticky header
 * beside their name, and this form carries no buttons of its own.
 *
 * <p>What we know about their money is stated and nothing beyond it. The advance balance is
 * arithmetic and is given flatly; what is owed in salary is not, so the last payment and the leaving
 * date are put side by side and the conclusion is left to the person signing it off.
 */

/** What the header's Terminate button points at. */
export const TERMINATE_FORM_ID = "terminate-form";

const FIELD = "min-h-touch rounded border border-hairline bg-canvas px-3";

export function TerminateForm({
  staff,
  pay,
  banCategories,
  onSubmit,
}: {
  staff: StaffProfileView;
  /** Null for the moment before the pay request lands. */
  pay: StaffPayView | null;
  banCategories: BanCategoryOption[];
  onSubmit: (e: React.FormEvent<HTMLFormElement>) => void;
}) {
  // A dismissal defaults to taking the sign-in away; a resignation does not. Someone who resigns is
  // still a devotee of this temple and should go on signing in as one.
  const [status, setStatus] = useState<Exclude<EmploymentStatus, "ACTIVE">>("RESIGNED");
  // Held so the sentence beneath the figures can name the day they are leaving beside the day they
  // were last paid. The gap between the two is the thing an admin is actually deciding about.
  const [lastDay, setLastDay] = useState("");

  const lastPaid = pay?.lastSalaryPayment ?? null;

  return (
    <>
      <InlineNotice tone="info">Their record and their work stay. Only their employment ends.</InlineNotice>

      <dl className="grid grid-cols-3 gap-4 rounded border border-hairline px-4 py-3 text-sm">
        <div>
          <dt className="text-ink-secondary">Monthly salary</dt>
          <dd className="mt-1 tabular-nums">
            {/* "No salary recorded" is a statement about the record and must not be made before the
                record has arrived; until then this is simply blank. */}
            {!pay ? (
              "—"
            ) : pay.monthlySalary !== null ? (
              money(pay.monthlySalary, pay.currency)
            ) : (
              <span className="text-ink-muted">No salary recorded</span>
            )}
          </dd>
        </div>
        <div>
          <dt className="text-ink-secondary">Cash advances outstanding</dt>
          <dd className="mt-1 tabular-nums">{pay ? money(pay.advanceBalance, pay.currency) : "—"}</dd>
        </div>
        <div>
          <dt className="text-ink-secondary">Last salary payment</dt>
          <dd className="mt-1 tabular-nums">
            {lastPaid ? (
              `${money(lastPaid.net, pay!.currency)} on ${shortDate(lastPaid.paidOn)}`
            ) : (
              <span className="text-ink-muted">None recorded</span>
            )}
          </dd>
        </div>
        {lastPaid && lastDay && (
          <div className="col-span-3 text-ink-secondary">
            <p>
              Last recorded payment {shortDate(lastPaid.paidOn)}, last working day{" "}
              {shortDate(lastDay)}.
            </p>
            <p>What is owed between them is yours to settle.</p>
          </div>
        )}
      </dl>

      <form
        id={TERMINATE_FORM_ID}
        className="grid grid-cols-2 gap-4"
        aria-label="Terminate employment"
        onSubmit={onSubmit}
      >
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">How did it end?</span>
          <select
            name="status"
            value={status}
            onChange={(e) => setStatus(e.target.value as Exclude<EmploymentStatus, "ACTIVE">)}
            className={FIELD}
          >
            <option value="RESIGNED">Resigned</option>
            <option value="CONTRACT_ENDED">Contract ended</option>
            <option value="TERMINATED">Dismissed</option>
          </select>
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Last working day</span>
          <input
            name="lastWorkingDay"
            type="date"
            required
            value={lastDay}
            onChange={(e) => setLastDay(e.target.value)}
            className={FIELD}
          />
        </label>

        <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Reason</span>
          <input name="reason" className={FIELD} />
        </label>

        {/* The settlement is typed, never worked out here. It is recorded as a payment on their last
            working day, so it lands in the same history as everything else they were paid. */}
        <fieldset className="col-span-2 grid grid-cols-3 gap-4 rounded border border-hairline px-4 py-3">
          <legend className="px-1 text-sm text-ink-secondary">
            Final settlement — leave blank if there is nothing to pay
          </legend>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Amount</span>
            <input
              name="settlementAmount"
              type="number"
              min="1"
              step="0.01"
              inputMode="decimal"
              className={FIELD}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Paid by</span>
            <select name="settlementMode" defaultValue="CASH" className={FIELD}>
              {PAYMENT_MODES.map((m) => (
                <option key={m.value} value={m.value}>
                  {m.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Reference</span>
            <input name="settlementReference" placeholder="Cheque number" className={FIELD} />
          </label>
        </fieldset>

        {staff.userId && (
          <label className="col-span-2 flex items-start gap-3 rounded border border-hairline px-4 py-3 text-sm">
            {/* Keyed so the default follows the reason rather than sticking at whatever loaded first. */}
            <input
              key={status}
              type="checkbox"
              name="revokeSignIn"
              defaultChecked={status === "TERMINATED"}
              className="mt-1"
            />
            <span>
              <span className="text-ink">Take their sign-in away entirely</span>
              <span className="block text-ink-secondary">
                They keep their devotee account unless you tick this.
              </span>
            </span>
          </label>
        )}

        {/* The gravest thing this screen offers (B9). Last, unticked, and deliberately not styled
            to invite a press — most dismissals raise nothing at all. */}
        <BanOnTermination categories={banCategories} />
      </form>
    </>
  );
}
