"use client";

import { Suspense, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Loading } from "@/components/Loading";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { HintedField } from "@/components/ds/InfoHint";
import { TABLE, THEAD, TR, TH_TEXT, TH_NUM, TH_ACTIONS, TD_TEXT, TD_NUM, TD_ACTIONS, WRAP } from "@/components/ds/table";
import { api, toApiError, type ApiError, type IngredientView, type VendorView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { FOOD_UNITS, todayIso, unitLabel } from "@/lib/format";

/**
 * Raising a purchase order by hand — step two, the lines (T-026, D-7).
 *
 * <p>Step one asked which vendor and sent it here in the address, so this screen is linkable and
 * survives a reload, and a person who came the wrong way is told so rather than shown a form that
 * cannot be submitted.
 *
 * <p><b>What a line may be.</b> Either an ingredient out of the catalogue, or a description of
 * something the catalogue has never heard of — four plastic stools from a furniture shop (T-024).
 * Never both and never neither: the server refuses that with KMS-400128 and the database with a
 * CHECK, and the two adders below are separate controls with separate buttons so the combination
 * cannot be built here by accident.
 *
 * <p>Both adders are the ones `/orders/[id]` already offers on a draft, written out again rather
 * than shared, because a component shared between the two would be a new file and this task owns
 * neither that file nor the one it would be lifted out of. It is a candidate for extraction the
 * next time somebody has both screens in their contract.
 */

/** Named so the header's primary button can submit the form in the body. */
const FORM = "raise-purchase-order";
const FIELD = "min-h-touch rounded-control border border-hairline px-3";

/** See `/orders/new`: `true` is active-only, and the flag is inverted. Stable, so it fetches once. */
const activeVendors = (token: string | undefined) => api.listVendors(true, token);
const allIngredients = (token: string | undefined) => api.listIngredients(token);

/**
 * A line as it is being built.
 *
 * <p>The quantity is the text in the box rather than a number, so somebody can clear the field and
 * retype it. It becomes a number once, on submit.
 *
 * <p>`key` is a React key and nothing else — the order does not exist yet, so a line has no id to
 * be known by. It is generated rather than taken from `ingredientId`, which is null on every
 * described line: two of those keyed alike is undefined reconciliation on a form somebody is typing
 * into, and it is the defect `/orders/[id]` was fixed for in T-024.
 */
interface DraftLine {
  key: string;
  ingredientId: string | null;
  ingredientName: string | null;
  description: string | null;
  quantity: string;
  unit: string;
}

/** What a line is for, in words. Never `ingredientName` alone — a described line has none. */
function subjectOf(line: DraftLine): string {
  return line.ingredientName ?? line.description ?? "";
}

export default function NewPurchaseOrderLinesPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      {/* useSearchParams — the vendor chosen on the screen before — needs a boundary. */}
      <Suspense>
        <NewPurchaseOrderLinesView />
      </Suspense>
    </RequireRole>
  );
}

function NewPurchaseOrderLinesView() {
  const { getToken } = useAuth();
  const router = useRouter();
  const vendorId = useSearchParams().get("vendor");

  const { data: vendorData, error: vendorError, loading: loadingVendors } = useAuthedQuery(activeVendors);
  const { data: ingredientData } = useAuthedQuery(allIngredients);
  const ingredients = ingredientData ?? [];

  // Resolved against the active list rather than fetched by id, and that is the guard as well as
  // the lookup: the server accepts an order against any vendor row that exists, including one the
  // temple deliberately dropped, so an id typed into the address bar has to be checked against the
  // same set the picker offered.
  const vendor: VendorView | undefined = (vendorData ?? []).find((v) => v.id === vendorId);

  const [lines, setLines] = useState<DraftLine[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  function addLine(line: DraftLine) {
    setLines((current) => [...current, line]);
  }

  function removeLine(key: string) {
    setLines((current) => current.filter((l) => l.key !== key));
  }

  function setQuantity(key: string, value: string) {
    setLines((current) => current.map((l) => (l.key === key ? { ...l, quantity: value } : l)));
  }

  async function raise(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!vendor) return;
    const f = new FormData(event.currentTarget);

    if (lines.length === 0) {
      setError(toApiError(null, "An order needs at least one line. Add what is being bought."));
      return;
    }
    const quantities = lines.map((l) => Number(l.quantity));
    if (quantities.some((q) => !Number.isFinite(q) || q <= 0)) {
      setError(toApiError(null, "Every line needs a quantity above zero. Remove a line you no longer want."));
      return;
    }

    // The server refuses a needed-by date earlier than the day the order is raised, with
    // KMS-400014. The date box carries a `min` so the browser refuses it first, and this is the
    // same refusal in words for anything the box lets through. The server is still the guard — this
    // only saves a round trip and says the reason where the person is looking.
    const neededBy = String(f.get("neededBy") ?? "");
    if (neededBy !== "" && neededBy < todayIso()) {
      setError(toApiError(null, "That date has already passed. Choose today or a day after it."));
      return;
    }

    setBusy(true);
    setError(null);
    try {
      const { id } = await api.createPurchaseOrder(
        {
          vendorId: vendor.id,
          neededBy: neededBy === "" ? null : neededBy,
          deliveryLocation: emptyToNull(String(f.get("deliveryLocation") ?? "")),
          notes: emptyToNull(String(f.get("notes") ?? "")),
          // Both halves of a line's subject travel on every line, always. `description` is
          // required-and-nullable on `PoLineInput` precisely so this literal cannot quietly omit
          // it: an omitted optional field arrives as undefined and the server then refuses the
          // whole order with KMS-400128, for a line that looked complete on screen.
          //
          // No expected price. That figure is a snapshot of the vendor's last known price, taken
          // when the shopping list generated an order, and there is nothing honest to put here for
          // an order somebody is raising by hand. The vendor sheet prints a dash, which is true.
          lines: lines.map((l, i) => ({
            ingredientId: l.ingredientId,
            description: l.description,
            quantity: quantities[i],
            unit: l.unit,
            expectedPrice: null,
          })),
        },
        await getToken()
      );
      // Rule 8: back to the list, with the confirmation waiting there rather than here. The order's
      // id rides along so the confirmation can offer it — a draft that has just been raised is
      // usually about to be read and sent.
      router.push(`/orders?added=${encodeURIComponent(vendor.name)}&po=${encodeURIComponent(id)}`);
    } catch (e) {
      setError(toApiError(e, "We couldn’t raise that order."));
      setBusy(false);
    }
  }

  // Came here without a vendor, or with one that is no longer active. Not an error to quote a code
  // for — nothing failed — but it is a dead end, so it says so and offers the way out.
  if (!loadingVendors && !vendor) {
    return (
      <FocusScreen
        task="Raise a purchase order"
        activeHref="/orders"
        actions={
          <ButtonLink href="/orders" variant="secondary">
            Cancel
          </ButtonLink>
        }
      >
        {vendorError && <ErrorNotice error={vendorError} />}
        <div className="card px-6 py-14 text-center">
          <p className="text-lg">No vendor chosen</p>
          <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
            An order is raised against one vendor, and that vendor has to be an active one. Choose
            it, then add the lines.
          </p>
          <div className="mt-6 flex justify-center">
            <ButtonLink href="/orders/new">Choose a vendor</ButtonLink>
          </div>
        </div>
      </FocusScreen>
    );
  }

  return (
    <FocusScreen
      task="Raise a purchase order"
      who={vendor?.name}
      activeHref="/orders"
      actions={
        <>
          <ButtonLink href="/orders" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={FORM} disabled={busy || loadingVendors}>
            Raise order
          </Button>
        </>
      }
    >
      {error && <ErrorNotice error={error} />}

      {loadingVendors ? (
        <Loading label="Loading the vendor…" />
      ) : (
        <form id={FORM} aria-label="Raise a purchase order" onSubmit={raise} className="grid gap-6">
          <div className="grid grid-cols-2 gap-4">
            {/* `min` is what refuses yesterday in the browser, in every language and on a phone.
                The temple's today, not the device's: a date is the kitchen's operational day, and
                the server measures this one against the same clock. */}
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Needed by</span>
              <input name="neededBy" type="date" min={todayIso()} className={FIELD} />
            </label>
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Deliver to</span>
              <input name="deliveryLocation" maxLength={300} placeholder="Main store" className={FIELD} />
            </label>
            <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Notes for the vendor</span>
              <input name="notes" maxLength={1000} className={FIELD} />
            </label>
          </div>

          <div>
            <h2 className="text-base font-semibold text-ink">Lines</h2>
            {lines.length === 0 ? (
              <p className="mt-2 max-w-prose text-ink-secondary">
                Nothing on this order yet. Add an ingredient from the catalogue, or describe
                something that is not in it.
              </p>
            ) : (
              <div className="table-wrap mt-2 overflow-x-auto">
                <table className={TABLE}>
                  <thead className={THEAD}>
                    <tr>
                      <th className={`${TH_TEXT} ${WRAP}`}>Item</th>
                      <th className={TH_NUM}>Quantity</th>
                      <th className={TH_TEXT}>Counted in</th>
                      <th className={TH_ACTIONS}>Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {lines.map((l) => (
                      <tr key={l.key} className={TR}>
                        <td className={`${TD_TEXT} ${WRAP}`}>{subjectOf(l)}</td>
                        <td className={TD_NUM}>
                          <input
                            type="number"
                            min="0"
                            step="any"
                            value={l.quantity}
                            aria-label={`Quantity of ${subjectOf(l)}`}
                            onChange={(e) => setQuantity(l.key, e.target.value)}
                            className="min-h-touch w-28 rounded-control border border-hairline px-3 text-right tabular-nums"
                          />
                        </td>
                        <td className={`${TD_TEXT} text-ink-secondary`}>{unitLabel(l.unit)}</td>
                        <td className={TD_ACTIONS}>
                          <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            onClick={() => removeLine(l.key)}
                            aria-label={`Remove ${subjectOf(l)}`}
                          >
                            Remove
                          </Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            <AddLine
              busy={busy}
              ingredients={ingredients}
              alreadyOnOrder={lines.map((l) => l.ingredientId).filter((id): id is string => id !== null)}
              onAdd={addLine}
            />
          </div>
        </form>
      )}
    </FocusScreen>
  );
}

/**
 * Adding a line — an ingredient from the catalogue, or something that is not in it at all.
 *
 * <p>A picker rather than a box to paste an identifier into: nobody knows an ingredient by its id,
 * and every other screen that names one chooses it this way.
 *
 * <p>Two controls rather than one, because the two are genuinely different acts and the server
 * treats them as exclusive. Neither offers the other's field, so a line naming both — refused with
 * KMS-400128 — cannot be built here.
 */
function AddLine({
  busy, ingredients, alreadyOnOrder, onAdd,
}: {
  busy: boolean;
  ingredients: IngredientView[];
  alreadyOnOrder: string[];
  onAdd: (line: DraftLine) => void;
}) {
  const [chosen, setChosen] = useState("");
  const [described, setDescribed] = useState("");
  const [describedUnit, setDescribedUnit] = useState("PIECES");

  // An ingredient already on the order is edited on its own row. Offering it twice would put two
  // lines for one thing on the sheet and leave the vendor to work out which is meant. Described
  // lines are deliberately not de-duplicated this way: "Extension cord" twice may well be two
  // different things, and there is no id to say otherwise.
  const available = ingredients.filter((i) => !alreadyOnOrder.includes(i.id));

  function add() {
    const ingredient = available.find((i) => i.id === chosen);
    if (!ingredient) return;
    onAdd({
      key: crypto.randomUUID(),
      ingredientId: ingredient.id,
      ingredientName: ingredient.name,
      description: null,
      quantity: "",
      // The catalogue's own unit, so the line cannot be measured in something the ingredient is not
      // measured in. The server refuses a mismatched family (BL-9) and there is no reason to offer
      // the mistake.
      unit: ingredient.unit,
    });
    setChosen("");
  }

  function addDescribed() {
    const text = described.trim();
    if (text === "") return;
    onAdd({
      key: crypto.randomUUID(),
      ingredientId: null,
      ingredientName: null,
      description: text,
      quantity: "",
      unit: describedUnit,
    });
    setDescribed("");
  }

  return (
    <div className="mt-4 grid gap-4 border-t border-hairline pt-4">
      <div className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Add an ingredient</span>
          <select
            value={chosen}
            onChange={(e) => setChosen(e.target.value)}
            className={FIELD}
          >
            <option value="">Choose…</option>
            {available.map((i) => (
              <option key={i.id} value={i.id}>
                {i.name}
              </option>
            ))}
          </select>
        </label>
        <Button type="button" variant="ghost" disabled={busy || chosen === ""} onClick={add}>
          Add line
        </Button>
      </div>

      <div className="flex flex-wrap items-end gap-3">
        {/* The hint says what this is for and, more usefully, what it costs: a described line never
            reaches stock, which is the whole reason it needs no catalogue entry. Saying so here is
            cheaper than saying it at the receiving table, where somebody has already gone looking
            for a box to type into. */}
        <HintedField
          label="Or describe something not in the catalogue"
          hint="For things the store room doesn’t track — a plastic stool, an extension cord. It goes on the order and the bill, but never into stock."
        >
          {(fieldId) => (
            <input
              id={fieldId}
              value={described}
              maxLength={200}
              placeholder="Plastic stool"
              onChange={(e) => setDescribed(e.target.value)}
              className="min-h-touch w-64 rounded-control border border-hairline px-3"
            />
          )}
        </HintedField>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Counted in</span>
          {/* The one vocabulary (E11-S2), never a list typed out here. Pieces first is expressed as
              the initial value rather than by reordering the list, so the words read the same on
              this screen as on every other. */}
          <select
            value={describedUnit}
            onChange={(e) => setDescribedUnit(e.target.value)}
            className={FIELD}
          >
            {FOOD_UNITS.map((u) => (
              <option key={u} value={u}>
                {unitLabel(u)}
              </option>
            ))}
          </select>
        </label>
        <Button
          type="button"
          variant="ghost"
          disabled={busy || described.trim() === ""}
          onClick={addDescribed}
        >
          Add described line
        </Button>
      </div>
    </div>
  );
}

function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
}
