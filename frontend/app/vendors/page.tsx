"use client";

import Link from "next/link";
import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { api, toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { languageLabel } from "@/lib/languages";
import { Loading } from "@/components/Loading";

export default function VendorsPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      {/* useSearchParams — for the confirmation a new vendor comes back with — needs a boundary. */}
      <Suspense>
        <VendorsView />
      </Suspense>
    </RequireRole>
  );
}

function VendorsView() {
  const { getToken } = useAuth();
  const [activeOnly, setActiveOnly] = useState(false);
  const fetchVendors = useCallback(
    (token: string | undefined) => api.listVendors(activeOnly, token),
    [activeOnly]
  );
  const { data, error, loading, reload } = useAuthedQuery(fetchVendors);
  const vendors = data ?? [];

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);

  // Adding a vendor happens on /vendors/new and ends back here, so the confirmation has to travel
  // in the URL. Captured behind a ref because setting it re-renders, and a router object that is
  // new on each render would otherwise turn this effect into a loop.
  const router = useRouter();
  const added = useSearchParams().get("added");
  const [flash, setFlash] = useState<string | null>(null);
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current || !added) return;
    captured.current = true;
    setFlash(added);
    router.replace("/vendors");
  }, [added, router]);

  async function run(mutation: (token: string | undefined) => Promise<unknown>, failure: string) {
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

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/vendors" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Vendors</h1>
              <p className="mt-1 text-ink-secondary">
                The WhatsApp number a purchase order goes to.
              </p>
            </div>
            <ButtonLink href="/vendors/new">Add a vendor</ButtonLink>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

          {flash && (
            <div className="mb-6">
              <InlineNotice tone="success" autoDismiss title={`${flash} was added.`}>
                Set which ingredients they supply so the order list can suggest them.
              </InlineNotice>
            </div>
          )}

          <div className="mb-4">
            <label className="text-sm text-ink-secondary">
              <input type="checkbox" checked={activeOnly} onChange={(e) => setActiveOnly(e.target.checked)} className="mr-2 align-middle" />
              Active vendors only
            </label>
          </div>

          {loading ? (
            <Loading label="Loading vendors…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : vendors.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">No vendors yet</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                Add a vendor, then set which ingredients they supply.
              </p>
            </div>
          ) : (
            <div className="overflow-hidden rounded-lg bg-raised">
              <table className="w-full text-left">
                <thead className="bg-sunken text-sm text-ink-secondary">
                  <tr>
                    <th className="px-5 py-3 font-medium">Vendor</th>
                    <th className="px-5 py-3 font-medium">Phone</th>
                    <th className="px-5 py-3 font-medium">Language</th>
                    <th className="px-5 py-3 font-medium">Status</th>
                    <th className="px-5 py-3 font-medium text-right">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {vendors.map((v) => (
                    <tr key={v.id} className="border-t border-hairline align-middle hover:bg-raised/60">
                      <td className="px-5 py-3">
                        <Link href={`/vendors/${v.id}`} className="font-medium text-accent-text hover:underline">
                          {v.name}
                        </Link>
                        {v.contactPerson && <span className="ml-2 text-xs text-ink-muted">{v.contactPerson}</span>}
                      </td>
                      <td className="px-5 py-3 text-ink-secondary tabular-nums">{v.phone}</td>
                      <td className="px-5 py-3 text-ink-secondary">{languageLabel(v.preferredLanguage)}</td>
                      <td className="px-5 py-3">
                        <div className="flex flex-wrap gap-1.5">
                          {v.active ? (
                            <span className="rounded-sm bg-success-bg px-2 py-1 text-xs text-success font-semibold">Active</span>
                          ) : (
                            <span className="rounded-sm bg-sunken px-2 py-1 text-xs text-ink-muted font-semibold">Inactive</span>
                          )}
                          {!v.whatsappReachable && (
                            <span className="rounded-sm bg-warning-bg px-2 py-1 text-xs text-warning font-semibold">Recheck WhatsApp</span>
                          )}
                        </div>
                      </td>
                      <td className="px-5 py-3 text-right">
                        {v.active ? (
                          <button type="button" disabled={busy} onClick={() => run((t) => api.deactivateVendor(v.id, t), "We couldn’t deactivate that vendor.")} className="text-sm text-ink-secondary hover:underline disabled:opacity-60">
                            Deactivate
                          </button>
                        ) : (
                          <button type="button" disabled={busy} onClick={() => run((t) => api.reactivateVendor(v.id, t), "We couldn’t reactivate that vendor.")} className="text-sm text-accent-text hover:underline disabled:opacity-60">
                            Reactivate
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
    </div>
  );
}
