"use client";

import { useEffect, useId, useRef, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import {
  api, toApiError, type ApiError, type BuyPackView, type IngredientView, type ShoppingListLineView,
  type VendorSupplyView, type VendorView,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import {
  cooksQuantity, dateWithYear, entryQuantity, fromEntry, pricePer, quantity, readablePackRate, readableRate,
  stepForUnit, todayIso, unitLabel,
} from "@/lib/format";
import { wholeNumberProblem } from "@/components/ds/formMessages";
import { Badge } from "@/components/ds/Badge";
import { Loading } from "@/components/Loading";
import { HintedField } from "@/components/ds/InfoHint";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { PurchaseOrderEditor, type PurchaseOrderDraft, type PurchaseOrderDraftLine } from "@/components/PurchaseOrderEditor";
import { RULED_TABLE, THEAD, TR, TH_LEAD, TD_LEAD, TH_PRIMARY, TD_PRIMARY, TH_SECOND, TD_SECOND, TH_FIXED, TD_FIXED, TD_FIXED_NUM } from "@/components/ds/table";

export default function ShoppingListPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <ShoppingListView />
    </RequireRole>
  );
}

/**
 * One vendor's share of the list: the lines that would go on one purchase order to them.
 *
 * <p>`vendorId` is null for the group holding lines with no preferred supplier. That group is a
 * tile like the others but has no button on it, because there is nobody to send an order to.
 */
interface VendorGroup {
  vendorId: string | null;
  vendorName: string | null;
  lines: ShoppingListLineView[];
}

/**
 * Where a "No vendor yet" line was put on this visit (R-SL-4).
 *
 * <p>Held on the page and nowhere else, so with "Use this vendor next time" unticked the move lasts
 * exactly as long as the visit — the conductor's reading of "the move lasts for this visit only".
 * With the tick on, the vendor is also saved as the ingredient's preferred vendor, and from the next
 * read the server puts the line there itself; the entry here is then ignored (see groupByVendor).
 */
interface MovedTo {
  vendorId: string;
  vendorName: string;
}

/** "4 × Bag (25 Kg)", or "1 × 1 Kg + 1 × 250 gm" for a mixed suggestion (R-SL-2, R-SL-3). */
function packsText(packs: BuyPackView[]): string {
  return packs.map((p) => `${p.count} × ${p.label}`).join(" + ");
}

/**
 * The shopping list's lines, as the purchase-order panel opens with them (T-134, T-264).
 *
 * <p><strong>Readable amounts (R-SL-1).</strong> A line with no packs is handed over in the unit it
 * reads in — 3000 gm goes in as 3 Kg — so the panel's box says "3 Kg", never "3000 gm", and the
 * order is written in Kg. The server takes any unit of the ingredient's own family (KMS-400013
 * refuses only a different family), so nothing is lost.
 *
 * <p><strong>Packs (R-SL-3).</strong> A line in packs becomes one editor line per pack size: "4 × Bag
 * (25 Kg)" is one line with a count of 4, and a mixed suggestion (1 × 1 Kg + 1 × 250 gm) is two.
 * That second case is the conductor's ruling while mixing sizes is allowed (provisional, Desk
 * Q-16): a purchase-order line holds one pack, so two sizes are two lines.
 *
 * <p><strong>The price (conductor's ruling, 2026-09-19).</strong> Each line carries the vendor's
 * list price, as the Create a purchase order form does. It is sent per the line's own `unit`, which
 * is what the server reads it as (T-260). Where the vendor sells this pack, the price per pack is
 * the one they quoted; for any other pack it is the per-unit list price times the pack. The words
 * under the item read "List price ₹1,500 / bag · ₹60 / Kg", with the rate per Kg or L whatever the
 * amount is shown in ("3 Kg" and "450 gm" both go with "₹71.20 / Kg"; the shared readableRate, T-293). No list price: no words and a blank price, which the
 * server fills from the same list price or leaves as a dash — never ₹0.
 */
function panelLines(lines: ShoppingListLineView[], supplies: VendorSupplyView[]): PurchaseOrderDraftLine[] {
  const out: PurchaseOrderDraftLine[] = [];
  for (const l of lines) {
    const supply = supplies.find((s) => s.ingredientId === l.ingredientId) ?? null;
    // The list price per this line's unit. It is stored per the ingredient's own unit, which is the
    // unit the list is in too, but converting costs nothing and does not assume it.
    const perLineUnit = supply ? pricePer(supply.lastPrice, supply.unit, l.unit) : null;
    const base = { ingredientId: l.ingredientId, ingredientName: l.ingredientName, description: null };

    if (l.buyPacks.length > 0) {
      for (const p of l.buyPacks) {
        const vendorsPack = supply !== null && supply.packSizeId === p.packSizeId && supply.pricePerPack !== null;
        const perPack = vendorsPack
          ? supply.pricePerPack
          : perLineUnit !== null ? Number((perLineUnit * p.perPackQty).toFixed(2)) : null;
        const expected = perPack !== null ? Number((perPack / p.perPackQty).toFixed(4)) : null;
        out.push({
          ...base,
          key: `${l.ingredientId}:${p.packSizeId}`,
          quantity: String(p.count),
          unit: l.unit,
          expectedPrice: expected,
          pack: { packSizeId: p.packSizeId, label: p.label, perPackQty: p.perPackQty },
          // The shared formatter (T-293): the pack as quoted, then the rate per Kg or L — a 500 gm
          // pack reads "₹250 / 500 gm · ₹500 / Kg", never "· ₹0.50 / gm".
          priceNote: perPack !== null
            ? `List price ${readablePackRate(perPack, p.label, expected, l.unit)}`
            : null,
        });
      }
    } else {
      const shown = entryQuantity(l.suggestedQty, l.unit);
      const expected = pricePer(perLineUnit, l.unit, shown.unit);
      out.push({
        ...base,
        key: l.ingredientId,
        quantity: String(shown.value),
        unit: shown.unit,
        expectedPrice: expected,
        priceNote: expected !== null ? `List price ${readableRate(expected, shown.unit)}` : null,
      });
    }
  }
  return out;
}

/**
 * The list, in the shape it is ordered in: one group per vendor, in the order the vendors first
 * appear.
 *
 * <p>Grouping rather than sorting, and it is the whole of Rajeev's complaint (D-24 §6). One flat
 * table "does not make it clear and obvious that these ingredients are going to be ordered from
 * different vendors via separate PO's" — the vendor was a column somebody had to read down, and the
 * one button at the top raised four orders at once without ever showing which was which.
 *
 * <p>Unticked lines stay in their vendor's group. An untick is a decision about a line, not a
 * removal of it: it still belongs to that vendor, it is still what this screen is for reading, and
 * the tick is how it comes back.
 */
function groupByVendor(lines: ShoppingListLineView[], moved: Record<string, MovedTo>): VendorGroup[] {
  const groups = new Map<string, VendorGroup>();
  for (const line of lines) {
    // A "No vendor yet" line somebody chose a vendor for on this visit (R-SL-4). Only a line the
    // server has no vendor for is ever moved: once the server names one — because the tick saved
    // the vendor as preferred — the server's answer is the one shown.
    const move = line.suggestedVendorId === null ? moved[line.ingredientId] : undefined;
    const vendorId = move ? move.vendorId : line.suggestedVendorId;
    const vendorName = move ? move.vendorName : line.suggestedVendorName;
    const key = vendorId ?? "";
    let group = groups.get(key);
    if (!group) {
      group = { vendorId, vendorName, lines: [] };
      groups.set(key, group);
    }
    group.lines.push(line);
  }
  // The vendorless group last, wherever its first line happened to fall: it is the one group
  // nothing can be done with from here, so it does not belong above the ones that can.
  return [...groups.values()].sort((a, b) => Number(a.vendorId === null) - Number(b.vendorId === null));
}

function ShoppingListView() {
  const { getToken } = useAuth();
  const { data, error, loading, reload } = useAuthedQuery(api.listShoppingList);
  const lines = data ?? [];

  // The catalogue, for the picker below the table. Its own failure is deliberately not raised as
  // the screen's error: the list is what somebody came here to read, and losing the ability to add
  // a line is not a reason to replace it with an error notice. A catalogue that did not load simply
  // means no picker — which is what the page has always looked like until now.
  const { data: catalogue } = useAuthedQuery(api.listIngredients);

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  /**
   * A refusal the order panel's form makes itself, before anything is sent: no lines, a line with
   * no quantity, part of a pack, a date that has passed. Kept apart from `actionError` because it
   * is not a failure of the server or the connection (T-303, the fix T-298 made on /orders/new).
   *
   * <p>These used to go through `toApiError(null, …)`, which exists for a request that never
   * reached the server, so the panel put "Check your connection and try again." and "If you need
   * help, quote KMS-0000" under a sentence that already says what to do. So it is the one sentence
   * and nothing under it. It lives exactly as long as the notice it replaced did: until the next
   * action starts or the panel closes, which is why every place that cleared `actionError` now
   * calls `clearNotices`.
   */
  const [refusal, setRefusal] = useState<string | null>(null);
  function clearNotices() {
    setActionError(null);
    setRefusal(null);
  }
  function refuse(message: string) {
    setActionError(null);
    setRefusal(message);
  }
  // The vendor whose order is open in the panel, and null while nobody is ordering. The panel is
  // mounted against this group and that vendor's supplies (for the list price, T-264), so pressing a
  // tile's button is the whole of opening it once the supplies are read.
  const [ordering, setOrdering] = useState<{ group: VendorGroup; supplies: VendorSupplyView[] } | null>(null);
  // "No vendor yet" lines somebody chose a vendor for on this visit (R-SL-4). See MovedTo.
  const [moved, setMoved] = useState<Record<string, MovedTo>>({});
  // The active vendors, for the Choose vendor dropdowns. Read only when there is a line with no
  // vendor, because that is the only place they are offered.
  const [vendors, setVendors] = useState<VendorView[] | null>(null);
  const needsVendors = lines.some((l) => l.suggestedVendorId === null);
  useEffect(() => {
    if (!needsVendors || vendors !== null) return;
    let cancelled = false;
    (async () => {
      try {
        const all = await api.listVendors(true, await getToken());
        if (!cancelled) setVendors(all.filter((v) => v.active));
      } catch (e) {
        if (!cancelled) setActionError(toApiError(e, "We couldn’t load the vendors to choose from."));
      }
    })();
    return () => { cancelled = true; };
  }, [needsVendors, vendors, getToken]);
  // The confirmation left behind by a created order. It names the order — "PO-2026-0041" — and
  // fades, because the person is still on this screen working down the vendors that are left.
  const [created, setCreated] = useState<{ poNumber: string; vendorName: string } | null>(null);

  async function run(mutation: (token: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    clearNotices();
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

  async function setIncluded(line: ShoppingListLineView, included: boolean) {
    await run(
      (t) => api.updateShoppingListLine(line.ingredientId, { suggestedQty: line.suggestedQty, included }, t),
      "We couldn’t update that line."
    );
  }

  async function setQty(line: ShoppingListLineView, qty: number) {
    await run(
      (t) => api.updateShoppingListLine(line.ingredientId, { suggestedQty: qty, included: line.included }, t),
      "We couldn’t update that quantity."
    );
  }

  // The whole of T-027 on this side: one POST, and the list reloads with the new line on it. The
  // server marks it hand-added, which is the only thing that puts a line on this list that no
  // demand stream will ever reach — and the reason the line is worth typing at all.
  async function addLine(ingredientId: string, suggestedQty: number) {
    return run(
      (t) => api.addShoppingListLine({ ingredientId, suggestedQty }, t),
      "We couldn’t add that to the list."
    );
  }

  /**
   * Choosing a vendor for "No vendor yet" lines — one line from its own dropdown, or every ticked
   * line from the bulk bar (R-SL-4, conductor's rulings 3 and 4).
   *
   * <p>The lines move into the vendor's tile at once, on this page. With "Use this vendor next time"
   * ticked the vendor is also saved as the ingredient's preferred vendor through the vendor page's
   * own call (PUT /vendors/{id}/supplies), and the list is read again, after which the server puts
   * the line under that vendor on every visit.
   *
   * <p><strong>Saving the link never loses what the vendor page already holds.</strong> That call
   * writes every column of the supply row, so a key left out is a value wiped (T-131). When this
   * vendor already supplies the ingredient, its list price, its "Sells it as" pack and price per
   * pack, and its lead time are read first and sent back unchanged; only Preferred is turned on. If
   * they cannot be read, nothing is written — a vendor saved for next time is not worth a price
   * lost — and the person is told the move is for this visit only.
   *
   * <p>A price sold in a pack goes back as the price per pack with the per-unit price null, as the
   * vendor page sends it: the server works the per-unit price out and refuses both at once
   * (KMS-400163).
   */
  async function chooseVendor(chosen: ShoppingListLineView[], vendor: VendorView, remember: boolean) {
    if (chosen.length === 0) return;
    setMoved((cur) => {
      const next = { ...cur };
      for (const l of chosen) next[l.ingredientId] = { vendorId: vendor.id, vendorName: vendor.name };
      return next;
    });
    if (!remember) return;
    setBusy(true);
    clearNotices();
    try {
      const token = await getToken();
      for (const l of chosen) {
        const existing = (await api.listIngredientSupplies(l.ingredientId, token))
          .find((s) => s.vendorId === vendor.id);
        const packed = existing?.packSizeId != null;
        await api.setVendorSupply(
          vendor.id,
          {
            ingredientId: l.ingredientId,
            lastPrice: existing && !packed ? existing.lastPrice : null,
            leadTimeDays: existing ? existing.leadTimeDays : null,
            preferred: true,
            packSizeId: packed ? existing.packSizeId : null,
            pricePerPack: packed ? existing.pricePerPack : null,
          },
          token
        );
      }
      reload();
    } catch (e) {
      setActionError(toApiError(e, `We couldn’t save ${vendor.name} for next time. The lines are under ${vendor.name} for this visit only.`));
    } finally {
      setBusy(false);
    }
  }

  /**
   * Opens a tile's order panel, after reading the vendor's supplies for the list price each line
   * carries (conductor's ruling, 2026-09-19). A vendor page that cannot be read opens the panel
   * anyway with no prices: the server fills a blank price from the same list price (T-260), so the
   * order loses nothing but the words under each item.
   */
  async function openOrder(group: VendorGroup) {
    if (!group.vendorId) return;
    clearNotices();
    setBusy(true);
    let supplies: VendorSupplyView[] = [];
    try {
      supplies = (await api.getVendor(group.vendorId, await getToken())).supplies;
    } catch {
      supplies = [];
    } finally {
      setBusy(false);
    }
    setOrdering({ group, supplies });
  }

  /**
   * Creates the order the panel is holding, and that press is what creates it (T-134, D-24 §6).
   *
   * <p><strong>Nothing exists until Save.</strong> The panel is a working copy — the quantities off
   * the list, the date, anything added or taken off — and it is written in one request. That is why
   * the "Cancel this PO" control is nowhere near it: there is no order to cancel, and the editor
   * gates that block on the order having a number rather than on which screen it is.
   *
   * <p><strong>Why this posts the order, and why there is no generator to ask instead.</strong>
   * The server used to offer {@code POST /purchase-orders/generate}, which raised one order per
   * vendor from the list as the server last computed it. It could not carry an adjusted quantity, a
   * line somebody removed, an uncatalogued item, or the date typed in the box — everything the panel
   * exists to let a person do — and generating first and correcting afterwards would have meant the
   * order existed before Save, with a number, which is precisely what Rajeev ruled against. Once
   * this screen stopped calling it nothing did, and it was retired on 2026-09-12 (T-153).
   *
   * <p>The notes line is the one the generator used to write, kept deliberately: an order raised
   * from this screen still says where it came from.
   */
  async function createOrder(group: VendorGroup, draft: PurchaseOrderDraft) {
    if (!group.vendorId) return;
    setBusy(true);
    clearNotices();
    try {
      const { poNumber } = await api.createPurchaseOrder(
        {
          vendorId: group.vendorId,
          neededBy: draft.neededBy,
          deliveryLocation: null,
          notes: "Generated from the shopping list",
          lines: draft.lines,
        },
        await getToken()
      );
      setOrdering(null);
      setCreated({ poNumber, vendorName: group.vendorName ?? "" });
      // D-24a: a line leaves the list the moment an order is created, draft or not — "IF we take it
      // off on send, they will be there in the shopping list begging to be ordered, someone else
      // will take pity and generate another PO." The server already answers that way, because a
      // draft covers its ingredients; this is the read that shows it.
      reload();
    } catch (e) {
      setActionError(toApiError(e, "We couldn’t create that order."));
    } finally {
      setBusy(false);
    }
  }

  const groups = groupByVendor(lines, moved);

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/shopping-list" />
      <main className="min-w-0 flex-1 px-4 py-10 sm:px-8">
        <div className="mx-auto max-w-content">
          {/* There is no "Generate shopping list" button here any more, and its absence is the whole
              of T-132 on this side. Rajeev asked why the screen needed one at all when the list
              could populate itself on load — and it turned out there were two doors onto the same
              write, the button and a job at 04:30, so the answer was that neither should exist.
              The list is worked out fresh every time this page is read. The sub-heading says so
              plainly rather than leaving somebody hunting for the button they remember.

              And there is no "Generate purchase orders" button either, which is T-134 (D-24 §6).
              One button at the top raised an order to every vendor at once: "one Generate button at
              the top tied to several vendors at once is the wrong shape". Each vendor's tile now
              carries its own, and the server endpoint that button called was retired with it
              (T-153, 2026-09-12). */}
          <header className="mb-6">
            <h1>Shopping list</h1>
            <p className="mt-1 max-w-prose text-ink-secondary">
              Worked out from the meal plan and the store room each time you open this page. Each
              vendor is ordered from separately — check the lines in a tile, then create that
              vendor’s purchase order.
            </p>
          </header>

          {created && (
            <div className="mb-6">
              {/* Named, and it fades (D-24 §6): "a green confirmation naming the PO number appears
                  and fades, and the user is back on the shopping list working through the vendors
                  that are left". No link on it for that reason — the next thing to do is the next
                  tile, and an action here would argue with a notice that is about to disappear.
                  "Was created", not "raised" (T-289, D-4): R-PO-1 named the act "Create", and
                  /orders confirms the same act with "…was created" — one word for one act. */}
              <InlineNotice tone="success" autoDismiss title={`${created.poNumber} was created for ${created.vendorName}.`}>
                It stays a draft until it is sent to the vendor. Its ingredients have left this list.
              </InlineNotice>
            </div>
          )}

          {actionError && !ordering && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

          {loading ? (
            <Loading label="Loading the shopping list…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : lines.length === 0 ? (
            <div className="card px-6 py-14 text-center">
              <p className="text-lg">Nothing to order</p>
              {/* No instruction here, because there is no longer an action to take: nothing is
                  short, nothing is below its reorder level, and anything already on a purchase
                  order is being dealt with. Telling somebody to press a button that is not there
                  was the old copy, and it was wrong the moment the button went. */}
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                Nothing is running short, and anything already on a purchase order is in hand. Add
                something below if you know of a need this list can’t see.
              </p>
            </div>
          ) : (
            <div className="grid gap-6">
              {groups.map((group) => (
                <VendorTile
                  key={group.vendorId ?? "no-vendor"}
                  group={group}
                  busy={busy}
                  vendors={vendors}
                  onInclude={setIncluded}
                  onQuantity={setQty}
                  onChooseVendor={chooseVendor}
                  onOrder={() => openOrder(group)}
                />
              ))}
            </div>
          )}

          {/* Offered under both the table and the empty state, and for the same reason in each: the
              list being empty is not evidence that nothing is needed, only that nothing was
              computed. A cook who knows the gas is nearly out has the same thing to say either way. */}
          {!loading && !error && catalogue && (
            <AddLine
              busy={busy}
              ingredients={catalogue}
              alreadyOnList={lines.map((l) => l.ingredientId)}
              onAdd={addLine}
            />
          )}
        </div>
      </main>

      {ordering && (
        <OrderPanel
          group={ordering.group}
          supplies={ordering.supplies}
          ingredients={catalogue ?? []}
          busy={busy}
          error={actionError}
          refusal={refusal}
          onRefuse={refuse}
          onSave={(draft) => createOrder(ordering.group, draft)}
          onClose={() => { setOrdering(null); clearNotices(); }}
        />
      )}
    </div>
  );
}

/**
 * One vendor's ingredients, and the button that orders them (T-134).
 *
 * <p>Rajeev, 2026-09-10, driving the deployed application: a tile per vendor, "holding that
 * vendor's ingredients, each with its own Generate Purchase Order button". The vendor is the tile's
 * heading rather than a column, because the tile <em>is</em> the purchase order that is about to
 * exist — everything in it goes on one order to one supplier, and nothing outside it does.
 *
 * <p>The button is refused, not hidden, when every line in the tile is unticked: the tile is still
 * worth reading, and a button that vanished would leave somebody wondering where it went. It is
 * absent only where there is no vendor to order from, which is a different thing entirely.
 */
function VendorTile({
  group, busy, vendors, onInclude, onQuantity, onChooseVendor, onOrder,
}: {
  group: VendorGroup;
  busy: boolean;
  /** Active vendors for the Choose vendor dropdowns, or null while they load. */
  vendors: VendorView[] | null;
  onInclude: (line: ShoppingListLineView, included: boolean) => void;
  onQuantity: (line: ShoppingListLineView, qty: number) => void;
  onChooseVendor: (lines: ShoppingListLineView[], vendor: VendorView, remember: boolean) => void;
  onOrder: () => void;
}) {
  const ticked = group.lines.filter((l) => l.included);
  const included = ticked.length;
  const noVendor = group.vendorId === null;

  return (
    <section className="card min-w-0 px-6 py-5" aria-labelledby={`vendor-${group.vendorId ?? "none"}`}>
      <div className="mb-4 flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 id={`vendor-${group.vendorId ?? "none"}`} className="text-lg">
            {group.vendorName ?? "No vendor yet"}
          </h2>
          <p className="mt-1 text-sm text-ink-secondary">
            {/* R-SL-4 replaced "Set one on the ingredient": the vendor is now chosen here, on the
                line or for every ticked line at once. */}
            {noVendor
              ? "These have no preferred vendor yet. Choose a vendor for a line, or one for every ticked line."
              : `${included} of ${group.lines.length} ${group.lines.length === 1 ? "line" : "lines"} will go on this order.`}
          </p>
        </div>
        {!noVendor && (
          <button
            type="button"
            disabled={busy || included === 0}
            onClick={onOrder}
            className="btn btn-primary min-h-touch px-5 transition-colors duration-state disabled:opacity-60"
          >
            Generate purchase order
          </button>
        )}
      </div>

      {noVendor && (
        <BulkVendorBar busy={busy} vendors={vendors} ticked={ticked} onChooseVendor={onChooseVendor} />
      )}

      <div className="table-wrap overflow-x-auto">
        {/* On the table rule since 2026-09-18 (T-233). The tick box leads on the left although it
            is fixed — the exception Rajeev accepted, because a selection box is first in every
            list anybody has used. The ingredient is the primary flexible column and the reasons
            (the chips) the secondary one; the figures and the order-by badge are fixed, one line
            each. Since T-236 every column reads left and the spare width is shared evenly between
            them — Rajeev's own example of the rule was this table's first three columns.

            The "No vendor yet" tile has one more column, Vendor, holding the line's Choose vendor
            dropdown and its "Use this vendor next time" tick side by side (R-SL-4, conductor's
            ruling 4). Last, because it is what is done after reading the rest of the row. */}
        <table className={RULED_TABLE} aria-label={`Ingredients from ${group.vendorName ?? "no vendor"}`}>
          <thead className={THEAD}>
            <tr>
              <th className={TH_LEAD}>Include</th>
              <th className={TH_PRIMARY}>Ingredient</th>
              <th className={TH_SECOND}>Why</th>
              <th className={TH_FIXED}>On hand</th>
              <th className={TH_FIXED}>Suggested</th>
              {/* "Order by", not "Needed by" — see OrderByCell. The date this column used to
                  show was the delivery date written on the purchase order, which is a
                  different question from the one somebody reading this list is asking. */}
              <th className={TH_FIXED}>Order by</th>
              {noVendor && <th className={TH_SECOND}>Vendor</th>}
            </tr>
          </thead>
          <tbody>
            {group.lines.map((l) => (
              <tr key={l.ingredientId} className={`${TR} ${l.included ? "" : "opacity-50"}`}>
                <td className={TD_LEAD}>
                  <input type="checkbox" aria-label={`Include ${l.ingredientName}`} checked={l.included} disabled={busy} onChange={(e) => onInclude(l, e.target.checked)}
                    className="accent-accent"
                  />
                </td>
                {/* The second unbounded value in this table, after the chips. An ingredient
                    somebody typed has no maximum length, and refusing it a second line would
                    carry the columns beyond it off the edge of the page. */}
                <td className={`${TD_PRIMARY} font-medium`}>
                  {l.ingredientName}
                  {l.edited && <span className="ml-2 text-xs text-ink-muted">edited</span>}
                  {/* An untick persists, and the cost of that was named rather than hidden: one
                      made in September suppresses a January shortfall, and in between the line
                      is not on the screen for anybody to notice. This is the mitigation. When
                      the ingredient is needed again the line comes back — unticked, and saying
                      when somebody decided against it, so a stale decision announces itself at
                      the moment it starts to matter. Re-ticking is the box to the left. */}
                  {!l.included && l.excludedSince && (
                    <span className="ml-2 text-xs text-warning">
                      Not ordering — since {dateWithYear(l.excludedSince)}
                    </span>
                  )}
                </td>
                <td className={TD_SECOND}>
                  {/* The chips sit on one line when the table has room, and reflow only when it has not.
                      Each chip is kept whole (T-264): measured at 1280 in the "No vendor yet" tile,
                      the column's break-anywhere rule split "shortfall" into "short" and "fall". */}
                  <div className="flex flex-wrap gap-1">
                    {l.shortfall > 0 && <span className="whitespace-nowrap rounded-control bg-warning-bg px-2 py-0.5 text-xs text-warning font-semibold">shortfall {cooksQuantity(l.shortfall, l.unit)}</span>}
                    {l.thresholdTopUp > 0 && <span className="whitespace-nowrap rounded-control bg-sunken px-2 py-0.5 text-xs text-ink-secondary font-semibold">Top-up {cooksQuantity(l.thresholdTopUp, l.unit)}</span>}
                    {l.poOutstanding > 0 && <span className="whitespace-nowrap rounded-control bg-accent-bg px-2 py-0.5 text-xs text-accent-text font-semibold">PO short {cooksQuantity(l.poOutstanding, l.unit)}</span>}
                    {l.shortPurchaseOrders.map((po) => <span key={po} className="whitespace-nowrap rounded-control bg-accent-bg px-2 py-0.5 text-xs text-accent-text font-semibold">{po}</span>)}
                  </div>
                </td>
                <td className={`${TD_FIXED_NUM} text-ink-secondary`} data-label="On hand">{cooksQuantity(l.currentStock, l.unit)}</td>
                {/* A quantity and its unit are one reading — "55 Kg", never a 55 with a Kg
                    somewhere under it — so the cell refuses to break between them. */}
                <td className={TD_FIXED_NUM} data-label="Order">
                  <SuggestedCell line={l} busy={busy} onQuantity={onQuantity} />
                </td>
                {/* Written the way the rest of the application writes a date, and kept whole:
                    "2026-09-" on one line and "01" on the next is not a date. */}
                <td className={`${TD_FIXED} text-ink-secondary`}>
                  <OrderByCell line={l} />
                </td>
                {noVendor && (
                  // Flexible, not fixed: the dropdown and its tick sit side by side when the
                  // table has room, and the tick drops under the dropdown when it has not —
                  // measured at 1280, a fixed column here took its full width from the ingredient
                  // name, which then broke onto four lines.
                  <td className={TD_SECOND}>
                    <ChooseVendor line={l} busy={busy} vendors={vendors} onChooseVendor={onChooseVendor} />
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

/**
 * The Suggested box: what will be ordered, readable, and in the vendor's pack where there is one.
 *
 * <p><strong>Readable (R-SL-1).</strong> The box shows the amount in the unit a person says it in —
 * 3000 gm is "3" beside "Kg" — and what is typed is read in the unit printed beside the box, so 2.5
 * typed beside "Kg" on a line kept in grams saves 2500. The box and its label come from one call
 * ({@link entryQuantity}) and cannot disagree, which is what made the old "gm beside a figure in
 * kilograms" warning safe to retire. The box is keyed on the amount and its unit, so when the list
 * comes back with a new figure the box is rebuilt with it rather than keeping a stale number beside
 * a label that has changed from gm to Kg.
 *
 * <p><strong>The server's buying amount (R-SL-2).</strong> The figure is the server's, already
 * rounded up to something a vendor sells (T-259); nothing here rounds. A typed figure is sent as
 * typed (only converted into the stored unit) and the server never re-rounds it.
 *
 * <p><strong>The vendor's pack (R-SL-3).</strong> When the preferred vendor sells it in a pack, the
 * box counts packs — "4" beside "× Bag (25 Kg)" — and the stock-unit amount (100 Kg) is said under
 * it. A pack suggestion that is not the vendor's (the ingredient's own sizes, R-SL-2) keeps the
 * amount box and says the packs under it, because a typed amount is not re-cut into packs.
 */
function SuggestedCell({
  line, busy, onQuantity,
}: {
  line: ShoppingListLineView;
  busy: boolean;
  onQuantity: (line: ShoppingListLineView, qty: number) => void;
}) {
  const vendorPack = line.packFromVendor && line.buyPacks.length === 1 ? line.buyPacks[0] : null;
  /*
   * This box commits on blur rather than on a button, so its refusal has to appear on blur too
   * (T-424). A fraction of a counted thing is held, shown in red under the box and NOT sent — the
   * typed figure stays on screen to be corrected rather than being silently dropped or rounded.
   */
  const [problem, setProblem] = useState<string | null>(null);

  if (vendorPack) {
    return (
      <>
        <input
          key={`${line.suggestedQty}-${vendorPack.packSizeId}`}
          type="number" min="1" step="1" defaultValue={vendorPack.count} disabled={busy}
          aria-label={`Quantity for ${line.ingredientName}, in ${vendorPack.label}`}
          onBlur={(e) => {
            const n = Number(e.target.value);
            if (e.target.value.trim() === "" || !Number.isFinite(n) || n === vendorPack.count) return;
            onQuantity(line, Number((n * vendorPack.perPackQty).toFixed(6)));
          }}
          className="w-16 rounded-control border border-hairline px-2 py-1 tabular-nums"
        />{" "}
        <span className="text-xs text-ink-muted">× {vendorPack.label}</span>
        <span className="block text-xs text-ink-muted">= {quantity(line.suggestedQty, line.unit)}</span>
      </>
    );
  }

  const shown = entryQuantity(line.suggestedQty, line.unit);
  return (
    <>
      <input
        key={[line.suggestedQty, line.unit].join("-")}
        type="number" min="0" step={stepForUnit(shown.unit)} defaultValue={shown.value} disabled={busy}
        inputMode={stepForUnit(shown.unit) === "1" ? "numeric" : "decimal"}
        aria-label={`Quantity for ${line.ingredientName}`}
        aria-invalid={problem ? true : undefined}
        onBlur={(e) => {
          const n = Number(e.target.value);
          if (e.target.value.trim() === "" || !Number.isFinite(n)) return;
          const whole = wholeNumberProblem(
            `Quantity for ${line.ingredientName}`,
            stepForUnit(shown.unit),
            e.target.value,
          );
          setProblem(whole);
          if (whole) return;
          const stored = fromEntry(n, shown.unit, line.unit);
          if (stored !== line.suggestedQty) onQuantity(line, stored);
        }}
        className={`w-20 rounded-control border px-2 py-1 tabular-nums ${problem ? "border-danger" : "border-hairline"}`}
      />{" "}
      <span className="text-xs text-ink-muted">{unitLabel(shown.unit)}</span>
      {problem && <span className="block text-xs text-danger">{problem}</span>}
      {line.buyPacks.length > 0 && (
        <span className="block text-xs text-ink-muted">{packsText(line.buyPacks)}</span>
      )}
    </>
  );
}

/** The vendor dropdown's options: a prompt, then every active vendor by name. */
function VendorOptions({ vendors }: { vendors: VendorView[] | null }) {
  return (
    <>
      <option value="">Choose vendor…</option>
      {(vendors ?? []).map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}
    </>
  );
}

/**
 * The vendor dropdown's look: the control corner, and a fixed width.
 *
 * <p>The width is the point. A select sizes itself to its longest option, so one vendor called "Sri
 * Lakshmi Venkateswara Wholesale Provisions" made every dropdown 276px wide — measured in an
 * isolated render, it pushed the tick under the dropdown at 1280 and the page sideways at 390. The
 * closed dropdown only ever reads "Choose vendor…" (the line leaves the tile the moment a vendor is
 * chosen), so a width that fits those words cuts nothing off, and the open list still shows every
 * name in full. `max-w-full` keeps it inside a phone card.
 */
const VENDOR_SELECT = "w-44 max-w-full rounded-control border border-hairline bg-canvas px-2 py-1 text-sm";

/**
 * One "No vendor yet" line's own choice (R-SL-4): the Choose vendor dropdown and, beside it, the
 * "Use this vendor next time" tick, on by default (conductor's ruling 4).
 *
 * <p>Choosing is the act, as R-SL-4 words it — "choosing a vendor moves the line into that vendor's
 * tile" — so there is no second button to press. The dropdown goes back to its prompt afterwards:
 * the line has already left this tile.
 */
function ChooseVendor({
  line, busy, vendors, onChooseVendor,
}: {
  line: ShoppingListLineView;
  busy: boolean;
  vendors: VendorView[] | null;
  onChooseVendor: (lines: ShoppingListLineView[], vendor: VendorView, remember: boolean) => void;
}) {
  const [remember, setRemember] = useState(true);
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
      <select
        value=""
        disabled={busy || vendors === null}
        aria-label={`Choose vendor for ${line.ingredientName}`}
        onChange={(e) => {
          const vendor = vendors?.find((v) => v.id === e.target.value);
          if (vendor) onChooseVendor([line], vendor, remember);
        }}
        className={VENDOR_SELECT}
      >
        <VendorOptions vendors={vendors} />
      </select>
      <label className="flex items-center gap-2 text-sm text-ink-secondary">
        <input
          type="checkbox"
          checked={remember}
          disabled={busy}
          aria-label={`Use this vendor next time for ${line.ingredientName}`}
          onChange={(e) => setRemember(e.target.checked)}
          className="accent-accent"
        />
        Use this vendor next time
      </label>
    </div>
  );
}

/**
 * "Order these from [vendor ▾]" (R-SL-4, conductor's ruling 3): one vendor for every ticked line in
 * the "No vendor yet" tile, with the same "Use this vendor next time" tick once more, applying to
 * all of them (ruling 4).
 *
 * <p>The ticks are the tile's existing Include ticks, as the conductor ruled, rather than a second
 * box on each row: a line somebody has decided not to order is exactly the line they do not want to
 * move with the rest. Refused, not hidden, with nothing ticked — the bar still says what it is for.
 */
function BulkVendorBar({
  busy, vendors, ticked, onChooseVendor,
}: {
  busy: boolean;
  vendors: VendorView[] | null;
  ticked: ShoppingListLineView[];
  onChooseVendor: (lines: ShoppingListLineView[], vendor: VendorView, remember: boolean) => void;
}) {
  const [remember, setRemember] = useState(true);
  const selectId = useId();
  return (
    <div className="mb-4 flex flex-wrap items-center gap-x-4 gap-y-2">
      <span className="flex items-center gap-2">
        <label htmlFor={selectId} className="text-sm font-medium text-ink">Order these from</label>
        <select
          id={selectId}
          value=""
          disabled={busy || vendors === null || ticked.length === 0}
          onChange={(e) => {
            const vendor = vendors?.find((v) => v.id === e.target.value);
            if (vendor) onChooseVendor(ticked, vendor, remember);
          }}
          className={`${VENDOR_SELECT} min-h-touch`}
        >
          <VendorOptions vendors={vendors} />
        </select>
      </span>
      <label className="flex items-center gap-2 text-sm text-ink-secondary">
        <input
          type="checkbox"
          checked={remember}
          disabled={busy}
          aria-label="Use this vendor next time for the ticked lines"
          onChange={(e) => setRemember(e.target.checked)}
          className="accent-accent"
        />
        Use this vendor next time
      </label>
    </div>
  );
}

/**
 * The purchase order a tile is about to raise, over the list it came from (T-134).
 *
 * <p><strong>The panel and the edit screen are the same thing</strong> — Rajeev's own sentence, and
 * the reason there is no form in this file. What is here is the panel: the covering, the heading
 * that names the vendor, somewhere for a refusal to appear, and Escape. The form inside it is
 * {@code PurchaseOrderEditor}, exactly as {@code /orders/[id]} mounts it.
 *
 * <p><strong>The needed-by date it opens with is the earliest of the vendor's lines</strong>, which
 * is the rule the retired generator used: the order is only useful if it arrives in time for the
 * first meal that wants any of it. Lines that nothing demanded — a top-up below the reorder level,
 * something typed in by hand — carry no date and so contribute none, and a tile made only of those
 * opens with the box empty, which is truthful: there is no date to meet.
 *
 * <p>The error notice is inside the panel rather than on the page behind it. A refusal rendered
 * under the covering would be a message nobody can see.
 */
function OrderPanel({
  group, supplies, ingredients, busy, error, refusal, onSave, onRefuse, onClose,
}: {
  group: VendorGroup;
  /** This vendor's supplies, for the list price on each line (T-264). Empty when unreadable. */
  supplies: VendorSupplyView[];
  ingredients: IngredientView[];
  busy: boolean;
  error: ApiError | null;
  /** The form's own refusal: one sentence, no next step, no code. See `refusal` on the page. */
  refusal: string | null;
  onSave: (draft: PurchaseOrderDraft) => void | Promise<void>;
  onRefuse: (message: string) => void;
  onClose: () => void;
}) {
  const ordering = group.lines.filter((l) => l.included);
  const dates = ordering.map((l) => l.neededBy).filter((d): d is string => d !== null).sort();

  // Escape closes the panel, as it does anywhere a panel covers what somebody was reading — on the
  // window, like the vendor dialog, because the covering itself is not focusable and a handler on
  // it would only fire once somebody had already clicked inside. Nothing is lost that was not typed
  // into this panel: no order exists until Save.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  // The panel is now the thing on the screen, so the keyboard belongs in it: a person who pressed
  // the tile's button with the keyboard would otherwise tab on through the list behind the
  // covering. The panel itself takes focus rather than its first field, so a screen reader reads
  // the heading before the date box.
  const panel = useRef<HTMLDivElement>(null);
  useEffect(() => panel.current?.focus(), []);

  return (
    <div
      className="fixed inset-0 z-50 overflow-y-auto bg-ink/40 px-4 py-10"
      role="dialog"
      aria-modal="true"
      aria-label={`Purchase order for ${group.vendorName ?? ""}`}
      ref={panel}
      tabIndex={-1}
    >
      <div className="modal mx-auto max-w-content px-8 py-7">
        {refusal && (
          // ErrorNotice's box, less its next-step line and reference code: see `refusal` on the page.
          <div role="alert" className="mb-6 rounded border border-danger bg-danger-bg p-4 text-danger">
            <p className="font-medium">{refusal}</p>
          </div>
        )}
        {error && <div className="mb-6"><ErrorNotice error={error} /></div>}
        <PurchaseOrderEditor
          words={{
            heading: `Purchase order for ${group.vendorName ?? ""}`,
            formLabel: `Purchase order for ${group.vendorName ?? ""}`,
            intro: "Nothing is ordered until you save. These lines leave the shopping list when the order is created, and come back if it is cancelled.",
            emptyOrder: "An order needs at least one line. Add what is being bought, or close this panel to leave the list as it is.",
            dateBeforeFloor: "That date has already passed. Choose today or a day after it.",
          }}
          // Readable amounts, one line per pack size, and the vendor's list price on each — see
          // panelLines. The price used to be left for the server to fill in; the conductor ruled
          // on 2026-09-19 that an order from a tile carries it, as the Create a purchase order form
          // does, so the person sees it before saving.
          initialLines={panelLines(ordering, supplies)}
          initialNeededBy={dates[0] ?? ""}
          // The order does not exist yet, so it is dated today the moment it is saved, and today is
          // the floor the server measures a typed date against (KMS-400014).
          minNeededBy={todayIso()}
          ingredients={ingredients}
          busy={busy}
          onSave={onSave}
          onCancel={onClose}
          onRefuse={onRefuse}
        />
      </div>
    </div>
  );
}

/**
 * When this line has to be ordered, and how much of the chance to do it is left (T-090).
 *
 * <p>Rajeev's rule: <em>"order-by date = the date it is needed minus the lead time. Amber while
 * there is still slack; red the day you hit the order-by date; and past that it is not a warning any
 * more but a fact, and should say something different."</em> The server decides which of the three
 * states this is, from the temple's own clock and the lead time recorded against the vendor the
 * order would go to; this decides only the words, so the shopping list and the planner badge cannot
 * come to disagree about whether there is still time.
 *
 * <p><strong>The third state is a different sentence, not a darker red.</strong> Once the date has
 * gone, asking somebody to order in time is asking for something that no longer exists, so the cell
 * says what is now true. It keeps the same red as "order today" on purpose — the words carry the
 * difference between the two problems, and a shade cannot.
 *
 * <p><strong>This column replaced "Needed by", and the replacement is the point.</strong> That
 * column showed <code>neededBy</code>, which is the delivery date written on the purchase order —
 * since T-130, the day of the earliest meal that wants the ingredient. It answers "what date do we
 * put on the order?", which is a question for the order screen. The question somebody reading
 * a shopping list is actually asking is "when does this have to go out?", and until now nothing on
 * this screen answered it. Showing both would have put two columns side by side displaying the same
 * date whenever no lead time is recorded, under two names, which is worse than either alone.
 * <code>neededBy</code> is still in the payload and still on every purchase order — and since T-134
 * it is what the vendor tile's panel opens with.
 *
 * <p>"assumed" appears where no lead time is recorded against the preferred vendor. It is the one
 * place a person can see that the date in front of them came from us rather than from their
 * supplier, and it is the nudge towards recording the real one on the vendor's page.
 */
function OrderByCell({ line }: { line: ShoppingListLineView }) {
  // A hand-added line: no meal demanded it, so there is no date to compute and none is invented.
  // An em dash, deliberately, and never today — nobody is late for a bale of leaf plates somebody
  // typed in without saying when they are wanted.
  if (line.orderBy === null || line.orderUrgency === null) {
    return <>—</>;
  }
  const assumed = line.leadTimeDays === null && (
    <span className="ml-2 text-xs text-ink-muted">assumed</span>
  );
  if (line.orderUrgency === "IN_TIME") {
    return (
      <>
        <Badge tone="warning">Order by {dateWithYear(line.orderBy)}</Badge>
        {assumed}
      </>
    );
  }
  if (line.orderUrgency === "ORDER_TODAY") {
    return (
      <>
        {/* Amber, as on the order itself: there is still time if it goes today. Red is kept for
            "won't arrive in time" (Rajeev, 2026-09-18, T-227). */}
        <Badge tone="warning">Order today</Badge>
        {assumed}
      </>
    );
  }
  return (
    <>
      <Badge tone="danger">Won’t arrive in time</Badge>
      {assumed}
    </>
  );
}

/**
 * Adding a line to the shopping list by hand (T-027).
 *
 * <p>This screen could once only edit what had already been computed, so a cook who could see the
 * list was missing something had nowhere to say so. Three demand streams build it — a meal-plan
 * shortfall, stock below its threshold, and a purchase order that came up short — and none of them
 * knows that the gas is nearly out or that Janmashtami needs flowers.
 *
 * <p>A picker rather than a box to paste an identifier into: nobody knows an ingredient by its id,
 * and the vendor page, the invoice form and the order detail all choose one this way already.
 *
 * <p><strong>No unit control, deliberately.</strong> The server writes the ingredient's own
 * canonical unit — the same one regeneration writes — so the label beside the quantity states what
 * the number will be counted in rather than offering to change it. A picker here is how a list ends
 * up asking a vendor for five litres of rice.
 *
 * <p><strong>Supplies are in this picker, and that is not an exception.</strong> A gas cylinder, a
 * bale of leaf plates and a bottle of dishwashing liquid are flagged ingredients (T-023, D-1), so
 * the catalogue hands them over with everything else and they add exactly the way food does. The
 * one picker in the application that excludes them is the recipe's, because a mop is not an
 * ingredient of anything.
 *
 * <p><strong>An ingredient the temple never buys IS excluded, and that is a different rule from the
 * one above rather than an exception to it</strong> (T-402, Rajeev 2026-09-19: "water and the like
 * must never reach a shopping list"). A supply is bought — that is the whole of why it stays in this
 * picker — and a marked ingredient is not bought at all, so offering it here would offer an action
 * whose only possible end is a line the server refuses and the derivation drops. `ShoppingListService`
 * leaves a marked ingredient out of the computed list, and `addLine` refuses the hand-add for
 * anybody who posts past this picker, because a picker is not a guard.
 */
function AddLine({
  busy, ingredients, alreadyOnList, onAdd,
}: {
  busy: boolean;
  ingredients: IngredientView[];
  alreadyOnList: string[];
  onAdd: (ingredientId: string, suggestedQty: number) => Promise<boolean>;
}) {
  const [chosen, setChosen] = useState("");
  const [qty, setQty] = useState("");

  // Something already on the list is changed on its own row, and the server refuses a second line
  // for it (KMS-400131) — and it now checks the derived list rather than a table, so an ingredient
  // the shortfall stream suggested is caught as well. Leaving it in the picker would be offering an
  // action that cannot succeed.
  const available = ingredients.filter((i) => !alreadyOnList.includes(i.id) && !i.notBought);
  const ingredient = available.find((i) => i.id === chosen);

  // A quantity of zero is refused by the server and by the column's own CHECK. The button is
  // disabled rather than the refusal being left to be discovered, but the server still decides.
  const quantity = Number(qty);
  // A counted ingredient is added as whole things (T-424). Said out loud under the box rather
  // than only greying the button, which leaves somebody pressing a dead control and guessing.
  const step = stepForUnit(ingredient?.unit);
  const wholeProblem = wholeNumberProblem("Quantity to add", step, qty);
  const ready =
    ingredient !== undefined && qty.trim() !== "" && Number.isFinite(quantity) && quantity > 0 && !wholeProblem;

  async function add() {
    if (!ingredient || !ready) return;
    // Cleared only on success, so a refusal leaves what was typed in place to be corrected rather
    // than typed again.
    if (await onAdd(ingredient.id, quantity)) {
      setChosen("");
      setQty("");
    }
  }

  return (
    <section className="mt-8 border-t border-hairline pt-6">
      <h2 className="text-base font-semibold">Add something to the list</h2>
      <div className="mt-3 flex flex-wrap items-end gap-3">
        <HintedField
          label="Item"
          hint="For anything the list didn’t work out for itself — gas, leaf plates, flowers for a festival. A line you add by hand stays on the list until it goes onto a purchase order. Anything marked “not bought” isn’t here."
        >
          {(fieldId) => (
            <select
              id={fieldId}
              value={chosen}
              disabled={busy}
              onChange={(e) => setChosen(e.target.value)}
              className="min-h-touch w-64 rounded-control border border-hairline px-3"
            >
              <option value="">Choose…</option>
              {available.map((i) => <option key={i.id} value={i.id}>{i.name}</option>)}
            </select>
          )}
        </HintedField>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Quantity</span>
          <span className="flex items-center gap-2">
            <input
              type="number" min="0" step={step} value={qty} disabled={busy}
              inputMode={step === "1" ? "numeric" : "decimal"}
              aria-label="Quantity to add"
              aria-invalid={wholeProblem ? true : undefined}
              onChange={(e) => setQty(e.target.value)}
              className={`min-h-touch w-24 rounded-control border px-2 tabular-nums ${wholeProblem ? "border-danger" : "border-hairline"}`}
            />
            {/* The unit the line will actually be written in, stated and not offered. Blank until an
                item is chosen, because there is nothing true to say yet — a placeholder unit beside
                an empty box is a guess the cook would reasonably read as a fact. */}
            <span className="text-xs text-ink-muted">{ingredient ? unitLabel(ingredient.unit) : ""}</span>
          </span>
          {wholeProblem && <span className="text-xs text-danger">{wholeProblem}</span>}
        </label>

        <button
          type="button"
          disabled={busy || !ready}
          onClick={add}
          className="min-h-touch rounded-control border border-hairline px-4 transition-colors duration-state hover:bg-sunken disabled:opacity-60"
        >
          Add to list
        </button>
      </div>
    </section>
  );
}
