"use client";

import Link from "next/link";
import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";

/**
 * Volunteer shifts, poster side (E6-S2) — what has been posted and how full it is.
 *
 * <p>Posting and correcting are both eight-field forms, so both are screens of their own rather than
 * a panel over this list. What comes back here is the confirmation, and — when a save has moved the
 * date or the time on a shift people have already claimed — the warning that their reminders moved
 * with it and nobody told them.
 */

export default function VolunteerShiftsPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      {/* useSearchParams — for what a posted or corrected shift comes back with. */}
      <Suspense>
        <VolunteerShiftsView />
      </Suspense>
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

  // Posting and correcting happen on their own screens and end here, so what they have to say
  // travels in the URL. Captured behind a ref: setting state re-renders, and a router object that
  // is new on each render would otherwise turn this effect into a loop.
  const router = useRouter();
  const params = useSearchParams();
  const posted = params.get("posted");
  const saved = params.get("saved");
  const movedId = params.get("moved");
  const [flash, setFlash] = useState<{ title: string; posted: boolean; movedId: string | null } | null>(null);
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current) return;
    const title = posted ?? saved;
    if (!title) return;
    captured.current = true;
    setFlash({ title, posted: posted !== null, movedId });
    router.replace("/volunteers");
  }, [posted, saved, movedId, router]);

  // The shift whose reminders just moved under the volunteers already on it, if there is one.
  const movedShift = flash?.movedId ? shifts.find((s) => s.id === flash.movedId) ?? null : null;

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

  async function cancel(id: string) {
    const reason = window.prompt("Why is this shift being cancelled?");
    if (reason) await run((t) => api.cancelShift(id, reason, t), "We couldn't cancel that shift.");
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/volunteers" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Volunteer shifts</h1>
              <p className="mt-1 text-ink-secondary">Volunteers see a shift the moment it is posted.</p>
            </div>
            <ButtonLink href="/volunteers/new">Post a shift</ButtonLink>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

          {flash && !movedShift && (
            <div className="mb-6">
              <InlineNotice
                tone="success"
                autoDismiss
                title={flash.posted ? `${flash.title} is posted.` : `${flash.title} was saved.`}
              />
            </div>
          )}

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

          {loading ? (
            <Loading label="Loading shifts…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : shifts.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">No shifts posted</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">Post a shift so volunteers can sign up.</p>
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
                        <Link href={`/volunteers/${s.id}/edit`} className="text-sm text-accent-text hover:underline">
                          Edit
                        </Link>
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
