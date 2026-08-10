import type { ReactNode } from "react";

/**
 * A labelled form field with its error message.
 *
 * <p>Label above rather than beside, always visible rather than a placeholder. Placeholder-only
 * labels vanish the moment someone types, which is exactly when a person filling in an
 * unfamiliar form most needs to check what they are answering.
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

export function Field({ id, label, hint, error, required, children }: FieldProps) {
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const describedBy = [hintId, errorId].filter(Boolean).join(" ") || undefined;

  return (
    <div>
      <label htmlFor={id} className="block text-sm font-medium text-ink">
        {label}
        {required && <span className="ml-1 text-ink-muted">(required)</span>}
      </label>

      {hint && (
        <p id={hintId} className="mt-1 text-sm text-ink-secondary">
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
        <p id={errorId} className="mt-1.5 text-sm text-danger">
          {error}
        </p>
      )}
    </div>
  );
}
