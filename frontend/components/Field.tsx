import type { ReactNode } from "react";

/**
 * A labelled form field with its hint and its error message.
 *
 * <p>Label above rather than beside, always visible rather than a placeholder. Placeholder-only
 * labels vanish the moment someone types, which is exactly when a person filling in an
 * unfamiliar form most needs to check what they are answering.
 *
 * <p>The label, the hint and the error are all indented by `field-inset` — the 13px that stands
 * between an input's outer edge and the first letter inside it. Set flush left they line up with
 * the box rather than with its contents, so a label floats 13px to the left of the very word it
 * names. One vertical line runs through the label, the value and the note about it.
 *
 * <p>Errors are wired to the input with aria-describedby and aria-invalid, so a screen reader
 * announces the problem rather than leaving it as red text nobody hears.
 */
interface FieldProps {
  id: string;
  label: string;
  hint?: string;
  error?: string;
  required?: boolean;
  children: (props: {
    id: string;
    "aria-invalid": boolean;
    "aria-describedby": string | undefined;
    className: string;
  }) => ReactNode;
}

/** The one label style in the app: a step darker and a step heavier than the hint under it. */
export const FIELD_LABEL = "pl-field-inset text-sm font-medium text-ink";
/** The one hint style. Quieter than the label, indented to the same line. */
export const FIELD_HINT = "pl-field-inset text-sm text-ink-secondary";
/** The one field-error style. */
export const FIELD_ERROR = "pl-field-inset text-sm text-danger";

export function Field({ id, label, hint, error, required, children }: FieldProps) {
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const describedBy = [hintId, errorId].filter(Boolean).join(" ") || undefined;

  return (
    <div>
      <label htmlFor={id} className={`block ${FIELD_LABEL}`}>
        {label}
        {required && <span className="ml-1 text-ink-muted">(required)</span>}
      </label>

      {hint && (
        <p id={hintId} className={`mt-1 ${FIELD_HINT}`}>
          {hint}
        </p>
      )}

      <div className="mt-2">
        {children({
          id,
          "aria-invalid": Boolean(error),
          "aria-describedby": describedBy,
          className: [
            "min-h-touch w-full rounded-sm border bg-canvas px-3 text-base",
            "transition-colors duration-state",
            error ? "border-danger" : "border-hairline-strong",
          ].join(" "),
        })}
      </div>

      {error && (
        <p id={errorId} className={`mt-1.5 ${FIELD_ERROR}`}>
          {error}
        </p>
      )}
    </div>
  );
}
