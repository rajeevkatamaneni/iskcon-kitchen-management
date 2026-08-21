"use client";

import { useParams } from "next/navigation";
import { useState } from "react";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Loading } from "@/components/Loading";
import { RequireRole } from "@/components/RequireRole";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { PayPanel } from "@/components/staff/PayPanel";
import { StaffNotFound } from "@/components/staff/StaffNotFound";
import { emptyToNull } from "@/components/staff/StaffForm";
import { whoLine } from "@/components/staff/labels";
import { useStaffRecord } from "@/components/staff/use-staff-record";
import { useAuth } from "@/lib/auth-context";
import { api, toApiError, type ApiError, type StaffPaymentMode } from "@/lib/api";

/**
 * Paying one member of staff (B8).
 *
 * <p>Its own page since 2026-08-20. It was a panel that opened above the register, which put the
 * salary, two forms and every payment ever made to somebody on top of a table of everyone else —
 * and the history is long enough that the register it belonged to scrolled away regardless. Paying
 * a person is one person's business, so it is one person's screen.
 *
 * <p>The back-link at its top left went on 2026-08-21. Every screen of this kind is left the same
 * way, by the Cancel in the header, and an arrow in the corner was a second answer to the same
 * question.
 *
 * <p>Gated to the temple administrator alone. Every endpoint behind this screen is `MANAGE_STAFF`,
 * which no other role holds — a kitchen manager who reached it would see a form that could only be
 * refused.
 */
export default function StaffPayPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <StaffPayScreen />
    </RequireRole>
  );
}

function StaffPayScreen() {
  const id = useParams<{ id: string }>().id;
  const { getToken } = useAuth();

  const { staff, pay, loading, error, reload } = useStaffRecord(id);

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);

  async function run(mutation: (t: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    setActionError(null);
    try {
      await mutation(await getToken());
      // A payment, an advance or a strike all move the figures on this screen, so it is refreshed
      // rather than left showing what was true a moment ago.
      reload();
      return true;
    } catch (e) {
      setActionError(toApiError(e, failure));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function submitPayment(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!staff) return;
    const form = event.currentTarget;
    const f = new FormData(form);

    // A blank or zero box against an advance means "not this one", not a deduction of nothing.
    const deductions = (pay?.advances ?? [])
      .filter((a) => !a.voidedAt && a.outstanding > 0)
      .map((a) => ({ advanceId: a.id, amount: Number(f.get(`deduct-${a.id}`) ?? "") }))
      .filter((d) => d.amount > 0);

    const ok = await run(
      (t) =>
        api.recordStaffPayment(
          staff.id,
          {
            paidOn: String(f.get("paidOn")),
            amount: Number(f.get("amount") ?? ""),
            mode: String(f.get("mode") ?? "CASH") as StaffPaymentMode,
            reference: emptyToNull(String(f.get("reference") ?? "")),
            // Somebody who has already left is not being paid for a month's work, so a payment
            // recorded against a former staff member is the settlement by elimination.
            purpose: staff.employmentStatus === "ACTIVE" ? "SALARY" : "SETTLEMENT",
            note: emptyToNull(String(f.get("note") ?? "")),
            deductions,
          },
          t
        ),
      "We couldn’t record that payment."
    );
    if (ok) form.reset();
  }

  async function submitAdvance(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!staff) return;
    const form = event.currentTarget;
    const f = new FormData(form);
    const ok = await run(
      (t) =>
        api.recordStaffAdvance(
          staff.id,
          {
            paidOn: String(f.get("advancePaidOn")),
            amount: Number(f.get("advanceAmount") ?? ""),
            mode: String(f.get("advanceMode") ?? "CASH") as "CHEQUE" | "CASH",
            reference: emptyToNull(String(f.get("advanceReference") ?? "")),
            note: emptyToNull(String(f.get("advanceNote") ?? "")),
          },
          t
        ),
      "We couldn’t record that advance."
    );
    if (ok) form.reset();
  }

  return (
    <FocusScreen
      task="Pay"
      // Whose pay, beside the title. "Pay" on its own is a screen an administrator could act on
      // believing it belongs to the person above or below in the register, and a payment recorded
      // against the wrong person is not a mistake this app can undo — it can only be struck out and
      // typed again.
      who={staff ? whoLine(staff) : undefined}
      activeHref="/staff"
      actions={
        <ButtonLink href="/staff" variant="secondary">
          Cancel
        </ButtonLink>
      }
    >
      {actionError && <ErrorNotice error={actionError} />}

      {loading ? (
        <Loading label="Loading pay…" />
      ) : error ? (
        <ErrorNotice error={error} />
      ) : !staff || !pay ? (
        <StaffNotFound />
      ) : (
        <PayPanel
          pay={pay}
          busy={busy}
          onSubmitPayment={submitPayment}
          onSubmitAdvance={submitAdvance}
          onVoidPayment={(paymentId) =>
            run((t) => api.voidStaffPayment(staff.id, paymentId, t), "We couldn’t strike that payment.")
          }
          onVoidAdvance={(advanceId) =>
            run((t) => api.voidStaffAdvance(staff.id, advanceId, t), "We couldn’t strike that advance.")
          }
        />
      )}
    </FocusScreen>
  );
}
