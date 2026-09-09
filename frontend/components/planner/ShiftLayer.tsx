"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { ErrorNotice } from "@/components/ErrorNotice";
import { api, toApiError, type ApiError, type ShiftInput, type ShiftView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { hhmm, longDate } from "@/lib/format";

/**
 * Raising a seva shift for one meal, over the planner, and correcting the one already raised.
 *
 * <p>The planner shows a meal short of hands and, until now, offered nothing to do about it. This is
 * that offer: it opens where the shortfall is drawn, it is posted, and it closes onto the same day
 * and the same meal, scrolled where the reader left it. Nothing navigates, which is the whole point
 * — a day being planned is work in progress, not a list you were passing through, and a trip to
 * another screen and back would throw away the scroll position and everything the day's blocks hold
 * open (the language a job card is set to print in, a correction half typed).
 *
 * <h2>Why this is a layer when the volunteers' own form is a screen</h2>
 *
 * <p>The design system's rule is arithmetic and deliberately not a judgement: <em>a form of four
 * fields or more becomes a screen; three or fewer stays inline</em>. `app/volunteers/new/page.tsx`
 * says so about itself in one line — <em>"Post a shift — eight fields, so a screen of its own"</em>
 * — and it became that screen on 2026-08-21 for exactly this reason.
 *
 * <p>So eight fields could not come here. What comes here instead is <strong>three</strong>: when
 * the crew starts, when it is done, and how many people. Everything else the form asks a stranger,
 * the planner already knows and this layer derives rather than asks:
 *
 * <ul>
 *   <li><strong>Title</strong> — "Lunch preparation on Tuesday, 1 September 2026", built from the
 *       meal and its day. A shift raised from a meal has no other name worth typing, and a
 *       volunteer reading the list needs the day in the title far more than the planner needs to
 *       compose one.</li>
 *   <li><strong>Date</strong> — the day whose planner this is.</li>
 *   <li><strong>End time</strong> is <em>offered</em> as the meal's ready-by rather than fixed at
 *       it: the crew is wanted up to the moment the food goes out, which is right often enough to
 *       be a good default and wrong often enough that it must stay editable. That is why it is one
 *       of the three fields and not a fourth derived fact.</li>
 *   <li><strong>The meal link</strong> — `mealDate`, `mealKind` and `mealEventName` (D-14), taken
 *       from the meal this was opened from. This is the reason the affordance is worth having at
 *       all: the shift counts toward <em>this</em> lunch, not toward whatever the clock happened to
 *       catch.</li>
 * </ul>
 *
 * <p><strong>Location, description and reminders are not asked here and are never blanked.</strong>
 * `updateShift` replaces the whole shift, so a three-field save that sent empties would quietly wipe
 * a location somebody had typed on the volunteers screen. They are carried through from the shift
 * being corrected, and on a new shift they take the same defaults the full form takes — no location,
 * no description, one reminder a day before. The link at the foot says where to set them, which is
 * where they already live; there is no second copy of that form.
 */

/** The default `readShiftForm` applies when the reminders box is left alone: one day before. */
const DEFAULT_REMINDER_MINUTES = 1440;

const FIELD = "min-h-touch rounded-control border border-hairline px-3";

export function ShiftLayer({
  date,
  mealKind,
  mealEventName,
  readyBy,
  suggestedCapacity,
  shift,
  onClose,
  onSaved,
}: {
  /** The planner day this was opened from; the shift is posted for it and linked to it. */
  date: string;
  mealKind: string;
  /** The event's own name where the meal is one, and null everywhere else. Part of the link. */
  mealEventName: string | null;
  /** "HH:mm:ss" — what the end time is offered as. */
  readyBy: string;
  /** How many hands the meal is short, floored at one. What the capacity box opens on. */
  suggestedCapacity: number;
  /** The shift being corrected, or null when one is being raised. */
  shift: ShiftView | null;
  onClose: () => void;
  /** Saved. The planner re-reads its crew and its shifts; nothing about the address changes. */
  onSaved: () => void;
}) {
  const { getToken } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  // Escape closes, as it does over any panel covering what somebody was reading. The backdrop
  // deliberately does not: this commits, and a stray click beside a half-typed form must not throw
  // it away. That is the same split the meal-kind and vendor dialogs make, and the reason the way
  // out of this one is called Cancel rather than Close.
  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") onClose();
    }
    document.addEventListener("keydown", onKey);
    // The planner behind must not scroll under the layer — the place the reader is coming back to
    // is the place they left, and it moving while they were away is the one thing this whole
    // arrangement exists to prevent.
    const previous = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = previous;
    };
  }, [onClose]);

  // A shift already raised keeps the name it was given. Re-deriving it on every save would rename a
  // shift somebody had deliberately retitled, from a form that never showed them the title.
  const title = shift ? shift.title : derivedTitle(mealKind, mealEventName, date);
  const meal = mealEventName || mealKind;

  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const input: ShiftInput = {
      title,
      // Carried, not asked, and never sent as an empty string — see the note above `updateShift`
      // replacing the whole shift.
      description: shift?.description ?? null,
      shiftDate: date,
      startTime: String(form.get("startTime") ?? ""),
      endTime: String(form.get("endTime") ?? ""),
      location: shift?.location ?? null,
      capacity: Number(form.get("capacity") ?? 1),
      reminderOffsetsMinutes:
        shift?.reminderOffsetsMinutes && shift.reminderOffsetsMinutes.length > 0
          ? shift.reminderOffsetsMinutes
          : [DEFAULT_REMINDER_MINUTES],
      // D-14, and the reason this affordance is here rather than on the volunteers screen. All
      // three move together; the server refuses a half-filled link.
      mealDate: date,
      mealKind,
      mealEventName: mealEventName ?? null,
    };

    setBusy(true);
    setError(null);
    try {
      const token = await getToken();
      if (shift) {
        await api.updateShift(shift.id, input, token);
      } else {
        await api.createShift(input, token);
      }
      onSaved();
    } catch (e) {
      setError(
        toApiError(e, shift ? "We couldn’t save that shift." : "We couldn’t post that shift.")
      );
      setBusy(false);
    }
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="shift-layer-title"
      className="fixed inset-0 z-50 flex animate-scrim-in items-start justify-center overflow-y-auto bg-ink/40 p-4 backdrop-blur-surface sm:p-8"
    >
      <div className="modal m-auto w-full max-w-prose px-8 py-7">
        <h2 id="shift-layer-title" className="text-lg">
          {shift ? "Change this shift" : "Ask for volunteers"}
        </h2>
        {/* The meal and the day, said once, at the top — because everything below is derived from
            them and a planner who opened the wrong block must be able to see that immediately. */}
        <p className="mt-1 text-sm text-ink-secondary">
          {meal} · {longDate(date)}
        </p>

        <form className="mt-5 grid gap-5" aria-label={shift ? "Change a shift" : "Ask for volunteers"} onSubmit={save}>
          {/* The title is shown and not typed. Volunteers see this sentence in their list, so it is
              worth reading before pressing, and it is the answer to "what will they be told". */}
          <p className="text-sm text-ink-secondary">
            Volunteers will see this as{" "}
            <span className="font-medium text-ink">“{title}”</span>
          </p>

          <div className="grid grid-cols-2 gap-4">
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Start</span>
              <input
                name="startTime"
                type="time"
                required
                defaultValue={hhmm(shift?.startTime)}
                className={FIELD}
              />
            </label>
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">End</span>
              <input
                name="endTime"
                type="time"
                required
                defaultValue={hhmm(shift?.endTime ?? readyBy)}
                className={FIELD}
              />
            </label>
            {/* "Capacity" on the volunteers form, because that form is about a shift. Here it is
                about a shortfall, and the number it opens on is that shortfall, so it says what the
                number means rather than what the column is called. */}
            <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">How many volunteers</span>
              <input
                name="capacity"
                type="number"
                min="1"
                required
                defaultValue={shift?.capacity ?? suggestedCapacity}
                className={FIELD}
              />
            </label>
          </div>

          {error && <ErrorNotice error={error} />}

          <div className="flex flex-wrap items-center justify-end gap-3">
            {/* Everything this layer does not ask stays where it already is, one link away, rather
                than being copied into a second form that would then have to be kept agreeing with
                the first. Offered only once there is a shift to open. */}
            {shift && (
              <ButtonLink
                href={`/volunteers/${shift.id}/edit`}
                variant="ghost"
                size="sm"
                className="mr-auto"
              >
                Location, notes and reminders
              </ButtonLink>
            )}
            <Button type="button" variant="secondary" onClick={onClose} disabled={busy}>
              Cancel
            </Button>
            <Button type="submit" busy={busy}>
              {shift ? "Save shift" : "Post shift"}
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
}

/**
 * "Lunch preparation on Tuesday, 1 September 2026" — what a volunteer scrolling a list of shifts
 * needs to be able to tell apart at a glance.
 *
 * <p>Day-first with the month spelled out, which is how this application writes every date
 * (`longDate`); the build row's example wrote it month-first, and one screen writing dates the
 * American way while every other writes them the Indian way is the drift that formatter exists to
 * stop.
 *
 * <p>An event is named by its own name, as it is everywhere else on the planner: "Children's
 * Bhagavad-gita Reading preparation on …", not "Event preparation on …".
 */
export function derivedTitle(mealKind: string, mealEventName: string | null, date: string): string {
  return `${mealEventName || mealKind} preparation on ${longDate(date)}`;
}
