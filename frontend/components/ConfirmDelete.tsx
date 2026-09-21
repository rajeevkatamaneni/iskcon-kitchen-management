"use client";

import { useEffect, useId, useState } from "react";

import { Button } from "@/components/ds/Button";
import { ErrorNotice } from "@/components/ErrorNotice";
import { toApiError, type ApiError } from "@/lib/api";

/**
 * Asks before a row is deleted, and is the only place that asks.
 *
 * <p>Rajeev, 2026-09-21, on swapping row Delete buttons for a trash can: *"I think it will look MUCH
 * nicer and cleaner than a big old button"*. Five of the eight row deletes had no confirmation at all
 * — glossary, ingredients, supplies, a vendor's supplies and a volunteer's shifts all removed the
 * thing on the first press. An icon is easier to hit by accident than a button with a word in it, so
 * the icon and this dialog ship together; the icon alone would make a live hazard worse. It is the
 * same fault he called unacceptable on the planner's preparation Cancel (build list P5).
 *
 * <p><strong>Why a shared component and not a fifth bespoke one.</strong> `kitchens` already had
 * `DeleteKitchen`, written for itself, and four more screens each writing their own would be five
 * wordings of the same question drifting apart — the failure `PeriodNav` was built to end. Kitchens
 * keeps its own, deliberately: it offers *Archive it instead* when the kitchen is in use, which is a
 * second outcome rather than a second wording, and folding that in here would make every caller
 * carry a branch it does not have.
 *
 * <p><strong>What the caller must supply.</strong> `name` is the thing in the reader's words, and it
 * is what the heading asks about — "Delete Toor Dal?", never "Delete this item?". `consequence` says
 * what goes with it, because a person deciding needs to know what they lose, and every one of these
 * is permanent. There is no generic fallback for either: a dialog that cannot say what it is about
 * to destroy has no business destroying it.
 *
 * <p><strong>Ways out.</strong> Escape, the backdrop and Cancel, per the layer rule `RecipePeek`
 * states — a layer with one way out is a trap. Focus lands on Cancel rather than on the delete, so a
 * stray Return cancels instead of destroying, and goes back where it came from on close.
 */
export function ConfirmDelete({
  name,
  consequence,
  confirmLabel = "Delete",
  onConfirm,
  onDone,
  onCancel,
}: {
  /** The thing being deleted, as the screen names it. Goes straight into the heading. */
  name: string;
  /** One sentence: what this removes, and that it cannot be undone. */
  consequence: string;
  /** The word on the destructive button, where "Delete" is not what the screen calls it. */
  confirmLabel?: string;
  /** Does the deletion. Rejects like any api call; its refusal is shown here rather than behind. */
  onConfirm: () => Promise<unknown>;
  /** Deleted, and the caller should reload. */
  onDone: () => void;
  /** Escape, the backdrop or Cancel. */
  onCancel: () => void;
}) {
  const titleId = useId();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    // Where focus was, so it goes back there — the reader was on the trash can of a particular row
    // and that row is where they still are. Focus moves onto Cancel through `autoFocus` below
    // rather than a ref, because `Button` does not forward one.
    const returnTo = document.activeElement as HTMLElement | null;

    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") onCancel();
    }
    document.addEventListener("keydown", onKey);
    const previous = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = previous;
      returnTo?.focus?.();
    };
  }, [onCancel]);

  async function confirm() {
    setBusy(true);
    setError(null);
    try {
      await onConfirm();
      onDone();
    } catch (e) {
      // Shown here, not on the page behind: the reader is looking at this dialog, and a refusal
      // that appears under a layer is a refusal nobody reads.
      setError(toApiError(e, `We couldn’t delete ${name}.`));
      setBusy(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 px-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby={titleId}
      onClick={(event) => {
        if (event.target === event.currentTarget && !busy) onCancel();
      }}
    >
      <div className="modal w-full max-w-prose px-8 py-7">
        <h2 id={titleId} className="text-lg text-danger">
          {confirmLabel} {name}?
        </h2>
        <p className="mt-2 text-sm text-ink-secondary">{consequence}</p>

        {error && (
          <div className="mt-4">
            <ErrorNotice error={error} />
          </div>
        )}

        <div className="mt-6 flex flex-wrap items-center justify-end gap-3">
          {/* Focus lands here, not on the delete, so a stray Return cancels rather than destroys. */}
          <Button autoFocus type="button" variant="secondary" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
          <Button type="button" variant="danger" onClick={confirm} disabled={busy}>
            {confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  );
}
