"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type BatchStock } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

const UNITS = ["KG", "GM", "L", "ML", "PIECES"];
const UNIT_LABEL: Record<string, string> = { KG: "Kg", GM: "gm", L: "L", ML: "ml", PIECES: "pieces" };
const REASONS = ["SPOILAGE", "DAMAGE", "COUNT_CORRECTION", "WASTE", "OTHER"];
const REASON_LABEL: Record<string, string> = {
  SPOILAGE: "Spoilage",
  DAMAGE: "Damage",
  COUNT_CORRECTION: "Count correction",
  WASTE: "Waste",
  OTHER: "Other",
};
const TYPE_LABEL: Record<string, string> = {
  PO_RECEIPT: "Received",
  DONATION_IN_KIND: "Donation",
  CONSUMPTION: "Cooked",
  ADJUSTMENT: "Adjustment",
};

export default function InventoryItemPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_STAFF"]}>
      <ItemView />
    </RequireRole>
  );
}

function ItemView() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const { getToken } = useAuth();

  const fetchItem = useCallback((token: string | undefined) => api.getInventoryItem(id, token), [id]);
  const { data, error, loading, reload } = useAuthedQuery(fetchItem);

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [historyNonce, setHistoryNonce] = useState(0);

  const item = data?.item;
  const batches = data?.batches ?? [];

  async function run(mutation: (token: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    setActionError(null);
    try {
      await mutation(await getToken());
      reload();
      setHistoryNonce((n) => n + 1);
      return true;
    } catch (e) {
      setActionError(toApiError(e, failure));
      return false;
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar templeName="Your temple" activeHref="/inventory" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <Link href="/inventory" className="text-sm text-accent-text hover:underline">← Inventory</Link>

          {loading ? (
            <p className="mt-6 text-ink-secondary">Loading…</p>
          ) : error ? (
            <div className="mt-6"><ErrorNotice error={error} /></div>
          ) : item ? (
            <>
              <header className="mb-6 mt-3 flex flex-wrap items-start justify-between gap-4">
                <div>
                  <h1>{item.ingredientName}</h1>
                  <p className="mt-1 text-ink-secondary">
                    {item.category}
                    {item.storageLocation ? ` · ${item.storageLocation}` : ""}
                  </p>
                </div>
                <div className="text-right">
                  <p className="text-3xl tabular-nums">{item.onHand} {UNIT_LABEL[item.unit] ?? item.unit}</p>
                  <p className="text-sm text-ink-secondary">on hand</p>
                  <div className="mt-2 flex justify-end gap-1.5">
                    {item.belowThreshold && <span className="rounded-sm bg-warning-bg px-2 py-1 text-xs text-warning">Below reorder level</span>}
                    {item.expiringSoon && <span className="rounded-sm bg-warning-bg px-2 py-1 text-xs text-warning">Expiring soon</span>}
                  </div>
                </div>
              </header>

              {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

              <section className="mb-8">
                <h2 className="mb-3 text-lg">Batches</h2>
                {batches.length === 0 ? (
                  <p className="rounded-lg bg-raised px-6 py-8 text-center text-ink-secondary">
                    No stock on the shelf. It appears here once goods are received or donated.
                  </p>
                ) : (
                  <div className="overflow-hidden rounded-lg bg-raised">
                    <table className="w-full text-left text-sm">
                      <thead className="bg-sunken text-ink-secondary">
                        <tr>
                          <th className="px-5 py-3 font-medium text-right">Quantity</th>
                          <th className="px-5 py-3 font-medium">Expires</th>
                          <th className="px-5 py-3 font-medium">Received</th>
                          <th className="px-5 py-3 font-medium">Batch</th>
                        </tr>
                      </thead>
                      <tbody>
                        {batches.map((b: BatchStock) => (
                          <tr key={b.batchId} className="border-t border-hairline">
                            <td className="px-5 py-3 text-right tabular-nums">{b.quantity} {UNIT_LABEL[b.unit] ?? b.unit}</td>
                            <td className="px-5 py-3">
                              {b.expiryDate ?? "—"}
                              {b.expiringSoon && <span className="ml-2 rounded-sm bg-warning-bg px-2 py-0.5 text-xs text-warning">soon</span>}
                            </td>
                            <td className="px-5 py-3 text-ink-secondary">{b.receivedDate ?? "—"}</td>
                            <td className="px-5 py-3 font-mono text-xs text-ink-muted">{b.batchId.slice(0, 8)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </section>

              {batches.length > 0 && (
                <AdjustForm
                  batches={batches}
                  unit={item.unit}
                  busy={busy}
                  onSubmit={(input) => run((t) => api.adjustStock(id, input, t), "We couldn't record that adjustment.")}
                />
              )}

              <MovementHistory ingredientId={item.ingredientId} nonce={historyNonce} />
            </>
          ) : null}
        </div>
      </main>
    </div>
  );
}

function AdjustForm({
  batches,
  unit,
  busy,
  onSubmit,
}: {
  batches: BatchStock[];
  unit: string;
  busy: boolean;
  onSubmit: (input: { batchId: string; quantity: number; unit: string; reason: string; note: string | null }) => Promise<boolean>;
}) {
  const [open, setOpen] = useState(false);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const f = new FormData(form);
    const ok = await onSubmit({
      batchId: String(f.get("batchId") ?? ""),
      quantity: Number(f.get("quantity") ?? 0),
      unit: String(f.get("unit") ?? unit),
      reason: String(f.get("reason") ?? "SPOILAGE"),
      note: (String(f.get("note") ?? "").trim() || null),
    });
    if (ok) form.reset();
  }

  if (!open) {
    return (
      <section className="mb-8">
        <button type="button" onClick={() => setOpen(true)} className="min-h-touch rounded border border-hairline-strong px-5 text-ink transition-colors duration-state hover:bg-sunken">
          Adjust stock
        </button>
      </section>
    );
  }

  return (
    <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="adjust-heading">
      <h2 id="adjust-heading" className="text-lg">Adjust a batch</h2>
      <p className="mt-1 text-sm text-ink-secondary">
        A signed change: negative to write off spoilage or waste, positive to correct a miscount.
        Large adjustments need a Temple Admin.
      </p>
      <form className="mt-4 grid grid-cols-2 gap-4" aria-label="Adjust stock" onSubmit={submit}>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          Batch
          <select name="batchId" required className="min-h-touch rounded border border-hairline bg-canvas px-3">
            {batches.map((b) => (
              <option key={b.batchId} value={b.batchId}>
                {b.quantity} {UNIT_LABEL[b.unit] ?? b.unit}{b.expiryDate ? ` · exp ${b.expiryDate}` : ""} · {b.batchId.slice(0, 8)}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          Reason
          <select name="reason" className="min-h-touch rounded border border-hairline bg-canvas px-3">
            {REASONS.map((r) => <option key={r} value={r}>{REASON_LABEL[r]}</option>)}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          Change (e.g. -2)
          <input name="quantity" type="number" step="any" required className="min-h-touch rounded border border-hairline bg-canvas px-3" />
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          Unit
          <select name="unit" defaultValue={unit} className="min-h-touch rounded border border-hairline bg-canvas px-3">
            {UNITS.map((u) => <option key={u} value={u}>{UNIT_LABEL[u]}</option>)}
          </select>
        </label>
        <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
          Note (required for &ldquo;Other&rdquo;)
          <input name="note" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
        </label>
        <div className="col-span-2 flex gap-3">
          <button type="submit" disabled={busy} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">
            Record adjustment
          </button>
          <button type="button" onClick={() => setOpen(false)} className="min-h-touch rounded px-4 text-ink-secondary hover:underline">Cancel</button>
        </div>
      </form>
    </section>
  );
}

function MovementHistory({ ingredientId, nonce }: { ingredientId: string; nonce: number }) {
  // nonce is a dependency so an upstream adjustment re-pulls the history through a fresh fetcher.
  const fetcher = useCallback(
    (token: string | undefined) => api.listMovements({ ingredientId, limit: 100 }, token),
    [ingredientId, nonce]
  );
  const { data, error, loading } = useAuthedQuery(fetcher);
  const movements = data ?? [];

  return (
    <section>
      <h2 className="mb-3 text-lg">Movement history</h2>
      {loading ? (
        <p className="text-ink-secondary">Loading…</p>
      ) : error ? (
        <ErrorNotice error={error} />
      ) : movements.length === 0 ? (
        <p className="rounded-lg bg-raised px-6 py-8 text-center text-ink-secondary">No movements yet.</p>
      ) : (
        <div className="overflow-hidden rounded-lg bg-raised">
          <table className="w-full text-left text-sm">
            <thead className="bg-sunken text-ink-secondary">
              <tr>
                <th className="px-5 py-3 font-medium">When</th>
                <th className="px-5 py-3 font-medium">Type</th>
                <th className="px-5 py-3 font-medium text-right">Change</th>
                <th className="px-5 py-3 font-medium">Reason / note</th>
                <th className="px-5 py-3 font-medium">By</th>
              </tr>
            </thead>
            <tbody>
              {movements.map((m) => (
                <tr key={m.id} className="border-t border-hairline align-top">
                  <td className="px-5 py-3 text-ink-secondary">{new Date(m.createdAt).toLocaleString()}</td>
                  <td className="px-5 py-3">{TYPE_LABEL[m.type] ?? m.type}</td>
                  <td className={`px-5 py-3 text-right tabular-nums ${m.quantity < 0 ? "text-danger" : ""}`}>
                    {m.quantity > 0 ? "+" : ""}{m.quantity} {UNIT_LABEL[m.unit] ?? m.unit}
                  </td>
                  <td className="px-5 py-3 text-ink-secondary">
                    {m.reason ? REASON_LABEL[m.reason] ?? m.reason : ""}
                    {m.referenceType === "CORRECTION" ? "Correction" : ""}
                    {m.note ? <span className="block text-xs text-ink-muted">{m.note}</span> : null}
                  </td>
                  <td className="px-5 py-3 text-ink-secondary">{m.actorName ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
