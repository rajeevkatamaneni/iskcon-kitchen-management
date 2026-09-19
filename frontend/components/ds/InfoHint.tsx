"use client";

import { useEffect, useId, useRef, useState } from "react";

import { Tooltip } from "@/components/ds/Tooltip";

/**
 * The small "i" beside a field's label, holding the sentence that used to sit under the box.
 *
 * <p>Asked for on 2026-09-04: the sub-text under every control was making forms tall and noisy, and
 * the guidance that genuinely earns its place should be there when somebody wants it and out of the
 * way when they do not.
 *
 * <p><strong>It is a button, not a hover target.</strong> The obvious build — a span that reveals
 * text on `:hover` — fails three ways at once, and this project has already rejected two of them:
 * there is no hover on a phone or a tablet, a keyboard user never reaches it, and a control that
 * only announces itself under the pointer is the exact complaint `OUTSTANDING_BUILD_LIST` P1 raised
 * against the planner's buttons. So this takes focus, opens on hover *and* focus *and* tap, closes
 * on Escape, and carries `aria-describedby` — all of which {@link Tooltip} already did for the
 * library recipe's Edit button, which is why this wraps it rather than reinventing it.
 *
 * <p>`type="button"` is load-bearing: these sit inside forms, and a bare button submits.
 */
export function InfoHint({ text, label }: { text: string; label?: string }) {
  const hidden = useHiddenWithItsHeader();
  return (
    // `contents`: this span draws no box of its own. It is here only so the hook has an element to
    // look up from, which it needs to keep while the button itself is gone.
    <span ref={hidden.ref} className="contents">
      {!hidden.gone && (
        <Tooltip text={text}>
          <button
            type="button"
            // The label names the field so a screen reader hears "more about Acquired on" rather than
            // sixty identical "more information" buttons down one form.
            //
            // The cost, which is worth knowing before this goes on fifty screens: the button's
            // accessible name contains the field's name, so `getByLabelText(/acquired on/i)` now
            // matches the control *and* this button. Tests on a hinted field need a `{ selector }`.
            // The alternative — a generic "More information" — keeps the queries simple and makes the
            // form read as a row of anonymous buttons to anyone using a screen reader, which is a worse
            // trade for the people it is meant to help.
            aria-label={label ? `More about ${label}` : "More information"}
            className="inline-flex h-5 w-5 items-center justify-center rounded-full border border-hairline-strong text-[11px] font-semibold leading-none text-ink-secondary transition-colors duration-state hover:bg-raised hover:text-ink"
          >
            i
          </button>
        </Tooltip>
      )}
    </span>
  );
}

/**
 * Whether this "i" is in a table heading that is hidden right now, and so must not exist at all.
 *
 * <p>Below 1024px a ruled table (`.kms-table`) and an entry grid (`.kms-entry-grid`) turn each row
 * into a card, and their heading row is hidden the way `sr-only` hides things: `position: absolute`,
 * 1px, `clip: rect(0,0,0,0)` (`app/globals.css`). That is right for heading *text*, which a screen
 * reader should still find. It is wrong for a *button*: it still takes a Tab, and the keyboard's focus
 * then lands on a 1px clipped box that nobody can see (VERIFY-A, defect 7, measured with
 * `document.activeElement` on the vendor page at 390). So an "i" in such a heading is not rendered
 * while the heading is hidden: out of the tab order and out of the accessibility tree together,
 * rather than left reachable for a screen reader but not a sighted keyboard user, which is the
 * mismatch that makes a focus stop invisible in the first place.
 *
 * <p>Not rendering it loses the hint on a phone, so a screen that wants it there shows it once on the
 * card view by other means (the vendor page puts one "Lead time (days)" line above its cards; T-292).
 *
 * <p><strong>Why script, not a Tailwind class.</strong> A `max-lg:[thead_&]:hidden` class would do
 * the same with no effect hook, but it can only be checked in a real browser: jsdom applies no
 * stylesheet and no media query, so the test would assert a class name and prove nothing. This
 * reads the same breakpoint the stylesheet and the table fitter use, `(min-width: 1024px)`, and
 * where there is no `matchMedia` (jsdom, unless a test gives it one) it leaves the button alone.
 * Rendered on the server it is present, and on a phone it goes on the first effect, before anybody
 * can Tab to it; the heading it sits in is invisible throughout, so nothing flickers.
 */
function useHiddenWithItsHeader() {
  const ref = useRef<HTMLSpanElement | null>(null);
  const [gone, setGone] = useState(false);
  useEffect(() => {
    const el = ref.current;
    if (!el || typeof window.matchMedia !== "function") return;
    if (!el.closest(".kms-table > thead, .kms-entry-grid > thead")) return;
    const wide = window.matchMedia("(min-width: 1024px)");
    const apply = () => setGone(!wide.matches);
    apply();
    wide.addEventListener?.("change", apply);
    return () => wide.removeEventListener?.("change", apply);
  }, []);
  return { ref, gone };
}

/**
 * A field: its label, its "i", and its control.
 *
 * <p>The control is a child render function taking the id it must carry, and that is not ceremony.
 * The first build wrapped everything in a `<label>` with the button inside it, which reads fine and
 * is quietly broken: a `<label>`'s control is its **first labelable descendant**, so the button
 * became the labelled thing and the input stopped being reachable by its own name. Clicking the word
 * *Acquired on* focused the "i" rather than the date box, and a test caught it before it reached
 * fifty screens. An explicit `htmlFor` cannot make that mistake.
 */
export function HintedField({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: (id: string) => React.ReactNode;
}) {
  const id = useId();
  return (
    <div className="flex flex-col gap-1 text-sm text-ink-secondary">
      <span className="pl-field-inset flex items-center gap-1.5 font-medium text-ink">
        <label htmlFor={id}>{label}</label>
        {hint && <InfoHint text={hint} label={label} />}
      </span>
      {children(id)}
    </div>
  );
}
