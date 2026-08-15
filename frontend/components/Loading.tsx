import { CookingLoader } from "@/components/CookingLoader";

/**
 * What a screen shows while it waits. One pot, everywhere — a wait on the recipes list and a wait on
 * the donations ledger should look like the same app thinking, not two different ones.
 *
 * <p>{@link Loading} is the block form, for a page or a panel that has nothing to show yet.
 * {@link BusyPot} is the inline form, sized to sit inside a button next to its label while an action
 * is in flight.
 */
export function Loading({ label = "Loading…", className = "" }: { label?: string; className?: string }) {
  return (
    <div
      role="status"
      aria-live="polite"
      className={`flex flex-col items-center gap-3 py-10 text-center ${className}`}
    >
      <CookingLoader className="h-10 w-10 text-accent" decorative />
      <p className="text-sm text-ink-secondary">{label}</p>
    </div>
  );
}

/** The same pot at button size. Decorative — the button's own text says what is happening. */
export function BusyPot({ className = "" }: { className?: string }) {
  return <CookingLoader className={`h-4 w-4 shrink-0 ${className}`} decorative />;
}
