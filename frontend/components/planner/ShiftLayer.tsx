"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ds/Button";
import { ErrorNotice } from "@/components/ErrorNotice";
import { api, toApiError, type ApiError, type ShiftInput, type ShiftView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { longDate } from "@/lib/format";
import {
  SHIFT_FORM,
  ShiftFields,
  movedUnderRoster,
  readShiftForm,
  type ShiftFormValues,
} from "@/app/volunteers/shift-form";

/**
 * A seva shift for one meal, posted or corrected over the planner (T-019, rebuilt by T-155).
 *
 * <p>The planner shows a meal short of hands and offers to ask for volunteers. This is where that
 * offer opens: over the day, not away from it, and closing onto the same day and the same meal,
 * scrolled where the reader left it. Nothing navigates — a day being planned is work in progress, and
 * a trip to another screen and back would throw away the scroll position and everything the day's
 * blocks hold open (the language a job card is set to print in, a correction half typed).
 *
 * <h2>The volunteers' own form, not a copy of it</h2>
 *
 * <p>T-019 built this with three fields of its own — start, end, how many — because the design
 * system then said a form of four fields or more must be a screen, and the shift form has eight.
 * Rajeev restated the rule on 2026-09-12 (DESIGN_SYSTEM v1.8 §4): <em>a record from another part of
 * the app opens as a layer showing that record's own full form, never a cut-down copy, so the two
 * cannot drift apart.</em> A shift is the volunteers' record and the planner only borrows it, so this
 * layer now renders `ShiftFields` — the same component `/volunteers/new` and `/volunteers/[id]/edit`
 * render — and reads it with the same `readShiftForm`.
 *
 * <p>That removed two things the copy had got wrong without anybody deciding it. It never said
 * "Ends the next day" for a shift typed as 20:00 to 02:00, because T-146 taught that to the real form
 * and the copy did not hear about it. And it could not set a location, a description or reminders at
 * all, so a planner had to leave for the volunteers screen to finish the shift they had just raised.
 *
 * <h2>What the planner fills in, and what it adds</h2>
 *
 * <ul>
 *   <li><strong>Fixed</strong>: the date. It is the meal's day, shown read-only on a new shift and
 *       on a correction alike — Rajeev, 2026-09-12: <em>"Should be restricted to the day of the meal
 *       plan and read only."</em> `ShiftFields` takes it as `fixedDate`, which only this layer passes.</li>
 *   <li><strong>Prefilled, and still editable</strong>, on a new shift: the title
 *       ("Lunch preparation on Tuesday, 1 September 2026"), the capacity (how many hands the meal is short, floored at one) and the end time (the meal's
 *       ready-by — the crew is wanted up to the moment the food goes out, which is right often
 *       enough to offer and wrong often enough to leave editable). Start, location and description
 *       open empty and reminders open on the form's own default, as they do on the volunteers
 *       screen.</li>
 *   <li><strong>Added, never asked</strong>: the meal link — `mealDate`, `mealKind` and
 *       `mealEventName` (D-14). It is the reason this affordance exists: the shift counts toward
 *       <em>this</em> lunch rather than whatever the clock happened to catch. It is sent on a
 *       correction too, and that matters: `updateShift` replaces the whole shift, and a save without
 *       the link <em>takes it off</em> (`ShiftMealLinkIT.anEditCanUnlinkAShift`).</li>
 * </ul>
 *
 * <h2>A correction that moves people's shift says so</h2>
 *
 * <p>Saving a new start or end time on a shift somebody has signed up for moves their reminders and
 * tells them nothing. The volunteers edit screen has always warned about that; this layer warns in
 * the same words, under the same rule (`movedUnderRoster`), with the same `MovedNotice` — Rajeev,
 * 2026-09-12: <em>"They need to know what their actions are resulting in. Cant be silent about
 * it."</em> The layer closes on save, so it hands the shift's id to the planner, which shows the
 * warning in that meal's block. With the date fixed, only a change of times can set it off here.
 */
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
  /** The planner day this was opened from; a new shift opens on it and is linked to it. */
  date: string;
  mealKind: string;
  /** The event's own name where the meal is one, and null everywhere else. Part of the link. */
  mealEventName: string | null;
  /** "HH:mm:ss" — what a new shift's end time opens on. */
  readyBy: string;
  /** How many hands the meal is short, floored at one. What a new shift's capacity opens on. */
  suggestedCapacity: number;
  /** The shift being viewed and corrected, or null when one is being posted. */
  shift: ShiftView | null;
  onClose: () => void;
  /**
   * Saved. The planner re-reads its crew and its shifts; nothing about the address changes. Handed
   * the shift's id when the save moved it under a roster, so the planner can warn, and null otherwise.
   */
  onSaved: (movedShiftId: string | null) => void;
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

  // A shift already raised opens on its own values, title included — re-deriving the title would
  // rename a shift somebody had deliberately retitled. A new one opens on what the planner knows.
  const values: ShiftFormValues = shift ?? {
    title: derivedTitle(mealKind, mealEventName, date),
    shiftDate: date,
    endTime: readyBy,
    capacity: suggestedCapacity,
  };
  const meal = mealEventName || mealKind;

  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const input: ShiftInput = {
      ...readShiftForm(new FormData(event.currentTarget)),
      // D-14. All three move together — the server refuses half a link — and they are sent on a
      // correction as well as on a new shift, because a save without them unlinks the shift.
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
      onSaved(shift && movedUnderRoster(shift, input) ? shift.id : null);
    } catch (e) {
      setError(
        toApiError(e, shift ? "We couldn’t save that change." : "We couldn’t post that shift.")
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
      <div className="modal m-auto w-full max-w-prose">
        {/* The volunteers screen's own header, carried into the layer: the task, one line saying
            whose record this is, and `[Cancel] [Primary]` top right with no second copy at the foot
            (§4, rules 3, 4 and 6). The same words as that screen, because it is that screen. */}
        <header className="flex flex-wrap items-start justify-between gap-4 border-b border-hairline px-8 py-4">
          <div className="min-w-0">
            <h2 id="shift-layer-title" className="text-xl font-semibold text-ink">
              {shift ? "Edit a shift" : "Post a shift"}
            </h2>
            {/* The meal it is for and the day, said once — the link is not a field, so this is the
                only place a planner who opened the wrong block can see that they did. */}
            <p className="mt-0.5 text-sm text-ink-secondary">
              For {meal} · {longDate(date)}
            </p>
          </div>
          <div className="flex flex-none gap-2">
            <Button variant="secondary" onClick={onClose} disabled={busy}>
              Cancel
            </Button>
            <Button type="submit" form={SHIFT_FORM} busy={busy}>
              {shift ? "Save changes" : "Post shift"}
            </Button>
          </div>
        </header>
        <div className="grid gap-6 px-8 pb-8 pt-6">
          {error && <ErrorNotice error={error} />}
          <ShiftFields shift={values} editing={shift !== null} fixedDate={date} onSubmit={save} />
        </div>
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
