"use client";

import { useCallback, useState } from "react";
import { useRouter } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { BanFindings } from "@/components/staff/Ban";
import { STAFF_FORM_ID, StaffForm, readStaffForm } from "@/components/staff/StaffForm";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { api, toApiError, type ApiError, type BanFinding, type HireStaffInput } from "@/lib/api";

/**
 * Hiring somebody (E6-S8), and the cross-temple check that runs as part of it (B9).
 *
 * <p>Its own screen since 2026-08-21. It was a panel that opened above the register, so the form
 * that decides who works at this temple was read over the top of a table of everybody who already
 * does, and the person filling it in lost the heading before they reached the button.
 *
 * <p>This is the only door into a temple's own roles. Devotees register themselves and hold one role
 * by definition; being hired is how anyone comes to hold more.
 */
export default function HireStaffPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <HireScreen />
    </RequireRole>
  );
}

function HireScreen() {
  const router = useRouter();
  const { getToken } = useAuth();

  const titles = useAuthedQuery(useCallback((t: string | undefined) => api.jobTitles(t), []));
  const devotees = useAuthedQuery(
    useCallback((t: string | undefined) => api.listUsers(t, "VOLUNTEER"), [])
  );

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);

  // What the check at hire found, held here until the admin answers it (B9). Its presence means the
  // hire has not happened yet — not that it was refused. Nothing about a finding blocks anything.
  const [findings, setFindings] = useState<{
    checkId: string;
    findings: BanFinding[];
    input: HireStaffInput;
  } | null>(null);

  /**
   * Hiring, and the check that runs as part of it (B9).
   *
   * <p>A hire has two successful outcomes, not one. Either the person is taken on, or there is
   * something another temple recorded that this admin should read first — which is not an error and
   * must not be rendered as one. Sending the same details again with the check's id is the decision
   * to go ahead, and that is what the second call here is.
   */
  async function attemptHire(input: HireStaffInput) {
    setBusy(true);
    setActionError(null);
    try {
      const outcome = await api.hireStaff(input, await getToken());
      if (outcome.checkId && outcome.findings && outcome.findings.length > 0) {
        setFindings({ checkId: outcome.checkId, findings: outcome.findings, input });
        return;
      }
      // Back to the register the way every one of these screens ends, with the confirmation waiting
      // there rather than on a screen that is about to close.
      router.push(`/staff?hired=${encodeURIComponent(input.fullName)}`);
    } catch (e) {
      setActionError(toApiError(e, "We couldn’t add that staff member."));
    } finally {
      setBusy(false);
    }
  }

  /** The admin read the findings, and is taking the person on regardless. A legitimate answer. */
  async function hireAnyway() {
    if (!findings) return;
    await attemptHire({ ...findings.input, acknowledgedBanCheckId: findings.checkId });
  }

  /**
   * The admin read the findings and is not going ahead. Recorded, because walking away is a decision
   * and the log would otherwise show only the hires that happened.
   */
  async function doNotHire() {
    if (!findings) return;
    const checkId = findings.checkId;
    setFindings(null);
    setBusy(true);
    try {
      await api.abandonHireCheck(checkId, await getToken());
      router.push("/staff");
    } catch (e) {
      setActionError(toApiError(e, "We couldn’t record that decision."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <FocusScreen
      task="Hire someone"
      who="Somebody joining your temple’s staff"
      activeHref="/staff"
      actions={
        <>
          <ButtonLink href="/staff" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={STAFF_FORM_ID} disabled={busy}>
            Hire
          </Button>
        </>
      }
    >
      {actionError && <ErrorNotice error={actionError} />}

      {/* Above the form, because it has to be read before the button is pressed again (B9). */}
      {findings && (
        <BanFindings findings={findings.findings} busy={busy} onProceed={hireAnyway} onStop={doNotHire} />
      )}

      <StaffForm
        staff={null}
        pay={null}
        options={titles.data ?? []}
        devotees={(devotees.data ?? []).filter((d) => d.status === "ACTIVE")}
        onSubmit={(event) => {
          event.preventDefault();
          void attemptHire(readStaffForm(new FormData(event.currentTarget)));
        }}
      />
    </FocusScreen>
  );
}
