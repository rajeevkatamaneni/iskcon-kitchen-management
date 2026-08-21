"use client";

import { useCallback, useState } from "react";
import { useRouter } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { api, toApiError, type ApiError } from "@/lib/api";
import { todayIso } from "@/lib/format";
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
const FIELD = "min-h-touch rounded border border-hairline bg-canvas px-3";

const UNITS = ["KG", "GM", "L", "ML", "PIECES"];
const UNIT_LABEL: Record<string, string> = { KG: "Kg", GM: "gm", L: "L", ML: "ml", PIECES: "pieces" };
const EQUIP_CATEGORIES = ["MACHINE", "TOOL", "FURNITURE"];
const EQUIP_LABEL: Record<string, string> = { MACHINE: "Machine", TOOL: "Tool", FURNITURE: "Furniture" };

interface IngredientLine {
  ingredientId: string;
  quantity: string;
  unit: string;
  expiryDate: string;
}
interface EquipmentLine {
  name: string;
  category: string;
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
    setEquipmentLines((ls) => [...ls, { name: "", category: "TOOL", notes: "" }]);
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    setBusy(true);
    setError(null);
    try {
      await api.recordDonation(
        {
          anonymous,
          donorName: anonymous ? null : String(f.get("donorName") ?? "").trim() || null,
          donorPhone: anonymous ? null : String(f.get("donorPhone") ?? "").trim() || null,
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
            .map((l) => ({ name: l.name.trim(), category: l.category, notes: l.notes.trim() || null })),
        },
        await getToken()
      );
      // The donor's name goes in the confirmation, because a gift recorded against the wrong person
      // is the mistake this screen can actually make, and the ledger it lands in is where it is put
      // right. An anonymous gift has no name to check, so it says so.
      const who = anonymous ? "" : String(f.get("donorName") ?? "").trim();
      router.push(`/donations?recorded=${encodeURIComponent(who)}`);
    } catch (e) {
      setError(toApiError(e, "We couldn't record that donation."));
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
          <Button type="submit" form={FORM} disabled={busy || (!hasCash && !hasItems)}>
            Record donation
          </Button>
        </>
      }
    >
      {error && <ErrorNotice error={error} />}

      <form id={FORM} className="grid gap-6" aria-label="Record a donation" onSubmit={submit}>
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={anonymous}
            onChange={(e) => setAnonymous(e.target.checked)}
            className="h-5 w-5 rounded-sm border-hairline-strong"
          />
          Anonymous donor
        </label>

        {!anonymous && (
          <div className="grid grid-cols-3 gap-4">
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Donor name</span>
              <input name="donorName" required={!anonymous} className={FIELD} />
            </label>
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Phone</span>
              <input name="donorPhone" placeholder="+91…" className={FIELD} />
              <span className="pl-field-inset text-sm text-ink-secondary">Where the thank-you goes</span>
            </label>
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Email</span>
              <input name="donorEmail" type="email" className={FIELD} />
            </label>
          </div>
        )}

        <div className="grid grid-cols-3 gap-4">
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
                        {stillNeeded > 0 ? ` — ₹${stillNeeded.toLocaleString("en-IN")} still needed` : ""}
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
              className="text-sm text-accent-text hover:underline disabled:opacity-60 disabled:no-underline"
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
                  className={`col-span-5 ${FIELD} text-sm`}
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
                  step="any"
                  placeholder="Qty"
                  value={line.quantity}
                  onChange={(e) =>
                    setIngredientLines((ls) => ls.map((l, i) => (i === idx ? { ...l, quantity: e.target.value } : l)))
                  }
                  className={`col-span-2 ${FIELD} text-sm`}
                />
                <select
                  aria-label={`Unit ${idx + 1}`}
                  value={line.unit}
                  onChange={(e) =>
                    setIngredientLines((ls) => ls.map((l, i) => (i === idx ? { ...l, unit: e.target.value } : l)))
                  }
                  className={`col-span-2 ${FIELD} text-sm`}
                >
                  {UNITS.map((u) => (
                    <option key={u} value={u}>
                      {UNIT_LABEL[u]}
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
                  className={`col-span-2 ${FIELD} text-sm`}
                />
                <button
                  type="button"
                  aria-label={`Remove food ${idx + 1}`}
                  onClick={() => setIngredientLines((ls) => ls.filter((_, i) => i !== idx))}
                  className="col-span-1 text-danger hover:underline"
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
              className="text-sm text-accent-text hover:underline disabled:opacity-60 disabled:no-underline"
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
                  className={`col-span-5 ${FIELD} text-sm`}
                />
                <select
                  aria-label={`Equipment category ${idx + 1}`}
                  value={line.category}
                  onChange={(e) =>
                    setEquipmentLines((ls) => ls.map((l, i) => (i === idx ? { ...l, category: e.target.value } : l)))
                  }
                  className={`col-span-3 ${FIELD} text-sm`}
                >
                  {EQUIP_CATEGORIES.map((c) => (
                    <option key={c} value={c}>
                      {EQUIP_LABEL[c]}
                    </option>
                  ))}
                </select>
                <input
                  aria-label={`Equipment notes ${idx + 1}`}
                  placeholder="Notes"
                  value={line.notes}
                  onChange={(e) =>
                    setEquipmentLines((ls) => ls.map((l, i) => (i === idx ? { ...l, notes: e.target.value } : l)))
                  }
                  className={`col-span-3 ${FIELD} text-sm`}
                />
                <button
                  type="button"
                  aria-label={`Remove equipment ${idx + 1}`}
                  onClick={() => setEquipmentLines((ls) => ls.filter((_, i) => i !== idx))}
                  className="col-span-1 text-danger hover:underline"
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
      </form>
    </FocusScreen>
  );
}

function numOrNull(s: string): number | null {
  const t = s.trim();
  return t === "" ? null : Number(t);
}
