"use client";

import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { NoticeCard } from "@/components/PlatformNotices";
import { api, toApiError, type ApiError, type NoticeSeverity } from "@/lib/api";
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

const SEVERITIES: { value: NoticeSeverity; label: string; hint: string }[] = [
  {
    value: "INFORMATION",
    label: "Information",
    hint: "Worth knowing. Sits quietly at the top of Today.",
  },
  {
    value: "IMPORTANT",
    label: "Important",
    hint: "Worth acting on before long. Marked, but not loud.",
  },
  {
    value: "URGENT",
    label: "Urgent",
    hint: "Stop and deal with it — a recall, a contaminated batch. The only one that shouts.",
  },
];

export default function NoticesPage() {
  return (
    <RequireRole roles={["SUPER_ADMIN", "TEMPLE_ADMIN"]}>
      <NoticesView />
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
  const [showRaise, setShowRaise] = useState(false);
  const [withdrawing, setWithdrawing] = useState<string | null>(null);

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

  async function raise(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const f = new FormData(form);
    const ok = await run(
      (t) =>
        api.raiseNotice(
          {
            severity: String(f.get("severity") ?? "INFORMATION") as NoticeSeverity,
            subject: String(f.get("subject") ?? "").trim(),
            body: String(f.get("body") ?? "").trim(),
          },
          t
        ),
      "We couldn't post that notice."
    );
    if (ok) {
      form.reset();
      setShowRaise(false);
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
                Messages that reach every temple on the platform. Yours carries your temple&rsquo;s
                name, so post only what other temples genuinely need to know — and withdraw it, with
                a reason, if it turns out to be wrong.
              </p>
            </div>
            <button
              type="button"
              onClick={() => setShowRaise((s) => !s)}
              className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover"
            >
              Raise a notice
            </button>
          </header>

          {actionError && (
            <div className="mb-6">
              <ErrorNotice error={actionError} />
            </div>
          )}

          {showRaise && (
            <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="raise-heading">
              <h2 id="raise-heading" className="text-lg">
                New notice
              </h2>
              <p className="mt-1 max-w-prose text-sm text-ink-muted">
                This goes out immediately, to every temple. Nobody reviews it first.
              </p>
              <form className="mt-4 grid gap-4" aria-label="Raise a platform notice" onSubmit={raise}>
                <fieldset className="grid gap-2">
                  <legend className="text-sm text-ink-secondary">Severity</legend>
                  {SEVERITIES.map((s, i) => (
                    <label key={s.value} className="flex items-baseline gap-2 text-sm">
                      <input
                        type="radio"
                        name="severity"
                        value={s.value}
                        defaultChecked={i === 0}
                        className="mt-1"
                      />
                      <span>
                        <span className="text-ink">{s.label}</span>{" "}
                        <span className="text-ink-muted">{s.hint}</span>
                      </span>
                    </label>
                  ))}
                </fieldset>

                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  <span className="pl-field-inset font-medium text-ink">Subject</span>
                  <input
                    name="subject"
                    required
                    maxLength={120}
                    className="min-h-touch rounded border border-hairline bg-canvas px-3"
                  />
                </label>

                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  <span className="pl-field-inset font-medium text-ink">What happened, and what other temples should do</span>
                  <textarea
                    name="body"
                    required
                    rows={5}
                    maxLength={4000}
                    className="rounded border border-hairline bg-canvas px-3 py-2"
                  />
                </label>

                <div>
                  <button
                    type="submit"
                    disabled={busy}
                    className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
                  >
                    Post to every temple
                  </button>
                </div>
              </form>
            </section>
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
