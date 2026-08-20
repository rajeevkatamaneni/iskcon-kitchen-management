"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useMemo, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { Loading } from "@/components/Loading";
import { RequireRole } from "@/components/RequireRole";
import { PayPanel } from "@/components/staff/PayPanel";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { api, toApiError, type ApiError, type StaffPaymentMode } from "@/lib/api";

/**
 * Paying one member of staff (B8).
 *
 * <p>Its own page since 2026-08-20. It was a panel that opened above the register, which put the
 * salary, two forms and every payment ever made to somebody on top of a table of everyone else —
 * and the history is long enough that the register it belonged to scrolled away regardless. Paying
 * a person is one person's business, so it is now one person's screen, reached from their row and
 * left by the link at the top left. Nothing else is offered: no hiring, no register, no Close.
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

  const pay = useAuthedQuery(useCallback((t: string | undefined) => api.staffPay(id, t), [id]));

  // The register, for the two things the pay record does not carry: what this person's job is
  // called, and whether they still work here — which decides whether a payment is salary or a
  // settlement. Fetched from the register rather than from `getStaffProfile`, because that endpoint
  // sits behind MANAGE_STAFF_SCHEDULE and would make this page depend on a second permission it has
  // no other use for. The register is also what tells us an id belongs to nobody at all.
  const register = useAuthedQuery(useCallback((t: string | undefined) => api.staffRegister(t), []));
  const staff = useMemo(
    () =>
      [...(register.data?.current ?? []), ...(register.data?.former ?? [])].find((s) => s.id === id) ??
      null,
    [register.data, id]
  );

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);

  async function run(mutation: (t: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    setActionError(null);
    try {
      await mutation(await getToken());
      // A payment, an advance or a strike all move the figures on this screen, so it is refreshed
      // rather than left showing what was true a moment ago. The register is untouched by any of
      // them and is not re-fetched.
      pay.reload();
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
    const deductions = (pay.data?.advances ?? [])
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
      "We couldn't record that payment."
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
      "We couldn't record that advance."
    );
    if (ok) form.reset();
  }

  const loadError = pay.error ?? register.error;
  const loading = (pay.loading && !pay.data) || (register.loading && !register.data);

  return (
    <div className="flex min-h-screen">
      {/* The register is still where this person came from, so the menu goes on saying so. */}
      <Sidebar activeHref="/staff" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <Link href="/staff" className="text-sm text-accent-text hover:underline">
            ← Back to staff
          </Link>

          <header className="mb-6 mt-3">
            <h1>Pay</h1>
            {/* Whose pay, immediately beneath the title. "Pay" on its own is a screen an
                administrator could act on believing it belongs to the person above or below in the
                register, and a payment recorded against the wrong person is not a mistake this app
                can undo — it can only be struck out and typed again. The job title is there for the
                same reason: two devotees at one temple can share a name. */}
            {staff && (
              <p className="mt-1 text-ink-secondary">
                {staff.fullName} · {staff.jobTitleLabel}
                {staff.employmentStatus !== "ACTIVE" &&
                  ` · no longer employed (last day ${staff.lastWorkingDay})`}
              </p>
            )}
          </header>

          {actionError && (
            <div className="mb-6">
              <ErrorNotice error={actionError} />
            </div>
          )}

          {loading ? (
            <Loading label="Loading pay…" />
          ) : loadError ? (
            <ErrorNotice error={loadError} />
          ) : !staff || !pay.data ? (
            // An id that belongs to nobody. Said plainly rather than shown as a failure, because
            // nothing went wrong: the likeliest cause is a stale link to somebody another
            // administrator has since removed, or an address typed by hand.
            <InlineNotice tone="warning" title="We can't find that person">
              Nobody on your temple&rsquo;s staff register has this record. They may have been
              removed, or the address may be wrong.{" "}
              <Link href="/staff" className="underline">
                Go back to staff
              </Link>
              .
            </InlineNotice>
          ) : (
            <PayPanel
              pay={pay.data}
              busy={busy}
              onSubmitPayment={submitPayment}
              onSubmitAdvance={submitAdvance}
              onVoidPayment={(paymentId) =>
                run(
                  (t) => api.voidStaffPayment(staff.id, paymentId, t),
                  "We couldn't strike that payment."
                )
              }
              onVoidAdvance={(advanceId) =>
                run(
                  (t) => api.voidStaffAdvance(staff.id, advanceId, t),
                  "We couldn't strike that advance."
                )
              }
            />
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
