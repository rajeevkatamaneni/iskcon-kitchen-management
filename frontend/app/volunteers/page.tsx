"use client";

import Link from "next/link";
import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type ShiftInput, type ShiftView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";

/**
 * Volunteer shifts, poster side (E6-S2). One form posts a shift and edits one — a shift that can be
 * created but not corrected is a shift whose only fix is cancelling it, which empties the roster and
 * makes every volunteer sign up again over a typo in the start time.
 *
 * <p>Editing a shift people have already claimed reschedules their reminders (the backend does that
 * on save), but it does not tell them anything. So when a save moves the date or the time on a shift
 * with a roster, this screen says so and points at the broadcast on the roster page, where the admin
 * writes the words themselves.
 */

export default function VolunteerShiftsPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <VolunteerShiftsView />
    </RequireRole>
  );
}

/** Which form is open: none, a new shift, or an existing one being corrected. */
type Editing = { mode: "closed" } | { mode: "new" } | { mode: "edit"; shift: ShiftView };

function VolunteerShiftsView() {
  const { getToken } = useAuth();
  const { data, error, loading, reload } = useAuthedQuery(
    useCallback((t: string | undefined) => api.listShifts({}, t), [])
  );
  const shifts = data ?? [];

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [editing, setEditing] = useState<Editing>({ mode: "closed" });
  const [movedShift, setMovedShift] = useState<ShiftView | null>(null);

  async function run(mutation: (t: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    setActionError(null);
    try {
      await mutation(await getToken());
      reload();
      return true;
    } catch (e) {
      setActionError(toApiError(e, failure));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (editing.mode === "closed") return;
    const form = event.currentTarget;
    const input = readForm(new FormData(form));
    const existing = editing.mode === "edit" ? editing.shift : null;

    const ok = existing
      ? await run((t) => api.updateShift(existing.id, input, t), "We couldn't save that change.")
      : await run((t) => api.createShift(input, t), "We couldn't post that shift.");

    if (!ok) return;
    // Reminders were rescheduled; the people already on the roster were not told.
    setMovedShift(existing && moved(existing, input) && existing.signedUpCount > 0 ? existing : null);
    form.reset();
    setEditing({ mode: "closed" });
  }

  async function cancel(id: string) {
    const reason = window.prompt("Why is this shift being cancelled?");
    if (reason) await run((t) => api.cancelShift(id, reason, t), "We couldn't cancel that shift.");
  }

  const shift = editing.mode === "edit" ? editing.shift : null;

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/volunteers" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Volunteer shifts</h1>
              <p className="mt-1 text-ink-secondary">Post seva shifts; volunteers see them the moment they&rsquo;re created.</p>
            </div>
            <button
              type="button"
              onClick={() => setEditing((e) => (e.mode === "new" ? { mode: "closed" } : { mode: "new" }))}
              className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover"
            >
              Post a shift
            </button>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

          {movedShift && (
            <div className="mb-6">
              <InlineNotice tone="warning">
                That shift moved, and the {movedShift.signedUpCount} volunteer
                {movedShift.signedUpCount === 1 ? "" : "s"} already signed up have not been told.
                Their reminders now fire at the new time.{" "}
                <Link href={`/volunteers/${movedShift.id}`} className="underline">
                  Send them an update
                </Link>
                .
              </InlineNotice>
            </div>
          )}

          {editing.mode !== "closed" && (
            <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="shift-form-heading">
              <h2 id="shift-form-heading" className="text-lg">{shift ? "Edit shift" : "New shift"}</h2>
              {shift && (
                <p className="mt-1 text-sm text-ink-secondary">
                  {shift.signedUpCount > 0
                    ? `${shift.signedUpCount} volunteer${shift.signedUpCount === 1 ? " has" : "s have"} already signed up. Reminders move with the shift.`
                    : "Nobody has signed up yet."}
                </p>
              )}
              {/* Keyed so switching between shifts (or to a new one) rebuilds the defaults. */}
              <form
                key={shift?.id ?? "new"}
                className="mt-4 grid grid-cols-2 gap-4"
                aria-label={shift ? "Edit a shift" : "Post a shift"}
                onSubmit={submit}
              >
                <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary"><span className="pl-field-inset font-medium text-ink">Title</span>
                  <input name="title" required defaultValue={shift?.title ?? ""} className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary"><span className="pl-field-inset font-medium text-ink">Date</span>
                  <input name="shiftDate" type="date" required defaultValue={shift?.shiftDate ?? ""} className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary"><span className="pl-field-inset font-medium text-ink">Capacity</span>
                  <input name="capacity" type="number" min="1" required defaultValue={shift?.capacity ?? 1} className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary"><span className="pl-field-inset font-medium text-ink">Start</span>
                  <input name="startTime" type="time" required defaultValue={hhmm(shift?.startTime)} className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary"><span className="pl-field-inset font-medium text-ink">End</span>
                  <input name="endTime" type="time" required defaultValue={hhmm(shift?.endTime)} className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary"><span className="pl-field-inset font-medium text-ink">Location</span>
                  <input name="location" defaultValue={shift?.location ?? ""} className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary"><span className="pl-field-inset font-medium text-ink">Reminder hours before (comma-separated)</span>
                  <input name="reminderHours" defaultValue={toHours(shift?.reminderOffsetsMinutes)} className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary"><span className="pl-field-inset font-medium text-ink">Description</span>
                  <input name="description" defaultValue={shift?.description ?? ""} className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <div className="col-span-2 flex items-center gap-3">
                  <button type="submit" disabled={busy} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">
                    {shift ? "Save changes" : "Post shift"}
                  </button>
                  <button type="button" onClick={() => setEditing({ mode: "closed" })} className="min-h-touch rounded border border-hairline px-4 hover:bg-sunken">
                    Cancel
                  </button>
                </div>
              </form>
            </section>
          )}

          {loading ? (
            <Loading label="Loading shifts…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : shifts.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">No shifts posted</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">Post a shift above so volunteers can sign up.</p>
            </div>
          ) : (
            <div className="overflow-hidden rounded-lg bg-raised">
              <table className="w-full text-left">
                <thead className="bg-sunken text-sm text-ink-secondary">
                  <tr>
                    <th className="px-5 py-3 font-medium">Shift</th>
                    <th className="px-5 py-3 font-medium">When</th>
                    <th className="px-5 py-3 font-medium text-right">Filled</th>
                    <th className="px-5 py-3 font-medium text-right">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {shifts.map((s) => (
                    <tr key={s.id} className="border-t border-hairline align-middle hover:bg-raised/60">
                      <td className="px-5 py-3">
                        <Link href={`/volunteers/${s.id}`} className="font-medium text-accent-text hover:underline">{s.title}</Link>
                        {s.location && <span className="ml-2 text-xs text-ink-muted">{s.location}</span>}
                      </td>
                      <td className="px-5 py-3 text-ink-secondary tabular-nums">{s.shiftDate} {s.startTime}–{s.endTime}</td>
                      <td className="px-5 py-3 text-right tabular-nums">
                        {s.signedUpCount}/{s.capacity}{s.waitlistCount > 0 ? ` (+${s.waitlistCount})` : ""}
                      </td>
                      <td className="px-5 py-3 text-right">
                        <button
                          type="button"
                          disabled={busy}
                          onClick={() => {
                            setMovedShift(null);
                            setEditing({ mode: "edit", shift: s });
                          }}
                          className="text-sm text-accent-text hover:underline disabled:opacity-60"
                        >
                          Edit
                        </button>
                        <button type="button" disabled={busy} onClick={() => cancel(s.id)} className="ml-3 text-sm text-danger hover:underline disabled:opacity-60">Cancel</button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </main>
    </div>
  );
}

function readForm(f: FormData): ShiftInput {
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
function moved(before: ShiftView, after: ShiftInput): boolean {
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
