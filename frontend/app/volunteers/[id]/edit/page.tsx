"use client";

import { useCallback, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Loading } from "@/components/Loading";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { ConfirmLayer } from "@/app/planner/confirm-layer";
import { timesChanged, timesChangedWarning } from "@/components/planner/ShiftLayer";
import { api, toApiError, type ApiError, type UpdateShiftInput } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { ShiftFields, SHIFT_FORM, dateChangedUnderRoster, mealOfShift, readShiftForm } from "../../shift-form";

/**
 * Correct a shift — the same eight fields as posting one, so the same shape of screen. Saved at once:
 * there is no meal open here to wait for (D-27 answer 7).
 *
 * <p><strong>A shift for a meal</strong> (D-27 answer 4) shows its date and its meal as words, with a
 * link to the meal, and offers no way to change either or to turn it into a shift not for a meal. If it
 * is no longer for that meal, it is cancelled and a new one asked for from the right meal. The server
 * holds the same line, refusing a different date or meal with KMS-400153, which this screen shows
 * through the ordinary error notice should it ever come back. A shift not for a meal edits its date as
 * it always has.
 *
 * <p><strong>New times with people signed up</strong> (answer 6): before the save, the editor is told
 * how many volunteers are signed up and that they will be told the new times — the same sentence, from
 * the same function, as the planner says before *Update this meal*, so the two screens cannot drift into
 * two wordings. Their places are kept and the server sends each of them the approved `shift_broadcast`
 * after the save. The word is "times", never "moved".
 *
 * <p><strong>A new date with people signed up</strong> — only possible on a shift not for a meal — is the
 * other case, and it is told to nobody: the server sends nothing when the date changes, with or without
 * new times. So there is no warning before that save, which would promise a message that never goes, and
 * the list this returns to says afterwards that the volunteers have not been told (`MovedNotice`, through
 * `?moved=`), pointing at the roster page where the admin writes to them himself.
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
  // The edit held while the times warning is on screen. Held as what was read off the form, not as
  // the form itself, so pressing on sends exactly what was there when Save changes was pressed.
  const [pending, setPending] = useState<UpdateShiftInput | null>(null);

  const meal = shift ? mealOfShift(shift) : null;

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!shift) return;
    const read = readShiftForm(new FormData(event.currentTarget));
    const input: UpdateShiftInput = {
      ...read,
      // A meal shift has no date box; its date is its meal's and goes back exactly as it was read,
      // because the server refuses any other (KMS-400153).
      shiftDate: meal ? shift.shiftDate : read.shiftDate,
      // The meal link, sent back exactly as it was read (D-27). The server refuses a meal shift whose
      // `mealId` is changed or dropped (KMS-400153), and a plain shift given one (KMS-400001), so this
      // screen — which has no box for the link — returns what it was given. Before D-27 the link was
      // three copied fields and leaving them out quietly unlinked the shift (T-158); it is one id now,
      // and `UpdateShiftInput` makes it required so it cannot be left out at all.
      mealId: shift.mealId,
    };
    // A new date under a roster goes straight through, and the list says afterwards that nobody was
    // told. Checked first, because a new date with new times too is still a message nobody is sent.
    if (dateChangedUnderRoster(shift, input)) {
      void save(input, true);
      return;
    }
    // Otherwise, only a change to when people turn up, and only with somebody signed up, is worth
    // stopping for. The waitlist is not counted: the server tells the signed-up volunteers, not those
    // waiting.
    if (shift.signedUpCount > 0 && timesChanged(shift, input)) {
      setPending(input);
      return;
    }
    void save(input);
  }

  async function save(input: UpdateShiftInput, notTold = false) {
    if (!shift) return;
    setBusy(true);
    setError(null);
    try {
      await api.updateShift(shift.id, input, await getToken());
      const warn = notTold ? `&moved=${shift.id}` : "";
      router.push(`/volunteers?saved=${encodeURIComponent(input.title)}${warn}`);
    } catch (e) {
      setPending(null);
      setError(toApiError(e, "We couldn’t save that change."));
      setBusy(false);
    }
  }

  const dismiss = useCallback(() => setPending(null), []);

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
        <ShiftFields shift={shift} editing meal={meal ?? undefined} onSubmit={submit} />
      ) : null}

      {/* D-27 answer 6, on this screen: warned before the save, in the ruled words. */}
      {pending && shift && (
        <ConfirmLayer
          title="This shift has new times"
          confirmLabel="Save changes"
          dismissLabel="Go back"
          busy={busy}
          onDismiss={dismiss}
          onConfirm={() => void save(pending)}
        >
          <p>{timesChangedWarning(shift.signedUpCount)}</p>
        </ConfirmLayer>
      )}
    </FocusScreen>
  );
}

/** Rule 3: one line under the task saying whose record this is. Here, which shift and who is on it. */
function whoLine(title: string, signedUp: number): string {
  if (signedUp === 0) return `${title} · nobody has signed up yet`;
  return `${title} · ${signedUp} volunteer${signedUp === 1 ? "" : "s"} signed up`;
}
