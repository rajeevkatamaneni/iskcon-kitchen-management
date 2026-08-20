"use client";

import { useCallback, useState } from "react";
import Link from "next/link";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import {
  api,
  toApiError,
  type ApiError,
  type BanCategory,
  type EmploymentBanView,
} from "@/lib/api";

/**
 * The records this temple has raised about people it dismissed (B9).
 *
 * <p>It sits under the staff register rather than in the main navigation, and that is deliberate:
 * this is not daily work, it should take a moment to reach, and putting it one click from the roster
 * would make it feel like an ordinary part of running a kitchen. It is not.
 *
 * <p><b>Only this temple's own records appear here.</b> There is no screen anywhere in the product
 * that shows anybody else's, and no endpoint that would serve one. The only way another temple's
 * record is ever seen is as a finding on a hire that is actually being made.
 *
 * <p>The page carries more weight than its size suggests. The person a record is about is never
 * shown it and cannot answer it, so the correction has to come from here: taking a record back, and
 * the ten years after which it stops being shown, are between them the whole of the remedy for a
 * wrong entry. Both are on the screen in plain words for that reason.
 */
export default function StaffBansPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <BansView />
    </RequireRole>
  );
}

function BansView() {
  const { getToken } = useAuth();
  const bans = useAuthedQuery(useCallback((t: string | undefined) => api.templeBans(t), []));
  const categories = useAuthedQuery(useCallback((t: string | undefined) => api.banCategories(t), []));

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [retracting, setRetracting] = useState<string | null>(null);

  const rows = bans.data ?? [];
  const options = categories.data ?? [];

  async function run(mutation: (t: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    setActionError(null);
    try {
      await mutation(await getToken());
      bans.reload();
      return true;
    } catch (e) {
      setActionError(toApiError(e, failure));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function submitAmend(event: React.FormEvent<HTMLFormElement>, id: string) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    const ok = await run(
      (t) =>
        api.amendBan(
          id,
          {
            category: String(f.get("category")) as BanCategory,
            account: String(f.get("account") ?? "").trim(),
          },
          t
        ),
      "We couldn't save that correction."
    );
    if (ok) setEditing(null);
  }

  async function submitRetract(event: React.FormEvent<HTMLFormElement>, id: string) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    const reason = String(f.get("reason") ?? "").trim();
    const ok = await run(
      (t) => api.retractBan(id, reason === "" ? null : reason, t),
      "We couldn't take that record back."
    );
    if (ok) setRetracting(null);
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/staff" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6">
            <Link href="/staff" className="text-sm text-accent-text hover:underline">
              ← Staff
            </Link>
            <h1 className="mt-2">Records we have raised</h1>
            <p className="mt-1 max-w-prose text-ink-secondary">
              People your temple dismissed and chose to warn other temples about. Every temple that
              tries to hire one of them is shown your temple&rsquo;s name and what you wrote, for ten
              years from the date it was recorded.
            </p>
          </header>

          <div className="mb-6">
            <InlineNotice tone="info">
              You can only see your own temple&rsquo;s records here — there is no list of anybody
              else&rsquo;s, by design. If one of these is wrong, take it back: the person it is about
              is not shown it and cannot correct it themselves.
            </InlineNotice>
          </div>

          {actionError && (
            <div className="mb-6">
              <ErrorNotice error={actionError} />
            </div>
          )}

          {bans.loading ? (
            <Loading label="Loading records…" />
          ) : bans.error ? (
            <ErrorNotice error={bans.error} />
          ) : rows.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="mx-auto max-w-prose text-ink-secondary">
                Your temple has raised none. That is the ordinary state of this page.
              </p>
            </div>
          ) : (
            <ul className="grid gap-4">
              {rows.map((ban) => (
                <li key={ban.id} className="rounded-lg bg-raised px-6 py-5">
                  <BanRow
                    ban={ban}
                    busy={busy}
                    categories={options}
                    editing={editing === ban.id}
                    retracting={retracting === ban.id}
                    onEdit={() => {
                      setRetracting(null);
                      setEditing(editing === ban.id ? null : ban.id);
                    }}
                    onRetract={() => {
                      setEditing(null);
                      setRetracting(retracting === ban.id ? null : ban.id);
                    }}
                    onSubmitAmend={(e) => submitAmend(e, ban.id)}
                    onSubmitRetract={(e) => submitRetract(e, ban.id)}
                  />
                </li>
              ))}
            </ul>
          )}
        </div>
      </main>
    </div>
  );
}

function BanRow({
  ban,
  busy,
  categories,
  editing,
  retracting,
  onEdit,
  onRetract,
  onSubmitAmend,
  onSubmitRetract,
}: {
  ban: EmploymentBanView;
  busy: boolean;
  categories: { value: BanCategory; label: string }[];
  editing: boolean;
  retracting: boolean;
  onEdit: () => void;
  onRetract: () => void;
  onSubmitAmend: (e: React.FormEvent<HTMLFormElement>) => void;
  onSubmitRetract: (e: React.FormEvent<HTMLFormElement>) => void;
}) {
  return (
    <>
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-lg">
          {ban.personName}
          {ban.retracted && (
            <span className="ml-3 rounded bg-sunken px-2 py-0.5 text-xs text-ink-muted">
              taken back
            </span>
          )}
        </h2>
        <span className="text-sm text-ink-muted tabular-nums">
          Recorded {ban.raisedAt.slice(0, 10)}
          {ban.raisedBy ? ` by ${ban.raisedBy}` : ""}
        </span>
      </div>

      <p className="mt-1 text-sm text-ink-secondary">{ban.categoryLabel}</p>
      <p className="mt-2 whitespace-pre-line">{ban.account}</p>

      <p className="mt-2 text-sm text-ink-muted">
        {ban.retracted
          ? `Taken back${ban.retractedAt ? ` on ${ban.retractedAt.slice(0, 10)}` : ""}. It stays on file and is no longer shown at any hire.`
          : `Shown to hiring temples until ${ban.fadesOn}.`}
      </p>
      {ban.retracted && ban.retractionReason && (
        <p className="mt-1 text-sm text-ink-secondary">{ban.retractionReason}</p>
      )}

      {!ban.retracted && (
        <div className="mt-4 flex flex-wrap items-center gap-4 text-sm">
          <button type="button" onClick={onEdit} disabled={busy} className="text-accent-text hover:underline disabled:opacity-60">
            Correct what it says
          </button>
          <button type="button" onClick={onRetract} disabled={busy} className="text-danger hover:underline disabled:opacity-60">
            Take it back
          </button>
        </div>
      )}

      {editing && !ban.retracted && (
        <form className="mt-4 grid gap-3" aria-label="Correct this record" onSubmit={onSubmitAmend}>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            Category
            <select name="category" defaultValue={ban.category} required className={FIELD}>
              {categories.map((c) => (
                <option key={c.value} value={c.value}>
                  {c.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            What happened, in your own words
            <textarea name="account" required rows={4} defaultValue={ban.account} className={`${FIELD} py-2`} />
          </label>
          <div>
            <button
              type="submit"
              disabled={busy}
              className="min-h-touch rounded bg-accent px-5 text-ink-inverse hover:bg-accent-hover disabled:opacity-60"
            >
              Save the correction
            </button>
          </div>
        </form>
      )}

      {retracting && !ban.retracted && (
        <form className="mt-4 grid gap-3" aria-label="Take this record back" onSubmit={onSubmitRetract}>
          <InlineNotice tone="warning">
            It stops being shown at hires straight away. The record stays on file with your reason on
            it, so that a mistake is visible rather than quietly erased.
          </InlineNotice>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            Why are you taking it back? (optional)
            <input name="reason" className={FIELD} />
          </label>
          <div>
            <button
              type="submit"
              disabled={busy}
              className="min-h-touch rounded bg-danger-bg px-5 text-danger hover:brightness-95 disabled:opacity-60"
            >
              Take it back
            </button>
          </div>
        </form>
      )}
    </>
  );
}

const FIELD = "min-h-touch rounded border border-hairline bg-canvas px-3";
