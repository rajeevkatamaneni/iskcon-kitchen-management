"use client";

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
import { Loading } from "@/components/Loading";

export default function WishlistAdminPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      {/* useSearchParams — for the confirmation a new item comes back with. */}
      <Suspense>
        <WishlistAdminView />
      </Suspense>
    </RequireRole>
  );
}

function WishlistAdminView() {
  const { getToken } = useAuth();
  const { data, error, loading, reload } = useAuthedQuery(
    useCallback((t: string | undefined) => api.listWishlist(false, t), [])
  );
  const items = data ?? [];

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);

  // Adding happens on /wishlist/new and ends here, so the confirmation travels in the URL. The ref
  // guards the capture against a router object that is new on every render.
  const router = useRouter();
  const added = useSearchParams().get("added");
  const [flash, setFlash] = useState<string | null>(null);
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current || !added) return;
    captured.current = true;
    setFlash(added);
    router.replace("/wishlist");
  }, [added, router]);

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

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/wishlist" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Wish list</h1>
            </div>
            <ButtonLink href="/wishlist/new">Add an item</ButtonLink>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

          {flash && (
            <div className="mb-6">
              <InlineNotice tone="success" autoDismiss title={`${flash} is on the wish list.`}>
                Devotees can sponsor it from the temple’s giving page.
              </InlineNotice>
            </div>
          )}

          {loading ? (
            <Loading label="Loading wish list…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : items.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">Nothing on the wish list</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">Add an item so devotees can sponsor it.</p>
            </div>
          ) : (
            <div className="overflow-hidden rounded-lg bg-raised">
              <table className="w-full text-left">
                <thead className="bg-sunken text-sm text-ink-secondary">
                  <tr>
                    <th className="px-5 py-3 font-medium">Item</th>
                    <th className="px-5 py-3 font-medium text-right">Price</th>
                    <th className="px-5 py-3 font-medium text-right">Received</th>
                    <th className="px-5 py-3 font-medium">Status</th>
                    <th className="px-5 py-3 font-medium text-right">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((i) => (
                    <tr key={i.id} className="border-t border-hairline align-middle hover:bg-sunken">
                      <td className="px-5 py-3 font-medium">{i.title}<span className="ml-2 text-xs text-ink-muted">{sentence(i.category)}</span></td>
                      <td className="px-5 py-3 text-right tabular-nums">{rupees(i.priceInr)}</td>
                      <td className="px-5 py-3 text-right tabular-nums">{rupees(i.paidInr)} of {rupees(i.priceInr * i.quantityWanted)}</td>
                      <td className="px-5 py-3">
                        <span className={`rounded-sm px-2 py-1 text-xs ${i.status === "FULFILLED" ? "bg-success-bg text-success" : i.status === "ARCHIVED" ? "bg-sunken text-ink-muted" : "bg-accent-bg text-accent-text"}`}>
                          {sentence(i.status)}
                        </span>
                      </td>
                      <td className="px-5 py-3 text-right">
                        <button type="button" disabled={busy} onClick={() => run((t) => api.archiveWishlistItem(i.id, t), "We couldn’t archive that item.")} className="text-sm text-ink-secondary hover:underline disabled:opacity-60">
                          Archive
                        </button>
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

/**
 * Rupees, grouped the Indian way. The wish-list column used to count units — three of ten sacks —
 * but an item is bought whole out of whatever has come in, so what an admin needs to see is the
 * money against the price rather than a tally of objects nobody buys one at a time.
 */
function rupees(amount: number): string {
  return `₹${Number(amount).toLocaleString("en-IN")}`;
}

/**
 * An enum from the API, said the way the rest of the site says everything: sentence case, capital
 * on the first word only. `CONSUMABLE` used to render as `consumable` here and as `FULFILLED` did
 * in the status pill beside it, which put three different cases in one table row.
 */
function sentence(value: string): string {
  const words = value.toLowerCase().replace(/_/g, " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}
