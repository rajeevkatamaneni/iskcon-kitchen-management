"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { BUTTON_CLASSES, type ButtonSize, type ButtonVariant } from "./Button";

/**
 * A destination that looks like a button.
 *
 * <p>Separate from {@link Button} because the two are different things to a browser and to a screen
 * reader: one does something here, the other takes you somewhere. Wrapping a `<button>` in a link —
 * which is what this replaces — nests two interactive elements, which is invalid HTML and leaves the
 * link with no accessible name at all.
 */
export function ButtonLink({
  href,
  variant = "primary",
  size = "md",
  icon,
  fullWidth,
  className = "",
  children,
}: {
  href: string;
  variant?: ButtonVariant;
  size?: ButtonSize;
  /** Tabler icon name without the `ti-` prefix. */
  icon?: string;
  fullWidth?: boolean;
  className?: string;
  children?: ReactNode;
}) {
  return (
    <Link href={href} className={BUTTON_CLASSES({ variant, size, fullWidth, className })}>
      {icon && <i className={`ti ti-${icon} text-lg`} aria-hidden="true" />}
      {children}
    </Link>
  );
}
