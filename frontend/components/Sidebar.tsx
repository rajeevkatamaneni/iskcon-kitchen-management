"use client";

import Link from "next/link";
import { useAuth } from "@/lib/auth-context";
import { navForRole } from "@/lib/nav";

/**
 * Persistent navigation — 280px, grouped, no motion.
 *
 * <p>Ported from the ISKCON Kitchen Design System (Claude Design, 2026-08-14): the temple's mark and
 * name at the top, destinations grouped under quiet uppercase headings, and the signed-in person at
 * the foot, where a person expects to find themselves.
 *
 * <p>The menu is chosen from the signed-in person's role, so nobody is offered a destination they
 * would only be refused at (see {@link navForRole}). It is not the security boundary — the API
 * enforces every permission on every request — but it should never mislead.
 *
 * <p>Deliberately no hover-reveal or slide transition. That is a marketing-site pattern for dozens
 * of destinations; we have a handful per role, it does not exist on touch devices, and the animation
 * is sluggish on the mid-range Android phones most volunteers carry.
 */
export function Sidebar({ activeHref }: { activeHref: string }) {
  const { appUser } = useAuth();
  const groups = navForRole(appUser?.role);
  // The temple's own name, from whoami. A platform operator belongs to no temple and runs the
  // platform itself, so they are told so rather than shown an empty line.
  const subtitle =
    appUser?.role === "SUPER_ADMIN" ? "Platform" : appUser?.tenantName?.trim() || "Your temple";

  return (
    <nav
      aria-label="Main"
      // Its own column, as tall as the window and pinned to it. Before this the sidebar was simply
      // as tall as the page, which put the profile and Sign out at the foot of the *document* — so
      // on a long screen (a month of meals, a hundred ingredients) you had to scroll past all of it
      // to reach your own account, and on a screen with a panel open you could not reach it at all.
      className="sticky top-0 flex h-screen w-sidebar shrink-0 flex-col gap-6 overflow-y-auto bg-raised px-4 py-6"
    >
      <div className="flex items-center gap-3 px-2">
        {/* The mark alone, not the full lockup: the wordmark is illegible at this size. */}
        <img src="/brand/iskcon-icon.svg" alt="" aria-hidden="true" className="h-9 w-9 object-contain" />
        <span className="grid">
          <span className="text-sm font-medium text-ink">Temple Kitchen</span>
          <span className="text-xs text-ink-muted">{subtitle}</span>
        </span>
      </div>

      <div className="grid flex-1 content-start gap-6">
        {groups.map((group) => (
          <div key={group.title ?? "main"} className="grid gap-1">
            {group.title && (
              <span className="mb-1 px-3 text-xs uppercase tracking-[0.08em] text-ink-muted">
                {group.title}
              </span>
            )}
            {group.items.map((item) => {
              const active = item.href === activeHref;
              return (
                <Link
                  key={item.href}
                  href={item.href}
                  aria-current={active ? "page" : undefined}
                  className={[
                    "flex min-h-touch items-center gap-3 rounded px-3 text-base",
                    "transition-colors duration-state",
                    active
                      ? "bg-accent-bg font-semibold text-accent-text"
                      : "text-ink-secondary hover:bg-sunken hover:text-ink",
                  ].join(" ")}
                >
                  <i className={`ti ti-${item.icon} text-lg`} aria-hidden="true" />
                  {item.label}
                </Link>
              );
            })}
          </div>
        ))}
      </div>

      <SignedInPerson activeHref={activeHref} />
    </nav>
  );
}

/**
 * Who you are, at the foot of the menu, and how to stop being you (E1-S16). A platform operator has
 * no temple profile, so they get their name without a link to a page that would refuse them.
 *
 * <p>Sign out sits here because this is where a person looks for themselves, and on a shared temple
 * tablet handing the device over is a routine act, not an edge case.
 */
function SignedInPerson({ activeHref }: { activeHref: string }) {
  const { appUser, signOut } = useAuth();
  if (!appUser) return null;

  const label = ROLE_LABELS[appUser.role] ?? appUser.role;
  // A name is expected but never assumed: an account created before whoami carried one, or any
  // future gap in the payload, must not take the whole menu down with it.
  const name = appUser.fullName?.trim() || label;
  const body = (
    <>
      <span className="flex h-9 w-9 flex-none items-center justify-center rounded-full bg-accent-bg text-sm font-medium text-accent-text">
        {initials(name)}
      </span>
      <span className="grid text-left">
        <span className="truncate text-sm font-medium text-ink">{name}</span>
        <span className="text-xs text-ink-muted">{label}</span>
      </span>
    </>
  );

  return (
    <div className="flex items-center gap-1">
      {appUser.role === "SUPER_ADMIN" ? (
        <div className="flex flex-1 items-center gap-3 overflow-hidden px-3 py-2">{body}</div>
      ) : (
        <Link
          href="/profile"
          aria-current={activeHref === "/profile" ? "page" : undefined}
          className={[
            "flex min-h-touch flex-1 items-center gap-3 overflow-hidden rounded px-3",
            "transition-colors duration-state",
            activeHref === "/profile" ? "bg-accent-bg" : "hover:bg-sunken",
          ].join(" ")}
        >
          {body}
        </Link>
      )}

      <button
        type="button"
        onClick={() => void signOut()}
        title="Sign out"
        className="flex min-h-touch min-w-touch flex-none items-center justify-center rounded text-ink-secondary transition-colors duration-state hover:bg-sunken hover:text-ink"
      >
        <i className="ti ti-logout text-lg" aria-hidden="true" />
        <span className="sr-only">Sign out</span>
      </button>
    </div>
  );
}

/** Two letters from the name, as the design system's Person mark does. */
function initials(name: string): string {
  return (name ?? "")
    .split(/\s+/)
    .filter(Boolean)
    .map((w) => w[0])
    .join("")
    .slice(0, 2)
    .toUpperCase() || "·";
}

const ROLE_LABELS: Record<string, string> = {
  SUPER_ADMIN: "Platform operator",
  TEMPLE_ADMIN: "Temple admin",
  KITCHEN_STAFF: "Kitchen staff",
  VOLUNTEER: "Volunteer",
};
