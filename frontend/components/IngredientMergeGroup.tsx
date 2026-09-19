"use client";

import { useEffect, useId, useRef, useState } from "react";
import { Button } from "@/components/ds/Button";
import { ErrorNotice } from "@/components/ErrorNotice";
import { InlineNotice } from "@/components/ds/InlineNotice";
import {
  api,
  ApiError,
  toApiError,
  type IngredientView,
  type MergeGroupInput,
  type MergePreviewView,
  type MergeProposalView,
  type MergeResultView,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { quantity, readableRate, unitLabel } from "@/lib/format";
import {
  RULED_TABLE,
  THEAD,
  TR,
  TH_LEAD,
  TD_LEAD,
  TH_PRIMARY,
  TD_PRIMARY,
  TH_SECOND,
  TD_SECOND,
  TH_FIXED,
  TD_FIXED,
  TD_FIXED_NUM,
  TH_ACTIONS_FIXED,
  TD_ACTIONS_FIXED,
  ACTIONS_ROW,
} from "@/components/ds/table";

/**
 * One proposed merge group on `/ingredients/merge` (R-DUP-3, T-276): what it would merge, the
 * admin's edits to it, the preview, and the merge itself.
 *
 * <p><strong>What the admin can change, and why each edit is a change to the payload and nothing
 * else.</strong> The server (T-270) accepts any group — "keep this one, merge these into it, with
 * these notes" — so every edit here is an edit to that one {@link MergeGroupInput}:
 * <ul>
 *   <li><em>Which one is kept</em> — the radio in the first column. The one that was kept joins the
 *       merged list with no note of its own (`null`: the server takes the note from its name, which
 *       for a base name like "Curd" is none).</li>
 *   <li><em>Take one out</em> — it simply leaves the list and is not touched by the merge.</li>
 *   <li><em>The preparation note</em> — the box on each merged row. It opens holding the proposal's
 *       note ("sour"); a blank box is sent as `""`, which the server reads as "no note". T-270's
 *       proof asks for exactly that: the proposal's note back, or `""` when it was cleared.</li>
 *   <li><em>Add another by hand</em> — the picker under the table. The server does not propose it,
 *       so there is no note to open with: it is sent as `null` (the server splits its name, as it
 *       does for a proposal) until the first preview comes back with that note, which then fills the
 *       box so what is shown is what will be sent.</li>
 * </ul>
 *
 * <p><strong>Why the preview is asked for rather than fetched on every keystroke.</strong> A proposal
 * list can hold dozens of groups, and a preview is a count over four tables. So it is a button, and
 * any edit clears the preview it made, because a preview of a different group is a preview of
 * nothing. The Merge button exists only beside a preview of exactly what it will send.
 *
 * <p><strong>The two things that stop a merge are the two the server refuses for</strong>: a unit
 * in another kind (KMS-400171 — shown in red, because nothing the admin can choose here makes it
 * mergeable except taking that ingredient out), and a vendor with two list prices (KMS-400172 —
 * shown as a plain question, because it is a choice rather than a fault). The server refuses both
 * anyway; the screen asks first so the admin is not sent to an error to find out.
 *
 * <p><strong>The confirmation is deliberate because a merge cannot be undone.</strong> The merged-away
 * ingredients are deleted (their names live on as aliases of the kept one). It is the app's confirm
 * layer — the scrim and `modal` card of `DuplicateIngredientPrompt` and the planner's confirm — with
 * focus landing on "Go back", never on the act, and Escape meaning go back.
 */

/** A row of the group: one ingredient, and the note it would carry onto recipe lines if merged. */
export interface MergeMember {
  ingredientId: string;
  name: string;
  unit: string;
  /**
   * The note sent for this ingredient. A string is sent as typed (`""` = no note); `null` asks the
   * server for the note its name implies — only ever for one added by hand or the one that was kept.
   */
  note: string | null;
  /** Null for one added by hand, until the preview has counted it. */
  recipeLineCount: number | null;
  /** On hand in this ingredient's own unit; null until counted, as above. */
  onHand: number | null;
  supplyCount: number | null;
}

/** A group being worked on. `key` is fixed at the proposal's kept id, so React keeps its state. */
export interface MergeDraft {
  key: string;
  keepId: string;
  members: MergeMember[];
}

export function draftFromProposal(p: MergeProposalView): MergeDraft {
  return {
    key: p.keep.ingredientId,
    keepId: p.keep.ingredientId,
    members: [p.keep, ...p.merge].map((c, i) => ({
      ingredientId: c.ingredientId,
      name: c.name,
      unit: c.unit,
      // The kept one carries no note; a proposed one opens with the note it would move.
      note: i === 0 ? null : c.preparationNote ?? "",
      recipeLineCount: c.recipeLineCount,
      onHand: c.onHand,
      supplyCount: c.supplyCount,
    })),
  };
}

/** Exactly what goes to the server for this group. Exported for the test. */
export function mergeInput(
  draft: MergeDraft,
  choices: Record<string, string> = {}
): MergeGroupInput {
  const input: MergeGroupInput = {
    keepIngredientId: draft.keepId,
    merge: draft.members
      .filter((m) => m.ingredientId !== draft.keepId)
      .map((m) => ({ ingredientId: m.ingredientId, preparationNote: m.note })),
  };
  const chosen = Object.entries(choices).map(([vendorId, keepPriceFromIngredientId]) => ({
    vendorId,
    keepPriceFromIngredientId,
  }));
  // Absent rather than empty when there is nothing to choose: the key means "these were asked".
  return chosen.length > 0 ? { ...input, supplyPriceChoices: chosen } : input;
}

/** "Curd, fresh; Curd, sour and Curd, whisked" — the names carry commas, so a semicolon separates them. */
function nameList(names: string[]): string {
  if (names.length <= 1) return names[0] ?? "";
  return `${names.slice(0, -1).join("; ")} and ${names[names.length - 1]}`;
}

export function IngredientMergeGroup({
  draft,
  catalogue,
  busy,
  onChange,
  onBusy,
  onMerged,
}: {
  draft: MergeDraft;
  /** Everything the admin may add by hand. Ingredients already in this group are left out here. */
  catalogue: IngredientView[];
  /** Another group is merging. One group at a time: the server runs one transaction per group. */
  busy: boolean;
  onChange: (next: MergeDraft) => void;
  onBusy: (on: boolean) => void;
  onMerged: (draft: MergeDraft, result: MergeResultView) => void;
}) {
  const { getToken } = useAuth();
  const headingId = useId();
  const pickerId = useId();
  const keep = draft.members.find((m) => m.ingredientId === draft.keepId)!;
  const merging = draft.members.filter((m) => m.ingredientId !== draft.keepId);

  const [preview, setPreview] = useState<MergePreviewView | null>(null);
  const [choices, setChoices] = useState<Record<string, string>>({});
  const [checking, setChecking] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [adding, setAdding] = useState("");
  // Button is a plain function component (React 18, no forwardRef), so focus is found through a wrapper.
  const mergeRow = useRef<HTMLDivElement>(null);
  const focusMerge = () => mergeRow.current?.querySelector<HTMLButtonElement>("button")?.focus();

  /** Every edit goes through here: the preview it made describes a group that no longer exists. */
  function edit(next: MergeDraft) {
    setPreview(null);
    setChoices({});
    setError(null);
    onChange(next);
  }

  function chooseKeep(id: string) {
    edit({
      ...draft,
      keepId: id,
      // The newly kept one's note is not sent; the one that was kept joins with none of its own.
      members: draft.members.map((m) =>
        m.ingredientId === draft.keepId ? { ...m, note: null } : m
      ),
    });
  }

  function takeOut(id: string) {
    edit({ ...draft, members: draft.members.filter((m) => m.ingredientId !== id) });
  }

  function setNote(id: string, note: string) {
    edit({ ...draft, members: draft.members.map((m) => (m.ingredientId === id ? { ...m, note } : m)) });
  }

  function add(id: string) {
    const ing = catalogue.find((i) => i.id === id);
    if (!ing) return;
    setAdding("");
    edit({
      ...draft,
      members: [
        ...draft.members,
        // Its counts are not known until the preview fills them in, so they read "—", not 0.
        { ingredientId: ing.id, name: ing.name, unit: ing.unit, note: null, recipeLineCount: null, onHand: null, supplyCount: null },
      ],
    });
  }

  async function check() {
    setChecking(true);
    setError(null);
    try {
      const view = await api.previewMerge(mergeInput(draft), await getToken());
      setPreview(view);
      setChoices({});
      // The preview knows each ingredient's counts, and the note the server would use for any row
      // sent as `null`. Filling them in means the box shows what will be sent.
      const byId = new Map([view.keep, ...view.merge].map((c) => [c.ingredientId, c]));
      onChange({
        ...draft,
        members: draft.members.map((m) => {
          const c = byId.get(m.ingredientId);
          if (!c) return m;
          return {
            ...m,
            recipeLineCount: c.recipeLineCount,
            onHand: c.onHand,
            supplyCount: c.supplyCount,
            note: m.ingredientId === draft.keepId || m.note !== null ? m.note : c.preparationNote ?? "",
          };
        }),
      });
    } catch (e) {
      setError(toApiError(e, "We couldn’t preview that merge."));
    } finally {
      setChecking(false);
    }
  }

  async function merge() {
    onBusy(true);
    setError(null);
    try {
      const result = await api.mergeIngredients(mergeInput(draft, choices), await getToken());
      setConfirming(false);
      onMerged(draft, result);
    } catch (e) {
      setConfirming(false);
      setError(toApiError(e, "We couldn’t merge those ingredients."));
      focusMerge();
    } finally {
      onBusy(false);
    }
  }

  const conflicts = preview?.conflicts ?? [];
  const unchosen = conflicts.filter((c) => !choices[c.vendorId]).length;
  const blocked = !preview || merging.length === 0 || preview.unitProblem !== null || unchosen > 0;
  const linesMoving = (preview?.merge ?? []).reduce((n, c) => n + c.recipeLineCount, 0);
  const suppliesMoving = (preview?.merge ?? []).reduce((n, c) => n + c.supplyCount, 0);
  const inGroup = new Set(draft.members.map((m) => m.ingredientId));
  const addable = catalogue.filter((i) => !inGroup.has(i.id));

  return (
    <section aria-labelledby={headingId} className="mt-10 first:mt-0">
      {/*
        The spec's own shape for a group: "Curd, fresh / Curd, sour / Curd, whisked → Curd". A slash
        between names because the names carry commas. The arrow is read as "into".
      */}
      <h2 id={headingId} className="mb-3 break-words text-lg">
        {merging.length > 0 ? merging.map((m) => m.name).join(" / ") : "Nothing to merge"}
        <span aria-hidden="true"> → </span>
        <span className="sr-only"> into </span>
        {keep.name}
      </h2>

      <div className="table-wrap overflow-x-auto">
        <table className={RULED_TABLE}>
          <thead className={THEAD}>
            <tr>
              <th className={TH_LEAD}>Keep</th>
              <th className={TH_PRIMARY}>Ingredient</th>
              <th className={TH_SECOND}>Preparation note</th>
              <th className={TH_FIXED}>Unit</th>
              <th className={TH_FIXED}>Recipe lines</th>
              <th className={TH_FIXED}>On hand</th>
              <th className={TH_ACTIONS_FIXED}><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {draft.members.map((m) => {
              const kept = m.ingredientId === draft.keepId;
              return (
                // Baseline, not TR's top: a row holding a 44px note box and a button would
                // otherwise print the name ~10px above the words in the box beside it (measured on
                // the T-276 render at 1280). The card layout below 1024 aligns on baseline already.
                <tr key={m.ingredientId} className={`${TR} !align-baseline`}>
                  <td className={TD_LEAD} data-label="Keep">
                    <input
                      type="radio"
                      name={`keep-${draft.key}`}
                      aria-label={`Keep ${m.name}`}
                      checked={kept}
                      disabled={busy}
                      onChange={() => chooseKeep(m.ingredientId)}
                      className="h-5 w-5 accent-accent align-middle"
                    />
                  </td>
                  <td className={TD_PRIMARY}>{m.name}</td>
                  {/*
                    The kept one has no note to move — its own lines keep whatever they have — so
                    its cell is empty (and on a phone's card, left out).
                  */}
                  <td className={TD_SECOND} data-label={kept ? undefined : "Note"}>
                    {kept ? (
                      ""
                    ) : (
                      <input
                        value={m.note ?? ""}
                        aria-label={`Preparation note for ${m.name}`}
                        disabled={busy}
                        maxLength={100}
                        onChange={(e) => setNote(m.ingredientId, e.target.value)}
                        // Sized to its text, as the table rule asks of every box ("a box must be wide
                        // enough to show the figure in it"): a note like "desiccated powder,
                        // unsweetened" was measured cut off at 186px in a fixed-width box (T-276).
                        className="min-h-touch min-w-40 max-w-full rounded-control border border-hairline px-2 [field-sizing:content]"
                      />
                    )}
                  </td>
                  <td className={`${TD_FIXED} text-ink-secondary`}>{unitLabel(m.unit)}</td>
                  <td className={TD_FIXED_NUM} data-label="Recipe lines">{m.recipeLineCount ?? "—"}</td>
                  <td className={TD_FIXED_NUM} data-label="On hand">{quantity(m.onHand, m.unit)}</td>
                  <td className={TD_ACTIONS_FIXED}>
                    {kept ? (
                      ""
                    ) : (
                      <div className={ACTIONS_ROW}>
                        <Button
                          variant="secondary"
                          size="sm"
                          disabled={busy}
                          aria-label={`Take ${m.name} out of this group`}
                          onClick={() => takeOut(m.ingredientId)}
                        >
                          Take out
                        </Button>
                      </div>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {/*
        Adding by hand and previewing sit on one row: they fit side by side at every width from a
        phone up, and wrap only when they don't. The picker is the shopping list's native select of
        the catalogue, not ItemCombobox — see the proof for why.
      */}
      <div className="mt-4 flex flex-wrap items-end justify-between gap-3">
        <div className="flex flex-wrap items-end gap-3">
          <label htmlFor={pickerId} className="flex flex-col gap-1 text-sm">
            <span className="font-medium text-ink">Add an ingredient to this group</span>
            <select
              id={pickerId}
              value={adding}
              disabled={busy}
              onChange={(e) => setAdding(e.target.value)}
              className="min-h-touch w-64 max-w-full rounded-control border border-hairline px-3"
            >
              <option value="">Choose…</option>
              {addable.map((i) => (
                <option key={i.id} value={i.id}>{i.name}</option>
              ))}
            </select>
          </label>
          <Button variant="secondary" disabled={busy || adding === ""} onClick={() => add(adding)}>
            Add
          </Button>
        </div>
        <Button variant="secondary" busy={checking} disabled={busy || merging.length === 0} onClick={check}>
          Preview merge
        </Button>
      </div>

      {merging.length === 0 && (
        <p className="mt-3 text-sm text-ink-secondary">
          Nothing is left to merge into {keep.name}. Add an ingredient to this group to merge it.
        </p>
      )}

      {error && (
        <div className="mt-4">
          <ErrorNotice error={error} />
          <RefusalDetail error={error} />
        </div>
      )}

      {preview && (
        <div className="card mt-4 p-6" aria-label={`Preview of merging into ${preview.keep.name}`} role="region">
          <dl className="flex flex-wrap gap-x-10 gap-y-3">
            <div>
              <dt className="text-xs text-ink-muted">On hand after</dt>
              <dd className="text-lg tabular-nums text-ink">{quantity(preview.onHandAfter, preview.keep.unit)}</dd>
            </div>
            <div>
              <dt className="text-xs text-ink-muted">Recipe lines moving</dt>
              <dd className="text-lg tabular-nums text-ink">{linesMoving}</dd>
            </div>
            <div>
              <dt className="text-xs text-ink-muted">Vendor supplies moving</dt>
              <dd className="text-lg tabular-nums text-ink">{suppliesMoving}</dd>
            </div>
          </dl>
          <p className="mt-3 text-sm text-ink-secondary">
            Stock, recipes, orders and prices move to {preview.keep.name}.{" "}
            {preview.merge.length === 1 ? "The other name stays" : "The other names stay"} as{" "}
            {preview.merge.length === 1 ? "an alias" : "aliases"}, so searching for{" "}
            {preview.merge.length === 1 ? "it" : "them"} still finds {preview.keep.name}.
          </p>

          {preview.unitProblem && (
            <div className="mt-4">
              <InlineNotice tone="danger" title="These can’t be merged">
                {preview.unitProblem}. Take out the one counted in a different kind of unit.
              </InlineNotice>
            </div>
          )}

          {conflicts.map((c) => (
            <fieldset key={c.vendorId} className="mt-5">
              <legend className="text-sm font-medium text-ink">
                {c.vendorName} has a different price for {c.prices.length === 2 ? "two" : c.prices.length} of these. Which price should it keep?
              </legend>
              <div className="mt-2 flex flex-wrap gap-x-6 gap-y-2">
                {c.prices.map((p) => (
                  <label key={p.ingredientId} className="flex min-h-touch items-center gap-2 text-sm text-ink">
                    <input
                      type="radio"
                      name={`price-${draft.key}-${c.vendorId}`}
                      checked={choices[c.vendorId] === p.ingredientId}
                      disabled={busy}
                      onChange={() => setChoices({ ...choices, [c.vendorId]: p.ingredientId })}
                      className="h-5 w-5 accent-accent"
                    />
                    <span>
                      {/* The shared readableRate (T-293, VERIFY-A defect 3): "₹300 / Kg", as the vendor
                          page prints the same price, never "₹0.30/gm". */}
                      {readableRate(p.listPrice, p.unit)}
                      {p.packLabel ? ` · ${p.packLabel}` : ""}
                      <span className="text-ink-secondary">{` · ${p.ingredientName}`}</span>
                    </span>
                  </label>
                ))}
              </div>
            </fieldset>
          ))}

          <div ref={mergeRow} className="mt-6 flex flex-wrap items-center gap-3">
            <Button disabled={busy || blocked} onClick={() => setConfirming(true)}>
              Merge into {preview.keep.name}
            </Button>
            {!preview.unitProblem && unchosen > 0 && (
              <span className="text-sm text-ink-secondary">
                Choose a price for {unchosen === 1 ? "the vendor" : `each of the ${unchosen} vendors`} above first.
              </span>
            )}
          </div>
        </div>
      )}

      {confirming && preview && (
        <ConfirmMerge
          keepName={preview.keep.name}
          names={preview.merge.map((c) => c.name)}
          busy={busy}
          onCancel={() => {
            setConfirming(false);
            focusMerge();
          }}
          onConfirm={merge}
        />
      )}
    </section>
  );
}

/**
 * The part of a refusal's details worth a sentence: the two units (KMS-400171) or the vendor whose
 * price needs choosing (KMS-400172). The notice above already says what failed and what to do; this
 * names *which*, from the server's own details rather than guessed from the group.
 */
function RefusalDetail({ error }: { error: ApiError }) {
  const f = error.byField();
  if (error.code === "KMS-400171" && f.keptIngredient && f.otherIngredient) {
    return (
      <p className="mt-2 text-sm text-ink-secondary">
        {f.keptIngredient} is in {f.keptUnit}, {f.otherIngredient} is in {f.otherUnit}.
      </p>
    );
  }
  if (error.code === "KMS-400172" && f.vendorName) {
    return <p className="mt-2 text-sm text-ink-secondary">Vendor: {f.vendorName}. Preview the merge again to choose.</p>;
  }
  return null;
}

/**
 * "Merge into Curd?" — the deliberate step before an act that cannot be undone.
 */
function ConfirmMerge({
  keepName,
  names,
  busy,
  onCancel,
  onConfirm,
}: {
  keepName: string;
  names: string[];
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const titleId = useId();
  const bodyId = useId();
  const card = useRef<HTMLDivElement>(null);

  // Focus lands on "Go back", the first button, never on the act.
  useEffect(() => {
    card.current?.querySelector<HTMLButtonElement>("button")?.focus();
  }, []);

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape" && !busy) onCancel();
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [busy, onCancel]);

  return (
    <div
      role="alertdialog"
      aria-modal="true"
      aria-labelledby={titleId}
      aria-describedby={bodyId}
      className="fixed inset-0 z-[60] flex items-center justify-center bg-ink/40 px-4"
    >
      <div ref={card} className="modal w-full max-w-prose px-6 py-6 sm:px-8 sm:py-7">
        <h2 id={titleId} className="break-words text-lg text-ink">
          Merge {names.length === 1 ? "1 ingredient" : `${names.length} ingredients`} into {keepName}?
        </h2>
        <p id={bodyId} className="mt-2 break-words text-sm text-ink-secondary">
          {nameList(names)} will be deleted, and everything that used {names.length === 1 ? "it" : "them"} will
          use {keepName}. This can’t be undone.
        </p>
        <div className="mt-6 flex flex-wrap items-stretch justify-end gap-3">
          <Button variant="secondary" className="grow text-center sm:grow-0" disabled={busy} onClick={onCancel}>
            Go back
          </Button>
          <Button variant="danger" className="grow text-center sm:grow-0" busy={busy} onClick={onConfirm}>
            Merge into {keepName}
          </Button>
        </div>
      </div>
    </div>
  );
}
