"use client";

import { useEffect, useRef, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type IngredientView, type ShoppingListLineView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { cooksQuantity, dateWithYear, todayIso, unitLabel } from "@/lib/format";
import { Badge } from "@/components/ds/Badge";
import { Loading } from "@/components/Loading";
import { HintedField } from "@/components/ds/InfoHint";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { PurchaseOrderEditor, type PurchaseOrderDraft } from "@/components/PurchaseOrderEditor";
import { TABLE, THEAD, TR, TH_TEXT, TH_NUM, TD_TEXT, TD_NUM, TD_DATE, WRAP } from "@/components/ds/table";

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
function groupByVendor(lines: ShoppingListLineView[]): VendorGroup[] {
  const groups = new Map<string, VendorGroup>();
  for (const line of lines) {
    const key = line.suggestedVendorId ?? "";
    let group = groups.get(key);
    if (!group) {
      group = { vendorId: line.suggestedVendorId, vendorName: line.suggestedVendorName, lines: [] };
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
  // The vendor whose order is open in the panel, and null while nobody is ordering. The panel is
  // mounted against this group, so pressing a tile's button is the whole of opening it.
  const [ordering, setOrdering] = useState<VendorGroup | null>(null);
  // The confirmation left behind by a created order. It names the order — "PO-2026-0041" — and
  // fades, because the person is still on this screen working down the vendors that are left.
  const [raised, setRaised] = useState<{ poNumber: string; vendorName: string } | null>(null);

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
    setActionError(null);
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
      setRaised({ poNumber, vendorName: group.vendorName ?? "" });
      // D-24a: a line leaves the list the moment an order is created, draft or not — "IF we take it
      // off on send, they will be there in the shopping list begging to be ordered, someone else
      // will take pity and generate another PO." The server already answers that way, because a
      // draft covers its ingredients; this is the read that shows it.
      reload();
    } catch (e) {
      setActionError(toApiError(e, "We couldn’t raise that order."));
    } finally {
      setBusy(false);
    }
  }

  const groups = groupByVendor(lines);

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/shopping-list" />
      <main className="min-w-0 flex-1 px-8 py-10">
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
              vendor is ordered from separately — check the lines in a tile, then raise that
              vendor’s purchase order.
            </p>
          </header>

          {raised && (
            <div className="mb-6">
              {/* Named, and it fades (D-24 §6): "a green confirmation naming the PO number appears
                  and fades, and the user is back on the shopping list working through the vendors
                  that are left". No link on it for that reason — the next thing to do is the next
                  tile, and an action here would argue with a notice that is about to disappear. */}
              <InlineNotice tone="success" autoDismiss title={`${raised.poNumber} raised for ${raised.vendorName}.`}>
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
                  onInclude={setIncluded}
                  onQuantity={setQty}
                  onOrder={() => { setActionError(null); setOrdering(group); }}
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
          group={ordering}
          ingredients={catalogue ?? []}
          busy={busy}
          error={actionError}
          onRefuse={(message) => setActionError(toApiError(null, message))}
          onSave={(draft) => createOrder(ordering, draft)}
          onClose={() => { setOrdering(null); setActionError(null); }}
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
  group, busy, onInclude, onQuantity, onOrder,
}: {
  group: VendorGroup;
  busy: boolean;
  onInclude: (line: ShoppingListLineView, included: boolean) => void;
  onQuantity: (line: ShoppingListLineView, qty: number) => void;
  onOrder: () => void;
}) {
  const included = group.lines.filter((l) => l.included).length;

  return (
    <section className="card px-6 py-5" aria-labelledby={`vendor-${group.vendorId ?? "none"}`}>
      <div className="mb-4 flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 id={`vendor-${group.vendorId ?? "none"}`} className="text-lg">
            {group.vendorName ?? "No vendor yet"}
          </h2>
          <p className="mt-1 text-sm text-ink-secondary">
            {group.vendorId === null
              ? "These have no preferred supplier, so there is nobody to raise an order to. Set one on the ingredient."
              : `${included} of ${group.lines.length} ${group.lines.length === 1 ? "line" : "lines"} will go on this order.`}
          </p>
        </div>
        {group.vendorId !== null && (
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

      <div className="table-wrap overflow-x-auto">
        <table className={TABLE} aria-label={`Ingredients from ${group.vendorName ?? "no vendor"}`}>
          <thead className={THEAD}>
            <tr>
              <th className={TH_TEXT}>Include</th>
              <th className={`${TH_TEXT} ${WRAP}`}>Ingredient</th>
              <th className={TH_NUM}>On hand</th>
              <th className={TH_NUM}>Suggested</th>
              {/* The only column allowed to grow downwards, so it is the only one that may
                  take the width the others give up. */}
              <th className={`${TH_TEXT} ${WRAP}`}>Why</th>
              {/* "Order by", not "Needed by" — see OrderByCell. The date this column used to
                  show was the delivery date written on the purchase order, which is a
                  different question from the one somebody reading this list is asking. */}
              <th className={`${TH_TEXT} ${WRAP}`}>Order by</th>
            </tr>
          </thead>
          <tbody>
            {group.lines.map((l) => (
              <tr key={l.ingredientId} className={`${TR} ${l.included ? "" : "opacity-50"}`}>
                <td className={TD_TEXT}>
                  <input type="checkbox" aria-label={`Include ${l.ingredientName}`} checked={l.included} disabled={busy} onChange={(e) => onInclude(l, e.target.checked)}
                    className="accent-accent"
                  />
                </td>
                {/* The second unbounded value in this table, after the chips. An ingredient
                    somebody typed has no maximum length, and refusing it a second line would
                    carry the columns beyond it off the edge of the page. */}
                <td className={`${TD_TEXT} ${WRAP} font-medium`}>
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
                <td className={`${TD_NUM} text-ink-secondary`}>{cooksQuantity(l.currentStock, l.unit)}</td>
                {/* A quantity and its unit are one reading — "55 Kg", never a 55 with a Kg
                    somewhere under it — so the cell refuses to break between them. */}
                <td className={TD_NUM}>
                  <input
                    type="number" min="0" step="any" defaultValue={l.suggestedQty} disabled={busy}
                    aria-label={`Quantity for ${l.ingredientName}`}
                    onBlur={(e) => { const n = Number(e.target.value); if (n !== l.suggestedQty) onQuantity(l, n); }}
                    className="w-16 rounded-control border border-hairline px-2 py-1 tabular-nums"
                  />{" "}
                  {/* The bare label, never a promoted one: the box beside it holds and submits
                      the ingredient's own stored unit, so calling it "gm" beside a figure in
                      kilograms would invite a thousandfold error. */}
                  <span className="text-xs text-ink-muted">{unitLabel(l.unit)}</span>
                </td>
                <td className={`${TD_TEXT} ${WRAP}`}>
                  {/* No cap: this is the column that absorbs the table's slack, so the
                      chips reflow across whatever width is going. Capping it as well would
                      leave the cell wide and its contents short — the gap Rajeev saw. */}
                  <div className="flex flex-wrap gap-1">
                    {l.shortfall > 0 && <span className="rounded-sm bg-warning-bg px-2 py-0.5 text-xs text-warning font-semibold">shortfall {cooksQuantity(l.shortfall, l.unit)}</span>}
                    {l.thresholdTopUp > 0 && <span className="rounded-sm bg-sunken px-2 py-0.5 text-xs text-ink-secondary font-semibold">Top-up {cooksQuantity(l.thresholdTopUp, l.unit)}</span>}
                    {l.poOutstanding > 0 && <span className="rounded-sm bg-accent-bg px-2 py-0.5 text-xs text-accent-text font-semibold">PO short {cooksQuantity(l.poOutstanding, l.unit)}</span>}
                    {l.shortPurchaseOrders.map((po) => <span key={po} className="rounded-sm bg-accent-bg px-2 py-0.5 text-xs text-accent-text font-semibold">{po}</span>)}
                  </div>
                </td>
                {/* Written the way the rest of the application writes a date, and kept whole:
                    "2026-09-" on one line and "01" on the next is not a date. */}
                <td className={`${TD_DATE} text-ink-secondary`}>
                  <OrderByCell line={l} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
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
  group, ingredients, busy, error, onSave, onRefuse, onClose,
}: {
  group: VendorGroup;
  ingredients: IngredientView[];
  busy: boolean;
  error: ApiError | null;
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
        {error && <div className="mb-6"><ErrorNotice error={error} /></div>}
        <PurchaseOrderEditor
          words={{
            heading: `Purchase order for ${group.vendorName ?? ""}`,
            formLabel: `Purchase order for ${group.vendorName ?? ""}`,
            intro: "Nothing is ordered until you save. These lines leave the shopping list when the order is created, and come back if it is cancelled.",
            emptyOrder: "An order needs at least one line. Add what is being bought, or close this panel to leave the list as it is.",
            dateBeforeFloor: "That date has already passed. Choose today or a day after it.",
          }}
          initialLines={ordering.map((l) => ({
            key: l.ingredientId,
            ingredientId: l.ingredientId,
            ingredientName: l.ingredientName,
            description: null,
            quantity: String(l.suggestedQty),
            unit: l.unit,
            // The vendor's last-known price is filled in by the server when the order is created,
            // from the same vendor_supplies figure the retired generator used. The list does not
            // carry it and this panel does not invent one.
            expectedPrice: null,
          }))}
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
        <Badge tone="danger">Order today</Badge>
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
  const available = ingredients.filter((i) => !alreadyOnList.includes(i.id));
  const ingredient = available.find((i) => i.id === chosen);

  // A quantity of zero is refused by the server and by the column's own CHECK. The button is
  // disabled rather than the refusal being left to be discovered, but the server still decides.
  const quantity = Number(qty);
  const ready = ingredient !== undefined && qty.trim() !== "" && Number.isFinite(quantity) && quantity > 0;

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
          hint="For anything the list didn’t work out for itself — gas, leaf plates, flowers for a festival. A line you add by hand stays on the list until it goes onto a purchase order."
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
              type="number" min="0" step="any" value={qty} disabled={busy}
              aria-label="Quantity to add"
              onChange={(e) => setQty(e.target.value)}
              className="min-h-touch w-24 rounded-control border border-hairline px-2 tabular-nums"
            />
            {/* The unit the line will actually be written in, stated and not offered. Blank until an
                item is chosen, because there is nothing true to say yet — a placeholder unit beside
                an empty box is a guess the cook would reasonably read as a fact. */}
            <span className="text-xs text-ink-muted">{ingredient ? unitLabel(ingredient.unit) : ""}</span>
          </span>
        </label>

        <button
          type="button"
          disabled={busy || !ready}
          onClick={add}
          className="min-h-touch rounded border border-hairline px-4 transition-colors duration-state hover:bg-sunken disabled:opacity-60"
        >
          Add to list
        </button>
      </div>
    </section>
  );
}
