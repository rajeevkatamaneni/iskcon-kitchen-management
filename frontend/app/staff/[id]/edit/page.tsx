"use client";

import { useCallback, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Loading } from "@/components/Loading";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { STAFF_FORM_ID, StaffForm, readStaffForm, stripHireOnly } from "@/components/staff/StaffForm";
import { StaffNotFound } from "@/components/staff/StaffNotFound";
import { whoLine } from "@/components/staff/labels";
import { useStaffRecord } from "@/components/staff/use-staff-record";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { api, toApiError, type ApiError, type StaffProfileView } from "@/lib/api";

/**
 * Updating one person's record (E6-S8).
 *
 * <p>Its own screen since 2026-08-21, and the reason current staff have no separate `View` action
 * (Q6): this <em>is</em> the whole record, in a form. A fourth button on the row would be a second
 * door to the same room.
 *
 * <p>No cross-temple check runs here and none must: correcting somebody's phone number is not a
 * hire. Re-hiring somebody is a hire, and that happens on `/staff/new`, where it is checked.
 */
export default function EditStaffPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <EditStaffScreen />
    </RequireRole>
  );
}

function EditStaffScreen() {
  const id = useParams<{ id: string }>().id;
  const router = useRouter();
  const { getToken } = useAuth();

  const { staff, pay, loading, error } = useStaffRecord(id);
  const titles = useAuthedQuery(useCallback((t: string | undefined) => api.jobTitles(t), []));

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [revealedPan, setRevealedPan] = useState<string | null>(null);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!staff) return;
    const input = stripHireOnly(readStaffForm(new FormData(event.currentTarget)));
    setBusy(true);
    setActionError(null);
    try {
      await api.updateStaffMember(staff.id, input, await getToken());
      router.push(`/staff?updated=${encodeURIComponent(input.fullName)}`);
    } catch (e) {
      setActionError(toApiError(e, "We couldn't save that change."));
    } finally {
      setBusy(false);
    }
  }

  async function revealPan(member: StaffProfileView) {
    setActionError(null);
    try {
      const { pan } = await api.revealStaffPan(member.id, await getToken());
      if (pan) setRevealedPan(pan);
    } catch (e) {
      setActionError(toApiError(e, "We couldn't read that PAN."));
    }
  }

  return (
    <FocusScreen
      task="Update staff"
      who={staff ? whoLine(staff) : undefined}
      activeHref="/staff"
      actions={
        <>
          <ButtonLink href="/staff" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={STAFF_FORM_ID} disabled={busy || !staff}>
            Save changes
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
      ) : (
        <StaffForm
          staff={staff}
          pay={pay}
          options={titles.data ?? []}
          devotees={[]}
          revealedPan={revealedPan}
          onRevealPan={revealPan}
          onSubmit={submit}
        />
      )}
    </FocusScreen>
  );
}
