"use client";

import { useCallback, useState } from "react";
import { DateRange } from "@/components/ds/DateRange";
import { useRouter } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { api, toApiError, type ApiError, type LeaveType } from "@/lib/api";
import { todayIso } from "@/lib/format";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * Record leave for somebody — six fields, so a screen rather than a panel.
 *
 * <p>It was a panel that opened over the queue with the queue's own tabs still showing behind it,
 * which read as though the form were one more thing on the list rather than the thing being done.
 *
 * <p>For staff with no app of their own. It is approved as it is recorded, because the person
 * recording it is the person who would have approved it.
 */

const FORM = "record-leave";
const FIELD = "min-h-touch rounded border border-hairline bg-canvas px-3";

const LEAVE_TYPES: { value: LeaveType; label: string }[] = [
  { value: "TIME_OFF", label: "Time off" },
  { value: "SICK", label: "Sick leave" },
  { value: "UNPAID", label: "Unpaid leave" },
];

export default function RecordLeavePage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER"]}>
      <RecordLeaveView />
    </RequireRole>
  );
}

function RecordLeaveView() {
  const { getToken } = useAuth();
  const router = useRouter();

  // The roster, not the staff register: the register is behind MANAGE_STAFF, which a kitchen
  // manager deliberately does not hold, and this week's grid already names every actively employed
  // person — which is exactly who can take leave.
  const roster = useAuthedQuery(useCallback((t: string | undefined) => api.staffWeek(mondayOfThisWeek(), t), []));
  const staff = roster.data?.staff ?? [];

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function record(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    const staffProfileId = String(f.get("staffProfileId") ?? "");
    setBusy(true);
    setError(null);
    try {
      await api.recordLeave(
        {
          staffProfileId,
          leaveType: String(f.get("leaveType") ?? "TIME_OFF") as LeaveType,
          fromDate: String(f.get("fromDate") ?? ""),
          toDate: String(f.get("toDate") ?? ""),
          halfDay: f.get("halfDay") === "on",
          reason: emptyToNull(String(f.get("reason") ?? "")),
        },
        await getToken()
      );
      const name = staff.find((s) => s.staffProfileId === staffProfileId)?.fullName ?? "";
      // Back to the queue on the tab this leave lands on, so it is on screen when it arrives.
      router.push(`/leave?tab=APPROVED&recorded=${encodeURIComponent(name)}`);
    } catch (e) {
      setError(toApiError(e, "We couldn’t record that leave."));
      setBusy(false);
    }
  }

  return (
    <FocusScreen
      task="Record leave for someone"
      who="Approved as you record it"
      activeHref="/leave"
      actions={
        <>
          <ButtonLink href="/leave" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={FORM} disabled={busy}>
            Record it
          </Button>
        </>
      }
    >
      {error && <ErrorNotice error={error} />}

      <form id={FORM} className="grid grid-cols-2 gap-4" aria-label="Record leave" onSubmit={record}>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Staff member</span>
          <select name="staffProfileId" required className={FIELD}>
            {staff.map((s) => (
              <option key={s.staffProfileId} value={s.staffProfileId}>
                {s.fullName}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Kind</span>
          <select name="leaveType" className={FIELD}>
            {LEAVE_TYPES.map((t) => (
              <option key={t.value} value={t.value}>
                {t.label}
              </option>
            ))}
          </select>
        </label>
        <DateRange
          from={{ name: "fromDate", label: "First day", defaultValue: todayIso(), required: true }}
          to={{ name: "toDate", label: "Last day", defaultValue: todayIso(), required: true }}
          className={FIELD}
        />
        <label className="col-span-2 flex min-h-touch items-center gap-2 text-sm text-ink-secondary">
          <input type="checkbox" name="halfDay" /> Half day
        </label>
        <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Note</span>
          <input name="reason" className={FIELD} />
        </label>
      </form>
    </FocusScreen>
  );
}

function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
}

function mondayOfThisWeek(): string {
  // From the temple's own day, not the device's — the roster week must not shift with the reader.
  const d = new Date(`${todayIso()}T00:00:00`);
  const day = d.getDay(); // 0 Sun … 6 Sat
  d.setDate(d.getDate() + (day === 0 ? -6 : 1 - day));
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const dd = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${dd}`;
}
