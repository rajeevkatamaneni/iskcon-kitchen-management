/**
 * A small cooking animation used as a loading indicator — a simmering pot with steam rising.
 * Themed to the kitchen this app runs, so a wait reads as "something's cooking" rather than a
 * generic spinner. Colours are inherited via {@code currentColor}, so it takes on the text colour
 * of wherever it sits. The steam animates via the `.kms-steam-wisp` keyframes in globals.css, which
 * the global prefers-reduced-motion rule quiets to a still pot.
 */
export function CookingLoader({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 48 48"
      fill="none"
      role="img"
      aria-label="Working…"
      className={className}
    >
      <g stroke="currentColor" strokeWidth="2" strokeLinecap="round" opacity="0.65">
        <path className="kms-steam-wisp" style={{ animationDelay: "0ms" }} d="M18 17c-2-2-2-4 0-6s2-4 0-6" />
        <path className="kms-steam-wisp" style={{ animationDelay: "300ms" }} d="M24 17c-2-2-2-4 0-6s2-4 0-6" />
        <path className="kms-steam-wisp" style={{ animationDelay: "600ms" }} d="M30 17c-2-2-2-4 0-6s2-4 0-6" />
      </g>

      {/* lid + knob */}
      <rect x="8" y="20" width="32" height="6" rx="3" fill="currentColor" />
      <circle cx="24" cy="17.5" r="1.8" fill="currentColor" />

      {/* pot body */}
      <path
        d="M11 27h26l-1.6 12.4a2.2 2.2 0 0 1-2.2 1.95H14.8a2.2 2.2 0 0 1-2.2-1.95L11 27Z"
        fill="currentColor"
      />

      {/* handles */}
      <rect x="3.5" y="28.5" width="5.5" height="3.4" rx="1.7" fill="currentColor" />
      <rect x="39" y="28.5" width="5.5" height="3.4" rx="1.7" fill="currentColor" />
    </svg>
  );
}
