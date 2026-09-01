"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { CookingLoader } from "@/components/CookingLoader";
import { api, toApiError, type ApiError, type TenantDetail } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { BusyPot, Loading } from "@/components/Loading";
import { moment, templeDay } from "@/lib/format";

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
              <header className="mb-8 mt-2">
                <h1>{data.name}</h1>
                <p className="mt-1 text-ink-secondary">
                  Added {templeDay(data.created_at)}.
                </p>
              </header>

              <section className="rounded-lg bg-raised px-6 py-5">
                <dl className="grid grid-cols-1 gap-x-10 gap-y-4 sm:grid-cols-2">
                  <Detail label="People with accounts" value={String(data.user_count)} />
                  <Detail label="Timezone" value={data.timezone} />
                  <Detail label="Currency" value={data.currency} />
                  <Detail label="80G receipts" value={data.is_80g_approved ? "Approved" : "Not approved"} />
                  <Detail label="Address" value={data.address || "—"} />
                </dl>
              </section>

              <section className="mt-6 rounded-lg bg-raised px-6 py-5">
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

              <section className="mt-6 rounded-lg border border-danger/30 px-6 py-5">
                <h2 className="text-lg text-danger">Delete this temple</h2>
                <p className="mt-1 text-sm text-ink-secondary">
                  Permanently removes {data.name} and <strong>all</strong> of its data. This cannot
                  be undone.
                </p>
                <button
                  type="button"
                  onClick={() => setConfirming(true)}
                  className="mt-4 min-h-touch rounded-sm border border-danger px-5 text-sm text-danger transition-colors duration-state hover:bg-danger-bg"
                >
                  Delete temple
                </button>
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
      <button
        type="button"
        onClick={download}
        disabled={busy}
        className="min-h-touch rounded-sm border border-hairline-strong px-5 text-sm transition-colors duration-state hover:bg-canvas disabled:opacity-60"
      >
        {busy ? (<span className="inline-flex items-center gap-2"><BusyPot />Preparing…</span>) : "Download data export"}
      </button>
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
 * <p>It also refuses to arm without a recent data export. The backend enforces that too (KMS-4941) —
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
      <div className="w-full max-w-prose rounded-lg border border-hairline bg-canvas px-8 py-7">
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
              className="mt-4 min-h-touch w-full rounded-sm border border-hairline-strong bg-canvas px-3 text-base"
            />

            <div className="mt-6 flex items-center justify-end gap-3">
              <button
                type="button"
                onClick={onCancel}
                className="min-h-touch rounded-sm border border-hairline-strong px-5 text-sm transition-colors duration-state hover:bg-raised"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={doDelete}
                disabled={!armed}
                className="min-h-touch rounded-sm bg-danger px-5 text-sm text-ink-inverse transition-colors duration-state hover:opacity-90 disabled:opacity-40"
              >
                Delete temple
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

