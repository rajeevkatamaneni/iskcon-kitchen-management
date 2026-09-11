"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type IngredientView, type ShoppingListLineView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { cooksQuantity, dateWithYear, unitLabel } from "@/lib/format";
import { Badge } from "@/components/ds/Badge";
import { Loading } from "@/components/Loading";
import { HintedField } from "@/components/ds/InfoHint";
import { TABLE, THEAD, TR, TH_TEXT, TH_NUM, TD_TEXT, TD_NUM, TD_DATE, WRAP } from "@/components/ds/table";

export default function ShoppingListPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <ShoppingListView />
    </RequireRole>
  );
}

function ShoppingListView() {
  const { getToken } = useAuth();
  const router = useRouter();
  const { data, error, loading, reload } = useAuthedQuery(api.listShoppingList);
  const lines = data ?? [];

  // The catalogue, for the picker below the table. Its own failure is deliberately not raised as
  // the screen's error: the list is what somebody came here to read, and losing the ability to add
  // a line is not a reason to replace it with an error notice. A catalogue that did not load simply
  // means no picker — which is what the page has always looked like until now.
  const { data: catalogue } = useAuthedQuery(api.listIngredients);

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);

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

  async function generate() {
    const ids = lines.filter((l) => l.included && l.suggestedVendorId).map((l) => l.ingredientId);
    setBusy(true);
    setActionError(null);
    try {
      await api.generatePurchaseOrders(ids.length > 0 ? ids : null, await getToken());
      router.push("/orders");
    } catch (e) {
      setActionError(toApiError(e, "We couldn’t generate purchase orders."));
      setBusy(false);
    }
  }

  const withVendor = lines.filter((l) => l.included && l.suggestedVendorId).length;

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/shopping-list" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          {/* There is no "Generate shopping list" button here any more, and its absence is the whole
              of T-132 on this side. Rajeev asked why the screen needed one at all when the list
              could populate itself on load — and it turned out there were two doors onto the same
              write, the button and a job at 04:30, so the answer was that neither should exist. The
              list is worked out fresh every time this page is read. The sub-heading says so plainly
              rather than leaving somebody hunting for the button they remember. */}
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Shopping list</h1>
              <p className="mt-1 text-ink-secondary">
                Worked out from the meal plan and the store room each time you open this page. Edit
                or uncheck a line before generating orders.
              </p>
            </div>
            <div className="flex gap-3">
              <button type="button" disabled={busy || withVendor === 0} onClick={generate} className="btn btn-primary min-h-touch px-5 transition-colors duration-state disabled:opacity-60">
                Generate purchase orders
              </button>
            </div>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

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
            <div className="table-wrap overflow-x-auto">
              <table className={TABLE}>
                <thead className={THEAD}>
                  <tr>
                    <th className={TH_TEXT}>Include</th>
                    <th className={`${TH_TEXT} ${WRAP}`}>Ingredient</th>
                    <th className={TH_NUM}>On hand</th>
                    <th className={TH_NUM}>Suggested</th>
                    {/* The only column allowed to grow downwards, so it is the only one that may
                        take the width the others give up. */}
                    <th className={`${TH_TEXT} ${WRAP}`}>Why</th>
                    <th className={`${TH_TEXT} ${WRAP}`}>Vendor</th>
                    {/* "Order by", not "Needed by" — see OrderByCell. The date this column used to
                        show was the delivery date written on the purchase order, which is a
                        different question from the one somebody reading this list is asking. */}
                    <th className={`${TH_TEXT} ${WRAP}`}>Order by</th>
                  </tr>
                </thead>
                <tbody>
                  {lines.map((l) => (
                    <tr key={l.ingredientId} className={`${TR} ${l.included ? "" : "opacity-50"}`}>
                      <td className={TD_TEXT}>
                        <input type="checkbox" aria-label={`Include ${l.ingredientName}`} checked={l.included} disabled={busy} onChange={(e) => setIncluded(l, e.target.checked)} 
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
                          onBlur={(e) => { const n = Number(e.target.value); if (n !== l.suggestedQty) setQty(l, n); }}
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
                      <td className={`${TD_TEXT} ${WRAP} text-ink-secondary`}>
                        {l.suggestedVendorName ?? <span className="text-warning">No vendor</span>}
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
          )}

          {lines.length > 0 && withVendor === 0 && (
            <p className="mt-4 text-sm text-ink-muted">
              Set a preferred vendor for these ingredients before generating orders.
            </p>
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
 * <code>neededBy</code> is still in the payload and still on every purchase order.
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
