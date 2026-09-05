"use client";

import { useId } from "react";

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
  return (
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
        className="inline-flex h-5 w-5 items-center justify-center rounded-full border border-hairline-strong text-[11px] font-semibold leading-none text-ink-secondary transition-colors duration-state hover:bg-raised hover:text-ink focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
      >
        i
      </button>
    </Tooltip>
  );
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
