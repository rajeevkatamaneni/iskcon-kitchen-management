"use client";

import Link from "next/link";
import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { EmptyState } from "@/components/ds/EmptyState";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { api, toApiError, type ApiError, type StockItemView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { expiryWord, quantity, unitLabel } from "@/lib/format";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";

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
  const { getToken } = useAuth();
  const [nonce, setNonce] = useState(0);
  const fetchInventory = useCallback(
    (token: string | undefined) => {
      void nonce;
      return api.listInventory({}, token);
    },
    [nonce]
  );
  const { data, error, loading } = useAuthedQuery(fetchInventory);
  const items = data ?? [];

  const [locationFilter, setLocationFilter] = useState("");
  const [onlyLow, setOnlyLow] = useState(false);
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState<string | null>(null);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [flash, setFlash] = useState<string | null>(null);

  // Adding happens on /inventory/new and ends back here, so the confirmation has to travel in the
  // URL. Captured behind a ref because setting it re-renders, and a router object that is new on
  // each render would otherwise turn this effect into a loop.
  const router = useRouter();
  const added = useSearchParams().get("added");
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current || !added) return;
    captured.current = true;
    setFlash(added);
    router.replace("/inventory");
  }, [added, router]);

  // Let the banner stand, then clear itself. Keyed on `flash` so stripping the param above does not
  // cut the timer short.
  useEffect(() => {
    if (!flash) return;
    const timer = setTimeout(() => setFlash(null), 6000);
    return () => clearTimeout(timer);
  }, [flash]);

  async function run(fn: (token: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    setActionError(null);
    try {
      await fn(await getToken());
      setNonce((n) => n + 1);
      return true;
    } catch (e) {
      setActionError(toApiError(e, failure));
      return false;
    } finally {
      setBusy(false);
    }
  }

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
          {/*
            Adding is a screen of its own at /inventory/new, and so is adding an ingredient. Five
            fields is over the threshold in DESIGN_SYSTEM.md — four or more becomes a screen — and
            the panel that used to sit here sat on top of the very list somebody was checking the
            item was not already in. Ingredients moved at the same time, so the two pages still do
            the same job the same way, and now agree with Recipes as well.
          */}
          <header className="mb-8 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Inventory</h1>
              <p className="mt-1 text-ink-secondary">
                What the store holds, counted from every receipt, donation and meal cooked.
              </p>
            </div>
            <ButtonLink href="/inventory/new">Add to inventory</ButtonLink>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

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
              <InlineNotice tone="success" autoDismiss title={`${flash} is now in your inventory.`}>
                Its stock moves on its own from here — every delivery, donation and meal cooked.
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
            <EmptyState
              title="Nothing in your inventory yet"
              action={<ButtonLink href="/inventory/new">Add to inventory</ButtonLink>}
            >
              Start with one consumable and what is on the shelf today. Everything after that —
              deliveries, donations, meals cooked — moves on its own.
            </EmptyState>
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
                    <th className="px-5 py-3 font-medium">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {visible.map((i) =>
                    editing === i.itemId ? (
                      <EditRow
                        key={i.itemId}
                        item={i}
                        busy={busy}
                        onCancel={() => setEditing(null)}
                        onSave={async (input) => {
                          const ok = await run(
                            (t) => api.updateInventoryItem(i.itemId, input, t),
                            "We couldn’t save that change."
                          );
                          if (ok) setEditing(null);
                        }}
                      />
                    ) : (
                      <tr key={i.itemId} className="border-t border-hairline align-middle hover:bg-sunken">
                        <td className="px-5 py-3">
                          <Link href={`/inventory/${i.itemId}`} className="font-medium text-accent-text hover:underline">
                            {i.ingredientName}
                          </Link>
                          <span className="ml-2 text-xs text-ink-muted">{i.category}</span>
                        </td>
                        <td className="px-5 py-3 text-ink-secondary">{i.storageLocation ?? "—"}</td>
                        <td className="px-5 py-3 text-right tabular-nums">{quantity(i.onHand, i.unit)}</td>
                        <td className="px-5 py-3 text-right tabular-nums text-ink-secondary">
                          {i.reorderThreshold == null ? "—" : quantity(i.reorderThreshold, i.unit)}
                        </td>
                        <td className="px-5 py-3">
                          <div className="flex flex-wrap gap-1.5">
                            {i.belowThreshold && <span className="rounded-sm bg-warning-bg px-2 py-1 text-xs text-warning font-semibold">Low</span>}
                            {i.expiringSoon && (
                              <span className="rounded-sm bg-warning-bg px-2 py-1 text-xs font-semibold text-warning">
                                {expiryWord(i.soonestExpiry) === "expired" ? "Expired" : "Expiring soon"}
                              </span>
                            )}
                            {!i.belowThreshold && !i.expiringSoon && <span className="text-xs text-ink-muted">Fine</span>}
                          </div>
                        </td>
                        {/* Changing your mind about a level is a one-click job on the row you are
                            looking at. It used to be impossible anywhere in the application: the
                            endpoint existed and no screen called it. */}
                        <td className="px-5 py-3">
                          <button
                            type="button"
                            onClick={() => setEditing(i.itemId)}
                            className="min-h-touch rounded border border-hairline-strong px-3 text-sm transition-colors duration-state hover:bg-sunken"
                          >
                            Edit
                          </button>
                        </td>
                      </tr>
                    )
                  )}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </main>
    </div>
  );
}

/** Changing a level, a location or a note, in place on the row — the Ingredients pattern. */
function EditRow({
  item,
  busy,
  onCancel,
  onSave,
}: {
  item: StockItemView;
  busy: boolean;
  onCancel: () => void;
  onSave: (input: { storageLocation: string | null; reorderThreshold: number | null; notes: string | null }) => void;
}) {
  const [location, setLocation] = useState(item.storageLocation ?? "");
  const [threshold, setThreshold] = useState(item.reorderThreshold == null ? "" : String(item.reorderThreshold));
  const [notes, setNotes] = useState(item.notes ?? "");
  const FIELD = "min-h-touch w-full rounded border border-hairline bg-canvas px-2";

  return (
    <tr className="border-t border-hairline bg-sunken/40 align-middle">
      <td className="px-5 py-3">
        <span className="font-medium">{item.ingredientName}</span>
        <span className="ml-2 text-xs text-ink-muted">{item.category}</span>
      </td>
      <td className="px-5 py-3">
        <input aria-label="Where it lives" value={location} onChange={(e) => setLocation(e.target.value)} className={FIELD} />
      </td>
      <td className="px-5 py-3 text-right tabular-nums text-ink-secondary">{quantity(item.onHand, item.unit)}</td>
      <td className="px-5 py-3">
        <div className="flex items-center gap-2">
          <input
            aria-label={`Tell me when ${item.ingredientName} drops below`}
            type="number"
            min="0"
            step="any"
            value={threshold}
            onChange={(e) => setThreshold(e.target.value)}
            className={`${FIELD} text-right`}
          />
          <span className="text-xs text-ink-secondary">{unitLabel(item.unit)}</span>
        </div>
      </td>
      <td className="px-5 py-3">
        <input aria-label="Notes" value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Notes" className={FIELD} />
      </td>
      <td className="px-5 py-3">
        <div className="flex gap-2">
          <button
            type="button"
            disabled={busy}
            onClick={() =>
              onSave({
                storageLocation: emptyToNull(location),
                reorderThreshold: threshold.trim() === "" ? null : Number(threshold),
                notes: emptyToNull(notes),
              })
            }
            className="min-h-touch rounded bg-accent px-3 text-sm text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
          >
            Save
          </button>
          <button type="button" onClick={onCancel} className="min-h-touch rounded px-3 text-sm text-ink-secondary hover:underline">
            Cancel
          </button>
        </div>
      </td>
    </tr>
  );
}

function emptyToNull(value: string): string | null {
  const trimmed = value.trim();
  return trimmed === "" ? null : trimmed;
}
