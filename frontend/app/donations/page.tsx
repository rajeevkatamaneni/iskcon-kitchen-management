"use client";

import { useCallback, useState } from "react";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { SegmentedControl } from "@/components/ds/SegmentedControl";
import {
  api,
  toApiError,
  type ApiError,
  type CategoryComparison,
  type LedgerPeriodKind,
} from "@/lib/api";
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

/**
 * The windows the ledger can be read over. "Another year" is last, and is the only one that needs a
 * second choice made — which is why it is a segment that reveals a picker rather than a list of
 * every year the temple has ever had crowded into the control itself.
 */
const PERIODS: readonly { value: LedgerPeriodKind; label: string }[] = [
  { value: "WEEK", label: "This week" },
  { value: "MONTH", label: "This month" },
  { value: "FINANCIAL_YEAR", label: "This financial year" },
  { value: "YEAR", label: "Another year" },
];

/** 2025 → "FY 2025–26", the way a financial year is written on an Indian receipt. */
function financialYearLabel(year: number): string {
  return `FY ${year}–${String(year + 1).slice(2)}`;
}

/** "1 Apr 2026". The year is spelled out because a closed financial year is often the one on show. */
function dayMonthYear(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString("en-GB", {
    day: "numeric",
    month: "short",
    year: "numeric",
  });
}

/** ₹1,24,000 — lakhs and crores, grouped the way the money is actually said. */
function rupees(amount: number | null | undefined): string {
  return amount == null ? "—" : `₹${amount.toLocaleString("en-IN")}`;
}

/**
 * What the tile says beneath its figure about the same window a year earlier.
 *
 * <p>Three of the four answers are not percentages, and that is the point of the feature. A prior
 * window of nothing has no denominator, so the tile says so rather than printing an increase of
 * infinity or quietly rounding it to 100%. A temple whose records do not reach back a year has no
 * prior window at all — a different statement again, and not a fall to zero. Only when there was
 * genuinely money there before does a percentage get printed.
 *
 * <p>The wording follows the window: while a period is still running the comparison is against the
 * same point last year, and once it has closed — a financial year the temple has finished — it is
 * against the whole of the year before, because that is what was actually measured.
 */
function comparisonNote(
  comparison: CategoryComparison | undefined,
  hasPriorYear: boolean,
  closed: boolean
): string {
  if (!hasPriorYear) {
    return "nothing recorded that far back";
  }
  if (!comparison || comparison.changePercent === null) {
    return closed ? "nothing in the year before" : "nothing at this point last year";
  }
  const lastYear = closed ? "the year before" : "this point last year";
  if (comparison.changePercent === 0) {
    return `level with ${lastYear}`;
  }
  const direction = comparison.changePercent > 0 ? "up" : "down";
  return `${direction} ${Math.abs(comparison.changePercent)}% on ${lastYear}`;
}

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
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <DonationsView />
    </RequireRole>
  );
}

function DonationsView() {
  const { appUser, getToken } = useAuth();
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
      form.reset();
      setCashAmount("");
      setWishlistItemId("");
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
            <div className="mb-6">
              <InlineNotice tone="success" autoDismiss>
                Donation recorded. Thank you for logging it.
              </InlineNotice>
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
                    <span className="pl-field-inset font-medium text-ink">Donor name</span>
                    <input name="donorName" required={!anonymous} className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                  </label>
                  <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                    <span className="pl-field-inset font-medium text-ink">Phone (for thank-you)</span>
                    <input name="donorPhone" placeholder="+91…" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                  </label>
                  <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                    <span className="pl-field-inset font-medium text-ink">Email</span>
                    <input name="donorEmail" type="email" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                  </label>
                </div>
              )}

              <div className="grid grid-cols-3 gap-4">
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  <span className="pl-field-inset font-medium text-ink">Date</span>
                  <input name="donatedOn" type="date" defaultValue={todayIso()} required className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  <span className="pl-field-inset font-medium text-ink">Estimated value of goods (₹)</span>
                  <input name="estimatedValueInr" type="number" min="0" step="any" disabled={hasCash} className="min-h-touch rounded border border-hairline bg-canvas px-3 disabled:opacity-60" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  <span className="pl-field-inset font-medium text-ink">Notes</span>
                  <input name="notes" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
              </div>

              {/* Cash */}
              <div>
                <h3 className="mb-2 text-sm font-medium">Cash</h3>
                <div className="grid grid-cols-12 gap-2">
                  <label className="col-span-5 flex flex-col gap-1 text-sm text-ink-secondary">
                    <span className="pl-field-inset font-medium text-ink">Amount (₹)</span>
                    <input
                      aria-label="Cash amount"
                      type="number" min="0" step="any"
                      value={cashAmount}
                      disabled={goodsStarted}
                      onChange={(e) => setCashAmount(e.target.value)}
                      className="min-h-touch rounded border border-hairline bg-canvas px-3 disabled:opacity-60"
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
                        className="min-h-touch rounded border border-hairline bg-canvas px-3 text-sm disabled:opacity-60"
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
  const { getToken } = useAuth();
  const [type, setType] = useState("");
  const [period, setPeriod] = useState<LedgerPeriodKind>("MONTH");
  const [financialYear, setFinancialYear] = useState<number | null>(null);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<ApiError | null>(null);

  // The summary is asked first and the list second, because the summary is what resolves a period
  // into two dates. The alternative — working the dates out here — would put the April-to-March rule
  // in two places, and the day they drifted apart the tiles and the rows beneath them would be
  // totalling different spans while looking like one screen.
  const fetchSummary = useCallback(
    (token: string | undefined) => api.donationPeriodSummary(period, financialYear, token),
    [period, financialYear, nonce]
  );
  const { data: summary, error: summaryError } = useAuthedQuery(fetchSummary);
  // Named apart from the global `window`, which it would otherwise shadow for the whole component.
  const periodWindow = summary?.window ?? null;
  const years = summary?.financialYearsWithGifts ?? [];

  // nonce changes after a new donation is recorded, giving a fresh fetcher so the ledger re-pulls.
  const fetchLedger = useCallback(
    (token: string | undefined) =>
      periodWindow
        ? api.donationLedger({ from: periodWindow.from, to: periodWindow.to, type: type || undefined }, token)
        : Promise.resolve(null),
    [periodWindow?.from, periodWindow?.to, type, nonce]
  );
  const { data, error, loading: rowsLoading } = useAuthedQuery(fetchLedger);
  const rows = data ?? [];
  // Until the window is known there is nothing to fetch and nothing true to say, so the list waits
  // rather than flashing "no donations yet" at somebody whose ledger is merely still loading.
  const loading = rowsLoading || (!periodWindow && !summaryError);
  // Either query failing leaves the screen unable to say anything true, so either is shown.
  const failure = summaryError ?? error;

  // A window that ended before today is closed and can no longer move; one that includes today is
  // still filling up. The tiles word their comparison differently for each.
  const closed = periodWindow != null && periodWindow.to < todayIso();

  /** Switching period drops any chosen year, except when Another year is what was chosen. */
  function choosePeriod(next: LedgerPeriodKind) {
    setPeriod(next);
    // The interesting "other" year is almost always the one just finished, so it is the default;
    // years arrives newest-first, and its second entry is therefore the last closed year.
    setFinancialYear(next === "YEAR" ? (years[1] ?? years[0] ?? null) : null);
  }

  // The file is fetched with the token and handed over as a blob. A plain link cannot carry an
  // Authorization header, so the old one answered every click with a 401 error page.
  async function exportCsv() {
    setExporting(true);
    setExportError(null);
    try {
      const { blob, filename } = await api.exportLedger(
        { from: periodWindow?.from, to: periodWindow?.to, type: type || undefined },
        await getToken()
      );
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch (e) {
      setExportError(toApiError(e, "We couldn't export the donations."));
    } finally {
      setExporting(false);
    }
  }

  return (
    <section aria-labelledby="ledger-heading">
      <header className="mb-4 flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 id="ledger-heading" className="text-lg">Every gift received</h2>
          <p className="mt-1 text-sm text-ink-secondary">Online, recurring, wish-list, cash, and in-kind, in one place.</p>
        </div>
        <button
          type="button"
          onClick={exportCsv}
          disabled={exporting}
          className="min-h-touch rounded border border-hairline px-5 py-2 text-sm hover:bg-sunken disabled:opacity-60"
        >
          {exporting ? "Preparing…" : "Export CSV"}
        </button>
      </header>

      {exportError && <div className="mb-4"><ErrorNotice error={exportError} /></div>}

      <div className="mb-4 flex flex-wrap items-center gap-3">
        <SegmentedControl
          label="Period"
          value={period}
          onChange={choosePeriod}
          // A temple in its first financial year has no other year to offer, so the segment that
          // would open an empty picker is not shown at all.
          options={years.length > 1 ? PERIODS : PERIODS.filter((p) => p.value !== "YEAR")}
        />
        {period === "YEAR" && (
          <label className="text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Year{" "}</span>
            <select
              value={financialYear ?? ""}
              onChange={(e) => setFinancialYear(Number(e.target.value))}
              className="ml-1 min-h-touch rounded border border-hairline bg-canvas px-3"
            >
              {years.map((y) => (
                <option key={y} value={y}>{financialYearLabel(y)}</option>
              ))}
            </select>
          </label>
        )}
        {periodWindow && (
          // Said once, here, rather than repeated on five tiles: the figures, the rows and the CSV
          // all cover exactly this span, and an accountant's first question is which one it is.
          <p className="text-sm text-ink-muted">
            {dayMonthYear(periodWindow.from)} to {dayMonthYear(periodWindow.to)}
          </p>
        )}
      </div>

      {summary && (
        // The page's own tiles rather than the design system's StatTile: five of these sit across a
        // row here, where Today shows four across a wider column, and StatTile's larger figure and
        // padding wrap a lakh figure onto two lines at this width.
        <div className="mb-6 grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-5">
          {CATEGORIES.map((cat) => (
            <div key={cat} className="rounded-lg bg-raised px-4 py-3">
              <p className="text-xs text-ink-muted">{CATEGORY_LABEL[cat]}</p>
              <p className="mt-1 text-lg font-medium tabular-nums">
                {rupees(summary.byCategory[cat]?.total ?? 0)}
              </p>
              <p className="mt-1 text-xs text-ink-muted">
                {comparisonNote(summary.byCategory[cat], summary.hasPriorYear, closed)}
              </p>
            </div>
          ))}
        </div>
      )}

      <div className="mb-4">
        <label className="text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Type{" "}</span>
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
      ) : failure ? (
        <ErrorNotice error={failure} />
      ) : rows.length === 0 ? (
        <div className="rounded-lg bg-raised px-6 py-14 text-center">
          <p className="text-lg">No donations in this period</p>
          {/* An empty list is now ambiguous — it can mean a quiet week rather than a new temple —
              so the message names the window instead of implying nothing has ever arrived. */}
          <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
            Nothing was given between {periodWindow ? dayMonthYear(periodWindow.from) : "these dates"} and{" "}
            {periodWindow ? dayMonthYear(periodWindow.to) : "these dates"}. Try a longer period.
          </p>
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
                <tr key={r.id} className="border-t border-hairline align-middle hover:bg-raised/60">
                  <td className="px-5 py-3 tabular-nums text-ink-secondary">{r.donatedOn}</td>
                  <td className="px-5 py-3">{CATEGORY_LABEL[r.category] ?? r.category}</td>
                  <td className="px-5 py-3">{r.donorDisplay}</td>
                  <td className="px-5 py-3 text-right tabular-nums">{rupees(r.amountInr)}</td>
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
