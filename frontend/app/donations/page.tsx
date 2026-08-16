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

// The order the ledger reads in: what the temple collected online first, then what it wrote down.
const CATEGORIES = ["ONE_TIME", "RECURRING", "WISHLIST", "MANUAL", "IN_KIND"] as const;
const CATEGORY_LABEL: Record<string, string> = {
  ONE_TIME: "One-time",
  RECURRING: "Recurring",
  WISHLIST: "Wish list",
  MANUAL: "Manual",
  IN_KIND: "In-kind",
};

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
  const [cashAmount, setCashAmount] = useState("");
  const [ingredientLines, setIngredientLines] = useState<IngredientLine[]>([]);
  const [equipmentLines, setEquipmentLines] = useState<EquipmentLine[]>([]);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [done, setDone] = useState(false);
  const [listNonce, setListNonce] = useState(0);

  // Cash and goods are two gifts, not one row: the money goes to the bank and the goods to the
  // shelf. The server refuses the pair outright, so each side closes the other here rather than
  // letting someone fill in a form that cannot be submitted.
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
    const form = event.currentTarget;
    const f = new FormData(form);
    setBusy(true);
    setActionError(null);
    setDone(false);
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
      setCashAmount("");
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
              Record a gift of cash, food, or equipment. Cash goes into the ledger, food into stock,
              equipment into the register, and a thank-you goes to the donor.
            </p>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}
          {done && (
            <div className="mb-6 rounded border border-success/20 bg-success-bg p-4 text-success" role="status">
              Donation recorded. Thank you for logging it.
            </div>
          )}

          <section className="mb-10 rounded-lg bg-raised px-6 py-5" aria-labelledby="intake-heading">
            <h2 id="intake-heading" className="text-lg">Record a Donation</h2>
            <form className="mt-4 space-y-6" aria-label="Record a Donation" onSubmit={submit}>
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
                  Estimated value of goods (₹)
                  <input name="estimatedValueInr" type="number" min="0" step="any" disabled={hasCash} className="min-h-touch rounded border border-hairline bg-canvas px-3 disabled:opacity-60" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Notes
                  <input name="notes" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
              </div>

              {/* Cash */}
              <div>
                <h3 className="mb-2 text-sm font-medium">Cash</h3>
                <div className="grid grid-cols-12 gap-2">
                  <label className="col-span-5 flex flex-col gap-1 text-sm text-ink-secondary">
                    Amount (₹)
                    <input
                      aria-label="Cash amount"
                      type="number" min="0" step="any"
                      value={cashAmount}
                      disabled={goodsStarted}
                      onChange={(e) => setCashAmount(e.target.value)}
                      className="min-h-touch rounded border border-hairline bg-canvas px-3 disabled:opacity-60"
                    />
                  </label>
                </div>
                {goodsStarted && (
                  <p className="mt-2 text-sm text-ink-muted">
                    Record cash as its own donation — remove the food and equipment lines first.
                  </p>
                )}
              </div>

              {/* Ingredient lines */}
              <div>
                <div className="mb-2 flex items-center justify-between">
                  <h3 className="text-sm font-medium">Food</h3>
                  <button type="button" onClick={addIngredientLine} disabled={hasCash} className="text-sm text-accent-text hover:underline disabled:opacity-60 disabled:no-underline">+ Add food item</button>
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
                  <button type="button" onClick={addEquipmentLine} disabled={hasCash} className="text-sm text-accent-text hover:underline disabled:opacity-60 disabled:no-underline">+ Add equipment</button>
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
                <button type="submit" disabled={busy || (!hasCash && !hasItems)} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">
                  Record donation
                </button>
                {!hasCash && !hasItems && (
                  <span className="ml-3 text-sm text-ink-muted">
                    Enter a cash amount, or add a food item or piece of equipment.
                  </span>
                )}
              </div>
            </form>
          </section>

          {isAdmin && <DonationsLedger nonce={listNonce} />}
        </div>
      </main>
    </div>
  );
}

/**
 * Every gift the temple has received, however it arrived. This is the ledger that used to be its own
 * page: the gift just recorded above appears in it, which is the whole point of them being one screen.
 */
function DonationsLedger({ nonce }: { nonce: number }) {
  const [type, setType] = useState("");
  // nonce changes after a new donation is recorded, giving a fresh fetcher so the ledger re-pulls.
  const fetchLedger = useCallback(
    (token: string | undefined) => api.donationLedger({ type: type || undefined }, token),
    [type, nonce]
  );
  const { data, error, loading } = useAuthedQuery(fetchLedger);
  const { data: summary } = useAuthedQuery(
    useCallback((t: string | undefined) => api.donationSummary(t), [nonce])
  );
  const rows = data ?? [];

  return (
    <section aria-labelledby="ledger-heading">
      <header className="mb-4 flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 id="ledger-heading" className="text-lg">Every gift received</h2>
          <p className="mt-1 text-sm text-ink-secondary">Online, recurring, wish-list, cash, and in-kind, in one place.</p>
        </div>
        <a href={api.ledgerExportUrl()} className="min-h-touch rounded border border-hairline px-5 py-2 text-sm hover:bg-sunken">
          Export CSV
        </a>
      </header>

      {summary && (
        <div className="mb-6 grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-5">
          {CATEGORIES.map((cat) => (
            <div key={cat} className="rounded-lg bg-raised px-4 py-3">
              <p className="text-xs text-ink-muted">{CATEGORY_LABEL[cat]} · FY to date</p>
              <p className="mt-1 text-lg font-medium tabular-nums">
                ₹{summary.financialYearToDateByCategory[cat] ?? 0}
              </p>
            </div>
          ))}
        </div>
      )}

      <div className="mb-4">
        <label className="text-sm text-ink-secondary">
          Type{" "}
          <select value={type} onChange={(e) => setType(e.target.value)} className="ml-1 min-h-touch rounded border border-hairline bg-canvas px-3">
            <option value="">All</option>
            {CATEGORIES.map((cat) => (
              <option key={cat} value={cat}>{CATEGORY_LABEL[cat]}</option>
            ))}
          </select>
        </label>
      </div>

      {loading ? (
        <Loading label="Loading donations…" />
      ) : error ? (
        <ErrorNotice error={error} />
      ) : rows.length === 0 ? (
        <div className="rounded-lg bg-raised px-6 py-14 text-center">
          <p className="text-lg">No donations yet</p>
          <p className="mx-auto mt-2 max-w-prose text-ink-secondary">Completed gifts appear here as they come in.</p>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg bg-raised">
          <table className="w-full text-left">
            <thead className="bg-sunken text-sm text-ink-secondary">
              <tr>
                <th className="px-5 py-3 font-medium">Date</th>
                <th className="px-5 py-3 font-medium">Type</th>
                <th className="px-5 py-3 font-medium">Donor</th>
                <th className="px-5 py-3 font-medium text-right">Amount</th>
                <th className="px-5 py-3 font-medium">Mode</th>
                <th className="px-5 py-3 font-medium">Linked to</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id} className="border-t border-hairline align-middle">
                  <td className="px-5 py-3 tabular-nums text-ink-secondary">{r.donatedOn}</td>
                  <td className="px-5 py-3">{CATEGORY_LABEL[r.category] ?? r.category}</td>
                  <td className="px-5 py-3">{r.donorDisplay}</td>
                  <td className="px-5 py-3 text-right tabular-nums">₹{r.amountInr ?? "—"}</td>
                  <td className="px-5 py-3 text-ink-secondary">{r.paymentMode ?? "—"}</td>
                  <td className="px-5 py-3 text-ink-secondary">{r.linkedTo ?? "—"}</td>
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
