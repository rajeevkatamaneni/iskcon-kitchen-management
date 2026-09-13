"use client";

import { useCallback, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Loading } from "@/components/Loading";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { api, toApiError, type ApiError, type ShiftInput } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { ShiftFields, SHIFT_FORM, movedUnderRoster, readShiftForm } from "../../shift-form";

/**
 * Correct a shift — the same eight fields as posting one, so the same shape of screen.
 *
 * <p>Saving reschedules the reminders of everyone already on the roster, and tells them nothing.
 * When the save moves the date or the time on a shift people have claimed, the list this returns to
 * says so and points at the broadcast on the roster page, where the admin writes the words himself.
 */
export default function EditShiftPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <EditShiftView />
    </RequireRole>
  );
}

function EditShiftView() {
  const id = useParams<{ id: string }>().id;
  const { getToken } = useAuth();
  const router = useRouter();
  const { data: shift, error: loadError, loading } = useAuthedQuery(
    useCallback((t: string | undefined) => api.getShift(id, t), [id])
  );

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!shift) return;
    const input: ShiftInput = {
      ...readShiftForm(new FormData(event.currentTarget)),
      // The meal link (D-14), carried through as it is stored, because this screen has no box for
      // it and `PUT /shifts/{id}` replaces the whole shift: an update without the three fields takes
      // the link off (`ShiftMealLinkIT.anEditCanUnlinkAShift`). Without this, retitling a shift
      // raised from the planner quietly stopped it counting toward its meal and dropped it from the
      // planner (T-158).
      //
      // Sent as the stored values, never derived from the form. So an unlinked shift sends three
      // nulls and stays unlinked, which is exactly what the server's all-or-nothing check accepts;
      // and a shift moved to another date keeps its meal, as the planner's own layer does (T-155) —
      // a crew for Sunday's feast can be wanted on Saturday, and the link is what says so. Taking a
      // link off is not something this screen offers; the API still allows it.
      mealDate: shift.mealDate ?? null,
      mealKind: shift.mealKind ?? null,
      mealEventName: shift.mealEventName ?? null,
    };
    setBusy(true);
    setError(null);
    try {
      await api.updateShift(shift.id, input, await getToken());
      const warn = movedUnderRoster(shift, input) ? `&moved=${shift.id}` : "";
      router.push(`/volunteers?saved=${encodeURIComponent(input.title)}${warn}`);
    } catch (e) {
      setError(toApiError(e, "We couldn’t save that change."));
      setBusy(false);
    }
  }

  return (
    <FocusScreen
      task="Edit a shift"
      who={shift ? whoLine(shift.title, shift.signedUpCount) : undefined}
      activeHref="/volunteers"
      actions={
        <>
          <ButtonLink href="/volunteers" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={SHIFT_FORM} disabled={busy || !shift}>
            Save changes
          </Button>
        </>
      }
    >
      {error && <ErrorNotice error={error} />}
      {loading ? (
        <Loading label="Loading the shift…" />
      ) : loadError ? (
        <ErrorNotice error={loadError} />
      ) : shift ? (
        <ShiftFields shift={shift} editing onSubmit={save} />
      ) : null}
    </FocusScreen>
  );
}

/** Rule 3: one line under the task saying whose record this is. Here, which shift and who is on it. */
function whoLine(title: string, signedUp: number): string {
  if (signedUp === 0) return `${title} · nobody has signed up yet`;
  return `${title} · ${signedUp} volunteer${signedUp === 1 ? "" : "s"} signed up`;
}
