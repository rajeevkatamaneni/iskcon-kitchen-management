"use client";

import { useCallback, useState } from "react";
import { useRouter } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Form } from "@/components/ds/Form";
import { countedBox } from "@/components/ds/formMessages";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { HintedField } from "@/components/ds/InfoHint";
import { api, toApiError, type ApiError } from "@/lib/api";
import { FOOD_UNITS, money, stepForUnit, todayIso, unitLabel } from "@/lib/format";
import { normalizeIndianMobile } from "@/lib/phone";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * Record a donation — nine fields before a single line of goods is added, so a screen of its own.
 *
 * <p>Cash and goods are two gifts, not one row: the money goes to the bank and the goods to the
 * shelf. The server refuses the pair outright, so each side closes the other here rather than
 * letting somebody fill in a form that cannot be submitted.
 */

const FORM = "record-donation";
const FIELD = "min-h-touch rounded-control border border-hairline px-3";


interface IngredientLine {
  ingredientId: string;
  quantity: string;
  unit: string;
  expiryDate: string;
}
interface EquipmentLine {
  name: string;
  notes: string;
}

export default function NewDonationPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <NewDonationView />
    </RequireRole>
  );
}

function NewDonationView() {
  const { appUser, getToken } = useAuth();
  const router = useRouter();
  const isAdmin = appUser?.role === "TEMPLE_ADMIN";
  const { data: ingredientsData } = useAuthedQuery(api.listIngredients);
  const ingredients = ingredientsData ?? [];

  // The wish list is behind MANAGE_WISHLIST, which kitchen staff do not hold. They can still record
  // the gift; they just cannot say which item it was towards, the same way they cannot see the ledger.
  const { data: wishlistData } = useAuthedQuery(
    useCallback((token?: string) => (isAdmin ? api.listWishlist(false, token) : Promise.resolve([])), [isAdmin])
  );
  const wishlistItems = (wishlistData ?? []).filter((i) => i.status === "ACTIVE");

  const [anonymous, setAnonymous] = useState(false);
  const [cashAmount, setCashAmount] = useState("");
  const [wishlistItemId, setWishlistItemId] = useState("");
  const [ingredientLines, setIngredientLines] = useState<IngredientLine[]>([]);
  const [equipmentLines, setEquipmentLines] = useState<EquipmentLine[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const hasCash = Number(cashAmount) > 0;
  const hasItems =
    ingredientLines.some((l) => l.ingredientId && Number(l.quantity) > 0) ||
    equipmentLines.some((l) => l.name.trim());
  const goodsStarted = hasItems || ingredientLines.length > 0 || equipmentLines.length > 0;

  function addIngredientLine() {
    setIngredientLines((ls) => [...ls, { ingredientId: "", quantity: "", unit: "KG", expiryDate: "" }]);
  }
  function addEquipmentLine() {
    setEquipmentLines((ls) => [...ls, { name: "", notes: "" }]);
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // Cash or goods is not a rule any one box can carry, so `Form` cannot refuse it. The page's own
    // sentence under the form ("Enter a cash amount, or add food or equipment.") is on screen for
    // exactly as long as this is true, and this stops the send. The button used to be disabled
    // instead (T-172 enabled it), which hid `Form`'s sentences for a blank donor name or date too.
    if (!hasCash && !hasItems) return;
    const f = new FormData(event.currentTarget);
    // The phone as it will be saved (T-186): "+91" and ten digits when what was typed can only be an
    // Indian mobile, and exactly what was typed otherwise. The server applies the same rule and is the
    // authority; it is worked out here too so the confirmation can say what was saved, and the office
    // can catch a wrong number before the donor has walked away.
    const donorPhone = anonymous ? "" : normalizeIndianMobile(String(f.get("donorPhone") ?? ""));
    setBusy(true);
    setError(null);
    try {
      await api.recordDonation(
        {
          anonymous,
          donorName: anonymous ? null : String(f.get("donorName") ?? "").trim() || null,
          donorPhone: donorPhone || null,
          donorEmail: anonymous ? null : String(f.get("donorEmail") ?? "").trim() || null,
          cashAmountInr: hasCash ? Number(cashAmount) : null,
          estimatedValueInr: hasCash ? null : numOrNull(String(f.get("estimatedValueInr") ?? "")),
          donatedOn: String(f.get("donatedOn") ?? todayIso()),
          notes: String(f.get("notes") ?? "").trim() || null,
          wishlistItemId: hasCash && wishlistItemId ? wishlistItemId : null,
          ingredients: ingredientLines
            .filter((l) => l.ingredientId && Number(l.quantity) > 0)
            .map((l) => ({
              ingredientId: l.ingredientId,
              quantity: Number(l.quantity),
              unit: l.unit,
              expiryDate: l.expiryDate || null,
            })),
          equipment: equipmentLines
            .filter((l) => l.name.trim())
            .map((l) => ({ name: l.name.trim(), notes: l.notes.trim() || null })),
        },
        await getToken()
      );
      // The donor's name goes in the confirmation, because a gift recorded against the wrong person
      // is the mistake this screen can actually make, and the ledger it lands in is where it is put
      // right. An anonymous gift has no name to check, so it says so.
      const who = anonymous ? "" : String(f.get("donorName") ?? "").trim();
      // And whether anybody was thanked, because the confirmation on the ledger claimed one was on
      // its way whatever had been typed. A cash gift with a name and no contact details showed
      // "a thank-you is on its way to the donor" and nothing was sent, because nothing could be:
      // `DonationIntakeService.sendThankYou` returns without sending when the gift is anonymous or
      // carries neither a phone number nor an email address, and that is the condition repeated
      // here. Two copies of one rule is worth a note — if the server's ever changes, this must
      // change with it, or the banner starts lying in the other direction.
      const reachable =
        !anonymous && (donorPhone !== "" || String(f.get("donorEmail") ?? "").trim() !== "");
      // The saved phone travels with the name, in its stored form, for the confirmation to read back.
      router.push(
        `/donations?recorded=${encodeURIComponent(who)}${reachable ? "" : "&thanked=no"}` +
          (donorPhone ? `&savedPhone=${encodeURIComponent(donorPhone)}` : "")
      );
    } catch (e) {
      setError(toApiError(e, "We couldn’t record that donation."));
      setBusy(false);
    }
  }

  return (
    <FocusScreen
      task="Record a donation"
      who="Cash to the ledger, food to the shelf, equipment to the register"
      activeHref="/donations"
      actions={
        <>
          <ButtonLink href="/donations" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={FORM} disabled={busy}>
            Record donation
          </Button>
        </>
      }
    >
      {error && (
        <div className="grid gap-3">
          <ErrorNotice error={error} />
          {/* The lines a refusal names, under the box that says what happened. A unit from the wrong
            family (KMS-400013) is answered with the ingredient's own sentence — "Ghee is measured
            in L, and there is no way to turn Kg into L." — and ErrorNotice prints only the message,
            next step and code, so without this the one line that says *which* ingredient was
            never on screen (UAT-081 step 17). Only the message is shown, never the key: for this
            refusal the key is the ingredient's name, which the sentence already opens with, and for
            a field that failed its checks it is a path like `ingredients[0].quantity`, whose
            message is written to stand alone. The order screen prints the key because its keys
            are the lines of an order; these are not. Keyed by position, since two lines can name
            the same ingredient. */}
          {error.fieldErrors.length > 0 && (
            <ul className="grid gap-1 rounded border border-hairline bg-raised px-5 py-4 text-sm">
              {error.fieldErrors.map((f, i) => (
                <li key={i}>{f.message}</li>
              ))}
            </ul>
          )}
        </div>
      )}

      <Form id={FORM} className="grid gap-6" aria-label="Record a donation" onSubmit={submit}>
        <label className="flex min-h-touch items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={anonymous}
            onChange={(e) => setAnonymous(e.target.checked)}
            className="h-5 w-5 rounded-sm border-hairline-strong accent-accent"
          />
          Anonymous donor
        </label>

        {!anonymous && (
          <div className="grid gap-4 sm:grid-cols-3">
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Donor name</span>
              <input name="donorName" required={!anonymous} className={FIELD} />
            </label>
            {/* What the number is for is guidance, so it goes behind the "i" beside the label. */}
            <HintedField label="Phone" hint="Where the thank-you goes">
              {(id) => <input id={id} name="donorPhone" placeholder="+91…" className={FIELD} />}
            </HintedField>
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Email</span>
              <input name="donorEmail" type="email" className={FIELD} />
            </label>
          </div>
        )}

        <div className="grid gap-4 sm:grid-cols-3">
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Date</span>
            <input name="donatedOn" type="date" defaultValue={todayIso()} required className={FIELD} />
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Estimated value of goods (₹)</span>
            <input
              name="estimatedValueInr"
              type="number"
              min="0"
              step="any"
              disabled={hasCash}
              className={`${FIELD} disabled:opacity-60`}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Notes</span>
            <input name="notes" className={FIELD} />
          </label>
        </div>

        {/* Cash */}
        <div>
          <h2 className="mb-2 text-sm font-medium">Cash</h2>
          <div className="grid grid-cols-12 gap-2">
            <label className="col-span-5 flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Amount (₹)</span>
              <input
                aria-label="Cash amount"
                type="number"
                min="0"
                step="any"
                value={cashAmount}
                disabled={goodsStarted}
                onChange={(e) => setCashAmount(e.target.value)}
                className={`${FIELD} disabled:opacity-60`}
              />
            </label>
            {isAdmin && wishlistItems.length > 0 && (
              <label className="col-span-7 flex flex-col gap-1 text-sm text-ink-secondary">
                <span className="pl-field-inset font-medium text-ink">Towards</span>
                <select
                  aria-label="Towards"
                  value={wishlistItemId}
                  disabled={goodsStarted}
                  onChange={(e) => setWishlistItemId(e.target.value)}
                  className={`${FIELD} text-sm disabled:opacity-60`}
                >
                  <option value="">The kitchen generally</option>
                  {wishlistItems.map((i) => {
                    const stillNeeded = Math.max(0, i.priceInr * i.quantityWanted - i.paidInr);
                    return (
                      <option key={i.id} value={i.id}>
                        {i.title}
                        {stillNeeded > 0 ? ` — ${money(stillNeeded, "INR")} still needed` : ""}
                      </option>
                    );
                  })}
                </select>
              </label>
            )}
          </div>
          {goodsStarted && (
            <p className="mt-2 text-sm text-ink-muted">
              Cash is recorded as its own donation, so remove the goods lines.
            </p>
          )}
        </div>

        {/* Ingredient lines */}
        <div>
          <div className="mb-2 flex items-center justify-between">
            <h2 className="text-sm font-medium">Food</h2>
            <button
              type="button"
              onClick={addIngredientLine}
              disabled={hasCash}
              className="min-h-touch text-sm link disabled:opacity-60 disabled:no-underline"
            >
              Add a food item
            </button>
          </div>
          {ingredientLines.length === 0 && <p className="text-sm text-ink-muted">No food items added.</p>}
          <div className="space-y-2">
            {ingredientLines.map((line, idx) => (
              <div key={idx} className="grid grid-cols-12 gap-2">
                <select
                  aria-label={`Food ingredient ${idx + 1}`}
                  value={line.ingredientId}
                  onChange={(e) => {
                    const ingredientId = e.target.value;
                    const chosen = ingredients.find((i) => i.id === ingredientId);
                    setIngredientLines((ls) =>
                      ls.map((l, i) => (i === idx ? { ...l, ingredientId, unit: chosen?.unit ?? l.unit } : l))
                    );
                  }}
                  className={`col-span-12 sm:col-span-5 ${FIELD} text-sm`}
                >
                  <option value="">Choose…</option>
                  {ingredients.map((i) => (
                    <option key={i.id} value={i.id}>
                      {i.name}
                    </option>
                  ))}
                </select>
                <input
                  aria-label={`Quantity ${idx + 1}`}
                  type="number"
                  min="0"
                  // Nobody donates 2.4 blankets (T-424). The two money boxes above are untouched.
                  step={stepForUnit(line.unit)}
                  // "Quantity 2 must be a whole number" named the row and nothing else. Named from
                  // the ingredient picked in the row, so the refusal says which thing and why
                  // (T-431); before one is picked the line carries no unit to refuse by either.
                  {...countedBox(ingredients.find((i) => i.id === line.ingredientId)?.name, line.unit)}
                  placeholder="Qty"
                  value={line.quantity}
                  onChange={(e) =>
                    setIngredientLines((ls) => ls.map((l, i) => (i === idx ? { ...l, quantity: e.target.value } : l)))
                  }
                  className={`col-span-3 sm:col-span-2 ${FIELD} text-sm`}
                />
                <select
                  aria-label={`Unit ${idx + 1}`}
                  value={line.unit}
                  onChange={(e) =>
                    setIngredientLines((ls) => ls.map((l, i) => (i === idx ? { ...l, unit: e.target.value } : l)))
                  }
                  className={`col-span-3 sm:col-span-2 ${FIELD} text-sm`}
                >
                  {FOOD_UNITS.map((u) => (
                    <option key={u} value={u}>
                      {unitLabel(u)}
                    </option>
                  ))}
                </select>
                <input
                  aria-label={`Expiry ${idx + 1}`}
                  type="date"
                  value={line.expiryDate}
                  onChange={(e) =>
                    setIngredientLines((ls) => ls.map((l, i) => (i === idx ? { ...l, expiryDate: e.target.value } : l)))
                  }
                  className={`col-span-5 sm:col-span-2 ${FIELD} text-sm`}
                />
                {/* Plain, not red: removing an unsaved line is trivial and undone by adding it again.
                    Red is for something serious (Rajeev, 2026-09-18, T-227). */}
                <button
                  type="button"
                  aria-label={`Remove food ${idx + 1}`}
                  onClick={() => setIngredientLines((ls) => ls.filter((_, i) => i !== idx))}
                  className="col-span-1 text-ink-secondary hover:text-ink hover:underline"
                >
                  ✕
                </button>
              </div>
            ))}
          </div>
        </div>

        {/* Equipment lines */}
        <div>
          <div className="mb-2 flex items-center justify-between">
            <h2 className="text-sm font-medium">Equipment</h2>
            <button
              type="button"
              onClick={addEquipmentLine}
              disabled={hasCash}
              className="min-h-touch text-sm link disabled:opacity-60 disabled:no-underline"
            >
              Add a piece of equipment
            </button>
          </div>
          {equipmentLines.length === 0 && <p className="text-sm text-ink-muted">No equipment added.</p>}
          <div className="space-y-2">
            {equipmentLines.map((line, idx) => (
              <div key={idx} className="grid grid-cols-12 gap-2">
                <input
                  aria-label={`Equipment name ${idx + 1}`}
                  placeholder="Name"
                  value={line.name}
                  onChange={(e) =>
                    setEquipmentLines((ls) => ls.map((l, i) => (i === idx ? { ...l, name: e.target.value } : l)))
                  }
                  className={`col-span-6 ${FIELD} text-sm`}
                />
                {/* No kind picker. The register stopped having a category on 2026-09-04 — a closed
                    vocabulary of three the temple could not extend was worse than none — so the
                    name and a note are the whole of what an in-kind gift says. */}
                <input
                  aria-label={`Equipment notes ${idx + 1}`}
                  placeholder="Notes"
                  value={line.notes}
                  onChange={(e) =>
                    setEquipmentLines((ls) => ls.map((l, i) => (i === idx ? { ...l, notes: e.target.value } : l)))
                  }
                  className={`col-span-5 ${FIELD} text-sm`}
                />
                <button
                  type="button"
                  aria-label={`Remove equipment ${idx + 1}`}
                  onClick={() => setEquipmentLines((ls) => ls.filter((_, i) => i !== idx))}
                  className="col-span-1 text-ink-secondary hover:text-ink hover:underline"
                >
                  ✕
                </button>
              </div>
            ))}
          </div>
        </div>

        {!hasCash && !hasItems && (
          <p className="text-sm text-ink-muted">
            Enter a cash amount, or add food or equipment.
          </p>
        )}
      </Form>
    </FocusScreen>
  );
}

function numOrNull(s: string): number | null {
  const t = s.trim();
  return t === "" ? null : Number(t);
}
