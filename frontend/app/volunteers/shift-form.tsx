"use client";

import type { ShiftInput, ShiftView } from "@/lib/api";

/**
 * The eight fields a seva shift is made of, shared by posting one and correcting one.
 *
 * <p>Both screens ask for exactly the same things, so they ask with the same markup. A shift that
 * could be created but not corrected is a shift whose only fix is cancelling it, which empties the
 * roster and makes every volunteer sign up again over a typo in the start time.
 */

/** Named so a header button outside the form can submit it. */
export const SHIFT_FORM = "shift-form";

const FIELD = "min-h-touch rounded border border-hairline bg-canvas px-3";

export function ShiftFields({
  shift,
  onSubmit,
}: {
  /** The shift being corrected, or nothing when one is being posted. */
  shift?: ShiftView;
  onSubmit: (event: React.FormEvent<HTMLFormElement>) => void;
}) {
  return (
    <form
      id={SHIFT_FORM}
      className="grid grid-cols-2 gap-4"
      aria-label={shift ? "Edit a shift" : "Post a shift"}
      onSubmit={onSubmit}
    >
      <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Title</span>
        <input name="title" required defaultValue={shift?.title ?? ""} className={FIELD} />
      </label>
      <label className="flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Date</span>
        <input name="shiftDate" type="date" required defaultValue={shift?.shiftDate ?? ""} className={FIELD} />
      </label>
      <label className="flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Capacity</span>
        <input name="capacity" type="number" min="1" required defaultValue={shift?.capacity ?? 1} className={FIELD} />
        <span className="pl-field-inset text-sm text-ink-secondary">How many volunteers are needed</span>
      </label>
      <label className="flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Start</span>
        <input name="startTime" type="time" required defaultValue={hhmm(shift?.startTime)} className={FIELD} />
      </label>
      <label className="flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">End</span>
        <input name="endTime" type="time" required defaultValue={hhmm(shift?.endTime)} className={FIELD} />
      </label>
      <label className="flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Location</span>
        <input name="location" defaultValue={shift?.location ?? ""} className={FIELD} />
      </label>
      <label className="flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Reminder hours before</span>
        <input name="reminderHours" defaultValue={toHours(shift?.reminderOffsetsMinutes)} className={FIELD} />
        <span className="pl-field-inset text-sm text-ink-secondary">Separate several with commas</span>
      </label>
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
