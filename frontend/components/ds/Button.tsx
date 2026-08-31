"use client";

import type { ButtonHTMLAttributes, ReactNode } from "react";

/**
 * The commit action.
 *
 * <p>Terracotta primary means "the main thing to do on this screen" — one per screen, per the
 * design system's own rule. If two are competing, one of them is a secondary.
 *
 * <p>Ported from the ISKCON Kitchen Design System (Claude Design, 2026-08-14). That kit expresses
 * the same tokens as CSS variables and inline styles; here they are Tailwind classes, because the
 * values are identical and the codebase should have one way of writing a component.
 */

export type ButtonVariant = "primary" | "secondary" | "ghost" | "danger";
export type ButtonSize = "sm" | "md";

const VARIANTS: Record<ButtonVariant, string> = {
  primary:
    // bg-accent is the fill; bg-accent-gradient sits over it and is `none` unless the pack
    // asked for one, so a flat theme is unaffected and a glossy one needs no second variant.
    "bg-accent bg-accent-gradient text-ink-inverse border border-accent hover:bg-accent-hover hover:border-accent-hover",
  secondary: "bg-canvas text-accent-text border border-accent-border hover:bg-accent-bg",
  // A resting border, not a transparent one. Ghost used to be invisible until the pointer touched
  // it, at which point a box appeared around what had read as a line of text — a button pretending
  // not to be one, which is how somebody comes to press something they did not know was pressable
  // (Rajeev, 2026-08-23, on "Open this day" and "Open the calendar"). It is still the quietest of
  // the four: hairline rather than accent, and no fill until hover.
  ghost: "bg-transparent text-ink-secondary border border-hairline hover:bg-sunken hover:text-ink",
  danger: "bg-danger-bg text-danger border border-danger-bg hover:brightness-95",
};

const SIZES: Record<ButtonSize, string> = {
  sm: "min-h-9 px-3 text-sm",
  md: "min-h-touch px-4 text-base",
};

/** The one place the button's look is defined, shared with {@link ButtonLink}. */
export function BUTTON_CLASSES({
  variant = "primary",
  size = "md",
  fullWidth,
  className = "",
}: {
  variant?: ButtonVariant;
  size?: ButtonSize;
  fullWidth?: boolean;
  className?: string;
}): string {
  return [
    "inline-flex items-center justify-center gap-2 rounded font-medium",
    // The press. A button is pressed tens of times a day, so this sits at the near-imperceptible
    // end on purpose — 120ms, a 1px drop and two per cent of give. What changed is that the give
    // is now part of the transition rather than instant: the fill used to fade over 150ms while
    // the drop happened in a single frame, so the half you could see was the half that was not
    // animated. Transform and colour, nothing that costs a layout.
    "transition-[transform,background-color,border-color,color] duration-press ease-out",
    "active:translate-y-px active:scale-press",
    "disabled:cursor-not-allowed disabled:active:translate-y-0 disabled:active:scale-100",
    SIZES[size],
    VARIANTS[variant],
    fullWidth ? "w-full" : "",
    className,
  ].join(" ");
}

export function Button({
  variant = "primary",
  size = "md",
  icon,
  fullWidth,
  className = "",
  busy = false,
  disabled,
  children,
  ...rest
}: {
  variant?: ButtonVariant;
  size?: ButtonSize;
  /** Tabler icon name without the `ti-` prefix. */
  icon?: string;
  fullWidth?: boolean;
  /**
   * This button's own act is in flight — not merely that something somewhere is.
   *
   * <p>Held apart from `disabled`, and the difference is the whole reason it exists. A disabled
   * button is dimmed to 45% because there is nothing on it to look at. A busy one is the only thing
   * on the screen worth looking at, and dimming it hid the very animation that was saying so: the
   * pot draws in `currentColor` and its steam — the only part that moves — is faint by design, so
   * at 45% the motion came out around a quarter opacity and read as a picture of a pot rather than
   * a pot working.
   *
   * <p>A busy button therefore keeps its full weight, still refuses clicks, and says `aria-busy`
   * for anybody listening rather than looking. A button disabled because some *other* action is in
   * flight stays dimmed, which is right: nothing is happening on it.
   */
  busy?: boolean;
  children?: ReactNode;
} & ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button
      className={BUTTON_CLASSES({
        variant,
        size,
        fullWidth,
        className: `${busy ? "" : "disabled:opacity-45"} ${className}`.trim(),
      })}
      // Refused either way; only the weight differs.
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      {...rest}
    >
      {icon && <i className={`ti ti-${icon} text-lg`} aria-hidden="true" />}
      {children}
    </button>
  );
}
