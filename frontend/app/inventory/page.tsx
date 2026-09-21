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
import { api, type StockItemView } from "@/lib/api";
import {
  expiryWord,
  lastCountedPhrase,
  oneUnitFor,
  stockCoverPhrase,
  stockRunsOutSoon,
} from "@/lib/format";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";
import { RULED_TABLE, THEAD, TR, TH_PRIMARY, TD_PRIMARY, TH_FIXED, TD_FIXED, TD_FIXED_NUM } from "@/components/ds/table";

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

  const [search, setSearch] = useState("");
  const [locationFilter, setLocationFilter] = useState("");
  const [onlyLow, setOnlyLow] = useState(false);
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

  const locations = useMemo(
    () => [...new Set(items.map((i) => i.storageLocation).filter(Boolean))] as string[],
    [items]
  );

  /*
   * Finding one consumable among a hundred and fourteen.
   *
   * <p>The seeded temple holds 114 and a real one will hold more, and until now the only way to
   * reach one of them was to scroll. The category is searched as well as the name, so "puja" finds
   * the camphor and the agarbatti together — a storekeeper thinks in shelves as often as in names.
   * A plain "contains", case-insensitive, exactly as ItemCombobox matches: two search boxes in one
   * application that match differently is a small betrayal every time somebody uses the second one.
   */
  const query = search.trim().toLowerCase();
  const visible = items.filter(
    (i) =>
      (!query ||
        i.ingredientName.toLowerCase().includes(query) ||
        (i.category ?? "").toLowerCase().includes(query)) &&
      (!locationFilter || i.storageLocation === locationFilter) &&
      (!onlyLow || i.belowThreshold)
  );
  const lowCount = items.filter((i) => i.belowThreshold).length;
  const expiringCount = items.filter((i) => i.expiringSoon).length;
  const filtered = Boolean(query) || Boolean(locationFilter) || onlyLow;

  const FILTER_BOX = "min-h-touch rounded-control border border-hairline px-3";

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/inventory" />
      <main className="min-w-0 flex-1 px-4 py-10 sm:px-8">
        <div className="mx-auto max-w-content">
          {/*
            Adding is a screen of its own at /inventory/new, and so is adding an ingredient. Five
            fields is over the threshold in DESIGN_SYSTEM.md — four or more becomes a screen — and
            the panel that used to sit here sat on top of the very list somebody was checking the
            item was not already in. Ingredients moved at the same time, so the two pages still do
            the same job the same way, and now agree with Recipes as well.
          */}
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div className="min-w-0 grow basis-60">
              <h1>Inventory</h1>
              <p className="mt-1 text-ink-secondary">
                What the store holds, counted from every receipt, donation and meal cooked.
              </p>
            </div>
            <ButtonLink href="/inventory/new">Add to inventory</ButtonLink>
          </header>

          {flash && (
            <div className="mb-6">
              <InlineNotice tone="success" autoDismiss title={`${flash} is now in your inventory.`}>
                Its stock moves on its own from here — every delivery, donation and meal cooked.
              </InlineNotice>
            </div>
          )}

          {/*
            Finding and warning on one line.

            The search box, the shelf filter and the two attention chips used to be two stacked
            blocks with a blank half-row beside each: the chips had the width to themselves and the
            filter sat alone underneath with a `<select>` two inches wide and nothing to its right.
            They all fit beside each other, so they go beside each other, and the search box takes
            the slack rather than leaving it empty.
          */}
          {items.length > 0 && (
            <div className="mb-4 flex flex-wrap items-center gap-3">
              <input
                type="search"
                aria-label="Search inventory"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search by name or category…"
                className={`${FILTER_BOX} min-w-0 grow basis-56`}
              />
              {locations.length > 0 && (
                <select
                  aria-label="Where is it stored"
                  value={locationFilter}
                  onChange={(e) => setLocationFilter(e.target.value)}
                  className={FILTER_BOX}
                >
                  <option value="">Everywhere</option>
                  {locations.map((l) => <option key={l} value={l}>{l}</option>)}
                </select>
              )}
              {lowCount > 0 && (
                <button
                  type="button"
                  onClick={() => setOnlyLow((s) => !s)}
                  aria-pressed={onlyLow}
                  className={`min-h-touch rounded-control px-4 text-sm ${onlyLow ? "bg-warning text-ink-inverse" : "bg-warning-bg text-warning"}`}
                >
                  {lowCount} below reorder level{onlyLow ? ", showing only these" : ""}
                </button>
              )}
              {expiringCount > 0 && (
                <span className="flex min-h-touch items-center rounded-control bg-warning-bg px-4 text-sm text-warning">
                  {expiringCount} with stock expiring soon
                </span>
              )}
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
          ) : visible.length === 0 ? (
            /* Filtered down to nothing. Not the empty state above: the temple has an inventory,
               this search does not match any of it, and offering "Add to inventory" here would
               invite somebody to add a second Toor dal because they mistyped the first. */
            <p className="card px-6 py-8 text-center text-ink-secondary">
              Nothing here matches what you are looking for. Try a shorter word, or clear the
              filters.
            </p>
          ) : (
            <>
              {filtered && (
                <p className="mb-3 text-sm text-ink-secondary">
                  Showing {visible.length} of {items.length}.
                </p>
              )}
              <div className="table-wrap overflow-x-auto">
                <table className={RULED_TABLE}>
                  <thead className={THEAD}>
                    <tr>
                      {/*
                        The columns read as one sentence, left to right: what it is, what you have,
                        what is coming, how long it lasts, when anybody last checked.

                        Three columns went in T-432 and each was carrying its weight badly.
                        `Location` became a line under the name, which is where the item's own page
                        has always shown it — one fact in one place, and a row that no longer spends
                        a whole column on "Main store". `Status` went because its commonest value was
                        the word "Fine", printed a hundred times down a column that a reader is
                        scanning for the exceptions; the badges it held now sit beside the name they
                        are about, which is also where they end up in the card layout below 1024.

                        `Committed` went last, and it is a deliberate departure from T-086, which
                        put it here on purpose so the three figures would add up in front of the
                        reader. It was measured rather than argued: at 1280 with the menu open this
                        table has 1000px, eight columns needed about 1060 for every cell on one line,
                        and what gave way was "Approximately 20 days" and "Not enough history"
                        breaking across two lines in a 103px column. Something had to go, and
                        committed is the one of the three that is never itself an action — it is the
                        gap between the other two, which the row still shows (1.96 on hand, −0.08
                        available), and the only place the number can be answered is the item's own
                        page, where it is listed meal by meal. What T-086 established is untouched:
                        Low still judges available, not on hand.

                        What is left reads left to right as one sentence, and every quantity in it is
                        said in ONE unit, because `oneUnitFor` picks the unit for the row rather than
                        each figure picking its own.

                        There is no actions column any more (T-440). The row's Edit button opened an
                        inline form over these six cells; Rajeev asked on 2026-09-20 that the name be
                        the way in — the item opens read-only, Edit is on that page, and the form is a
                        screen of its own with Save and Cancel — which is the shape Staff already has.
                        Nothing is left to put in an actions column, so the column goes rather than
                        standing empty, and the six that remain get its width.
                      */}
                      <th className={TH_PRIMARY}>Item</th>
                      <th className={TH_FIXED}>On hand</th>
                      <th className={TH_FIXED}>Available</th>
                      <th className={TH_FIXED}>On order</th>
                      <th className={TH_FIXED}>Lasts</th>
                      <th className={TH_FIXED}>Last counted</th>
                    </tr>
                  </thead>
                  <tbody>
                    {visible.map((i) => (
                      <ItemRow key={i.itemId} item={i} />
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </div>
      </main>
    </div>
  );
}

/** One consumable, read left to right. Its name opens it (T-440). */
function ItemRow({ item: i }: { item: StockItemView }) {
  /*
   * One row, one unit (T-432).
   *
   * <p>Rajeev found a row on staging reading 2.06 Kg on hand, 2.04 Kg committed and 20 gm
   * available. Each figure was right and the row was unreadable: the subtraction that the three
   * columns exist to show had a thousandfold change of scale in the middle of it. Every quantity in
   * this row now goes through one renderer, so they are said on one scale — and `onOrder` is in the
   * set, because it is a quantity of the same thing and would otherwise reintroduce the defect in a
   * new column.
   *
   * <p>Only what is *printed* is in the set. Committed is no longer a column here (see the
   * headings), and a figure nobody can see must not decide the unit of the ones they can.
   */
  const say = oneUnitFor(i.unit, [i.onHand, i.available, i.onOrder]);
  const expired = i.expiringSoon && expiryWord(i.soonestExpiry) === "expired";

  return (
    <tr className={TR}>
      <td className={TD_PRIMARY}>
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
          <Link href={`/inventory/${i.itemId}`} className="font-medium text-accent-text hover:underline">
            {i.ingredientName}
          </Link>
          {i.belowThreshold && (
            <span className="rounded-control bg-warning-bg px-2 py-0.5 text-xs font-semibold text-warning">Low</span>
          )}
          {/* Expired is red: the food cannot be served and needs dealing with now. Expiring soon
              stays amber, act before it goes (Rajeev, 2026-09-18, T-227). */}
          {i.expiringSoon && (
            <span className={`rounded-control px-2 py-0.5 text-xs font-semibold ${expired ? "bg-danger-bg text-danger" : "bg-warning-bg text-warning"}`}>
              {expired ? "Expired" : "Expiring soon"}
            </span>
          )}
        </div>
        <span className="mt-0.5 block text-xs text-ink-muted">
          {i.category}
          {i.storageLocation ? ` · ${i.storageLocation}` : ""}
        </span>
      </td>
      <td className={TD_FIXED_NUM} data-label="On hand">{say(i.onHand)}</td>
      <td className={TD_FIXED_NUM} data-label="Available">{say(i.available)}</td>
      {/* Null, not zero, when nothing is coming — the server says so — and a dash rather than
          "0 Kg", because the column is scanned down and a column of zeroes hides the one row that
          is not. A draft order is not on order. */}
      <td className={`${TD_FIXED_NUM} text-ink-secondary`} data-label="On order">
        {i.onOrder == null ? "—" : say(i.onOrder)}
      </td>
      <td className={TD_FIXED} data-label="Lasts">
        {/* Amber only where it runs out inside a week. "Not enough history" is never coloured: it
            is the absence of a judgement, and amber has to go on meaning "act on this". */}
        <span
          className={
            stockRunsOutSoon(i.lastsFor, i.onHand)
              ? "font-semibold text-warning"
              : i.lastsFor
                ? "text-ink-secondary"
                : "text-ink-muted"
          }
        >
          {stockCoverPhrase(i.lastsFor, i.onHand)}
        </span>
      </td>
      <td className={`${TD_FIXED} text-ink-secondary`} data-label="Last counted">
        {lastCountedPhrase(i.lastCounted)}
      </td>
    </tr>
  );
}
