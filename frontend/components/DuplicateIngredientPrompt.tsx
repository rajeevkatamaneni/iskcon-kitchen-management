"use client";

import { useEffect, useId, useRef, useState } from "react";
import { Button } from "@/components/ds/Button";
import { ApiError } from "@/lib/api";

/**
 * "Did you mean Curd?" (R-DUP-2, T-251) — the one prompt every place that creates or renames an
 * ingredient shows when the server says the name looks like one the temple already has.
 *
 * <p><strong>Why it exists.</strong> Duplicate ingredients split stock, prices and shopping-list
 * lines: "Curd", "Curd, fresh", "Curd, sour" and "Curd, whisked" were four ingredients, so the temple
 * held curd in four places and bought it on four lines. The server decides what counts as a
 * lookalike (`IngredientNameMatcher`) and refuses with KMS-400156, naming the ingredient in the
 * error's details; this component is how that refusal is put to a person as a question.
 *
 * <p><strong>The wording is the spec's, word for word</strong> (PROCUREMENT-REQUIREMENTS.md §9A):
 * "Did you mean Curd? Use Curd, or add a preparation note instead.", with the buttons "Use Curd" and
 * "It's a different ingredient". Do not reword them.
 *
 * <p><strong>Two steps, because the spec asks for "a deliberate confirmation".</strong> "It's a
 * different ingredient" does not save. It turns the card into a second question that says what
 * saving it will mean, and only that question's own button re-sends with `confirmDifferent`. The
 * server records the override on the audit trail with the name it looked like.
 *
 * <p><strong>Styled as the app's confirm prompts are</strong> — the planner's `ConfirmLayer`, the
 * kitchen and vendor-status dialogs: the same scrim, the same `modal` card, `alertdialog` because it
 * interrupts to ask something, and Escape answering with the way back. A layer rather than a line at
 * the top of the form because on `/ingredients` the rename happens in a table row that can be two
 * hundred rows down the page, and a question drawn at the top would be one nobody sees. It is a
 * question, not a warning, so nothing on it is amber or red (colour rule, DESIGN_SYSTEM.md v1.13).
 *
 * <p>Both of the first step's buttons are the raised secondary (style E): neither is "the main thing
 * to do" — the person is being asked which of two is true. They sit side by side when they fit and
 * each takes a full row when they do not, so on a phone they are still the same size as each other.
 */

/** The ingredient the server said this one looks like. */
export interface Lookalike {
  id: string;
  name: string;
}

/** KMS-400156, INGREDIENT_LOOKS_LIKE_EXISTING. */
export const LOOKS_LIKE_EXISTING = "KMS-400156";

/**
 * The lookalike a refused save named, or null when the failure was anything else.
 *
 * <p>The server carries it in `fieldErrors` — `ApplicationException`'s narrow `details` channel —
 * as `existingIngredientId` and `existingIngredientName`. Both must be there: a prompt that cannot
 * say which Curd, or cannot send "Use Curd" anywhere, falls back to the ordinary error notice.
 */
export function lookalikeFrom(error: unknown): Lookalike | null {
  if (!(error instanceof ApiError) || error.code !== LOOKS_LIKE_EXISTING) return null;
  const fields = error.byField();
  const id = fields.existingIngredientId;
  const name = fields.existingIngredientName;
  return id && name ? { id, name } : null;
}

export function DuplicateIngredientPrompt({
  candidate,
  existing,
  busy = false,
  onUse,
  onDifferent,
  onDismiss,
}: {
  /** The name the person typed. */
  candidate: string;
  existing: Lookalike;
  /** The confirmed save is in flight. */
  busy?: boolean;
  /** "Use Curd". What that means is the screen's to decide. */
  onUse: () => void;
  /** Confirmed as different: the screen re-sends with `confirmDifferent: true`. */
  onDifferent: () => void;
  /** Escape, or "Go back" twice: back to the form with what was typed still in it. */
  onDismiss: () => void;
}) {
  const [confirming, setConfirming] = useState(false);
  const titleId = useId();
  const bodyId = useId();
  const card = useRef<HTMLDivElement>(null);

  // Focus goes to the first button of whichever step is showing, so the keyboard lands inside the
  // question — and on the confirm step it lands on "Go back", never on the act.
  useEffect(() => {
    card.current?.querySelector<HTMLButtonElement>("button")?.focus();
  }, [confirming]);

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key !== "Escape") return;
      // One step back at a time: out of the confirmation to the question, then out of the question.
      if (confirming) setConfirming(false);
      else onDismiss();
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [confirming, onDismiss]);

  return (
    <div
      role="alertdialog"
      aria-modal="true"
      aria-labelledby={titleId}
      aria-describedby={bodyId}
      className="fixed inset-0 z-[60] flex items-center justify-center bg-ink/40 px-4"
    >
      <div ref={card} className="modal w-full max-w-prose px-6 py-6 sm:px-8 sm:py-7">
        {confirming ? (
          <>
            <h2 id={titleId} className="break-words text-lg text-ink">
              Keep “{candidate}” separate from {existing.name}?
            </h2>
            <p id={bodyId} className="mt-2 break-words text-sm text-ink-secondary">
              It will have its own stock, prices and orders. Your choice is recorded in the audit
              log.
            </p>
            <div className="mt-6 flex flex-wrap items-stretch justify-end gap-3">
              <Button
                variant="secondary"
                className="grow text-center sm:grow-0"
                disabled={busy}
                onClick={() => setConfirming(false)}
              >
                Go back
              </Button>
              <Button className="grow text-center sm:grow-0" busy={busy} onClick={onDifferent}>
                Keep it separate
              </Button>
            </div>
          </>
        ) : (
          <>
            <h2 id={titleId} className="break-words text-lg text-ink">
              Did you mean {existing.name}?
            </h2>
            <p id={bodyId} className="mt-2 break-words text-sm text-ink-secondary">
              Use {existing.name}, or add a preparation note instead.
            </p>
            <div className="mt-6 flex flex-wrap items-stretch justify-end gap-3">
              <Button variant="secondary" className="grow text-center sm:grow-0" onClick={onUse}>
                Use {existing.name}
              </Button>
              <Button
                variant="secondary"
                className="grow text-center sm:grow-0"
                onClick={() => setConfirming(true)}
              >
                It’s a different ingredient
              </Button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
