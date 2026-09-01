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
import { money } from "@/lib/format";
import { Loading } from "@/components/Loading";
import { TABLE, THEAD, TR, TH_TEXT, TH_NUM, TH_ACTIONS, TD_TEXT, TD_NUM, TD_ACTIONS, WRAP } from "@/components/ds/table";
import { Button } from "@/components/ds/Button";

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
            <div className="overflow-x-auto rounded-lg bg-raised">
              <table className={TABLE}>
                <thead className={THEAD}>
                  <tr>
                    <th className={`${TH_TEXT} ${WRAP}`}>Item</th>
                    <th className={TH_NUM}>Price</th>
                    <th className={TH_NUM}>Received</th>
                    <th className={TH_TEXT}>Status</th>
                    <th className={TH_ACTIONS}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((i) => (
                    <tr key={i.id} className={TR}>
                      <td className={`${TD_TEXT} ${WRAP} font-medium`}>{i.title}<span className="ml-2 text-xs text-ink-muted">{sentence(i.category)}</span></td>
                      <td className={TD_NUM}>{money(i.priceInr, "INR")}</td>
                      <td className={TD_NUM}>{money(i.paidInr, "INR")} of {money(i.priceInr * i.quantityWanted, "INR")}</td>
                      <td className={TD_TEXT}>
                        <span className={`rounded-sm px-2 py-1 text-xs ${i.status === "FULFILLED" ? "bg-success-bg text-success" : i.status === "ARCHIVED" ? "bg-sunken text-ink-muted" : "bg-accent-bg text-accent-text"}`}>
                          {sentence(i.status)}
                        </span>
                      </td>
                      <td className={TD_ACTIONS}>
                        <Button variant="ghost" size="sm" disabled={busy} onClick={() => run((t) => api.archiveWishlistItem(i.id, t), "We couldn’t archive that item.")}>
                          Archive
                        </Button>
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
 * An enum from the API, said the way the rest of the site says everything: sentence case, capital
 * on the first word only. `CONSUMABLE` used to render as `consumable` here and as `FULFILLED` did
 * in the status pill beside it, which put three different cases in one table row.
 */
function sentence(value: string): string {
  const words = value.toLowerCase().replace(/_/g, " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}
