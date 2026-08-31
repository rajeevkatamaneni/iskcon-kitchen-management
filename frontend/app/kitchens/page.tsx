"use client";

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Badge } from "@/components/ds/Badge";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { EmptyState } from "@/components/ds/EmptyState";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { Loading } from "@/components/Loading";
import Link from "next/link";
import { api, toApiError, type ApiError, type Kitchen } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * The kitchens a temple runs (E10-S3).
 *
 * <p>Flat, by D1: every kitchen hangs off the temple and one of them carries the main-kitchen
 * label. Which kitchens a temple runs is a structural fact about the temple rather than a daily
 * kitchen act, which is why this screen is Temple Admin only.
 */
export default function KitchensPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      {/* useSearchParams — for the confirmation a saved kitchen comes back with — needs a boundary. */}
      <Suspense>
        <KitchensView />
      </Suspense>
    </RequireRole>
  );
}

function KitchensView() {
  const { getToken } = useAuth();
  const [includeArchived, setIncludeArchived] = useState(false);
  const fetcher = useCallback((token: string | undefined) => api.listKitchens(includeArchived, token), [includeArchived]);
  const { data, error, loading, reload } = useAuthedQuery(fetcher);
  const kitchens = data ?? [];

  // Adding and editing both happen on their own URL and end back here, so the confirmation travels
  // in the address bar. Captured behind a ref because setting it re-renders, and a router object
  // that is new on each render would otherwise turn this effect into a loop.
  const router = useRouter();
  const searchParams = useSearchParams();
  const added = searchParams.get("added");
  const updated = searchParams.get("updated");
  const [flash, setFlash] = useState<{ kind: "added" | "updated"; name: string } | null>(null);
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current) return;
    if (added) {
      captured.current = true;
      setFlash({ kind: "added", name: added });
      router.replace("/kitchens");
    } else if (updated) {
      captured.current = true;
      setFlash({ kind: "updated", name: updated });
      router.replace("/kitchens");
    }
  }, [added, updated, router]);

  // Let the banner stand, then clear itself. Keyed on `flash` so stripping the param above does not
  // cut the timer short.
  useEffect(() => {
    if (!flash) return;
    const timer = setTimeout(() => setFlash(null), 6000);
    return () => clearTimeout(timer);
  }, [flash]);

  const [confirming, setConfirming] = useState<Kitchen | null>(null);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  async function restore(kitchen: Kitchen) {
    setBusy(true);
    setActionError(null);
    try {
      await api.restoreKitchen(kitchen.id, await getToken());
      reload();
    } catch (e) {
      setActionError(toApiError(e, "We couldn’t bring that kitchen back."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/kitchens" />

      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-8 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Kitchens</h1>
              <p className="mt-1 text-ink-secondary">
                The kitchens this temple runs, and how each of them gets its ingredients.
              </p>
            </div>
            <ButtonLink href="/kitchens/new">Add a kitchen</ButtonLink>
          </header>

          {actionError && (
            <div className="mb-6">
              <ErrorNotice error={actionError} />
            </div>
          )}

          {flash && (
            <div className="mb-6">
              <InlineNotice
                tone="success"
                autoDismiss
                title={flash.kind === "added" ? `${flash.name} was added.` : `${flash.name} was saved.`}
              >
                {flash.kind === "added"
                  ? "It can be named on an ingredient request now."
                  : "Everyone sees the change from their next screen."}
              </InlineNotice>
            </div>
          )}

          <label className="mb-4 flex items-center gap-2 text-sm text-ink-secondary">
            <input type="checkbox" checked={includeArchived}
              onChange={(e) => setIncludeArchived(e.target.checked)}
              className="h-4 w-4" />
            <span>Show archived kitchens</span>
          </label>

          {loading ? (
            <Loading label="Loading kitchens…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : kitchens.length === 0 ? (
            <EmptyState
              title="No kitchens yet"
              action={<ButtonLink href="/kitchens/new">Add a kitchen</ButtonLink>}
            >
              A kitchen is who the store is issuing to, and who a request for ingredients comes from.
              The first one you add is the temple’s main kitchen.
            </EmptyState>
          ) : (
            <div className="overflow-hidden rounded-lg bg-raised">
              <table className="w-full text-left">
                <thead className="bg-sunken text-sm text-ink-secondary">
                  <tr>
                    <th className="px-5 py-3 font-medium">Kitchen</th>
                    <th className="px-5 py-3 font-medium">Where</th>
                    <th className="px-5 py-3 font-medium">Who runs it</th>
                    <th className="px-5 py-3 font-medium">Gets ingredients by</th>
                    <th className="px-5 py-3 font-medium">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {kitchens.map((k) => (
                    <tr key={k.id} className="border-t border-hairline align-middle hover:bg-sunken">
                      <td className="px-5 py-3">
                        <span className="flex flex-wrap items-center gap-2">
                          <span className="font-medium">{k.name}</span>
                          {k.isMain && <Badge tone="accent">Main kitchen</Badge>}
                          {k.status === "ARCHIVED" && <Badge>Archived</Badge>}
                        </span>
                        {k.description && (
                          <span className="mt-0.5 block text-sm text-ink-secondary">{k.description}</span>
                        )}
                      </td>
                      <td className="px-5 py-3 text-ink-secondary">{k.location ?? "—"}</td>
                      <td className="px-5 py-3 text-ink-secondary">{k.inChargeName ?? "Nobody yet"}</td>
                      <td className="px-5 py-3 text-ink-secondary">
                        {k.usesMealPlanner ? "Its own meal plan" : "Asking the store"}
                      </td>
                      <td className="px-5 py-3 text-sm">
                        <Link href={`/kitchens/${k.id}/edit`} className="text-accent-text hover:underline">
                          Edit
                        </Link>
                        <span className="mx-2 text-ink-muted">·</span>
                        {k.status === "ARCHIVED" ? (
                          <button type="button" disabled={busy} onClick={() => restore(k)}
                            className="text-accent-text hover:underline">
                            Restore
                          </button>
                        ) : (
                          <button type="button" onClick={() => { setActionError(null); setConfirming(k); }}
                            className="text-danger hover:underline">
                            Delete
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </main>

      {confirming && (
        <DeleteKitchen
          kitchen={confirming}
          onDone={() => {
            setConfirming(null);
            reload();
          }}
          onCancel={() => setConfirming(null)}
        />
      )}
    </div>
  );
}

/**
 * Delete, and the answer the store's history forces.
 *
 * <p>A kitchen named on requests the store has already answered cannot be deleted without orphaning
 * that record, so the server refuses with `KMS-4973`. Rather than leave the person at a dead end,
 * the same confirmation turns into the offer that is actually available — archive it, which takes
 * it off the list and leaves everything it has asked for readable.
 */
function DeleteKitchen({
  kitchen,
  onDone,
  onCancel,
}: {
  kitchen: Kitchen;
  onDone: () => void;
  onCancel: () => void;
}) {
  const { getToken } = useAuth();
  const [inUse, setInUse] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function remove() {
    setBusy(true);
    setError(null);
    try {
      await api.deleteKitchen(kitchen.id, await getToken());
      onDone();
    } catch (e) {
      const failed = toApiError(e, "We couldn’t delete that kitchen.");
      if (failed.code === "KMS-4973") setInUse(true);
      else setError(failed);
      setBusy(false);
    }
  }

  async function archive() {
    setBusy(true);
    setError(null);
    try {
      await api.archiveKitchen(kitchen.id, await getToken());
      onDone();
    } catch (e) {
      setError(toApiError(e, "We couldn’t archive that kitchen."));
      setBusy(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 px-4"
      role="dialog" aria-modal="true" aria-labelledby="delete-kitchen-title">
      <div className="w-full max-w-prose rounded-lg border border-hairline bg-canvas px-8 py-7">
        {inUse ? (
          <>
            <h2 id="delete-kitchen-title" className="text-lg">
              {kitchen.name} cannot be deleted
            </h2>
            <p className="mt-2 text-sm text-ink-secondary">
              It has already asked the store for things, and that history stays readable. Archiving
              takes it off this list and off every picker, and leaves the record exactly as it is.
            </p>
          </>
        ) : (
          <>
            <h2 id="delete-kitchen-title" className="text-lg text-danger">
              Delete {kitchen.name}?
            </h2>
            <p className="mt-2 text-sm text-ink-secondary">
              This removes the kitchen from the temple. It cannot be undone.
            </p>
          </>
        )}

        {error && (
          <div className="mt-4">
            <ErrorNotice error={error} />
          </div>
        )}

        <div className="mt-6 flex flex-wrap items-center justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
          {inUse ? (
            <Button type="button" onClick={archive} disabled={busy}>
              Archive it instead
            </Button>
          ) : (
            <Button type="button" variant="danger" onClick={remove} disabled={busy}>
              Delete kitchen
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}
