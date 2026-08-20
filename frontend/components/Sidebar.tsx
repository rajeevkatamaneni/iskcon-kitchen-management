"use client";

import Link from "next/link";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { navForRole } from "@/lib/nav";

/** Where the menu was left. Session-scoped: a new tab starts at the top, as it should. */
const SCROLL_POSITION_KEY = "kms.sidebar.scroll";

// Restoring has to happen before the browser paints, or the menu is visibly yanked back into
// place. useLayoutEffect does that but has nothing to do during server rendering, where it would
// only warn.
const useBeforePaint = typeof window === "undefined" ? useEffect : useLayoutEffect;

/**
 * Whose kitchen this is — and, for someone who serves at more than one, the way to the others.
 * Switching changes which temple the next request speaks for; it never signs anyone in again, and
 * it can only offer temples this person has actually joined.
 */
/**
 * How big the temple's name can be and still sit on one line.
 *
 * <p>Asked for on 2026-08-20: the mark on its own line, and the name "scaled to whatever size it
 * can fit without wrapping". A temple's name is its own length, so no single font size is right for
 * all of them — "ISKCON Mayapur" has room to spare where "ISKCON Sri Radha Krishna Chandra Temple"
 * has none.
 *
 * <p>Estimated from the character count rather than measured from the rendered element, which
 * sounds worse than it is. Measuring means laying out at one size, reading it back and re-rendering
 * — a flash on every load, and a wrong answer on the first paint if the webfont has not arrived.
 * The estimate is stable, needs no effect, and renders identically on the server and the client.
 *
 * <p>{@code EM_PER_CHARACTER} was measured in a browser against Anek at weight 500, over the real
 * names this product actually shows. They ranged from 0.437 to 0.495 em per character; the widest
 * is used, so the estimate errs towards a slightly smaller name and never towards one that spills.
 * `truncate` on the element is the last resort for a name of unusually wide letters.
 */
/**
 * Measured on the running page, not derived from the 280px column: the menu carries 16px of padding
 * either side and this lockup another 8px, so the name has 232px, not the 264px a first guess gives
 * it. Getting that wrong by 32px is what truncated "ISKCON South Bengaluru" on the first attempt.
 */
const SIDEBAR_NAME_WIDTH_PX = 232;
const EM_PER_CHARACTER = 0.495;
/** The `2xl` token — the size the name was doubled to, and no larger. */
const NAME_MAX_PX = 28;
/** `xs` on the type scale. Small, but it holds the longest real temple name on one line. */
const NAME_MIN_PX = 12;

export function templeNameSize(name: string): number {
  const ideal = SIDEBAR_NAME_WIDTH_PX / (Math.max(name.length, 1) * EM_PER_CHARACTER);
  return Math.max(NAME_MIN_PX, Math.min(NAME_MAX_PX, Math.floor(ideal)));
}

function TempleHeader({ subtitle }: { subtitle: string }) {
  const { appUser, switchTemple } = useAuth();
  const [open, setOpen] = useState(false);
  const temples = appUser?.temples ?? [];
  const many = temples.length > 1;

  // The switcher's chevron rides on the mark's line, so it costs the name nothing.
  const nameStyle = { fontSize: `${templeNameSize(subtitle)}px` };

  const mark = (
    <>
      {/* The mark alone, not the full lockup: the wordmark is illegible at this size. */}
      <span className="flex items-center">
        <img
          src="/brand/iskcon-icon.svg"
          alt=""
          aria-hidden="true"
          className="h-16 w-16 flex-none object-contain"
        />
        {many && (
          <span aria-hidden className="ml-auto text-xs text-ink-muted">
            ▾
          </span>
        )}
      </span>
      {/*
        The temple's own name, and nothing above it. "Temple Kitchen" used to sit here in the
        primary weight with the temple demoted to a grey line underneath — which told every user
        the name of the software they were already looking at, and whispered the one thing that
        actually identifies where they are. This is somebody's temple, not a product.

        Stacked, and never wrapped. Beside the mark the name had only 160px and broke across two or
        three lines; on its own line it has the full column, and is sized to whatever fits on one —
        which is what was asked for on 2026-08-20, and reads far better than a name in pieces.
      */}
      <span
        style={nameStyle}
        title={subtitle}
        className="block truncate whitespace-nowrap text-left font-medium leading-tight text-ink"
      >
        {subtitle}
      </span>
    </>
  );

  if (!many) {
    return <div className="grid gap-1 px-2">{mark}</div>;
  }

  return (
    <div className="relative grid">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        aria-label={`${subtitle}. Switch temple`}
        className="grid gap-1 rounded px-2 py-1 text-left transition-colors duration-state hover:bg-sunken"
      >
        {mark}
      </button>

      {open && (
        <div className="absolute left-2 right-0 top-full z-10 mt-1 overflow-hidden rounded-lg border border-hairline bg-canvas shadow-sm">
          <span className="block bg-sunken px-3 py-1 text-xs uppercase tracking-[0.08em] text-ink-muted">
            Your temples
          </span>
          {temples.map((temple, index) => (
            <button
              key={temple.id}
              type="button"
              onClick={() => {
                setOpen(false);
                if (temple.id !== appUser?.tenantId) switchTemple(temple.id);
              }}
              className={[
                "flex w-full items-center gap-2 border-t border-hairline px-3 py-2 text-left text-sm first:border-t-0",
                temple.id === appUser?.tenantId ? "bg-accent-bg text-ink" : "text-ink-secondary hover:bg-raised",
              ].join(" ")}
            >
              <span className="flex-1">{temple.name}</span>
              {index === 0 && (
                <span className="text-[0.65rem] uppercase tracking-[0.07em] text-accent-text">Home</span>
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

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

  const scroller = useRef<HTMLDivElement>(null);

  useBeforePaint(() => {
    const list = scroller.current;
    if (!list) {
      return;
    }
    const saved = Number(sessionStorage.getItem(SCROLL_POSITION_KEY) ?? "0");
    if (saved > 0) {
      list.scrollTop = saved;
    }
  }, []);

  function rememberScroll(event: React.UIEvent<HTMLDivElement>) {
    sessionStorage.setItem(SCROLL_POSITION_KEY, String(event.currentTarget.scrollTop));
  }

  return (
    <nav
      aria-label="Main"
      // Its own column, as tall as the window and pinned to it. Before this the sidebar was simply
      // as tall as the page, which put the profile and Sign out at the foot of the *document* — so
      // on a long screen (a month of meals, a hundred ingredients) you had to scroll past all of it
      // to reach your own account, and on a screen with a panel open you could not reach it at all.
      // The column is the height of the window and does not scroll as a whole: the destinations
      // scroll inside it and the person at the foot stays put. Scrolling the sidebar itself was the
      // bug — on a short window the profile sat below the fold, and the page's own scrollbar could
      // not reach it because the sidebar is pinned.
      className="sticky top-0 flex h-screen w-sidebar shrink-0 flex-col gap-4 overflow-hidden bg-raised px-4 py-6"
    >
      <TempleHeader subtitle={subtitle} />

      {/* Every page mounts its own copy of this menu, so choosing a destination unmounts the list
          and mounts a fresh one — which starts at the top, throwing away where you were. Until the
          menu lives in a layout that survives navigation, it remembers its own position and puts
          itself back before the first paint. */}
      <div
        ref={scroller}
        onScroll={rememberScroll}
        className="grid min-h-0 flex-1 content-start gap-6 overflow-y-auto"
      >
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
 * Who you are, at the foot of the menu (E1-S16).
 *
 * <p>Modelled on the design system's profile menu, with one change: it opens *within* the sidebar,
 * growing upward from the row, rather than floating a panel wider than the menu over the top of it.
 * The sidebar is a column; a panel that overhangs it reads as a mistake.
 *
 * <p>Sign out stays beside the avatar rather than inside the panel. On a shared temple tablet,
 * handing the device to the next person is routine, and it should cost one press and no hunting.
 */
function SignedInPerson({ activeHref }: { activeHref: string }) {
  const { appUser, signOut } = useAuth();
  const [open, setOpen] = useState(false);
  if (!appUser) return null;

  const label = ROLE_LABELS[appUser.role] ?? appUser.role;
  // A name is expected but never assumed: an account created before whoami carried one, or any
  // future gap in the payload, must not take the whole menu down with it.
  const name = appUser.fullName?.trim() || label;
  const isOperator = appUser.role === "SUPER_ADMIN";

  return (
    <div className="grid gap-1">
      {open && (
        <div className="grid max-h-[50vh] gap-3 overflow-y-auto rounded-lg border border-hairline bg-canvas px-4 py-4">
          <div className="flex items-center gap-3">
            <span className="flex h-11 w-11 flex-none items-center justify-center rounded-full bg-accent text-base font-medium text-ink-inverse">
              {initials(name)}
            </span>
            <span className="grid min-w-0">
              <span className="text-base font-medium text-ink">{name}</span>
              <span className="text-xs text-ink-muted">
                {label}
                {appUser.tenantName ? ` · ${appUser.tenantName}` : ""}
              </span>
            </span>
          </div>

          {!isOperator && (
            <div className="grid gap-0.5 border-t border-hairline pt-2">
              <Link
                href="/profile"
                onClick={() => setOpen(false)}
                className="flex min-h-touch items-center gap-3 rounded px-3 text-sm text-ink-secondary transition-colors duration-state hover:bg-sunken hover:text-ink"
              >
                <i className="ti ti-user text-lg" aria-hidden="true" />
                My profile
              </Link>
              <Link
                href="/profile"
                onClick={() => setOpen(false)}
                className="flex min-h-touch items-center gap-3 rounded px-3 text-sm text-ink-secondary transition-colors duration-state hover:bg-sunken hover:text-ink"
              >
                <i className="ti ti-bell text-lg" aria-hidden="true" />
                Notification preferences
              </Link>
            </div>
          )}
        </div>
      )}

      <div className="flex items-center gap-1">
        <button
          type="button"
          onClick={() => setOpen((o) => !o)}
          aria-expanded={open}
          aria-current={activeHref === "/profile" ? "page" : undefined}
          className={[
            "flex min-h-touch flex-1 items-center gap-3 overflow-hidden rounded px-3 text-left",
            "transition-colors duration-state",
            open ? "bg-sunken" : "hover:bg-sunken",
          ].join(" ")}
        >
          <span className="flex h-9 w-9 flex-none items-center justify-center rounded-full bg-accent-bg text-sm font-medium text-accent-text">
            {initials(name)}
          </span>
          <span className="grid min-w-0 flex-1 text-left">
            <span className="truncate text-sm font-medium text-ink">{name}</span>
            <span className="text-xs text-ink-muted">{label}</span>
          </span>
          <i
            className={`ti ti-chevron-${open ? "down" : "up"} text-base text-ink-muted`}
            aria-hidden="true"
          />
        </button>

        <button
          type="button"
          onClick={() => signOut()}
          aria-label="Sign out"
          title="Sign out"
          className="flex h-11 w-11 flex-none items-center justify-center rounded text-ink-muted transition-colors duration-state hover:bg-sunken hover:text-ink"
        >
          <i className="ti ti-logout text-lg" aria-hidden="true" />
        </button>
      </div>
    </div>
  );
}

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
