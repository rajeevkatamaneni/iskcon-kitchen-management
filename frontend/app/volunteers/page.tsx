"use client";

import Link from "next/link";
import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { TEMPLE_NAV } from "@/lib/nav";
import { api, toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

export default function VolunteerShiftsPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_STAFF"]}>
      <VolunteerShiftsView />
    </RequireRole>
  );
}

function VolunteerShiftsView() {
  const { getToken } = useAuth();
  const { data, error, loading, reload } = useAuthedQuery(
    useCallback((t: string | undefined) => api.listShifts({}, t), [])
  );
  const shifts = data ?? [];

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [showAdd, setShowAdd] = useState(false);

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

  async function add(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const f = new FormData(form);
    const ok = await run(
      (t) =>
        api.createShift(
          {
            title: String(f.get("title") ?? "").trim(),
            description: emptyToNull(String(f.get("description") ?? "")),
            shiftDate: String(f.get("shiftDate") ?? ""),
            startTime: String(f.get("startTime") ?? ""),
            endTime: String(f.get("endTime") ?? ""),
            location: emptyToNull(String(f.get("location") ?? "")),
            capacity: Number(f.get("capacity") ?? 1),
            reminderOffsetsMinutes: parseHours(String(f.get("reminderHours") ?? "24")),
          },
          t
        ),
      "We couldn't post that shift."
    );
    if (ok) {
      form.reset();
      setShowAdd(false);
    }
  }

  async function duplicate(id: string) {
    const date = window.prompt("New date for the duplicated shift (YYYY-MM-DD)?");
    if (date) await run((t) => api.duplicateShift(id, date, t), "We couldn't duplicate that shift.");
  }

  async function cancel(id: string) {
    const reason = window.prompt("Why is this shift being cancelled?");
    if (reason) await run((t) => api.cancelShift(id, reason, t), "We couldn't cancel that shift.");
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar templeName="Your temple" items={TEMPLE_NAV} activeHref="/volunteers" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Volunteer shifts</h1>
              <p className="mt-1 text-ink-secondary">Post seva shifts; volunteers see them the moment they&rsquo;re created.</p>
            </div>
            <button type="button" onClick={() => setShowAdd((s) => !s)} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover">
              Post a shift
            </button>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

          {showAdd && (
            <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="add-heading">
              <h2 id="add-heading" className="text-lg">New shift</h2>
              <form className="mt-4 grid grid-cols-2 gap-4" aria-label="Post a shift" onSubmit={add}>
                <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">Title
                  <input name="title" required className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">Date
                  <input name="shiftDate" type="date" required className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">Capacity
                  <input name="capacity" type="number" min="1" defaultValue="1" required className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">Start
                  <input name="startTime" type="time" required className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">End
                  <input name="endTime" type="time" required className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">Location
                  <input name="location" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">Reminder hours before (comma-separated)
                  <input name="reminderHours" defaultValue="24" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">Description
                  <input name="description" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <div className="col-span-2">
                  <button type="submit" disabled={busy} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">Post shift</button>
                </div>
              </form>
            </section>
          )}

          {loading ? (
            <p className="text-ink-secondary">Loading shifts…</p>
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
                    <tr key={s.id} className="border-t border-hairline align-middle">
                      <td className="px-5 py-3">
                        <Link href={`/volunteers/${s.id}`} className="font-medium text-accent-text hover:underline">{s.title}</Link>
                        {s.location && <span className="ml-2 text-xs text-ink-muted">{s.location}</span>}
                      </td>
                      <td className="px-5 py-3 text-ink-secondary tabular-nums">{s.shiftDate} {s.startTime}–{s.endTime}</td>
                      <td className="px-5 py-3 text-right tabular-nums">
                        {s.signedUpCount}/{s.capacity}{s.waitlistCount > 0 ? ` (+${s.waitlistCount})` : ""}
                      </td>
                      <td className="px-5 py-3 text-right">
                        <button type="button" disabled={busy} onClick={() => duplicate(s.id)} className="text-sm text-accent-text hover:underline disabled:opacity-60">Duplicate</button>
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

function parseHours(csv: string): number[] {
  return csv.split(",").map((s) => Number(s.trim())).filter((n) => Number.isFinite(n) && n > 0).map((h) => h * 60);
}

function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
}
