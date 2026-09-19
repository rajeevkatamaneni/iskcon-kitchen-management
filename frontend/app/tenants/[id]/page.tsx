"use client";

import Link from "next/link";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { CookingLoader } from "@/components/CookingLoader";
import {
  api,
  toApiError,
  type ApiError,
  type TempleTemplateStatus,
  type TempleTemplateStatusView,
  type TenantDetail,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { BusyPot, Loading } from "@/components/Loading";
import { moment, templeDay } from "@/lib/format";
import { RULED_TABLE, THEAD, TR, TH_PRIMARY, TD_PRIMARY, TH_SECOND, TD_SECOND, TH_FIXED, TD_FIXED } from "@/components/ds/table";

export default function TenantDetailPage() {
  return (
    <RequireRole roles={["SUPER_ADMIN"]}>
      <TenantDetailView />
    </RequireRole>
  );
}

function TenantDetailView() {
  const params = useParams<{ id: string }>();
  const id = params.id;

  const fetcher = useCallback((token: string | undefined) => api.getTenant(id, token), [id]);
  const { data, error, loading, reload } = useAuthedQuery(fetcher);

  const [confirming, setConfirming] = useState(false);

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/tenants" />

      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-prose">
          <Link href="/tenants" className="text-sm text-ink-secondary hover:text-ink">
            Temples
          </Link>

          {loading ? (
            <Loading label="Loading temple…" />
          ) : error || !data ? (
            <div className="mt-4">
              <ErrorNotice error={error ?? toApiError(null, "We couldn’t load this temple.")} />
            </div>
          ) : (
            <>
              <header className="mb-8 mt-2 flex items-start justify-between gap-4">
                <div>
                  <h1>{data.name}</h1>
                  <p className="mt-1 text-ink-secondary">
                    Added {templeDay(data.created_at)}.
                  </p>
                </div>

                {/*
                  The way in to the correction screen (T-008). It sits in the header rather than
                  inside the panel below because it corrects every field in that panel, not one of
                  them — and because the two things this page already offers, export and delete,
                  are both destructive and neither is where a typo gets fixed. Behind the same
                  SUPER_ADMIN guard as this page: D-13 put a temple's profile on the operator's
                  side entirely, so there is nobody who can read this page and not use this link.
                */}
                <ButtonLink href={`/tenants/${id}/edit`} variant="secondary" className="shrink-0">
                  Edit details
                </ButtonLink>
              </header>

              <section className="card px-6 py-5">
                <dl className="grid grid-cols-1 gap-x-10 gap-y-4 sm:grid-cols-2">
                  <Detail label="People with accounts" value={String(data.user_count)} />
                  <Detail label="Timezone" value={data.timezone} />
                  <Detail label="Currency" value={data.currency} />
                  <Detail label="80G receipts" value={data.is_80g_approved ? "Approved" : "Not approved"} />
                  <Detail label="Address" value={data.address || "—"} />
                </dl>
              </section>

              <WhatsAppTemplatesSection id={id} />

              <section className="card mt-6 px-6 py-5">
                <h2 className="text-lg">Data export</h2>
                <p className="mt-1 text-sm text-ink-secondary">
                  Everything this temple holds, as a spreadsheet. Take one before deleting.
                </p>
                <div className="mt-3 flex flex-wrap items-center gap-3">
                  <ExportButton id={id} slug={data.slug} onExported={reload} />
                  <span className="text-sm text-ink-muted">
                    {data.last_export_at
                      ? `Last exported ${moment(data.last_export_at)}`
                      : "Never exported"}
                  </span>
                </div>
              </section>

              <section className="mt-6 rounded-lg border border-danger px-6 py-5">
                <h2 className="text-lg text-danger">Delete this temple</h2>
                <p className="mt-1 text-sm text-ink-secondary">
                  Permanently removes {data.name} and <strong>all</strong> of its data. This cannot
                  be undone.
                </p>
                <Button variant="danger" onClick={() => setConfirming(true)} className="mt-4">
                  Delete temple
                </Button>
              </section>

              {confirming && (
                <DeleteConfirm
                  id={id}
                  name={data.name}
                  slug={data.slug}
                  lastExportAt={data.last_export_at}
                  onExported={reload}
                  onCancel={() => setConfirming(false)}
                />
              )}
            </>
          )}
        </div>
      </main>
    </div>
  );
}

/** Meta's category, as a word. The stored value is upper case and is not printed as it is. */
const CATEGORY_LABEL: Record<string, string> = {
  UTILITY: "Utility",
  MARKETING: "Marketing",
  AUTHENTICATION: "Authentication",
};

/**
 * Meta's status, as a word. `REJECTED` reads "Refused", the word the rest of the app already uses for
 * Meta saying no to a template.
 */
const META_STATUS_LABEL: Record<string, string> = {
  APPROVED: "Approved",
  PENDING: "Pending",
  IN_APPEAL: "In appeal",
  REJECTED: "Refused",
  PAUSED: "Paused",
  DISABLED: "Disabled",
  LIMIT_EXCEEDED: "Limit exceeded",
  PENDING_DELETION: "Being deleted",
  DELETED: "Deleted",
  ARCHIVED: "Archived",
};

/** A value Meta sends that the map above does not know yet, still said as a word. */
function asWord(stored: string): string {
  const spaced = stored.replace(/_/g, " ").toLowerCase();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

function metaStatusWords(row: TempleTemplateStatus): string {
  if (row.held === null) return row.lookupProblem ?? "Meta did not answer for this message.";
  if (!row.held) return "Not held by Meta";
  if (!row.metaStatus) return "No status given";
  return META_STATUS_LABEL[row.metaStatus] ?? asWord(row.metaStatus);
}

function categoryWords(row: TempleTemplateStatus): string {
  if (!row.metaCategory) return "Not known";
  const theirs = CATEGORY_LABEL[row.metaCategory] ?? asWord(row.metaCategory);
  if (row.metaCategory === row.ourCategory) return theirs;
  return `${theirs}, app sends ${CATEGORY_LABEL[row.ourCategory] ?? asWord(row.ourCategory)}`;
}

function wordingWords(row: TempleTemplateStatus): string {
  if (row.wordingMatches === null) return "Not known";
  return row.wordingMatches ? "Matches" : "Differs";
}

/**
 * This temple's WhatsApp templates as Meta holds them (T-178): each one's status, Meta's category and
 * whether the wording matches, from the copy stored when the temple last reloaded or an operator last
 * refreshed.
 *
 * <p><strong>Collapsed by default, and nothing is fetched until it is opened.</strong> Twenty rows open
 * on arrival would push this page's export and delete, the two acts it exists for, below the fold. So
 * the section is one line until somebody asks for it.
 *
 * <p><strong>Refresh is an act on the temple, not a view.</strong> It asks Meta again with this temple's
 * own token and is recorded on the temple's audit log, which is why the line beside it says so. It creates
 * and edits nothing at Meta.
 */
function WhatsAppTemplatesSection({ id }: { id: string }) {
  const { getToken } = useAuth();
  const [open, setOpen] = useState(false);
  const [view, setView] = useState<TempleTemplateStatusView | null>(null);
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function toggle() {
    const opening = !open;
    setOpen(opening);
    if (!opening || view || loading) return;
    setLoading(true);
    setError(null);
    try {
      setView(await api.templeTemplateStatus(id, await getToken()));
    } catch (e) {
      setError(toApiError(e, "We couldn’t load this temple’s WhatsApp templates."));
    } finally {
      setLoading(false);
    }
  }

  async function refresh() {
    if (refreshing) return;
    setRefreshing(true);
    setError(null);
    try {
      setView(await api.refreshTempleTemplateStatus(id, await getToken()));
    } catch (e) {
      setError(toApiError(e, "We couldn’t ask Meta about this temple’s templates."));
    } finally {
      setRefreshing(false);
    }
  }

  return (
    <section className="card mt-6 px-6 py-5">
      <h2 className="text-lg">
        <button
          type="button"
          onClick={toggle}
          aria-expanded={open}
          aria-controls="temple-whatsapp-templates"
          className="flex w-full items-center justify-between gap-4 text-left"
        >
          <span>WhatsApp templates</span>
          <i className={`ti ti-chevron-${open ? "up" : "down"} text-ink-secondary`} aria-hidden="true" />
        </button>
      </h2>

      {open && (
        <div id="temple-whatsapp-templates" className="mt-3">
          {loading ? (
            <Loading label="Loading the templates…" />
          ) : (
            <>
              {view && (
                <div className="flex flex-wrap items-center gap-3">
                  <Button variant="secondary" onClick={refresh} busy={refreshing}>
                    {refreshing ? (
                      <span className="inline-flex items-center gap-2">
                        <BusyPot />
                        Asking Meta…
                      </span>
                    ) : (
                      "Refresh from Meta"
                    )}
                  </Button>
                  <span className="text-sm text-ink-muted">
                    {view.asOf ? `As of ${moment(view.asOf)}.` : "Meta has not been asked for this temple yet."}
                  </span>
                </div>
              )}
              {view && (
                <p className="mt-2 text-sm text-ink-secondary">
                  Refresh uses this temple’s own WhatsApp token. The temple’s audit log records it.
                </p>
              )}

              {error && (
                <div className="mt-3">
                  <ErrorNotice error={error} />
                </div>
              )}

              {view && view.templates.length > 0 && (
                <div className="mt-4 overflow-x-auto">
                  {/* On the table rule since 2026-09-18 (T-233). The message's name is the primary
                      flexible column. Meta status is usually one word but becomes Meta's whole
                      sentence when Meta did not answer, so Rajeev classified it as the secondary
                      flexible column; category and wording are short fixed answers (one line, reading left since T-236),
                      labelled in the phone's card layout where their headings are not shown. */}
                  <table className={`${RULED_TABLE} text-sm`}>
                    <thead className={THEAD}>
                      <tr>
                        <th className={TH_PRIMARY}>Message</th>
                        <th className={TH_SECOND}>Meta status</th>
                        <th className={TH_FIXED}>Category at Meta</th>
                        <th className={TH_FIXED}>Wording</th>
                      </tr>
                    </thead>
                    <tbody>
                      {view.templates.map((row) => (
                        <tr key={row.name} className={TR}>
                          <td className={`${TD_PRIMARY} font-mono`}>{row.name}</td>
                          <td className={TD_SECOND}>{metaStatusWords(row)}</td>
                          <td className={TD_FIXED} data-label="Category at Meta">{categoryWords(row)}</td>
                          <td className={TD_FIXED} data-label="Wording">{wordingWords(row)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          )}
        </div>
      )}
    </section>
  );
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-sm text-ink-secondary">{label}</dt>
      <dd className="mt-1">{value}</dd>
    </div>
  );
}

/** How recent an export has to be to count — the backend's own rule (E1-S15, D6). */
const EXPORT_VALID_HOURS = 24;

function exportIsRecent(lastExportAt: string | null): boolean {
  if (!lastExportAt) return false;
  const age = Date.now() - new Date(lastExportAt).getTime();
  return age < EXPORT_VALID_HOURS * 60 * 60 * 1000;
}

/**
 * Downloads the temple's data export. Separate from the delete dialog because an operator may want
 * a copy without deleting anything — but it is also the first step inside that dialog.
 */
function ExportButton({
  id,
  slug,
  onExported,
}: {
  id: string;
  slug: string;
  onExported: () => void;
}) {
  const { getToken } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function download() {
    setBusy(true);
    setError(null);
    try {
      const { blob, filename } = await api.exportTenant(id, slug, await getToken());
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
      onExported();
    } catch (e) {
      setError(toApiError(e, "We couldn’t export this temple’s data."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Button variant="secondary" onClick={download} busy={busy}>
        {busy ? (<span className="inline-flex items-center gap-2"><BusyPot />Preparing…</span>) : "Download data export"}
      </Button>
      {error && (
        <div className="w-full">
          <ErrorNotice error={error} />
        </div>
      )}
    </>
  );
}

/**
 * The type-the-name-to-confirm dialog. A generic "DELETE" becomes muscle memory; requiring the
 * temple's own name forces the operator to look at which temple they're about to erase.
 *
 * <p>It also refuses to arm without a recent data export. The backend enforces that too (KMS-400081) —
 * this is here so the operator meets the rule as a step to take, not as a refusal after the fact.
 */
function DeleteConfirm({
  id,
  name,
  slug,
  lastExportAt,
  onExported,
  onCancel,
}: {
  id: string;
  name: string;
  slug: string;
  lastExportAt: string | null;
  onExported: () => void;
  onCancel: () => void;
}) {
  const router = useRouter();
  const { getToken } = useAuth();
  const [confirmText, setConfirmText] = useState("");
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const exported = exportIsRecent(lastExportAt);
  const armed = confirmText.trim() === name && exported;

  async function doDelete() {
    if (!armed || deleting) return;
    setDeleting(true);
    setError(null);
    try {
      await api.deleteTenant(id, await getToken());
      router.push(`/tenants?deleted=${encodeURIComponent(name)}`);
    } catch (e) {
      setError(toApiError(e, "We couldn’t delete the temple."));
      setDeleting(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 px-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="delete-title"
    >
      <div className="modal w-full max-w-prose px-8 py-7">
        {deleting ? (
          <div className="flex flex-col items-center gap-4 py-4 text-center">
            <CookingLoader className="h-12 w-12 text-danger" />
            <p className="font-medium">Deleting {name}…</p>
          </div>
        ) : (
          <>
            <h2 id="delete-title" className="text-lg text-danger">
              Delete {name}?
            </h2>
            <p className="mt-2 text-sm text-ink-secondary">
              This erases the temple and every bit of its data, permanently.
            </p>

            <div
              className={`mt-4 rounded-sm px-4 py-3 text-sm ${
                exported ? "bg-success-bg text-success" : "bg-danger-bg text-danger"
              }`}
            >
              {exported ? (
                <p>Data export taken {moment(lastExportAt!)}.</p>
              ) : (
                <>
                  <p className="font-medium">
                    You haven’t exported this temple’s data. It cannot be recovered.
                  </p>
                  <div className="mt-3 flex flex-wrap items-center gap-3">
                    <ExportButton id={id} slug={slug} onExported={onExported} />
                  </div>
                </>
              )}
            </div>

            <p className="mt-4 text-sm text-ink-secondary">To confirm, type its name exactly:</p>
            <p className="mt-2 font-mono text-sm">{name}</p>

            {error && (
              <div className="mt-4">
                <ErrorNotice error={error} />
              </div>
            )}

            <input
              type="text"
              autoFocus
              value={confirmText}
              onChange={(e) => setConfirmText(e.target.value)}
              aria-label="Type the temple’s name to confirm"
              placeholder={name}
              className="mt-4 min-h-touch w-full rounded-control border border-hairline-strong px-3 text-base"
            />

            <div className="mt-6 flex items-center justify-end gap-3">
              <Button variant="secondary" onClick={onCancel}>
                Cancel
              </Button>
              <Button variant="danger" onClick={doDelete} disabled={!armed}>
                Delete temple
              </Button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

