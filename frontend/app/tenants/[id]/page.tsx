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

  const [origin, setOrigin] = useState("");
  const [copied, setCopied] = useState(false);
  const [confirming, setConfirming] = useState(false);
  useEffect(() => setOrigin(window.location.origin), []);

  const publicUrl = data ? `${origin}/t/${data.slug}` : "";

  async function copyUrl() {
    try {
      await navigator.clipboard.writeText(publicUrl);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard access can be denied; the address is visible to copy by hand.
    }
  }

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
              <ErrorNotice error={error ?? toApiError(null, "We couldn't load this temple.")} />
            </div>
          ) : (
            <>
              <header className="mb-8 mt-2">
                <h1>{data.name}</h1>
                <p className="mt-1 text-ink-secondary">
                  Added {new Date(data.created_at).toLocaleDateString()}.
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
                <h2 className="text-lg">Web address</h2>
                <p className="mt-1 text-sm text-ink-secondary">
                  This temple&rsquo;s public page — where devotees find its donations and wishlist.
                  Copy it to share with the temple.
                </p>
                <div className="mt-3 flex items-stretch gap-2">
                  <div className="flex min-h-touch flex-1 items-center overflow-x-auto whitespace-nowrap rounded-sm border border-hairline bg-sunken px-3 font-mono text-sm text-ink-secondary">
                    {publicUrl}
                  </div>
                  <button
                    type="button"
                    onClick={copyUrl}
                    className="min-h-touch shrink-0 rounded-sm border border-hairline-strong px-4 text-sm transition-colors duration-state hover:bg-canvas"
                  >
                    {copied ? "Copied" : "Copy"}
                  </button>
                </div>
              </section>

              <section className="mt-6 rounded-lg bg-raised px-6 py-5">
                <h2 className="text-lg">Data export</h2>
                <p className="mt-1 text-sm text-ink-secondary">
                  Everything this temple holds, as a spreadsheet — one tab per kind of record, each
                  with column headings and filters. Take one before deleting: it is the only copy.
                </p>
                <div className="mt-3 flex flex-wrap items-center gap-3">
                  <ExportButton id={id} slug={data.slug} onExported={reload} />
                  <span className="text-sm text-ink-muted">
                    {data.last_export_at
                      ? `Last exported ${new Date(data.last_export_at).toLocaleString()}`
                      : "Never exported"}
                  </span>
                </div>
              </section>

              <section className="mt-6 rounded-lg border border-danger/30 px-6 py-5">
                <h2 className="text-lg text-danger">Delete this temple</h2>
                <p className="mt-1 text-sm text-ink-secondary">
                  Permanently removes {data.name} and <strong>all</strong> of its data — recipes,
                  inventory, orders, staff, donations, and history. This cannot be undone.
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
      setError(toApiError(e, "We couldn't export this temple's data."));
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
      setError(toApiError(e, "We couldn't delete the temple."));
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
                <p>Data export taken {new Date(lastExportAt!).toLocaleString()}.</p>
              ) : (
                <>
                  <p className="font-medium">
                    You haven&rsquo;t exported this temple&rsquo;s data. Once deleted, it cannot be
                    recovered.
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
              aria-label="Type the temple's name to confirm"
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

