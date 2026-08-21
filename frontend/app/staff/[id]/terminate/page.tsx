"use client";

import { useCallback, useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Loading } from "@/components/Loading";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { StaffNotFound } from "@/components/staff/StaffNotFound";
import { TERMINATE_FORM_ID, TerminateForm } from "@/components/staff/TerminateForm";
import { emptyToNull } from "@/components/staff/StaffForm";
import { whoLine } from "@/components/staff/labels";
import { useStaffRecord } from "@/components/staff/use-staff-record";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import {
  api,
  toApiError,
  type ApiError,
  type BanCategory,
  type EmploymentStatus,
  type StaffPaymentMode,
} from "@/lib/api";

/**
 * Ending somebody's employment (E6-S8), on a screen of its own.
 *
 * <p>This is the screen the whole focus-screen pattern was measured on. As a panel over the register
 * there was no scroll position where the person's name and the button that ends their employment
 * were both visible; the name is now in a sticky header beside the button.
 *
 * <p>Nobody is removed. The record, the shifts, the stock entries and the orders all stay exactly as
 * they are — what ends is the employment.
 */
export default function TerminateStaffPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <TerminateScreen />
    </RequireRole>
  );
}

function TerminateScreen() {
  const id = useParams<{ id: string }>().id;
  const router = useRouter();
  const { getToken } = useAuth();

  const { staff, pay, loading, error } = useStaffRecord(id);
  // The categories are fetched with the screen rather than when the option is ticked, so that
  // deciding to record something never waits on a request (B9).
  const banCategories = useAuthedQuery(
    useCallback((t: string | undefined) => api.banCategories(t), [])
  );

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);

  const employed = staff?.employmentStatus === "ACTIVE";

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!staff) return;
    const f = new FormData(event.currentTarget);
    setBusy(true);
    setActionError(null);

    try {
      // The settlement goes in first, and the order is deliberate. A payment refused for a missing
      // cheque number would otherwise leave somebody already terminated with the money unrecorded,
      // whereas a settlement recorded against an employment that then fails to end is still a true
      // record of money that changed hands, and the termination can simply be done again.
      const settlement = Number(f.get("settlementAmount") ?? "");
      if (settlement > 0) {
        await api.recordStaffPayment(
          staff.id,
          {
            paidOn: String(f.get("lastWorkingDay")),
            amount: settlement,
            mode: String(f.get("settlementMode") ?? "CASH") as StaffPaymentMode,
            reference: emptyToNull(String(f.get("settlementReference") ?? "")),
            purpose: "SETTLEMENT",
          },
          await getToken()
        );
      }

      await api.endEmployment(
        staff.id,
        {
          status: String(f.get("status")) as Exclude<EmploymentStatus, "ACTIVE">,
          lastWorkingDay: String(f.get("lastWorkingDay")),
          reason: emptyToNull(String(f.get("reason") ?? "")),
          revokeSignIn: f.get("revokeSignIn") === "on",
          // Only when the admin deliberately ticked the option (B9). Absent otherwise, which is the
          // ordinary case: most dismissals warn nobody.
          ban:
            f.get("raiseBan") === "on"
              ? {
                  category: String(f.get("banCategory")) as BanCategory,
                  account: String(f.get("banAccount") ?? "").trim(),
                }
              : null,
        },
        await getToken()
      );
      router.push(`/staff?terminated=${encodeURIComponent(staff.fullName)}`);
    } catch (e) {
      setActionError(toApiError(e, "We couldn't end that employment."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <FocusScreen
      task="Terminate employment"
      who={staff ? whoLine(staff) : undefined}
      activeHref="/staff"
      actions={
        <>
          <ButtonLink href="/staff" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" variant="danger" form={TERMINATE_FORM_ID} disabled={busy || !employed}>
            Terminate
          </Button>
        </>
      }
    >
      {actionError && <ErrorNotice error={actionError} />}

      {loading ? (
        <Loading label="Loading the record…" />
      ) : error ? (
        <ErrorNotice error={error} />
      ) : !staff ? (
        <StaffNotFound />
      ) : !employed ? (
        // Reached by a stale link or a typed address. The API refuses it too, but being told after
        // filling the form in is being told too late.
        <InlineNotice tone="warning" title="Their employment has already ended">
          <Link href={`/staff/${staff.id}`} className="underline">
            See their record
          </Link>
          .
        </InlineNotice>
      ) : (
        <TerminateForm
          staff={staff}
          pay={pay}
          banCategories={banCategories.data ?? []}
          onSubmit={submit}
        />
      )}
    </FocusScreen>
  );
}
