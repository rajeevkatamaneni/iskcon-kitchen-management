"use client";

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { NoticeCard } from "@/components/PlatformNotices";
import { api, toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * The platform notice board (E9-S1) — the permanent record behind the band on Today.
 *
 * <p>One screen for two audiences, because it is one board. A platform operator reaches it beside
 * Operations, where a downtime notice is an operations act; a temple admin reaches it under Temple,
 * beside the audit log and the settings, because raising one is rare and serious enough to sit
 * somewhere deliberate rather than one click from the day's work.
 *
 * <p>Nothing is filtered out and nothing ever leaves: dismissed and withdrawn notices are here
 * alongside the rest. A temple that cleared a recall in March and needs it again in June has exactly
 * one place to look, and a board that hid what had been dismissed would not be that place.
 *
 * <p>There is no review queue, deliberately. A recall at nine on a Sunday evening cannot wait for a
 * reviewer and there is no operator on duty then. What stands in its place is on every card: the
 * raising temple named in the open, the raiser on the platform audit log, and a withdrawal control
 * for the temple that posted it and for an operator.
 */

export default function NoticesPage() {
  return (
    <RequireRole roles={["SUPER_ADMIN", "TEMPLE_ADMIN"]}>
      {/* useSearchParams — for the confirmation a raised notice comes back with. */}
      <Suspense>
        <NoticesView />
      </Suspense>
    </RequireRole>
  );
}

function NoticesView() {
  const { getToken } = useAuth();
  const { data, error, loading, reload } = useAuthedQuery(
    useCallback((t: string | undefined) => api.listNotices(t), [])
  );
  const notices = data ?? [];

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [withdrawing, setWithdrawing] = useState<string | null>(null);

  // Raising happens on /notices/new and ends here, so the confirmation travels in the URL. The ref
  // guards the capture against a router object that is new on every render.
  const router = useRouter();
  const raised = useSearchParams().get("raised");
  const [flash, setFlash] = useState<string | null>(null);
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current || !raised) return;
    captured.current = true;
    setFlash(raised);
    router.replace("/notices");
  }, [raised, router]);

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

  async function withdraw(id: string, event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    const ok = await run(
      (t) => api.withdrawNotice(id, String(f.get("reason") ?? "").trim(), t),
      "We couldn't withdraw that notice."
    );
    if (ok) setWithdrawing(null);
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/notices" />

      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Notices</h1>
              <p className="mt-1 max-w-prose text-ink-secondary">
                Yours carries your temple&rsquo;s name, and reaches every temple.
              </p>
            </div>
            <ButtonLink href="/notices/new">Raise a notice</ButtonLink>
          </header>

          {actionError && (
            <div className="mb-6">
              <ErrorNotice error={actionError} />
            </div>
          )}

          {flash && (
            <div className="mb-6">
              <InlineNotice tone="success" autoDismiss title={`${flash} went out to every temple.`}>
                Withdraw it here, with a reason, if it is wrong.
              </InlineNotice>
            </div>
          )}

          {loading ? (
            <Loading label="Loading notices…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : notices.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">Nothing has been raised</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                Notices are rare by design. When a temple finds a supplier problem or the platform
                has to go down for work, it appears here and on everyone&rsquo;s Today screen.
              </p>
            </div>
          ) : (
            <div className="grid gap-3">
              {notices.map((n) => (
                <NoticeCard
                  key={n.id}
                  notice={n}
                  action={
                    n.canWithdraw ? (
                      withdrawing === n.id ? (
                        <form
                          className="flex flex-wrap items-center gap-2"
                          aria-label={`Withdraw ${n.subject}`}
                          onSubmit={(e) => withdraw(n.id, e)}
                        >
                          <input
                            name="reason"
                            required
                            maxLength={500}
                            placeholder="Why is it being withdrawn?"
                            className="min-h-9 min-w-64 rounded border border-hairline bg-canvas px-3 text-sm"
                          />
                          <button
                            type="submit"
                            disabled={busy}
                            className="min-h-9 rounded bg-danger-bg px-3 text-sm text-danger disabled:opacity-60"
                          >
                            Withdraw
                          </button>
                          <button
                            type="button"
                            onClick={() => setWithdrawing(null)}
                            className="min-h-9 rounded px-3 text-sm text-ink-secondary hover:bg-sunken"
                          >
                            Keep it
                          </button>
                        </form>
                      ) : (
                        <button
                          type="button"
                          onClick={() => setWithdrawing(n.id)}
                          className="min-h-9 rounded px-3 text-sm text-ink-secondary transition-colors duration-state hover:bg-sunken hover:text-ink"
                        >
                          Withdraw
                        </button>
                      )
                    ) : null
                  }
                />
              ))}
            </div>
          )}
        </div>
      </main>
    </div>
  );
}
