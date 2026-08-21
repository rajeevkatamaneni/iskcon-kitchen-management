"use client";

import Link from "next/link";
import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { api } from "@/lib/api";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";

const UNIT_LABEL: Record<string, string> = { KG: "Kg", GM: "gm", L: "L", ML: "ml", PIECES: "pieces" };

export default function InventoryPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      {/* useSearchParams — for the confirmation a newly tracked item comes back with. */}
      <Suspense>
        <InventoryView />
      </Suspense>
    </RequireRole>
  );
}

function InventoryView() {
  const fetchInventory = useCallback((token: string | undefined) => api.listInventory({}, token), []);
  const { data, error, loading } = useAuthedQuery(fetchInventory);
  const items = data ?? [];

  const [locationFilter, setLocationFilter] = useState("");
  const [onlyLow, setOnlyLow] = useState(false);

  // Tracking starts on /inventory/new and ends here, so the confirmation travels in the URL. The
  // ref guards the capture against a router object that is new on every render.
  const router = useRouter();
  const tracking = useSearchParams().get("tracking");
  const [flash, setFlash] = useState<string | null>(null);
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current || !tracking) return;
    captured.current = true;
    setFlash(tracking);
    router.replace("/inventory");
  }, [tracking, router]);

  const locations = useMemo(
    () => [...new Set(items.map((i) => i.storageLocation).filter(Boolean))] as string[],
    [items]
  );

  const visible = items.filter(
    (i) => (!locationFilter || i.storageLocation === locationFilter) && (!onlyLow || i.belowThreshold)
  );
  const lowCount = items.filter((i) => i.belowThreshold).length;
  const expiringCount = items.filter((i) => i.expiringSoon).length;

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/inventory" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Inventory</h1>
              <p className="mt-1 text-ink-secondary">
                Computed from every receipt, donation and meal cooked.
              </p>
            </div>
            <ButtonLink href="/inventory/new">Track an item</ButtonLink>
          </header>

          {(lowCount > 0 || expiringCount > 0) && (
            <div className="mb-6 flex flex-wrap gap-3">
              {lowCount > 0 && (
                <button
                  type="button"
                  onClick={() => setOnlyLow((s) => !s)}
                  className={`rounded-md px-4 py-2 text-sm ${onlyLow ? "bg-warning text-ink-inverse" : "bg-warning-bg text-warning"}`}
                >
                  {lowCount} below reorder level{onlyLow ? ", showing only these" : ""}
                </button>
              )}
              {expiringCount > 0 && (
                <span className="rounded-md bg-warning-bg px-4 py-2 text-sm text-warning">
                  {expiringCount} with stock expiring soon
                </span>
              )}
            </div>
          )}

          {flash && (
            <div className="mb-6">
              <InlineNotice tone="success" autoDismiss title={`${flash} is now tracked.`}>
                Stock moves as goods are received, donated or cooked.
              </InlineNotice>
            </div>
          )}

          {locations.length > 0 && (
            <div className="mb-4">
              <label className="text-sm text-ink-secondary">
                <span className="font-medium text-ink">Location</span>
                <select value={locationFilter} onChange={(e) => setLocationFilter(e.target.value)} className="ml-2 min-h-touch rounded border border-hairline bg-canvas px-3">
                  <option value="">All</option>
                  {locations.map((l) => <option key={l} value={l}>{l}</option>)}
                </select>
              </label>
            </div>
          )}

          {loading ? (
            <Loading label="Loading inventory…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : items.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">Nothing tracked yet</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                Track a consumable to see its stock move.
              </p>
            </div>
          ) : (
            <div className="overflow-hidden rounded-lg bg-raised">
              <table className="w-full text-left">
                <thead className="bg-sunken text-sm text-ink-secondary">
                  <tr>
                    <th className="px-5 py-3 font-medium">Item</th>
                    <th className="px-5 py-3 font-medium">Location</th>
                    <th className="px-5 py-3 font-medium text-right">On hand</th>
                    <th className="px-5 py-3 font-medium text-right">Reorder at</th>
                    <th className="px-5 py-3 font-medium">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {visible.map((i) => (
                    <tr key={i.itemId} className="border-t border-hairline align-middle hover:bg-raised/60">
                      <td className="px-5 py-3">
                        <Link href={`/inventory/${i.itemId}`} className="font-medium text-accent-text hover:underline">
                          {i.ingredientName}
                        </Link>
                        <span className="ml-2 text-xs text-ink-muted">{i.category}</span>
                      </td>
                      <td className="px-5 py-3 text-ink-secondary">{i.storageLocation ?? "—"}</td>
                      <td className="px-5 py-3 text-right tabular-nums">{i.onHand} {UNIT_LABEL[i.unit] ?? i.unit}</td>
                      <td className="px-5 py-3 text-right tabular-nums text-ink-secondary">
                        {i.reorderThreshold == null ? "—" : `${i.reorderThreshold} ${UNIT_LABEL[i.unit] ?? i.unit}`}
                      </td>
                      <td className="px-5 py-3">
                        <div className="flex flex-wrap gap-1.5">
                          {i.belowThreshold && <span className="rounded-sm bg-warning-bg px-2 py-1 text-xs text-warning font-semibold">Low</span>}
                          {i.expiringSoon && <span className="rounded-sm bg-warning-bg px-2 py-1 text-xs text-warning font-semibold">Expiring soon</span>}
                          {!i.belowThreshold && !i.expiringSoon && <span className="text-xs text-ink-muted">Fine</span>}
                        </div>
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
