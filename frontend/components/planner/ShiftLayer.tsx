"use client";

import { useEffect } from "react";
import { Button } from "@/components/ds/Button";
import type { MealShiftDraft, ShiftView } from "@/lib/api";
import { longDate } from "@/lib/format";
import {
  SHIFT_FORM,
  ShiftFields,
  readShiftForm,
  type ShiftFormValues,
} from "@/app/volunteers/shift-form";

/**
 * The volunteer shift for one meal, drafted over the meal it is for (T-019, T-155, rebuilt by D-27).
 *
 * <p>It opens from section 4 of the meal composer — *Ask for volunteers* when the meal needs more
 * people than are rostered, *View volunteer shift* once there is one — and it closes back onto the
 * same form, scrolled where the planner left it.
 *
 * <h2>It saves nothing</h2>
 *
 * <p>Until D-27 this layer posted the shift the moment its button was pressed. Rajeev, 2026-09-13:
 * <em>"IF the user does the shift setup and all from the meal planner page and abandons the meal plan
 * with out saving, we should not be left with an orphan shift."</em> And, for a change to a shift that
 * already exists (answer 7): <em>"nothing saved until the meal is saved: Aggreed."</em> So the button
 * reads **Done**, pressing it hands the draft back to the composer, and the composer sends it with
 * *Save this meal* or *Update this meal*, where the server saves the meal and the shift in one
 * transaction. Walking away from the meal walks away from the shift with it. There is no API call in
 * this file, and a test holds it to that.
 *
 * <h2>The volunteers' own form, not a copy of it</h2>
 *
 * <p>DESIGN_SYSTEM v1.8 §4: a record from another part of the app opens as a layer showing that
 * record's own full form, so the two cannot drift apart. So this renders `ShiftFields`, the form
 * `/volunteers/new` and `/volunteers/[id]/edit` render, and reads it with the same `readShiftForm`.
 * What differs is only what the planner fixes:
 *
 * <ul>
 *   <li><strong>Fixed</strong>: the date, which is the meal's day, shown read-only. A meal shift's day
 *       is always its meal's (D-27 answer 4).</li>
 *   <li><strong>Not asked</strong>: which meal. There is no meal checkbox and no meal picker here — the
 *       shift is for the meal being saved, and the server links them when it saves.</li>
 *   <li><strong>Prefilled, and still editable</strong>, on a new shift: the title, *Volunteers
 *       requested* as People needed minus Rostered (answer 1), and the end time as the meal's
 *       ready-by. A shift already drafted or saved opens on its own values.</li>
 * </ul>
 *
 * <p>The escape hatch is Cancel, and the backdrop deliberately does not close it: a stray click beside
 * a half-typed form must not throw it away.
 */
export function ShiftLayer({
  date,
  mealKind,
  mealEventName,
  readyBy,
  suggestedCapacity,
  values,
  saved,
  onClose,
  onDone,
}: {
  /** The meal's day. The shift is on it, and it cannot be changed here. */
  date: string;
  mealKind: string;
  /** The event's own name where the meal is one, and null everywhere else. */
  mealEventName: string | null;
  /** "HH:mm" or "HH:mm:ss" — what a new shift's end time opens on. */
  readyBy: string;
  /** People needed minus Rostered. What a new shift's *Volunteers requested* opens on. */
  suggestedCapacity: number;
  /** What the form opens on: the draft in hand, or the meal's saved shift. Null for a new one. */
  values: ShiftFormValues | null;
  /**
   * Whether the meal already has a saved shift. It decides what the form announces itself as: a
   * drafted shift nobody has saved is still one being posted, however many times it is reopened.
   */
  saved: boolean;
  onClose: () => void;
  /** Done: the draft, for the composer to hold until the meal is saved. */
  onDone: (draft: MealShiftDraft) => void;
}) {
  // Escape closes, as it does over any panel covering what somebody was reading.
  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") onClose();
    }
    document.addEventListener("keydown", onKey);
    // The planner behind must not scroll under the layer — the place the reader is coming back to is
    // the place they left.
    const previous = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = previous;
    };
  }, [onClose]);

  const opening: ShiftFormValues = values ?? {
    title: derivedTitle(mealKind, mealEventName, date),
    shiftDate: date,
    endTime: readyBy,
    capacity: suggestedCapacity,
  };
  const meal = mealEventName || mealKind;

  function done(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // Only what the meal save takes. The date is left behind on purpose: the server reads it off the
    // meal, so there is no second copy of it to disagree.
    const read = readShiftForm(new FormData(event.currentTarget));
    onDone({
      title: read.title,
      description: read.description ?? null,
      startTime: read.startTime,
      endTime: read.endTime,
      location: read.location ?? null,
      capacity: read.capacity,
      reminderOffsetsMinutes: read.reminderOffsetsMinutes ?? [],
    });
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
            (§4, rules 3, 4 and 6). */}
        <header className="flex flex-wrap items-start justify-between gap-4 border-b border-hairline px-8 py-4">
          <div className="min-w-0">
            <h2 id="shift-layer-title" className="text-xl font-semibold text-ink">
              {saved ? "Edit a shift" : "Post a shift"}
            </h2>
            {/* The meal it is for and the day, said once — there is no meal field, so this is the only
                place a planner can see which meal the shift will belong to. */}
            <p className="mt-0.5 text-sm text-ink-secondary">
              For {meal} · {longDate(date)}
            </p>
          </div>
          <div className="flex flex-none gap-2">
            <Button variant="secondary" onClick={onClose}>
              Cancel
            </Button>
            {/* "Done", not "Post shift" or "Save changes" (D-27 answer 7). Nothing is posted or saved
                by it, and a button that said so would be the one lie on the screen. */}
            <Button type="submit" form={SHIFT_FORM}>
              Done
            </Button>
          </div>
        </header>
        <div className="grid gap-6 px-8 pb-8 pt-6">
          {/* Said once, where the button is read, because it is the one thing about this layer that is
              different from the volunteers screen it looks like. */}
          <p className="text-sm text-ink-secondary">Saved when you save the meal.</p>
          <ShiftFields shift={opening} editing={saved} fixedDate={date} onSubmit={done} />
        </div>
      </div>
    </div>
  );
}

/**
 * Whether a change to a shift touches when people have to turn up: its start or its end.
 *
 * <p>The date is not compared, because a meal shift's date cannot change (D-27 answer 4). This is what
 * decides the warning before *Update this meal* saves — *"3 volunteers are signed up. They’ll be told
 * the new times."* (answer 6) — and the server makes the same comparison when it decides to tell them.
 */
export function timesChanged(
  before: Pick<ShiftView, "startTime" | "endTime">,
  after: Pick<MealShiftDraft, "startTime" | "endTime">
): boolean {
  return hhmm(before.startTime) !== hhmm(after.startTime) || hhmm(before.endTime) !== hhmm(after.endTime);
}

/**
 * The warning before times change under a roster (D-27 answer 6), with the count said properly for one.
 *
 * <p>Exported so the Volunteer shifts edit screen can say the same sentence in the same words.
 */
export function timesChangedWarning(signedUp: number): string {
  return signedUp === 1
    ? "1 volunteer is signed up. They’ll be told the new times."
    : `${signedUp} volunteers are signed up. They’ll be told the new times.`;
}

/**
 * "Lunch preparation on Tuesday, 1 September 2026" — what a volunteer scrolling a list of shifts needs
 * to be able to tell apart at a glance. Day-first with the month spelled out (`longDate`), and an event
 * named by its own name rather than as another "Event".
 */
export function derivedTitle(mealKind: string, mealEventName: string | null, date: string): string {
  return `${mealEventName || mealKind} preparation on ${longDate(date)}`;
}

/** "HH:mm" out of "HH:mm" or "HH:mm:ss". */
function hhmm(time: string): string {
  return time.slice(0, 5);
}
