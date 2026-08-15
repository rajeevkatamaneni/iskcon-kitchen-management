"use client";

import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError } from "@/lib/api";
import { todayIso } from "@/lib/format";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";

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

export default function DonationsPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_STAFF"]}>
      <DonationsView />
    </RequireRole>
  );
}



function DonationsView() {
  const { appUser, getToken } = useAuth();
  const isAdmin = appUser?.role === "TEMPLE_ADMIN";
  const { data: ingredientsData } = useAuthedQuery(api.listIngredients);
  const ingredients = ingredientsData ?? [];

  const [anonymous, setAnonymous] = useState(false);
  const [ingredientLines, setIngredientLines] = useState<IngredientLine[]>([]);
  const [equipmentLines, setEquipmentLines] = useState<EquipmentLine[]>([]);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [done, setDone] = useState(false);
  const [listNonce, setListNonce] = useState(0);

  const hasItems =
    ingredientLines.some((l) => l.ingredientId && Number(l.quantity) > 0) ||
    equipmentLines.some((l) => l.name.trim());

  function addIngredientLine() {
    setIngredientLines((ls) => [...ls, { ingredientId: "", quantity: "", unit: "KG", expiryDate: "" }]);
  }
  function addEquipmentLine() {
    setEquipmentLines((ls) => [...ls, { name: "", category: "TOOL", notes: "" }]);
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const f = new FormData(form);
    setBusy(true);
    setActionError(null);
    setDone(false);
    try {
      await api.recordInKindDonation(
        {
          anonymous,
          donorName: anonymous ? null : String(f.get("donorName") ?? "").trim() || null,
          donorPhone: anonymous ? null : String(f.get("donorPhone") ?? "").trim() || null,
          donorEmail: anonymous ? null : String(f.get("donorEmail") ?? "").trim() || null,
          estimatedValueInr: numOrNull(String(f.get("estimatedValueInr") ?? "")),
          donatedOn: String(f.get("donatedOn") ?? todayIso()),
          notes: String(f.get("notes") ?? "").trim() || null,
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
      form.reset();
      setIngredientLines([]);
      setEquipmentLines([]);
      setAnonymous(false);
      setDone(true);
      setListNonce((n) => n + 1);
    } catch (e) {
      setActionError(toApiError(e, "We couldn't record that donation."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/donations" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6">
            <h1>Donations</h1>
            <p className="mt-1 text-ink-secondary">
              Record a gift of food or equipment. Food goes into stock, equipment into the register,
              and a thank-you goes to the donor.
            </p>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}
          {done && (
            <div className="mb-6 rounded border border-success/20 bg-success-bg p-4 text-success" role="status">
              Donation recorded. Thank you for logging it.
            </div>
          )}

          <section className="mb-10 rounded-lg bg-raised px-6 py-5" aria-labelledby="intake-heading">
            <h2 id="intake-heading" className="text-lg">Record an in-kind donation</h2>
            <form className="mt-4 space-y-6" aria-label="Record an in-kind donation" onSubmit={submit}>
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={anonymous} onChange={(e) => setAnonymous(e.target.checked)} className="h-5 w-5 rounded-sm border-hairline-strong" />
                Anonymous donor
              </label>

              {!anonymous && (
                <div className="grid grid-cols-3 gap-4">
                  <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                    Donor name
                    <input name="donorName" required={!anonymous} className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                  </label>
                  <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                    Phone (for thank-you)
                    <input name="donorPhone" placeholder="+91…" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                  </label>
                  <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                    Email
                    <input name="donorEmail" type="email" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                  </label>
                </div>
              )}

              <div className="grid grid-cols-3 gap-4">
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Date
                  <input name="donatedOn" type="date" defaultValue={todayIso()} required className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Estimated value (₹)
                  <input name="estimatedValueInr" type="number" min="0" step="any" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Notes
                  <input name="notes" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
              </div>

              {/* Ingredient lines */}
              <div>
                <div className="mb-2 flex items-center justify-between">
                  <h3 className="text-sm font-medium">Food</h3>
                  <button type="button" onClick={addIngredientLine} className="text-sm text-accent-text hover:underline">+ Add food item</button>
                </div>
                {ingredientLines.length === 0 && <p className="text-sm text-ink-muted">No food items added.</p>}
                <div className="space-y-2">
                  {ingredientLines.map((line, idx) => (
                    <div key={idx} className="grid grid-cols-12 items-end gap-2">
                      <select
                        aria-label={`Food ingredient ${idx + 1}`}
                        value={line.ingredientId}
                        onChange={(e) => {
                          const ingredientId = e.target.value;
                          const chosen = ingredients.find((i) => i.id === ingredientId);
                          setIngredientLines((ls) => ls.map((l, i) => i === idx ? { ...l, ingredientId, unit: chosen?.unit ?? l.unit } : l));
                        }}
                        className="col-span-5 min-h-touch rounded border border-hairline bg-canvas px-3 text-sm"
                      >
                        <option value="">Choose…</option>
                        {ingredients.map((i) => <option key={i.id} value={i.id}>{i.name}</option>)}
                      </select>
                      <input
                        aria-label={`Quantity ${idx + 1}`}
                        type="number" min="0" step="any" placeholder="Qty"
                        value={line.quantity}
                        onChange={(e) => setIngredientLines((ls) => ls.map((l, i) => i === idx ? { ...l, quantity: e.target.value } : l))}
                        className="col-span-2 min-h-touch rounded border border-hairline bg-canvas px-3 text-sm"
                      />
                      <select
                        aria-label={`Unit ${idx + 1}`}
                        value={line.unit}
                        onChange={(e) => setIngredientLines((ls) => ls.map((l, i) => i === idx ? { ...l, unit: e.target.value } : l))}
                        className="col-span-2 min-h-touch rounded border border-hairline bg-canvas px-2 text-sm"
                      >
                        {UNITS.map((u) => <option key={u} value={u}>{UNIT_LABEL[u]}</option>)}
                      </select>
                      <input
                        aria-label={`Expiry ${idx + 1}`}
                        type="date"
                        value={line.expiryDate}
                        onChange={(e) => setIngredientLines((ls) => ls.map((l, i) => i === idx ? { ...l, expiryDate: e.target.value } : l))}
                        className="col-span-2 min-h-touch rounded border border-hairline bg-canvas px-2 text-sm"
                      />
                      <button type="button" aria-label={`Remove food ${idx + 1}`} onClick={() => setIngredientLines((ls) => ls.filter((_, i) => i !== idx))} className="col-span-1 text-danger hover:underline">✕</button>
                    </div>
                  ))}
                </div>
              </div>

              {/* Equipment lines */}
              <div>
                <div className="mb-2 flex items-center justify-between">
                  <h3 className="text-sm font-medium">Equipment</h3>
                  <button type="button" onClick={addEquipmentLine} className="text-sm text-accent-text hover:underline">+ Add equipment</button>
                </div>
                {equipmentLines.length === 0 && <p className="text-sm text-ink-muted">No equipment added.</p>}
                <div className="space-y-2">
                  {equipmentLines.map((line, idx) => (
                    <div key={idx} className="grid grid-cols-12 items-end gap-2">
                      <input
                        aria-label={`Equipment name ${idx + 1}`}
                        placeholder="Name"
                        value={line.name}
                        onChange={(e) => setEquipmentLines((ls) => ls.map((l, i) => i === idx ? { ...l, name: e.target.value } : l))}
                        className="col-span-5 min-h-touch rounded border border-hairline bg-canvas px-3 text-sm"
                      />
                      <select
                        aria-label={`Equipment category ${idx + 1}`}
                        value={line.category}
                        onChange={(e) => setEquipmentLines((ls) => ls.map((l, i) => i === idx ? { ...l, category: e.target.value } : l))}
                        className="col-span-3 min-h-touch rounded border border-hairline bg-canvas px-2 text-sm"
                      >
                        {EQUIP_CATEGORIES.map((c) => <option key={c} value={c}>{EQUIP_LABEL[c]}</option>)}
                      </select>
                      <input
                        aria-label={`Equipment notes ${idx + 1}`}
                        placeholder="Notes"
                        value={line.notes}
                        onChange={(e) => setEquipmentLines((ls) => ls.map((l, i) => i === idx ? { ...l, notes: e.target.value } : l))}
                        className="col-span-3 min-h-touch rounded border border-hairline bg-canvas px-3 text-sm"
                      />
                      <button type="button" aria-label={`Remove equipment ${idx + 1}`} onClick={() => setEquipmentLines((ls) => ls.filter((_, i) => i !== idx))} className="col-span-1 text-danger hover:underline">✕</button>
                    </div>
                  ))}
                </div>
              </div>

              <div>
                <button type="submit" disabled={busy || !hasItems} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">
                  Record donation
                </button>
                {!hasItems && <span className="ml-3 text-sm text-ink-muted">Add at least one food item or piece of equipment.</span>}
              </div>
            </form>
          </section>

          {isAdmin && <DonationsList nonce={listNonce} />}
        </div>
      </main>
    </div>
  );
}

function DonationsList({ nonce }: { nonce: number }) {
  // nonce changes after a new donation is recorded, giving a fresh fetcher so the list re-pulls.
  const fetcher = useCallback((token: string | undefined) => api.listDonations(token), [nonce]);
  const { data, error, loading } = useAuthedQuery(fetcher);
  const donations = data ?? [];

  return (
    <section>
      <h2 className="mb-3 text-lg">Recent donations</h2>
      {loading ? (
        <Loading />
      ) : error ? (
        <ErrorNotice error={error} />
      ) : donations.length === 0 ? (
        <p className="rounded-lg bg-raised px-6 py-8 text-center text-ink-secondary">No donations recorded yet.</p>
      ) : (
        <div className="overflow-hidden rounded-lg bg-raised">
          <table className="w-full text-left text-sm">
            <thead className="bg-sunken text-ink-secondary">
              <tr>
                <th className="px-5 py-3 font-medium">Date</th>
                <th className="px-5 py-3 font-medium">Donor</th>
                <th className="px-5 py-3 font-medium">Items</th>
                <th className="px-5 py-3 font-medium text-right">Est. value</th>
                <th className="px-5 py-3 font-medium">Thanked</th>
              </tr>
            </thead>
            <tbody>
              {donations.map((d) => (
                <tr key={d.id} className="border-t border-hairline align-middle">
                  <td className="px-5 py-3 text-ink-secondary">{d.donatedOn}</td>
                  <td className="px-5 py-3">{d.anonymous ? <span className="text-ink-muted">Anonymous</span> : d.donorName}</td>
                  <td className="px-5 py-3 text-ink-secondary">
                    {[d.ingredientCount ? `${d.ingredientCount} food` : "", d.equipmentCount ? `${d.equipmentCount} equipment` : ""].filter(Boolean).join(", ") || "—"}
                  </td>
                  <td className="px-5 py-3 text-right tabular-nums">{d.estimatedValueInr == null ? "—" : `₹${d.estimatedValueInr}`}</td>
                  <td className="px-5 py-3">
                    {d.acknowledged ? <span className="text-xs text-success">Sent</span> : <span className="text-xs text-ink-muted">—</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function numOrNull(s: string): number | null {
  const t = s.trim();
  return t === "" ? null : Number(t);
}
