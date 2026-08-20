"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";

export default function ShiftRosterPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <ShiftRosterView />
    </RequireRole>
  );
}

function ShiftRosterView() {
  const id = useParams<{ id: string }>().id;
  const { getToken } = useAuth();
  const { data, error, loading, reload } = useAuthedQuery(
    useCallback((t: string | undefined) => api.shiftRoster(id, t), [id])
  );

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [showBroadcast, setShowBroadcast] = useState(false);

  async function broadcast(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const f = new FormData(form);
    setBusy(true);
    setActionError(null);
    setNotice(null);
    try {
      const res = await api.broadcastShift(
        id,
        { message: String(f.get("message") ?? "").trim(), includeWaitlist: f.get("includeWaitlist") === "on" },
        await getToken()
      );
      form.reset();
      setShowBroadcast(false);
      setNotice(`Update sent to ${res.recipients} volunteer(s).`);
      reload();
    } catch (e) {
      setActionError(toApiError(e, "We couldn't send that update."));
    } finally {
      setBusy(false);
    }
  }

  const roster = data;
  const shift = roster?.shift;
  const activeSignups = (roster?.signups ?? []).filter((s) => !s.releasedAt);
  const released = (roster?.signups ?? []).filter((s) => s.releasedAt);

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/volunteers" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <Link href="/volunteers" className="text-sm text-accent-text hover:underline">← All shifts</Link>

          {loading ? (
            <Loading label="Loading roster…" />
          ) : error ? (
            <div className="mt-6"><ErrorNotice error={error} /></div>
          ) : !shift ? null : (
            <>
              <header className="mb-6 mt-3 flex flex-wrap items-start justify-between gap-4">
                <div>
                  <h1>{shift.title}</h1>
                  <p className="mt-1 text-ink-secondary tabular-nums">
                    {shift.shiftDate} · {shift.startTime}–{shift.endTime}{shift.location ? ` · ${shift.location}` : ""}
                  </p>
                  <p className="mt-1 text-sm text-ink-muted tabular-nums">
                    {activeSignups.length}/{shift.capacity} filled{roster!.waitlist.length > 0 ? ` · ${roster!.waitlist.length} waiting` : ""}
                  </p>
                </div>
                {shift.status === "OPEN" && (
                  <button type="button" onClick={() => setShowBroadcast((s) => !s)} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover">
                    Send update to all
                  </button>
                )}
              </header>

              {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}
              {notice && <div className="mb-6 rounded border border-hairline bg-success-bg px-4 py-3 text-sm text-success">{notice}</div>}
              {shift.status === "CANCELLED" && (
                <div className="mb-6 rounded border border-hairline bg-warning-bg px-4 py-3 text-sm text-warning">
                  This shift was cancelled{shift.cancelReason ? `: ${shift.cancelReason}` : ""}.
                </div>
              )}

              {showBroadcast && (
                <section className="mb-8 rounded-lg bg-raised px-6 py-5">
                  <h2 className="text-lg">Send an update</h2>
                  <form className="mt-3" aria-label="Send an update" onSubmit={broadcast}>
                    <textarea name="message" required maxLength={1000} rows={3} placeholder="e.g. Gate B today, not A"
                      className="w-full rounded border border-hairline bg-canvas px-3 py-2" />
                    <label className="mt-2 flex items-center gap-2 text-sm text-ink-secondary">
                      <input type="checkbox" name="includeWaitlist" /> Also send to the waitlist
                    </label>
                    <button type="submit" disabled={busy} className="mt-3 min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">Send now</button>
                  </form>
                </section>
              )}

              <section className="mb-8">
                <h2 className="mb-3 text-lg">Signed up ({activeSignups.length})</h2>
                {activeSignups.length === 0 ? (
                  <p className="text-sm text-ink-secondary">No one signed up yet.</p>
                ) : (
                  <div className="overflow-hidden rounded-lg bg-raised">
                    <table className="w-full text-left">
                      <thead className="bg-sunken text-sm text-ink-secondary">
                        <tr><th className="px-5 py-3 font-medium">Volunteer</th><th className="px-5 py-3 font-medium">Reminders</th></tr>
                      </thead>
                      <tbody>
                        {activeSignups.map((s) => (
                          <tr key={s.userId} className="border-t border-hairline align-middle">
                            <td className="px-5 py-3">
                              {s.fullName}
                              {s.source === "PROMOTION" && <span className="ml-2 rounded-sm bg-accent-bg px-2 py-0.5 text-xs text-accent-text font-semibold">promoted</span>}
                            </td>
                            <td className="px-5 py-3 text-sm text-ink-secondary">
                              {s.reminders.length === 0 ? "—" : s.reminders.map((r, i) => (
                                <span key={i} className="mr-2 inline-block tabular-nums">{r.offsetMinutes / 60}h: {(r.status ?? "").toLowerCase()}{r.channel ? ` (${r.channel.toLowerCase()})` : ""}</span>
                              ))}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </section>

              {roster!.waitlist.length > 0 && (
                <section className="mb-8">
                  <h2 className="mb-3 text-lg">Waitlist</h2>
                  <ol className="space-y-2">
                    {roster!.waitlist.map((w) => (
                      <li key={w.userId} className="rounded-lg bg-raised px-5 py-3 text-sm">
                        <span className="tabular-nums text-ink-muted">{w.position}.</span> {w.fullName}
                      </li>
                    ))}
                  </ol>
                </section>
              )}

              {released.length > 0 && (
                <section className="mb-8">
                  <h2 className="mb-3 text-lg">Released</h2>
                  <ul className="space-y-1 text-sm text-ink-secondary">
                    {released.map((s) => <li key={s.userId}>{s.fullName} — released</li>)}
                  </ul>
                </section>
              )}

              {roster!.broadcasts.length > 0 && (
                <section>
                  <h2 className="mb-3 text-lg">Updates sent</h2>
                  <ul className="space-y-3">
                    {roster!.broadcasts.map((b, i) => (
                      <li key={i} className="rounded-lg bg-raised px-5 py-3">
                        <p className="text-sm">{b.message}</p>
                        <p className="mt-1 text-xs text-ink-muted">
                          {b.sentByName ?? "Someone"} · {new Date(b.createdAt).toLocaleString()} · {b.recipients.length} recipient(s)
                        </p>
                      </li>
                    ))}
                  </ul>
                </section>
              )}
            </>
          )}
        </div>
      </main>
    </div>
  );
}
