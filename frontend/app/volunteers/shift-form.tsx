"use client";

import { useState } from "react";
import { HintedField } from "@/components/ds/InfoHint";
import type { ShiftInput, ShiftView } from "@/lib/api";
import { crossesMidnight } from "@/lib/format";

/**
 * The eight fields a seva shift is made of, shared by posting one and correcting one.
 *
 * <p>Both screens ask for exactly the same things, so they ask with the same markup. A shift that
 * could be created but not corrected is a shift whose only fix is cancelling it, which empties the
 * roster and makes every volunteer sign up again over a typo in the start time.
 *
 * <p>A third caller renders it too: the meal planner's layer (`components/planner/ShiftLayer.tsx`,
 * T-155), which opens this same form over the day rather than a cut-down copy of it. DESIGN_SYSTEM
 * v1.8 §4 asks for exactly that, and the copy it replaced had already drifted — it never learned to
 * say "Ends the next day".
 */

/** Named so a header button outside the form can submit it. */
export const SHIFT_FORM = "shift-form";

const FIELD = "min-h-touch rounded-control border border-hairline px-3";

/**
 * What the boxes open on. A whole `ShiftView` fits, which is what the edit screen hands in; so does
 * the handful of facts the meal planner already knows about a shift nobody has posted yet (T-155).
 */
export type ShiftFormValues = Partial<
  Pick<
    ShiftView,
    "title" | "description" | "shiftDate" | "startTime" | "endTime" | "location" | "capacity" | "reminderOffsetsMinutes"
  >
>;

export function ShiftFields({
  shift,
  editing,
  fixedDate,
  onSubmit,
}: {
  /**
   * What the boxes open on: the shift being corrected, or what is already known about one being
   * posted, or nothing at all on a blank form.
   */
  shift?: ShiftFormValues;
  /**
   * Whether this shift already exists. Separate from `shift` on purpose, and required so no caller
   * can leave it to be guessed (T-155): the planner opens this form on a *new* shift with its date,
   * title, size and end time already filled in, and the form used to decide what to call itself from
   * whether it had values. So a prefilled new shift announced itself to a screen reader as "Edit a
   * shift". Having values and having been saved are two different facts.
   */
  editing: boolean;
  /**
   * A date the shift is fixed to, shown and sent but not editable. Only the meal planner's layer
   * passes it (T-155, Rajeev 2026-09-12: the date "Should be restricted to the day of the meal plan
   * and read only"). The volunteers screens leave it out and keep an editable date.
   */
  fixedDate?: string;
  onSubmit: (event: React.FormEvent<HTMLFormElement>) => void;
}) {
  // The two times are held in state for one reason: the form has to say, while somebody is typing,
  // what an end time before a start time means (T-146). Everything else on this form is
  // uncontrolled and stays that way — a defaultValue that the browser owns is fewer moving parts
  // than a controlled input, and only these two have anything to say about each other.
  //
  // Seeded from the props once, which is safe for the same reason the `defaultValue`s it replaces
  // were: the edit screen renders this component only after the shift has loaded, so the props are
  // never the empty placeholder that a later load would have to correct.
  const [startTime, setStartTime] = useState(hhmm(shift?.startTime));
  const [endTime, setEndTime] = useState(hhmm(shift?.endTime));

  // Equal times are the one pairing the product refuses: 20:00 to 20:00 is either a shift of no
  // length or one of twenty-four hours and nothing can say which. The server refuses it too
  // (KMS-400001, naming the field), and this is that same rule said before the press rather than
  // after it — the failure notice on these screens shows the code's own sentence and does not
  // highlight fields, so a refusal that only came back from the server would leave a coordinator
  // staring at a form with nothing marked on it.
  const sameTime = startTime !== "" && startTime === endTime;
  // And where the shift genuinely runs through the night, the form says so rather than leaving
  // "20:00" above "02:00" for a reader to interpret. `crossesMidnight` is true of equal times as
  // well, so the refusal above wins and only one of the two lines is ever shown.
  const overnight = !sameTime && startTime !== "" && endTime !== "" && crossesMidnight(startTime, endTime);

  return (
    <form
      id={SHIFT_FORM}
      className="grid grid-cols-2 gap-4"
      aria-label={editing ? "Edit a shift" : "Post a shift"}
      onSubmit={(event) => {
        // Held here rather than in each of the two screens that use this form, so neither can
        // forget it. A submit blocked here never reaches the API, so nothing is saved and nothing
        // is reported — the sentence under the End box is already on screen saying why.
        if (sameTime) {
          event.preventDefault();
          return;
        }
        onSubmit(event);
      }}
    >
      <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Title</span>
        <input name="title" required defaultValue={shift?.title ?? ""} className={FIELD} />
      </label>
      <label className="flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Date</span>
        {/* Fixed, it is `readOnly` and never `disabled`: a disabled input is left out of the form's
            data, so the save would go without a date, and a disabled box cannot be focused, so a
            keyboard or screen-reader user could not even reach it to hear what it holds. Read-only
            stays in the tab order and is announced as read-only; the recessed fill and the line
            under it are how a sighted reader tells, and that line is also its description. */}
        {fixedDate ? (
          <>
            <input
              name="shiftDate"
              type="date"
              required
              readOnly
              value={fixedDate}
              aria-describedby="shift-date-fixed"
              className={`${FIELD} cursor-default bg-sunken text-ink-secondary`}
            />
            <span id="shift-date-fixed" className="pl-field-inset text-ink-muted">
              <i aria-hidden="true" className="ti ti-lock" /> The day of the meal. It cannot be changed here.
            </span>
          </>
        ) : (
          <input name="shiftDate" type="date" required defaultValue={shift?.shiftDate ?? ""} className={FIELD} />
        )}
      </label>
      {/* No line under this one. "How many volunteers are needed" is the word *Capacity* said
          again, and a shift has no other capacity to be confused with. */}
      <label className="flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Capacity</span>
        <input name="capacity" type="number" min="1" required defaultValue={shift?.capacity ?? 1} className={FIELD} />
      </label>
      <label className="flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Start</span>
        <input
          name="startTime"
          type="time"
          required
          value={startTime}
          onChange={(e) => setStartTime(e.target.value)}
          className={FIELD}
        />
      </label>
      <label className="flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">End</span>
        <input
          name="endTime"
          type="time"
          required
          value={endTime}
          onChange={(e) => setEndTime(e.target.value)}
          className={FIELD}
        />
        {/* One line, never both: the refusal replaces the explanation, because a form cannot at
            once be telling somebody their shift runs to the next morning and that it is not a
            shift at all. `role="alert"` on the refusal only — "Ends the next day" is an
            explanation of what was typed, and announcing it on every keystroke would talk over
            somebody still choosing the time. */}
        {sameTime ? (
          <span role="alert" className="pl-field-inset text-danger">
            A shift cannot start and end at the same time. For one that runs through the night, give
            the time it ends the next morning.
          </span>
        ) : overnight ? (
          <span className="pl-field-inset text-ink-muted">Ends the next day — this shift runs through midnight</span>
        ) : null}
      </label>
      <label className="flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Location</span>
        <input name="location" defaultValue={shift?.location ?? ""} className={FIELD} />
      </label>
      {/* The one line on this form that says something the label does not: that the box takes more
          than one number. It is in the label's "i" rather than under the box — and a HintedField
          rather than a hand-built one, because the "i" is a button and a button inside a `<label>`
          becomes the labelled thing in place of the input. */}
      <HintedField label="Reminder hours before" hint="Separate several with commas">
        {(id) => (
          <input
            id={id}
            name="reminderHours"
            defaultValue={toHours(shift?.reminderOffsetsMinutes)}
            className={FIELD}
          />
        )}
      </HintedField>
      <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Description</span>
        <input name="description" defaultValue={shift?.description ?? ""} className={FIELD} />
      </label>
    </form>
  );
}

export function readShiftForm(f: FormData): ShiftInput {
  return {
    title: String(f.get("title") ?? "").trim(),
    description: emptyToNull(String(f.get("description") ?? "")),
    shiftDate: String(f.get("shiftDate") ?? ""),
    startTime: String(f.get("startTime") ?? ""),
    endTime: String(f.get("endTime") ?? ""),
    location: emptyToNull(String(f.get("location") ?? "")),
    capacity: Number(f.get("capacity") ?? 1),
    reminderOffsetsMinutes: parseHours(String(f.get("reminderHours") ?? "24")),
  };
}

/** Did the save move when people have to turn up? Only that is worth interrupting them for. */
export function moved(before: ShiftView, after: ShiftInput): boolean {
  return (
    before.shiftDate !== after.shiftDate ||
    hhmm(before.startTime) !== after.startTime.slice(0, 5) ||
    hhmm(before.endTime) !== after.endTime.slice(0, 5)
  );
}

/**
 * Should the person saving be warned that volunteers were not told? When the save moved the shift
 * and somebody is already signed up to it: their reminders move with the shift and nobody tells them.
 *
 * <p>One rule for every place a shift is corrected — the edit screen and the planner's layer — so
 * the two cannot come to warn about different things (T-155; Rajeev 2026-09-12: "They need to know
 * what their actions are resulting in. Cant be silent about it.").
 */
export function movedUnderRoster(before: ShiftView, after: ShiftInput): boolean {
  return moved(before, after) && before.signedUpCount > 0;
}

/** The API sends `HH:mm:ss`; a time input wants `HH:mm`. */
function hhmm(time: string | undefined): string {
  return (time ?? "").slice(0, 5);
}

function toHours(minutes: number[] | undefined): string {
  if (!minutes || minutes.length === 0) return "24";
  return minutes.map((m) => m / 60).join(", ");
}

function parseHours(csv: string): number[] {
  return csv.split(",").map((s) => Number(s.trim())).filter((n) => Number.isFinite(n) && n > 0).map((h) => h * 60);
}

function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
}
