"use client";

import { Suspense, useCallback, useEffect, useId, useRef, useState, type ReactNode } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { DeliveryHistory, DeliveryHistoryList, DeliveryHistoryToggle } from "@/components/DeliveryHistory";
import { RecordDeliveryPanel, DeliveryDay, daysBetween, days, inLineUnit } from "@/components/RecordDeliveryPanel";
import { Button } from "@/components/ds/Button";
import { Badge } from "@/components/ds/Badge";
import { PageHeader } from "@/components/ds/PageHeader";
import { StatTile } from "@/components/ds/StatTile";
import { SegmentedControl } from "@/components/ds/SegmentedControl";
import { InlineNotice } from "@/components/ds/InlineNotice";
import {
  api,
  toApiError,
  type ApiError,
  type DeliveriesView,
  type DeliveryLineView,
  type DeliveryReceiptView,
  type PrincipalRole,
  type RejectReason,
  type ReturnReason,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { quantity, shortDate } from "@/lib/format";
import {
  RULED_TABLE,
  THEAD,
  TR,
  TH_PRIMARY,
  TD_PRIMARY,
  TH_SECOND,
  TD_SECOND,
  TH_FIXED,
  TD_FIXED,
  TD_FIXED_NUM,
} from "@/components/ds/table";

/**
 * Deliveries (R-DEL-1..5, PROCUREMENT-REQUIREMENTS.md §7; the approved `dev-deliveries` mock).
 *
 * <p>The storekeeper's and the Kitchen Manager's screen for what vendors are bringing and what has
 * come in. Everything is grouped by vendor, because the person at the gate thinks "the van from
 * Kalasipalya is here", not "open PO-0044", and one "Record a delivery" per vendor gathers every
 * line that vendor still owes across all their open orders.
 *
 * <p>**No prices anywhere** (R-DEL-1, R-DEL-5): a price belongs to the invoice.
 *
 * <p>**Who is let in.** Whoever holds `RECEIVE_DELIVERIES`, the permission every endpoint behind
 * this screen declares: the Temple Admin, the Kitchen Manager and Kitchen Staff. Kitchen Staff were
 * refused here while Rajeev's question Q-1 ("Does Kitchen Staff get RECEIVE_DELIVERIES by default, or
 * only named staff?") was open. He answered it on 2026-09-19: by default. The server grants it
 * (T-282) and the menu shows them the item, so the page follows. See {@link RECEIVE_DELIVERIES_HOLDERS}.
 *
 * <p>**Today** is the server's (`DeliveriesView.today`), never the browser's clock, so a tablet with
 * the wrong date cannot call a delivery late.
 */
/**
 * The roles holding `RECEIVE_DELIVERIES` in `RolePermissions.java`, named by the permission rather
 * than listed as roles at the guard, the way `components/ingredient/access.tsx` does for the
 * ingredient page (T-286): the browser is told a role and not the policy, so the translation is
 * written once, under the permission's name, and moves when `RolePermissions` moves the grant.
 * That helper is typed to the ingredient page's three permissions, so this one lives here. Hiding
 * the page is a courtesy; the server refuses anyone without the permission whatever this says.
 */
const RECEIVE_DELIVERIES_HOLDERS: PrincipalRole[] = ["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"];

export default function DeliveriesPage() {
  return (
    <RequireRole roles={RECEIVE_DELIVERIES_HOLDERS}>
      {/* useSearchParams, for the `?order=` link from the purchase order page, needs a boundary. */}
      <Suspense>
        <DeliveriesScreen />
      </Suspense>
    </RequireRole>
  );
}

type View = "expected" | "partly" | "received";

/** One vendor's open lines, and how late the earliest of them is. */
interface VendorGroup {
  vendorId: string;
  vendorName: string;
  lines: DeliveryLineView[];
  earliest: string | null;
  overdueBy: number;
}

function groupByVendor(lines: DeliveryLineView[], today: string): VendorGroup[] {
  const map = new Map<string, DeliveryLineView[]>();
  for (const l of lines) map.set(l.vendorId, [...(map.get(l.vendorId) ?? []), l]);
  return [...map.values()]
    .map((ls) => {
      const earliest = ls.map((l) => l.neededBy).filter((d): d is string => !!d).sort()[0] ?? null;
      return {
        vendorId: ls[0].vendorId,
        vendorName: ls[0].vendorName,
        lines: ls,
        earliest,
        overdueBy: earliest ? Math.max(0, daysBetween(earliest, today)) : 0,
      };
    })
    // Overdue first, most overdue at the top; then by the earliest date anything is needed; a vendor
    // with no needed-by date at all goes last.
    .sort(
      (a, b) =>
        b.overdueBy - a.overdueBy ||
        (a.earliest ?? "9999").localeCompare(b.earliest ?? "9999") ||
        a.vendorName.localeCompare(b.vendorName),
    );
}

/**
 * One row of the Received tab: one save at the gate.
 *
 * <p>The mock has one row per delivery recorded (the clarifier: settled by the mock). The server
 * keeps one goods receipt per order, so a van that brought two orders is two receipts; they are put
 * back together here by vendor, day and receiver, the three things that are one visit. Receipts
 * arrive newest first and only neighbours are joined. A receipt for an order already in the visit
 * starts a new one, because one save makes at most one receipt per order: recording the first 30
 * Kg of an order and then the other 20 on the same day is two rows, as it was two deliveries.
 * (The API names no save, so two saves for different orders of one vendor, by one person, on one
 * day, with nothing between them, still read as one row. Reported in the T-266 proof.)
 */
interface Visit {
  key: string;
  vendorName: string;
  receivedOn: string;
  receivedByName: string | null;
  receipts: DeliveryReceiptView[];
}

function visits(receipts: DeliveryReceiptView[]): Visit[] {
  const out: Visit[] = [];
  for (const r of receipts) {
    const last = out[out.length - 1];
    if (
      last &&
      last.vendorName === r.vendorName &&
      last.receivedOn === r.receivedOn &&
      last.receivedByName === r.receivedByName &&
      !last.receipts.some((x) => x.poId === r.poId)
    ) {
      last.receipts.push(r);
    } else {
      out.push({ key: r.receiptId, vendorName: r.vendorName, receivedOn: r.receivedOn, receivedByName: r.receivedByName, receipts: [r] });
    }
  }
  return out;
}

const REASON_WORD: Record<RejectReason, string> = {
  DAMAGED: "damaged",
  SPOILED: "spoiled",
  WRONG_ITEM: "wrong item",
  OTHER: "other",
};

/**
 * A return's reason as the app already writes one: the order page's `reasonLabel`, lowercase with
 * the underscore a space ("not delivered"). Written out rather than shared because that function is
 * private to the order page; the five words are what it produces for the five values.
 */
const RETURN_WORD: Record<ReturnReason, string> = {
  DAMAGED: "damaged",
  SPOILED: "spoiled",
  WRONG_ITEM: "wrong item",
  NOT_DELIVERED: "not delivered",
  OTHER: "other",
};

function DeliveriesScreen() {
  const { data, error, loading, reload } = useAuthedQuery(api.getDeliveries);
  const { getToken } = useAuth();
  const [view, setView] = useState<View>("expected");
  const [recording, setRecording] = useState<string | null>(null);
  const [orderFilter, setOrderFilter] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);

  // "Show older deliveries" (the conductor's ruling, 2026-09-19): the Received tab opens on the last
  // 30 days, and each press appends the 30 before the earliest loaded.
  const [older, setOlder] = useState<{ received: DeliveryReceiptView[]; lines: DeliveryLineView[]; from: string; hasOlder: boolean } | null>(null);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [olderError, setOlderError] = useState<ApiError | null>(null);

  // The purchase order page links here with `?order=<poId>` to record against that order. Captured
  // once behind a ref and then taken out of the address bar: a router object that is new each
  // render would otherwise turn this effect into a loop, which is what /vendors once did.
  const router = useRouter();
  const params = useSearchParams();
  const orderParam = params.get("order");
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current || !orderParam || !data) return;
    captured.current = true;
    const line = data.open.find((l) => l.poId === orderParam);
    setView("expected");
    setOrderFilter(orderParam);
    setRecording(line ? line.vendorId : null);
    router.replace("/deliveries");
  }, [orderParam, data, router]);

  const loadOlder = useCallback(async () => {
    if (!data) return;
    setLoadingOlder(true);
    setOlderError(null);
    try {
      const got = await api.getOlderDeliveries(older?.from ?? data.receivedFrom, await getToken());
      setOlder((o) => ({
        received: [...(o?.received ?? []), ...got.received],
        lines: [...(o?.lines ?? []), ...got.receivedLines],
        from: got.receivedFrom,
        hasOlder: got.hasOlder,
      }));
    } catch (caught) {
      setOlderError(toApiError(caught, "We couldn’t load the older deliveries."));
    } finally {
      setLoadingOlder(false);
    }
  }, [data, older, getToken]);

  function closePanel() {
    setRecording(null);
    setOrderFilter(null);
  }

  function onSaved(confirmation: string) {
    closePanel();
    setSaved(confirmation);
    // What was loaded further back is dropped with the reload: the history it holds has just changed.
    setOlder(null);
    reload();
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/deliveries" />
      <main className="min-w-0 flex-1 px-4 pb-24 pt-8 sm:px-8">
        <div className="mx-auto grid max-w-content gap-6">
          <PageHeader title="Deliveries" subtitle="What vendors are bringing, and what has come in." />

          {loading && !data ? (
            <Loading label="Loading deliveries…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : data ? (
            <Loaded
              data={data}
              view={view}
              setView={(v) => {
                setView(v);
                closePanel();
              }}
              recording={recording}
              setRecording={(v) => {
                setRecording(v);
                setOrderFilter(null);
              }}
              orderFilter={orderFilter}
              closePanel={closePanel}
              onSaved={onSaved}
              saved={saved}
              older={older}
              loadOlder={loadOlder}
              loadingOlder={loadingOlder}
              olderError={olderError}
            />
          ) : null}
        </div>
      </main>
    </div>
  );
}

function Loaded({
  data,
  view,
  setView,
  recording,
  setRecording,
  orderFilter,
  closePanel,
  onSaved,
  saved,
  older,
  loadOlder,
  loadingOlder,
  olderError,
}: {
  data: DeliveriesView;
  view: View;
  setView: (v: View) => void;
  recording: string | null;
  setRecording: (v: string | null) => void;
  orderFilter: string | null;
  closePanel: () => void;
  onSaved: (confirmation: string) => void;
  saved: string | null;
  older: { received: DeliveryReceiptView[]; lines: DeliveryLineView[]; from: string; hasOlder: boolean } | null;
  loadOlder: () => void;
  loadingOlder: boolean;
  olderError: ApiError | null;
}) {
  const today = data.today;
  const owed = data.open.filter((l) => l.stillToCome > 0);
  const groups = groupByVendor(owed, today);
  const partly = owed.filter((l) => l.receivedQty > 0);
  const partlyGroups = groupByVendor(partly, today);
  const dueToday = groups.filter((g) => g.lines.some((l) => l.neededBy === today)).length;
  const overdue = groups.filter((g) => g.overdueBy > 0).length;
  const partlyOrders = new Set(partly.map((l) => l.poId)).size;
  const received = [...data.received, ...(older?.received ?? [])];
  const allVisits = visits(received);
  const thisWeek = allVisits.filter((v) => daysBetween(v.receivedOn, today) <= 6).length;
  const hasOlder = older ? older.hasOlder : data.hasOlder;

  // Every line a history can be asked of, by id: the open ones and those the received window touched.
  const lineById = new Map<string, DeliveryLineView>();
  for (const l of [...(older?.lines ?? []), ...data.receivedLines, ...data.open]) lineById.set(l.poLineId, l);

  // `?order=` for an order with nothing left to come (the conductor's ruling, 2026-09-19): its
  // items' histories and a neutral line, and no recording form.
  const deliveredOrder =
    orderFilter && !data.open.some((l) => l.poId === orderFilter)
      ? [...lineById.values()].filter((l) => l.poId === orderFilter)
      : null;

  const viewProps = { today, recording, setRecording, orderFilter, closePanel, onSaved };

  return (
    <>
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatTile label="Due today" value={`${dueToday} ${dueToday === 1 ? "vendor" : "vendors"}`} />
        <StatTile
          label="Overdue"
          value={`${overdue} ${overdue === 1 ? "vendor" : "vendors"}`}
          tone={overdue > 0 ? "warning" : "neutral"}
        />
        <StatTile label="Partly delivered" value={`${partlyOrders} ${partlyOrders === 1 ? "order" : "orders"}`} />
        <StatTile label="Received this week" value={`${thisWeek} ${thisWeek === 1 ? "delivery" : "deliveries"}`} />
      </div>

      {saved && (
        <InlineNotice tone="success" autoDismiss>
          {saved}
        </InlineNotice>
      )}

      {deliveredOrder && (
        <DeliveredOrder orderId={orderFilter!} lines={deliveredOrder} today={today} onClose={closePanel} />
      )}

      <SegmentedControl<View>
        label="Deliveries view"
        value={view}
        onChange={setView}
        // No counts in the labels. With them the three tabs measured wider than a 390 phone and
        // wrapped onto two rows, and the tiles above already carry the counts that matter.
        options={[
          { value: "expected", label: "Expected" },
          { value: "partly", label: "Partly delivered" },
          { value: "received", label: "Received" },
        ]}
      />

      {view === "expected" && <ExpectedView groups={groups} allOwed={owed} {...viewProps} />}
      {view === "partly" && <PartlyView groups={partlyGroups} allOwed={owed} {...viewProps} />}
      {view === "received" && (
        <ReceivedView
          today={today}
          visits={allVisits}
          lineById={lineById}
          hasOlder={hasOlder}
          loadOlder={loadOlder}
          loadingOlder={loadingOlder}
          olderError={olderError}
        />
      )}
    </>
  );
}

/**
 * The `?order=` link for an order that has nothing left to come: say so, plainly and in neutral
 * ink (a settled fact, not a success of the reader's own), and show what came.
 */
function DeliveredOrder({ orderId, lines, today, onClose }: { orderId: string; lines: DeliveryLineView[]; today: string; onClose: () => void }) {
  const number = lines[0]?.poNumber;
  return (
    <section className="card grid gap-3 px-5 py-4" aria-label="Order delivered">
      <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3">
        <p className="min-w-0 grow basis-60 text-ink">
          {number ? `Everything on ${number} has been delivered.` : "Everything on this order has been delivered."}
        </p>
        <Button variant="ghost" onClick={onClose}>
          Close
        </Button>
      </div>
      {lines.length > 0 && (
        <ul className="grid gap-3" data-order={orderId}>
          {lines.map((l) => (
            <li key={l.poLineId} className="grid gap-1">
              <span className="font-medium text-ink">{l.itemName}</span>
              <DeliveryHistory parts={l.parts} unit={l.unit} orderedQty={l.orderedQty} completedOn={l.completedOn} itemName={l.itemName} today={today} />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** One vendor's card: the heading, its one "Record a delivery", and either the table or the panel. */
function VendorCard({
  group,
  allOwed,
  today,
  open,
  orderFilter,
  onOpen,
  onCancel,
  onSaved,
  children,
}: {
  group: VendorGroup;
  /** Everything owed, so the panel lists all this vendor owes whichever tab it was opened from. */
  allOwed: DeliveryLineView[];
  today: string;
  open: boolean;
  orderFilter: string | null;
  onOpen: () => void;
  onCancel: () => void;
  onSaved: (confirmation: string) => void;
  children: ReactNode;
}) {
  const orders = [...new Set(group.lines.map((l) => l.poNumber))];
  const vendorLines = allOwed.filter((l) => l.vendorId === group.vendorId);
  return (
    <section className="card overflow-hidden" aria-label={group.vendorName}>
      <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3 px-5 py-4">
        <div className="grid min-w-0 grow basis-60 gap-1">
          <h2 className="flex flex-wrap items-center gap-x-3 gap-y-1 text-lg font-semibold text-ink">
            {group.vendorName}
            {group.overdueBy > 0 && <Badge tone="warning">Overdue</Badge>}
          </h2>
          <p className="text-sm text-ink-secondary">
            {group.lines.length} {group.lines.length === 1 ? "item" : "items"} on {orders.length === 1 ? "order" : "orders"}{" "}
            {orders.join(", ")}
          </p>
        </div>
        {!open && (
          <Button variant="ghost" icon="package-import" onClick={onOpen}>
            Record a delivery
          </Button>
        )}
      </div>
      {open ? (
        // R-DEL-3 (the clarifier: the document wins over the mock): the panel lists everything this
        // vendor still owes across all their open orders, from Partly delivered as from Expected.
        <RecordDeliveryPanel
          vendorId={group.vendorId}
          vendorName={group.vendorName}
          lines={vendorLines}
          orderId={orderFilter}
          today={today}
          onCancel={onCancel}
          onSaved={onSaved}
        />
      ) : (
        children
      )}
    </section>
  );
}

interface ViewProps {
  groups: VendorGroup[];
  allOwed: DeliveryLineView[];
  today: string;
  recording: string | null;
  setRecording: (v: string | null) => void;
  orderFilter: string | null;
  closePanel: () => void;
  onSaved: (confirmation: string) => void;
}

function ExpectedView({ groups, allOwed, today, recording, setRecording, orderFilter, closePanel, onSaved }: ViewProps) {
  if (groups.length === 0) {
    return <p className="card px-5 py-6 text-ink-secondary">Nothing is expected. Every sent order has arrived.</p>;
  }
  return (
    <div className="grid gap-6">
      {groups.map((g) => (
        <VendorCard
          key={g.vendorId}
          group={g}
          allOwed={allOwed}
          today={today}
          open={recording === g.vendorId}
          orderFilter={recording === g.vendorId ? orderFilter : null}
          onOpen={() => setRecording(g.vendorId)}
          onCancel={closePanel}
          onSaved={onSaved}
        >
          <table className={RULED_TABLE}>
            <thead className={THEAD}>
              <tr>
                <th className={TH_PRIMARY}>Item</th>
                <th className={TH_FIXED}>Order</th>
                <th className={TH_FIXED}>Still to come</th>
                <th className={TH_FIXED}>Needed by</th>
              </tr>
            </thead>
            <tbody>
              {g.lines.map((l) => {
                const first = [...l.parts].sort((a, b) => a.receivedOn.localeCompare(b.receivedOn))[0];
                return (
                  <tr key={l.poLineId} className={TR}>
                    <td className={TD_PRIMARY}>
                      {l.itemName}
                      {l.receivedQty > 0 && first && (
                        <span className="ms-2 text-sm font-normal text-ink-muted">
                          {inLineUnit(l, l.receivedQty)} came <DeliveryDay iso={first.receivedOn} today={today} />
                        </span>
                      )}
                    </td>
                    <td className={`${TD_FIXED} text-ink-secondary`}>{l.poNumber}</td>
                    {/* In the unit the line was ordered in, the panel's words: "2.8 bags (70 Kg)". */}
                    <td className={TD_FIXED_NUM} data-label="Still to come">
                      {inLineUnit(l, l.stillToCome)}
                    </td>
                    <td className={TD_FIXED} data-label="Needed by">
                      {l.neededBy ? <DeliveryDay iso={l.neededBy} today={today} late /> : "—"}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </VendorCard>
      ))}
    </div>
  );
}

/** Part-delivered lines, by vendor, each vendor with its own "Record a delivery" for the rest. */
function PartlyView({ groups, allOwed, today, recording, setRecording, orderFilter, closePanel, onSaved }: ViewProps) {
  if (groups.length === 0) {
    return <p className="card px-5 py-6 text-ink-secondary">No order is part delivered.</p>;
  }
  return (
    <div className="grid gap-6">
      {groups.map((g) => (
        <VendorCard
          key={g.vendorId}
          group={g}
          allOwed={allOwed}
          today={today}
          open={recording === g.vendorId}
          orderFilter={recording === g.vendorId ? orderFilter : null}
          onOpen={() => setRecording(g.vendorId)}
          onCancel={closePanel}
          onSaved={onSaved}
        >
          <table className={RULED_TABLE}>
            <thead className={THEAD}>
              <tr>
                <th className={TH_PRIMARY}>Item</th>
                <th className={TH_FIXED}>Order</th>
                <th className={TH_FIXED}>Ordered</th>
                <th className={TH_FIXED}>Received</th>
                <th className={TH_FIXED}>Still owed</th>
                <th className={TH_FIXED}>Owed for</th>
              </tr>
            </thead>
            <tbody>
              {g.lines.map((l) => (
                <PartlyRow key={l.poLineId} line={l} today={today} />
              ))}
            </tbody>
          </table>
        </VendorCard>
      ))}
    </div>
  );
}

/**
 * One part-delivered line, and under it, once opened, its history in a row of its own (T-343).
 *
 * <p>The history spans the whole table, as it does on the purchase order page (T-265) and as the
 * mock draws it: its lines under the item, reading across. It used to open inside the Item cell,
 * and at 1024 that cell is the one the fitter narrows, so the history read one to three words a
 * line in a 115px column: VERIFY-B Rice's row was 413px tall while the five columns beside it held
 * one line each, about 560px of empty row beside squeezed text (VERIFY3, F-1). A cell spanning the
 * row is not measured by the fitter, so opening a history changes no column's width either.
 *
 * <p>No rule above it and no top padding, so it reads as the rest of the item's row; below 1024px
 * it is the last line of the item's card, the way `[colspan]` cells are placed there.
 */
function PartlyRow({ line: l, today }: { line: DeliveryLineView; today: string }) {
  const [open, setOpen] = useState(false);
  const id = useId();
  const first = [...l.parts].sort((a, b) => a.receivedOn.localeCompare(b.receivedOn))[0]?.receivedOn ?? today;
  const owedFor = daysBetween(first, today);
  return (
    <>
      <tr className={TR}>
        <td className={TD_PRIMARY}>
          {l.itemName}
          {l.parts.length > 0 && (
            <DeliveryHistoryToggle
              count={l.parts.length}
              open={open}
              onToggle={() => setOpen((o) => !o)}
              controls={id}
              itemName={l.itemName}
            />
          )}
        </td>
        <td className={`${TD_FIXED} text-ink-secondary`}>{l.poNumber}</td>
        {/* All three amounts in the unit the line was ordered in, with the stock it
            makes beside it: "4 bags (100 Kg)", "2 bags (50 Kg)", "2 bags (50 Kg)". Ordered
            and Received used to be plain stock units, so one row read "100 Kg" beside
            "2 bags (50 Kg)" (T-310, consistency across views). */}
        <td className={TD_FIXED_NUM} data-label="Ordered">{inLineUnit(l, l.orderedQty)}</td>
        <td className={TD_FIXED_NUM} data-label="Received">{inLineUnit(l, l.receivedQty)}</td>
        <td className={TD_FIXED_NUM} data-label="Still owed">{inLineUnit(l, l.stillToCome)}</td>
        <td className={TD_FIXED} data-label="Owed for">
          {owedFor <= 0 ? (
            <DeliveryDay iso={first} today={today} />
          ) : (
            <>
              {days(owedFor)}, since <DeliveryDay iso={first} today={today} />
            </>
          )}
        </td>
      </tr>
      {open && (
        <tr className={HISTORY_ROW}>
          <td colSpan={6} className={HISTORY_CELL}>
            <DeliveryHistoryList
              id={id}
              parts={l.parts}
              unit={l.unit}
              orderedQty={l.orderedQty}
              completedOn={l.completedOn}
              today={today}
            />
          </td>
        </tr>
      )}
    </>
  );
}

/**
 * The row an opened history sits in, under the row it belongs to: no rule above it and no top
 * padding, so it reads as part of that row (the purchase order page's classes, T-265).
 */
const HISTORY_ROW = "align-top !border-t-0 hover:bg-sunken max-lg:!pb-3 max-lg:!pt-0";
const HISTORY_CELL = "!border-t-0 lg:!pb-3 lg:!pt-0";

/**
 * One visit on the Received tab, and under it, once any of its items' histories is opened, those
 * histories in a row of their own spanning the table (T-343), for the reason {@link PartlyRow}
 * gives: in the Items cell a history was held to that column's width beside short columns of one
 * line each.
 *
 * <p>A visit can hold several items, each with its own "▸ N deliveries", and more than one can be
 * open. The opened ones are listed in the items' own order. Where the visit shows more than one
 * item, each history is headed with its item's name, because under the row it no longer sits
 * beside the item it belongs to; with a single item there is nothing to tell apart and the name
 * would only repeat the line above.
 */
function ReceivedRow({ visit: v, lineById, today }: { visit: Visit; lineById: Map<string, DeliveryLineView>; today: string }) {
  const [opened, setOpened] = useState<ReadonlySet<string>>(() => new Set());
  const base = useId();
  const lines = v.receipts.flatMap((r) => r.lines.map((l) => ({ ...l, key: `${r.receiptId}-${l.poLineId}` })));
  const rejected = lines
    .filter((l) => l.rejectedQty > 0)
    .map((l) => `${l.itemName} ${quantity(l.rejectedQty, l.unit)}${l.rejectReason ? `, ${REASON_WORD[l.rejectReason]}` : ""}`);
  // The mock's words, one return to a line: "Paneer 1 Kg, spoiled, 17 Sept" (T-285). Each
  // return is its own line because two returns of one item are two decisions, made on
  // different days for different reasons. The date is written plain, with no Today
  // pill: this column says what happened, and the row's own date carries the pill.
  const returned = lines.flatMap((l) =>
    l.returns.map((r) => `${l.itemName} ${quantity(r.quantity, l.unit)}, ${RETURN_WORD[r.reason]}, ${shortDate(r.returnedOn)}`),
  );
  // One item to a line, like the delivery note itself. The history toggle appears only where
  // there is more to it than this row: a line that came in more than one part, or one still owed.
  const shown = lines
    .filter((l) => l.receivedQty > 0)
    .map((l, i) => {
      const full = lineById.get(l.poLineId);
      const more = full && (full.parts.length > 1 || full.stillToCome > 0) ? full : null;
      return { ...l, full: more, listId: `${base}-${i}` };
    });
  const open = shown.filter((l) => l.full && opened.has(l.key));
  const toggle = (key: string) =>
    setOpened((was) => {
      const next = new Set(was);
      if (!next.delete(key)) next.add(key);
      return next;
    });
  return (
    <>
      <tr className={TR}>
        <td className={TD_FIXED}>
          <DeliveryDay iso={v.receivedOn} today={today} />
        </td>
        <td className={TD_SECOND}>{v.vendorName}</td>
        <td className={`${TD_PRIMARY} font-normal`}>
          {shown.map((l) => (
            <div key={l.key} className="[&+&]:mt-1">
              {l.itemName} <span className="whitespace-nowrap tabular-nums">{quantity(l.receivedQty, l.unit)}</span>
              {l.full && (
                <DeliveryHistoryToggle
                  count={l.full.parts.length}
                  open={opened.has(l.key)}
                  onToggle={() => toggle(l.key)}
                  controls={l.listId}
                  itemName={l.full.itemName}
                />
              )}
            </div>
          ))}
        </td>
        <td className={TD_FIXED} data-label="Received by">{v.receivedByName ?? "—"}</td>
        <td className={`${TD_SECOND} ${rejected.length ? "" : "max-lg:!hidden"}`} data-label="Rejected">
          {rejected.length ? rejected.map((r) => <span key={r} className="block">{r}</span>) : "None"}
        </td>
        <td className={`${TD_SECOND} ${returned.length ? "" : "max-lg:!hidden"}`} data-label="Returned">
          {returned.length ? returned.map((r, i) => <span key={i} className="block">{r}</span>) : "None"}
        </td>
      </tr>
      {open.length > 0 && (
        <tr className={HISTORY_ROW}>
          <td colSpan={6} className={HISTORY_CELL}>
            {/* A div for the spacing: a grid on the cell itself would stop it being a table cell. */}
            <div className="grid gap-3">
              {open.map((l) => (
                <div key={l.key}>
                  {shown.length > 1 && <p className="text-sm font-medium text-ink">{l.full!.itemName}</p>}
                  <DeliveryHistoryList
                    id={l.listId}
                    parts={l.full!.parts}
                    unit={l.full!.unit}
                    orderedQty={l.full!.orderedQty}
                    completedOn={l.full!.completedOn}
                    today={today}
                    className={shown.length > 1 ? "mt-1" : ""}
                  />
                </div>
              ))}
            </div>
          </td>
        </tr>
      )}
    </>
  );
}

function ReceivedView({
  today,
  visits,
  lineById,
  hasOlder,
  loadOlder,
  loadingOlder,
  olderError,
}: {
  today: string;
  visits: Visit[];
  lineById: Map<string, DeliveryLineView>;
  hasOlder: boolean;
  loadOlder: () => void;
  loadingOlder: boolean;
  olderError: ApiError | null;
}) {
  return (
    <div className="grid gap-4">
      {visits.length === 0 ? (
        <p className="card px-5 py-6 text-ink-secondary">Nothing was received in the last 30 days.</p>
      ) : (
        <div className="card overflow-hidden">
          <table className={RULED_TABLE}>
            <thead className={THEAD}>
              <tr>
                <th className={TH_FIXED}>Date</th>
                <th className={TH_SECOND}>Vendor</th>
                <th className={TH_PRIMARY}>Items</th>
                <th className={TH_FIXED}>Received by</th>
                {/* Rejected and Returned are text, not short values: held on one line they took
                    width from the Vendor and Items columns and wrapped those at 1280. */}
                <th className={TH_SECOND}>Rejected</th>
                <th className={TH_SECOND}>Returned</th>
              </tr>
            </thead>
            <tbody>
              {/* "None" is kept on a wide screen, where an empty cell under a heading reads as
                  missing data, and dropped from a phone card, where "Rejected None · Returned None"
                  on every delivery was two lines of noise per card. */}
              {visits.map((v) => (
                <ReceivedRow key={v.key} visit={v} lineById={lineById} today={today} />
              ))}
            </tbody>
          </table>
        </div>
      )}
      {olderError && <ErrorNotice error={olderError} />}
      {hasOlder && (
        <div>
          <Button variant="ghost" onClick={loadOlder} busy={loadingOlder}>
            Show older deliveries
          </Button>
        </div>
      )}
    </div>
  );
}
