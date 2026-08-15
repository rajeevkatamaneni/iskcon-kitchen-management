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
  primary: "bg-accent text-ink-inverse border border-accent hover:bg-accent-hover hover:border-accent-hover",
  secondary: "bg-canvas text-accent-text border border-accent-border hover:bg-accent-bg",
  ghost: "bg-transparent text-ink-secondary border border-transparent hover:bg-sunken hover:text-ink",
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
    "transition-colors duration-state active:translate-y-px",
    "disabled:cursor-not-allowed disabled:opacity-45 disabled:active:translate-y-0",
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
  children,
  ...rest
}: {
  variant?: ButtonVariant;
  size?: ButtonSize;
  /** Tabler icon name without the `ti-` prefix. */
  icon?: string;
  fullWidth?: boolean;
  children?: ReactNode;
} & ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button className={BUTTON_CLASSES({ variant, size, fullWidth, className })} {...rest}>
      {icon && <i className={`ti ti-${icon} text-lg`} aria-hidden="true" />}
      {children}
    </button>
  );
}
