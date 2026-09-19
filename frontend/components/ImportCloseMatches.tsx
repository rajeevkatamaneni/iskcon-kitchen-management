"use client";

import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Button } from "@/components/ds/Button";
import { ErrorNotice } from "@/components/ErrorNotice";
import {
  ApiError,
  type ImportCloseMatchDecision,
  type ImportCloseMatchView,
} from "@/lib/api";

/**
 * "Did you mean these ingredients?" — the one screen a library copy shows when some of the recipe's
 * ingredient names are close to, but not the same as, ones the temple already has (Q-11, Rajeev
 * 2026-09-19; T-287).
 *
 * <p><strong>Why it exists.</strong> Until Q-11 was answered the copy decided on its own: a close
 * spelling ("Tomatos" beside the temple's "Tomato, ripe") became a new ingredient, which is how a
 * temple ends up buying one tomato on two shopping-list lines. The copy cannot tell a slip from a
 * genuinely different ingredient, so it now asks — every close match on one screen, before
 * anything is written. Exact matches are never asked about; they are the same ingredient.
 *
 * <p><strong>Each row is the ingredient form's question, word for word</strong> (R-DUP-2,
 * `DuplicateIngredientPrompt`): "Did you mean Tomato, ripe?" with "Use Tomato, ripe" and "It's a
 * different ingredient". The second needs the same deliberate confirmation, in the same words — "Keep
 * “Tomatos” separate from Tomato, ripe?", "Go back", "Keep it separate" — and the server audits the
 * override exactly as it does for the form. What the form's sentence adds ("or add a preparation note
 * instead") is left off here: the copy puts the library's preparation on the line by itself, and the
 * row already shows it ("Tomatos · chopped").
 *
 * <p><strong>Nothing is copied until the person presses "Add to my recipes"</strong>, which stays
 * pressable throughout: pressed with a row unanswered, it says so and puts the keyboard on that row
 * rather than sitting greyed out with no reason given.
 *
 * <p><strong>A real modal dialog.</strong> Rendered into `document.body`, with everything else on the
 * page made `inert` while it is open, so neither a pointer nor a screen reader nor Tab can reach what
 * is behind it. Focus goes to the first row's first button on open, moves to the next unanswered row
 * as each is answered, and returns to whatever opened it on close. Escape backs out of a row's
 * confirmation first, then cancels. Neutral colours throughout: it is a question, not a warning
 * (colour rule, DESIGN_SYSTEM.md).
 */

/** KMS-400156, INGREDIENT_LOOKS_LIKE_EXISTING. */
const LOOKS_LIKE_EXISTING = "KMS-400156";

/**
 * The close matches a refused copy listed, or null when the failure was anything else.
 *
 * <p>For the case where the catalogue changed between the screen asking and the copy being sent (or a
 * caller that copied without asking). The server's error envelope carries details only as
 * field/message pairs, so each match arrives flattened as `closeMatches[i].libraryName`, `.note`
 * (absent when there is none), `.existingIngredientId` and `.existingIngredientName`.
 */
export function closeMatchesFrom(error: unknown): ImportCloseMatchView[] | null {
  if (!(error instanceof ApiError) || error.code !== LOOKS_LIKE_EXISTING) return null;
  const fields = error.byField();
  const out: ImportCloseMatchView[] = [];
  for (let i = 0; fields[`closeMatches[${i}].libraryName`] !== undefined; i++) {
    const at = `closeMatches[${i}]`;
    const id = fields[`${at}.existingIngredientId`];
    const name = fields[`${at}.existingIngredientName`];
    if (!id || !name) return null;
    out.push({
      libraryName: fields[`${at}.libraryName`],
      note: fields[`${at}.note`] ?? null,
      existingIngredientId: id,
      existingIngredientName: name,
    });
  }
  return out.length > 0 ? out : null;
}

type Answer = "use" | "different";

/**
 * The row's heading: the ingredient as the library wrote it — "Tomatos · chopped", "Ginger, peeled".
 *
 * <p>`note` is what the line gets if the person answers "Use" (A-N5, T-297): the preparation split off
 * the name ("chopped"), plus whatever else the library wrote that isn't the existing ingredient's name
 * ("peeled", from "Ginger, peeled"). That second part is still inside `libraryName`, because "It's a
 * different ingredient" creates the name whole, so writing the note after it again would read "Ginger,
 * peeled · peeled". Only the parts not already in the name are added, matched as whole words so that
 * "cut" is never found inside "Coconut".
 */
function asWritten(match: ImportCloseMatchView): string {
  if (!match.note) return match.libraryName;
  const escaped = (part: string) => part.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const inName = (part: string) =>
    new RegExp(`(^|[^\\p{L}\\p{N}])${escaped(part)}($|[^\\p{L}\\p{N}])`, "iu").test(match.libraryName);
  const extra = match.note.split(", ").filter((part) => !inName(part));
  return extra.length > 0 ? `${match.libraryName} · ${extra.join(", ")}` : match.libraryName;
}

/** "Ginger · peeled": the line as it will read if the person answers "Use" (R-DUP-1's display form). */
function asUsed(match: ImportCloseMatchView): string {
  return match.note ? `${match.existingIngredientName} · ${match.note}` : match.existingIngredientName;
}

export function ImportCloseMatches({
  recipeName,
  matches,
  busy = false,
  error = null,
  onAdd,
  onCancel,
}: {
  recipeName: string;
  matches: ImportCloseMatchView[];
  /** The copy is in flight. */
  busy?: boolean;
  /** A refusal of the copy that isn't about close matches, shown inside the dialog. */
  error?: ApiError | null;
  /** Every row answered and "Add to my recipes" pressed. */
  onAdd: (decisions: ImportCloseMatchDecision[]) => void;
  onCancel: () => void;
}) {
  const [answers, setAnswers] = useState<Record<string, Answer>>({});
  const [confirming, setConfirming] = useState<string | null>(null);
  const [missing, setMissing] = useState(false);
  const titleId = useId();
  const bodyId = useId();
  const card = useRef<HTMLDivElement>(null);
  const layer = useRef<HTMLDivElement>(null);
  // Where focus goes after the next render: a row's button, keyed by `data-focus`.
  const [focusTarget, setFocusTarget] = useState<string | null>(null);

  // A fresh list (the server refused with a newer one) starts over.
  useEffect(() => {
    setAnswers({});
    setConfirming(null);
    setMissing(false);
    setFocusTarget(matches.length > 0 ? `use-0` : null);
  }, [matches]);

  useEffect(() => {
    if (!focusTarget) return;
    card.current?.querySelector<HTMLElement>(`[data-focus="${focusTarget}"]`)?.focus();
    setFocusTarget(null);
  }, [focusTarget]);

  // Everything outside the dialog is inert while it is open, and focus goes back to what opened it.
  useLayoutEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    const others = Array.from(document.body.children).filter(
      (el) => el !== layer.current && !el.hasAttribute("inert")
    );
    others.forEach((el) => el.setAttribute("inert", ""));
    return () => {
      others.forEach((el) => el.removeAttribute("inert"));
      opener?.focus?.();
    };
  }, []);

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        if (confirming !== null) {
          const row = matches.findIndex((m) => m.libraryName === confirming);
          setConfirming(null);
          setFocusTarget(`different-${row}`);
        } else if (!busy) {
          onCancel();
        }
        return;
      }
      // Tab stays inside the dialog: `inert` keeps it off the page, and this keeps it off the
      // browser's own chrome between the last button and the first.
      if (event.key === "Tab" && card.current) {
        const stops = Array.from(
          card.current.querySelectorAll<HTMLElement>("button:not([disabled]), [href], [tabindex='0']")
        );
        if (stops.length === 0) return;
        const first = stops[0];
        const last = stops[stops.length - 1];
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault();
          last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault();
          first.focus();
        }
      }
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [confirming, busy, matches, onCancel]);

  /** After a row is answered, the keyboard goes on to the next row that still needs one. */
  function answer(index: number, value: Answer) {
    const next = { ...answers, [matches[index].libraryName]: value };
    setAnswers(next);
    setConfirming(null);
    setMissing(false);
    const after = matches.findIndex((m, i) => i > index && !next[m.libraryName]);
    const before = matches.findIndex((m) => !next[m.libraryName]);
    const target = after >= 0 ? after : before;
    setFocusTarget(target >= 0 ? `use-${target}` : "add");
  }

  function change(index: number) {
    const next = { ...answers };
    delete next[matches[index].libraryName];
    setAnswers(next);
    setFocusTarget(`use-${index}`);
  }

  function add() {
    const unanswered = matches.findIndex((m) => !answers[m.libraryName]);
    if (unanswered >= 0) {
      setMissing(true);
      setConfirming(null);
      setFocusTarget(`use-${unanswered}`);
      return;
    }
    onAdd(
      matches.map((m) =>
        answers[m.libraryName] === "use"
          ? { libraryName: m.libraryName, useIngredientId: m.existingIngredientId, confirmDifferent: false }
          : { libraryName: m.libraryName, useIngredientId: null, confirmDifferent: true }
      )
    );
  }

  const count = matches.length;

  const dialog = (
    <div
      ref={layer}
      className="fixed inset-0 z-[60] flex items-center justify-center bg-ink/40 px-4 py-6"
    >
      <div
        ref={card}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={bodyId}
        className="modal flex max-h-full w-full max-w-prose flex-col px-6 py-6 sm:px-8 sm:py-7"
      >
        <h2 id={titleId} className="break-words text-lg text-ink">
          Did you mean {count === 1 ? "this ingredient" : "these ingredients"}?
        </h2>
        <p id={bodyId} className="mt-2 break-words text-sm text-ink-secondary">
          {count === 1
            ? `An ingredient in ${recipeName} is close to one you already have. Choose which it is before the recipe is added.`
            : `${count} ingredients in ${recipeName} are close to ones you already have. Choose which each one is before the recipe is added.`}
        </p>

        <ul className="mt-5 min-h-0 flex-1 divide-y divide-hairline overflow-y-auto overscroll-contain border-y border-hairline">
          {matches.map((match, i) => {
            const rowId = `${titleId}-row-${i}`;
            const answered = answers[match.libraryName];
            const isConfirming = confirming === match.libraryName;
            return (
              <li key={match.libraryName} role="group" aria-labelledby={rowId} className="py-4">
                <p id={rowId} className="break-words font-medium text-ink">
                  {asWritten(match)}
                </p>

                {answered ? (
                  // One line, never wrapped: the answer's words wrap beside "Change" rather than
                  // pushing it onto a row of its own at the far edge (measured at 390, T-287).
                  <div className="mt-2 flex items-center gap-3">
                    <p className="min-w-0 flex-1 break-words text-sm text-ink-secondary">
                      {answered === "use"
                        ? `Using ${asUsed(match)}.`
                        : `Kept separate. “${match.libraryName}” will be added as a new ingredient.`}
                    </p>
                    <Button
                      variant="ghost"
                      className="shrink-0"
                      data-focus={`change-${i}`}
                      disabled={busy}
                      aria-label={`Change the answer for ${match.libraryName}`}
                      onClick={() => change(i)}
                    >
                      Change
                    </Button>
                  </div>
                ) : isConfirming ? (
                  <div className="mt-2">
                    <p className="break-words text-sm text-ink">
                      Keep “{match.libraryName}” separate from {match.existingIngredientName}?
                    </p>
                    <p className="mt-1 break-words text-sm text-ink-secondary">
                      It will have its own stock, prices and orders. Your choice is recorded in the
                      audit log.
                    </p>
                    <div className="mt-3 flex flex-wrap items-stretch justify-end gap-3">
                      <Button
                        variant="secondary"
                        className="grow text-center sm:grow-0"
                        data-focus={`back-${i}`}
                        onClick={() => {
                          setConfirming(null);
                          setFocusTarget(`different-${i}`);
                        }}
                      >
                        Go back
                      </Button>
                      <Button
                        className="grow text-center sm:grow-0"
                        onClick={() => answer(i, "different")}
                      >
                        Keep it separate
                      </Button>
                    </div>
                  </div>
                ) : (
                  <div className="mt-1">
                    <p className="break-words text-sm text-ink-secondary">
                      Did you mean {match.existingIngredientName}?
                    </p>
                    {/* R-DUP-2 gives the question word for word, so the note is not added to it. What
                        "Use" will put on the recipe is said on its own line, before the answer (A-N5):
                        the note is words the person would otherwise lose without seeing them. */}
                    {match.note && (
                      <p className="mt-1 break-words text-sm text-ink-secondary">
                        The line will read “{asUsed(match)}” if you use it.
                      </p>
                    )}
                    <div className="mt-3 flex flex-wrap items-stretch justify-end gap-3">
                      <Button
                        variant="secondary"
                        className="grow text-center sm:grow-0"
                        data-focus={`use-${i}`}
                        disabled={busy}
                        onClick={() => answer(i, "use")}
                      >
                        Use {match.existingIngredientName}
                      </Button>
                      <Button
                        variant="secondary"
                        className="grow text-center sm:grow-0"
                        data-focus={`different-${i}`}
                        disabled={busy}
                        onClick={() => {
                          setConfirming(match.libraryName);
                          setFocusTarget(`back-${i}`);
                        }}
                      >
                        It’s a different ingredient
                      </Button>
                    </div>
                  </div>
                )}
              </li>
            );
          })}
        </ul>

        {/* Always in the page, so the sentence is announced when it appears rather than lost. */}
        <p role="status" className={missing ? "mt-3 text-sm text-ink" : ""}>
          {missing ? "Choose an answer for every ingredient first." : ""}
        </p>

        {error && (
          <div className="mt-3">
            <ErrorNotice error={error} />
          </div>
        )}

        <div className="mt-4 flex flex-wrap items-stretch justify-end gap-3">
          <Button
            variant="secondary"
            className="grow text-center sm:grow-0"
            disabled={busy}
            onClick={onCancel}
          >
            Cancel
          </Button>
          <Button
            className="grow text-center sm:grow-0"
            data-focus="add"
            busy={busy}
            onClick={add}
          >
            Add to my recipes
          </Button>
        </div>
      </div>
    </div>
  );

  return typeof document === "undefined" ? null : createPortal(dialog, document.body);
}
