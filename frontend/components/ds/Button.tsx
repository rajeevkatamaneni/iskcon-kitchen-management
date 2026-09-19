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

export type ButtonVariant = "primary" | "secondary" | "ghost" | "danger" | "warning";
export type ButtonSize = "sm" | "md";

const VARIANTS: Record<ButtonVariant, string> = {
  // The four materials of THEME-TOKENS §4, by the names §4 gives them. Each is a component class in
  // `globals.css` rather than a string of utilities, because a button's fill, border and shadow are
  // one indivisible value per pack — a gradient with two inset highlights in the glossy packs, a
  // lifted translucent pane in the frosted ones, a plain fill in the flat ones — and §8.2 is
  // explicit that no colour utility can express that.
  //
  // Two of the mappings are worth stating, because the names do not line up one to one. This
  // codebase's `secondary` is accent text inside an accent hairline, which is §4's `.btn-quiet`.
  // Its `ghost` is the neutral second action, which is §4's `.btn-secondary` — and that is the one
  // that gains most, because §6 gives the white button the same gradient and top highlight as the
  // coloured one. A flat white button beside a glossy coloured one is the thing §6 says reads as
  // two different products.
  primary: "btn btn-primary",
  secondary: "btn btn-quiet",
  // A resting border, not a transparent one. Ghost used to be invisible until the pointer touched
  // it, at which point a box appeared around what had read as a line of text — a button pretending
  // not to be one, which is how somebody comes to press something they did not know was pressable
  // (Rajeev, 2026-08-23, on "Open this day" and "Open the calendar"). §4's `.btn-secondary` carries
  // a resting border of its own, so that stays true.
  ghost: "btn btn-secondary",
  danger: "btn btn-danger",
  // An act to take with care rather than one that destroys anything: correcting a date the
  // calendar worked out (Rajeev, 2026-09-18, T-237). Built exactly as `.btn-danger` is — tinted
  // fill, coloured text, a 1px border of the same colour — from the warning tokens, so it reads as
  // amber in every pack. Utilities rather than a `.btn-warning` class in `globals.css` because
  // nothing about it varies by pack beyond the tokens themselves, which these utilities already
  // follow.
  warning: "btn border border-warning bg-warning-bg text-warning",
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
    // No `rounded` here: §4's `.btn` sets `border-radius: var(--radius-control)`, which follows
    // the pack — 11px glossy, 9px frosted, 5px flat.
    "inline-flex items-center justify-center gap-2 font-medium",
    // No press here any more. The give under a finger — the `press` scale, a 1px drop, 120ms,
    // none on a disabled button, no movement for someone who asked for less — moved to `.btn` in
    // `globals.css` (T-240, Rajeev 2026-09-19), because about sixty buttons are hand-made with the
    // `btn` classes and never came through this function, so they never pressed. One rule on the
    // base class now covers both kinds.
    "disabled:cursor-not-allowed",
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
  // "button", not the browser's "submit". A <button> with no type submits whatever form it sits
  // in, so every Button in a form used to be a save button whether or not it said so — which is
  // how pressing "remove" on an order line also saved the order with the line still on it. A
  // screen that means to submit now says `type="submit"`; forgetting to fails safe, as a button
  // that does nothing on Enter, rather than as a save nobody asked for.
  //
  // Taken out of the props here rather than written as `type="button"` ahead of `{...rest}`,
  // because a caller passing `type={undefined}` would spread over that and put the submit back.
  // A destructuring default covers undefined too, and the attribute is set after the spread.
  type = "button",
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
      type={type}
    >
      {icon && <i className={`ti ti-${icon} text-lg`} aria-hidden="true" />}
      {children}
    </button>
  );
}
