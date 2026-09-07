"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useMemo, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type BatchStock, type StockMovement } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { FOOD_UNITS, dateWithYear, expiryWord, moment, quantity, unitLabel } from "@/lib/format";
import { Loading } from "@/components/Loading";
import {
  ACTIONS_ROW, TABLE, TD_ACTIONS, TD_DATE, TD_NUM, TD_TEXT, THEAD, TH_ACTIONS, TH_NUM, TH_TEXT, TR, WRAP,
} from "@/components/ds/table";
import { Button } from "@/components/ds/Button";

const REASONS = ["SPOILAGE", "DAMAGE", "COUNT_CORRECTION", "WASTE", "OTHER"];
const REASON_LABEL: Record<string, string> = {
  SPOILAGE: "Spoilage",
  DAMAGE: "Damage",
  COUNT_CORRECTION: "Count correction",
  WASTE: "Waste",
  OTHER: "Other",
};
/**
 * The server's refusal for a movement that already carries a reversal.
 *
 * <p>Branched on by code and never by status. `api.ts` is explicit that a screen reading the status
 * number is a screen drifting away from the error contract, and it is not a hypothetical here: 409
 * is the answer to several different refusals on this screen's endpoints, and only this one has
 * something particular to say to the person who caused it.
 */
const ALREADY_CORRECTED = "KMS-400039";

const TYPE_LABEL: Record<string, string> = {
  PO_RECEIPT: "Received",
  DONATION_IN_KIND: "Donation",
  CONSUMPTION: "Cooked",
  ADJUSTMENT: "Adjustment",
};

export default function InventoryItemPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
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
  /** Whether the confirmation in front of stopping tracking is on screen. */
  const [stopping, setStopping] = useState(false);

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
      <Sidebar activeHref="/inventory" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <Link href="/inventory" className="text-sm text-accent-text hover:underline">← Inventory</Link>

          {loading ? (
            <Loading />
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
                  <p className="text-3xl tabular-nums">{quantity(item.onHand, item.unit)}</p>
                  <p className="text-sm text-ink-secondary">On hand</p>
                  <div className="mt-2 flex justify-end gap-1.5">
                    {item.belowThreshold && <span className="rounded-sm bg-warning-bg px-2 py-1 text-xs text-warning font-semibold">Below reorder level</span>}
                    {item.expiringSoon && (
                      <span className="rounded-sm bg-warning-bg px-2 py-1 text-xs font-semibold text-warning">
                        {expiryWord(item.soonestExpiry) === "expired" ? "Expired" : "Expiring soon"}
                      </span>
                    )}
                  </div>
                </div>
              </header>

              {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

              <section className="mb-8">
                {/* "Batches" was a database word. This is what the store is holding, and the only
                    order it is ever read in is what needs using first. */}
                <h2 className="mb-3 text-lg">
                  Current inventory{" "}
                  <span className="text-sm font-normal text-ink-secondary">— soonest to expire first</span>
                </h2>
                {batches.length === 0 ? (
                  <p className="card px-6 py-8 text-center text-ink-secondary">
                    Nothing on the shelf yet. Record what is there, or it appears here as goods are
                    received and donated.
                  </p>
                ) : (
                  <div className="table-wrap overflow-x-auto">
                    <table className={`${TABLE} text-sm`}>
                      <thead className={THEAD}>
                        <tr>
                          <th className={TH_NUM}>Quantity</th>
                          <th className={TH_TEXT}>Expires</th>
                          <th className={TH_TEXT}>Received</th>
                          <th className={TH_TEXT}>How it arrived</th>
                        </tr>
                      </thead>
                      <tbody>
                        {batches.map((b: BatchStock) => (
                          <tr key={b.batchId} className={TR}>
                            <td className={TD_NUM}>{quantity(b.quantity, b.unit)}</td>
                            <td className={TD_DATE}>
                              {b.expiryDate ? dateWithYear(b.expiryDate) : "—"}
                              {b.expiringSoon && (
                                <span className="ml-2 rounded-sm bg-warning-bg px-2 py-0.5 text-xs font-semibold text-warning">
                                  {expiryWord(b.expiryDate)}
                                </span>
                              )}
                            </td>
                            <td className={`${TD_DATE} text-ink-secondary`}>
                              {b.receivedDate ? dateWithYear(b.receivedDate) : "—"}
                            </td>
                            {/* A hex id is not something anybody can recognise. Until each lot
                                carries where it came from, the date it arrived is the honest
                                answer — and it is the one a storekeeper actually uses. */}
                            <td className={`${TD_DATE} text-ink-secondary`}>
                              {b.receivedDate ? `Arrived ${dateWithYear(b.receivedDate)}` : "—"}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </section>

              {/* Offered even with no batches, which is when it matters most: an item somebody has
                  just started tracking holds nothing the ledger knows about, and every other way in
                  describes stock arriving rather than stock already on the shelf. Without this the
                  screen said "below reorder level" and offered nothing that could answer it. */}
              <AdjustForm
                batches={batches}
                unit={item.unit}
                busy={busy}
                onSubmit={(input) => run((t) => api.adjustStock(id, input, t), "We couldn’t record that adjustment.")}
              />

              {/* A correction changes what is on hand, so the header above has to be re-read too —
                  hence the item query's own reload rather than only the history's. */}
              <MovementHistory
                ingredientId={item.ingredientId}
                nonce={historyNonce}
                onCorrected={reload}
              />

              {/* Kept at the foot of the screen, under a rule, and away from the stock controls.
                  It is the only action here that takes something off a list, and putting it beside
                  the ones a storekeeper uses every day is how it gets pressed by accident. */}
              <section className="mt-10 border-t border-hairline pt-6">
                <Button type="button" variant="ghost" onClick={() => setStopping(true)}>
                  Stop tracking this item
                </Button>
                <p className="mt-2 text-sm text-ink-secondary">
                  Takes {item.ingredientName} off the inventory list. Everything above stays in the
                  ledger.
                </p>
              </section>

              {stopping && (
                <StopTracking
                  itemId={id}
                  name={item.ingredientName}
                  onCancel={() => setStopping(false)}
                />
              )}
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
  onSubmit: (input: { batchId: string | null; quantity: number; unit: string; reason: string; note: string | null }) => Promise<boolean>;
}) {
  const [open, setOpen] = useState(false);
  /** Nothing in the ledger yet, so this is the first count rather than a correction to a lot. */
  const opening = batches.length === 0;

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const f = new FormData(form);
    const ok = await onSubmit({
      batchId: opening ? null : String(f.get("batchId") ?? ""),
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
          {opening ? "Record what's on the shelf" : "Adjust stock"}
        </button>
      </section>
    );
  }

  return (
    <section className="card mb-8 px-6 py-5" aria-labelledby="adjust-heading">
      <h2 id="adjust-heading" className="text-lg">
        {opening ? "Record what's on the shelf" : "Adjust a batch"}
      </h2>
      <p className="mt-1 text-sm text-ink-secondary">
        {opening
          ? "Count what is in the store today and it becomes the opening batch. Everything after this — deliveries, donations, meals cooked — moves on its own."
          : "Negative writes off spoilage. Positive corrects a miscount. A large one needs an admin."}
      </p>
      <form className="mt-4 grid grid-cols-2 gap-4" aria-label="Adjust stock" onSubmit={submit}>
        {!opening && (
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Batch</span>
            <select name="batchId" required className="min-h-touch rounded-control border border-hairline px-3">
              {batches.map((b) => (
                <option key={b.batchId} value={b.batchId}>
                  {quantity(b.quantity, b.unit)}
                  {b.expiryDate ? ` · use by ${dateWithYear(b.expiryDate)}` : ""}
                  {b.receivedDate ? ` · arrived ${dateWithYear(b.receivedDate)}` : ""}
                </option>
              ))}
            </select>
          </label>
        )}
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Reason</span>
          <select name="reason" className="min-h-touch rounded-control border border-hairline px-3">
            {REASONS.map((r) => <option key={r} value={r}>{REASON_LABEL[r]}</option>)}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">
            {opening ? "How much is there" : "Change (e.g. -2)"}
          </span>
          <input
            name="quantity"
            type="number"
            step="any"
            min={opening ? 0 : undefined}
            required
            className="min-h-touch rounded-control border border-hairline px-3"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Unit</span>
          <select name="unit" defaultValue={unit} className="min-h-touch rounded-control border border-hairline px-3">
            {FOOD_UNITS.map((u) => <option key={u} value={u}>{unitLabel(u)}</option>)}
          </select>
        </label>
        <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Note (required for &ldquo;Other&rdquo;)</span>
          <input name="note" className="min-h-touch rounded-control border border-hairline px-3" />
        </label>
        <div className="col-span-2 flex gap-3">
          <button type="submit" disabled={busy} className="btn btn-primary min-h-touch px-5 transition-colors duration-state disabled:opacity-60">
            {opening ? "Record the count" : "Record adjustment"}
          </button>
          <button type="button" onClick={() => setOpen(false)} className="min-h-touch rounded px-4 text-ink-secondary hover:underline">Cancel</button>
        </div>
      </form>
    </section>
  );
}

function MovementHistory({
  ingredientId,
  nonce,
  onCorrected,
}: {
  ingredientId: string;
  nonce: number;
  onCorrected: () => void;
}) {
  // nonce is a dependency so an upstream adjustment re-pulls the history through a fresh fetcher.
  const fetcher = useCallback(
    (token: string | undefined) => api.listMovements({ ingredientId, limit: 100 }, token),
    [ingredientId, nonce]
  );
  const { data, error, loading, reload } = useAuthedQuery(fetcher);
  const movements = data ?? [];

  /** The movement a correction is being written against, or null when nobody is correcting one. */
  const [correcting, setCorrecting] = useState<StockMovement | null>(null);

  /*
   * The cross-reference, read in both directions off this one list.
   *
   * <p>A correction is an ADJUSTMENT carrying `referenceType=CORRECTION` and the original's id in
   * `referenceId`, so the link between the two rows is already in the history and needs no second
   * request to follow. `byOriginal` answers "has this movement been reversed?" — which marks the
   * original — and `byId` answers "what does this correction reverse?", which lets the reversal say
   * so in words instead of showing a hex id nobody can resolve.
   */
  const { byOriginal, byId } = useMemo(() => {
    const rows = data ?? [];
    const original = new Map<string, StockMovement>();
    for (const m of rows) {
      if (m.referenceType === "CORRECTION" && m.referenceId) original.set(m.referenceId, m);
    }
    return { byOriginal: original, byId: new Map(rows.map((m) => [m.id, m])) };
  }, [data]);

  /** A correction landed: the history gains a row, and the count above it has moved. */
  function ledgerChanged() {
    reload();
    onCorrected();
  }

  return (
    <section>
      {/* "Movement history" was the ledger describing itself. This is what came in and what went
          out, which is what a person is actually reading — and the only reason the number above can
          be trusted, because it is the sum of exactly this. */}
      <h2 className="mb-3 text-lg">
        Comings and goings{" "}
        <span className="text-sm font-normal text-ink-secondary">— everything that changed the count</span>
      </h2>
      {loading ? (
        <Loading />
      ) : error ? (
        <ErrorNotice error={error} />
      ) : movements.length === 0 ? (
        <p className="card px-6 py-8 text-center text-ink-secondary">No movements yet.</p>
      ) : (
        <div className="table-wrap overflow-x-auto">
          <table className={`${TABLE} text-sm`}>
            <thead className={THEAD}>
              <tr>
                <th className={TH_TEXT}>When</th>
                <th className={TH_TEXT}>Type</th>
                <th className={TH_NUM}>Change</th>
                <th className={`${TH_TEXT} ${WRAP}`}>Reason / note</th>
                <th className={TH_TEXT}>By</th>
                <th className={TH_ACTIONS}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {movements.map((m) => {
                const reverses = m.referenceType === "CORRECTION" && m.referenceId
                  ? byId.get(m.referenceId)
                  : undefined;
                const reversedBy = byOriginal.get(m.id);
                return (
                  <tr key={m.id} className={TR}>
                    <td className={`${TD_DATE} text-ink-secondary`}>{moment(m.createdAt)}</td>
                    <td className={TD_TEXT}>{TYPE_LABEL[m.type] ?? m.type}</td>
                    <td className={`${TD_NUM} ${m.quantity < 0 ? "text-danger" : ""}`}>
                      {m.quantity > 0 ? "+" : ""}{quantity(m.quantity, m.unit)}
                    </td>
                    <td className={`${TD_TEXT} ${WRAP} text-ink-secondary`}>
                      {/* Somebody's own words about a write-off — the one column here that takes the
                          table's slack, and so the only one allowed to run to a second line. */}
                      <div>
                        {/* A correction always carries COUNT_CORRECTION as its reason, so printing
                            the reason and the word "Correction" side by side read "Count
                            correctionCorrection" — the ledger's word and the reader's, run
                            together. One of them is the answer, and for a correction it is the one
                            that says what the row reverses. */}
                        {m.referenceType === "CORRECTION" ? (
                          <span>
                            Correction
                            {reverses
                              ? ` of the ${(TYPE_LABEL[reverses.type] ?? reverses.type).toLowerCase()} on ${moment(reverses.createdAt)}`
                              : ""}
                          </span>
                        ) : (
                          m.reason ? REASON_LABEL[m.reason] ?? m.reason : ""
                        )}
                        {reversedBy && (
                          <span className="ml-2 rounded-sm bg-sunken px-2 py-0.5 text-xs font-semibold text-ink-secondary">
                            Corrected
                          </span>
                        )}
                        {m.note ? <span className="block text-xs text-ink-muted">{m.note}</span> : null}
                      </div>
                    </td>
                    <td className={`${TD_TEXT} text-ink-secondary`}>{m.actorName ?? "—"}</td>
                    <td className={TD_ACTIONS}>
                      <span className={ACTIONS_ROW}>
                        {/* Not offered on a movement this row can already see a correction against.
                            A person read "Corrected" in the column to the left, pressed this,
                            wrote out a reason and was then refused by the server
                            (MOVEMENT_ALREADY_CORRECTED, KMS-400039) — work asked for and thrown
                            away, over an answer the row was holding all along in `reversedBy`.

                            What is removed is the *ordinary* way of reaching that refusal, not the
                            refusal. Two readers on two tabs is the everyday case in a temple store,
                            and whoever presses second is looking at a screen that was right when it
                            loaded; the branch in CorrectMovement that puts the server's sentence on
                            screen stays exactly as it is, and is still reachable that way. Deleting
                            it would trade a rude screen for a broken one.

                            Nothing takes this control's place, and there is no disabled button
                            here: the "Corrected" badge beside the reason already says why there is
                            nothing to press, and a disabled control that never becomes enabled only
                            asks the reader to work out what would enable it. That is the precedent
                            T-002 set across the five controls it withdrew — the affordance goes,
                            the explanation stays in words a person can read. */}
                        {!reversedBy && (
                          <Button type="button" variant="ghost" size="sm" onClick={() => setCorrecting(m)}>
                            Correct
                          </Button>
                        )}
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {correcting && (
        <CorrectMovement
          movement={correcting}
          onLedgerChanged={ledgerChanged}
          onClose={() => setCorrecting(null)}
        />
      )}
    </section>
  );
}

/**
 * Writing a correction against one movement.
 *
 * <p>Nothing here edits the original. The ledger is append-only, so the server appends the exact
 * reverse of the movement — same batch, same unit, opposite quantity — cross-referenced to what it
 * reverses, and both rows stay readable afterwards. That is the whole reason the wording below
 * promises the original stays: a person who reads "correct" as "erase" and finds the row still
 * there will assume the correction did not work.
 *
 * <p>The note is mandatory at the server, and rightly. A count that moves twice on the same day is
 * a thing the next person has to be able to explain, and the only place that explanation can live
 * is the row itself.
 *
 * <p><strong>The refusal branch below is for the race, and only the race.</strong> It was once for
 * both: the Correct control was rendered on every row, including rows the screen could already see
 * a correction against, and this dialog carried the refusal for all of them. That made the server
 * answer an ordinary reading mistake — badge says "Corrected", control says "Correct", and a note
 * is typed out before anything says no (Rajeev, 2026-09-07). The row now withholds the control when
 * it holds the reversal, so the only way left to this branch is the one it was written for: two
 * people on two tabs, where whoever presses second is looking at a screen that was right when it
 * loaded. Their refusal is still theirs to be told about, plainly — and the correction they are
 * told to go and look at is pulled onto the screen behind this dialog while they read about it.
 */
function CorrectMovement({
  movement,
  onLedgerChanged,
  onClose,
}: {
  movement: StockMovement;
  onLedgerChanged: () => void;
  onClose: () => void;
}) {
  const { getToken } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  /** The server's own words for a movement that has already been reversed — see the note above. */
  const [alreadyCorrected, setAlreadyCorrected] = useState<ApiError | null>(null);

  /**
   * What is being reversed, in the terms the row itself uses.
   *
   * <p>The type label is lower-cased <strong>here, and nothing lower-cases this string again</strong>.
   * Both sentences below set it mid-sentence — "The cooked of -2 Kg on 23 Aug 2026 stays…" — and
   * they used to get there by calling `summary.toLowerCase()` at the call site. That took the unit
   * and the month down with the label: the table one row above printed "+1.8 Kg" on "23 Aug 2026"
   * and the dialog answered "+1.8 kg on 23 aug 2026" (Rajeev, 2026-09-07).
   *
   * <p>Units are not decoration — `Kg`, `gm`, `L` and `ml` name different amounts of the same
   * thing — and this codebase has been bitten by exactly this before: the note at
   * `components/planner/MealComposer.tsx:1362` records `toLowerCase()` rendering a litre's "L" as
   * the digit-like "l", which is why unit labels there are printed through `unitLabel()` and left
   * in the case it gives them. Same lesson, second place: decide the case where the string is
   * built, so a call site cannot flatten a formatter's output on its way to the screen.
   */
  const summary =
    `${(TYPE_LABEL[movement.type] ?? movement.type).toLowerCase()} of ` +
    `${movement.quantity > 0 ? "+" : ""}${quantity(movement.quantity, movement.unit)} ` +
    `on ${moment(movement.createdAt)}`;

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const note = String(new FormData(event.currentTarget).get("note") ?? "").trim();
    setBusy(true);
    setError(null);
    try {
      await api.compensateMovement(movement.id, note, await getToken());
      onLedgerChanged();
      onClose();
    } catch (e) {
      const failed = toApiError(e, "We couldn’t record that correction.");
      if (failed.code === ALREADY_CORRECTED) {
        setAlreadyCorrected(failed);
        // Its next step is "look at the correction that was already recorded", so put that
        // correction on the screen rather than asking them to reload the page to find it.
        onLedgerChanged();
      } else {
        setError(failed);
      }
      setBusy(false);
    }
  }

  if (alreadyCorrected) {
    return (
      <div
        className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 px-4"
        role="dialog"
        aria-modal="true"
        aria-labelledby="correct-movement-title"
      >
        <div className="modal w-full max-w-prose px-8 py-7">
          {/* The server's sentence and its next step, as written. Both are already plain language
              with no technical detail in them, and rewriting them here would be two places saying
              the same thing in different words. The code stays quiet and quotable, as ErrorNotice
              has it — this is not a red alarm, it is an answer. */}
          <h2 id="correct-movement-title" className="text-lg">{alreadyCorrected.message}</h2>
          <p className="mt-2 text-sm text-ink-secondary">{alreadyCorrected.action}</p>
          <p className="mt-2 text-sm text-ink-secondary">
            It is in the list behind this, marked as a correction of {summary}.
          </p>
          <p className="mt-3 text-xs text-ink-muted">
            If you need help, quote{" "}
            <span className="font-mono font-medium">{alreadyCorrected.code}</span>
          </p>
          <div className="mt-6 flex items-center justify-end gap-3">
            <Button type="button" onClick={onClose}>Close</Button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 px-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="correct-movement-title"
    >
      <form className="modal w-full max-w-prose px-8 py-7" onSubmit={submit}>
        <h2 id="correct-movement-title" className="text-lg">Correct this movement</h2>
        <p className="mt-2 text-sm text-ink-secondary">
          The {summary} stays in the ledger exactly as it is. The opposite amount is
          added beneath it, marked as a correction of it, so the count comes back to where it was.
        </p>
        <label className="mt-4 flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Why it is being corrected</span>
          <textarea
            name="note"
            required
            maxLength={500}
            rows={3}
            className="rounded-control border border-hairline px-3 py-2"
          />
        </label>

        {error && (
          <div className="mt-4">
            <ErrorNotice error={error} />
          </div>
        )}

        <div className="mt-6 flex flex-wrap items-center justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" busy={busy} disabled={busy}>
            Record the correction
          </Button>
        </div>
      </form>
    </div>
  );
}

/**
 * Stopping tracking, and the sentence that has to be on the screen before it happens.
 *
 * <p>The server deletes the inventory item's metadata and nothing else — its own comment reads
 * "the movement history remains, so stopping tracking never erases the ledger". The person pressing
 * this cannot see that comment, and the two available readings of the word are very far apart: one
 * takes a row off a list, the other destroys the record of everything ever received and cooked. So
 * the confirmation says both halves out loud — what goes, and what stays — rather than asking
 * "are you sure?" about a word whose meaning is the thing in doubt.
 *
 * <p><strong>What it deliberately does not do is write the stock down to zero first.</strong> That
 * would put a movement in the ledger saying the store lost everything it was holding, on a day
 * nothing of the sort happened, and it would be indistinguishable ever after from a real loss.
 * Item I2 of the outstanding build list was raised about exactly that confusion.
 */
function StopTracking({
  itemId,
  name,
  onCancel,
}: {
  itemId: string;
  name: string;
  onCancel: () => void;
}) {
  const router = useRouter();
  const { getToken } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function stop() {
    setBusy(true);
    setError(null);
    try {
      await api.deleteInventoryItem(itemId, await getToken());
      // Back to the list, because the screen behind this one is now about an item the temple no
      // longer tracks and there is nothing left on it to do.
      router.push("/inventory");
    } catch (e) {
      setError(toApiError(e, "We couldn’t stop tracking that item."));
      setBusy(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 px-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="stop-tracking-title"
    >
      <div className="modal w-full max-w-prose px-8 py-7">
        <h2 id="stop-tracking-title" className="text-lg text-danger">Stop tracking {name}?</h2>
        <p className="mt-2 text-sm text-ink-secondary">
          {name} comes off the inventory list, and it stops appearing in low-stock warnings.
        </p>
        <p className="mt-2 text-sm text-ink-secondary">
          Every movement stays in the ledger. Nothing that was received, donated, cooked or written
          off is erased, and the history goes on being readable. You can start tracking {name}{" "}
          again later.
        </p>

        {error && (
          <div className="mt-4">
            <ErrorNotice error={error} />
          </div>
        )}

        <div className="mt-6 flex flex-wrap items-center justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
          <Button type="button" variant="danger" onClick={stop} busy={busy} disabled={busy}>
            Stop tracking it
          </Button>
        </div>
      </div>
    </div>
  );
}
