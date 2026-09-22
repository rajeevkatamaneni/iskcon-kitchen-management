"use client";

import Link from "next/link";
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { SIDEBAR_SCROLL_KEY, navForRole } from "@/lib/nav";

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
 * Sizes the temple's name to the width it actually has: one line when it fits at a readable size,
 * and otherwise two lines, broken between words. Never truncated, never clipped, never split
 * mid-word.
 *
 * <p>Estimating this from the character count is what the first two attempts did, and both were
 * wrong in the way estimates are: the first assumed a column 32px wider than the name really has,
 * and even corrected it could only ever be approximately right, because a name of wide letters
 * measures differently from a name of narrow ones. The result was an ellipsis in the menu, which is
 * the one outcome this is supposed to prevent.
 *
 * <p>So it measures. The name is laid out on one line at the largest size allowed, its width read
 * back, and the size scaled by the ratio of the room available to the room wanted — text width is
 * linear in font size, so one measurement is enough and the answer is exact rather than close.
 *
 * <p><b>The third attempt (T-284) still cut the name off</b>, and the reason is worth keeping. A
 * verifier's screenshot at 1280 showed "ISKCON South Benga" at 28px: the size this sets before it
 * measures, left in place because the measurement ran against a width that was not the column's
 * final one — and nothing ever measured again. Two changes close that for good:
 *
 * <ol>
 *   <li>It re-measures whenever the name's own box changes width ({@link useFittedName} watches it
 *       with a ResizeObserver), not only on mount and when the webfont lands. A stylesheet arriving
 *       late, a drawer opening, a window resized: each one changes the width, so each one re-fits.
 *   <li>The fallback — no measurement yet, or a hidden element that measures zero — is the CSS
 *       default, and the CSS default wraps. `whitespace-nowrap` and `overflow-hidden` are no longer
 *       in the class list; one line is something this function grants after it has measured that
 *       the name fits, never something the page assumes. If the script never runs, the name is on
 *       two lines, which is untidy; before, it was cut off, which is wrong.
 * </ol>
 *
 * <p>The floor is what decides between shrinking and wrapping. Scaling alone would take a long name
 * all the way down to 12px to keep it on one line, which is technically whole and practically
 * unreadable at the top of the menu. Below the floor, it stops shrinking and wraps instead, at the
 * floor size, balanced so the two lines are close in length rather than one long and one orphan.
 */
const NAME_MAX_PX = 28;
/** `lg` on the type scale: below this the column's name wraps rather than shrinks. */
const NAME_MIN_PX = 18;
/** So the longest name stops a little short of the edge rather than exactly on it. */
const NAME_BREATHING_PX = 6;

function fitToWidth(el: HTMLElement, maxPx: number, minPx: number) {
  // Measured on one line at the largest size; anything that returns early hands the name back to
  // the class list, which wraps, so a failed measurement can only ever cost a line, not letters.
  el.style.whiteSpace = "nowrap";
  el.style.fontSize = `${maxPx}px`;
  const available = el.clientWidth - NAME_BREATHING_PX;
  const wanted = el.scrollWidth;
  if (available <= 0 || wanted <= 0) {
    el.style.whiteSpace = "";
    el.style.fontSize = "";
    return;
  }
  const scaled = Math.floor((maxPx * available) / wanted);
  if (scaled >= minPx) {
    // Fits on one line at a readable size: keep the nowrap set above.
    el.style.fontSize = `${Math.min(maxPx, scaled)}px`;
  } else {
    el.style.whiteSpace = "";
    el.style.fontSize = `${minPx}px`;
  }
}

/**
 * The same measure-and-fit, for a name that has to be re-fitted whenever its room changes.
 *
 * <p>On a phone the menu is `display: none` until it is opened, and a hidden element measures zero
 * wide — {@link fitToWidth} quite rightly does nothing with that. The ResizeObserver catches the
 * frame it first becomes visible; `refit` (the drawer's open state) is kept as a second trigger for
 * the browsers and test environments that have no ResizeObserver.
 */
function useFittedName(text: string, maxPx: number, minPx: number, refit?: unknown) {
  const ref = useRef<HTMLSpanElement>(null);
  // Before paint, so the name is never seen at the wrong size; again once the webfont has arrived,
  // because the first measurement was of the fallback face; and again on any change of width.
  useBeforePaint(() => {
    const el = ref.current;
    if (!el) {
      return;
    }
    fitToWidth(el, maxPx, minPx);
    let cancelled = false;
    document.fonts?.ready.then(() => {
      if (!cancelled && ref.current) {
        fitToWidth(ref.current, maxPx, minPx);
      }
    });
    // Width only. Wrapping onto a second line changes the box's height, and re-fitting on that
    // would be a measurement answering itself; the width is set by the column, never by the name.
    let lastWidth = el.clientWidth;
    const observer =
      typeof ResizeObserver === "undefined"
        ? null
        : new ResizeObserver(() => {
            const width = el.clientWidth;
            if (!cancelled && width !== lastWidth) {
              lastWidth = width;
              fitToWidth(el, maxPx, minPx);
            }
          });
    observer?.observe(el);
    return () => {
      cancelled = true;
      observer?.disconnect();
    };
  }, [text, maxPx, minPx, refit]);
  return ref;
}

function TempleHeader({ subtitle, refit }: { subtitle: string; refit?: unknown }) {
  const { appUser, switchTemple } = useAuth();
  const [open, setOpen] = useState(false);
  const temples = appUser?.temples ?? [];
  const many = temples.length > 1;

  const name = useFittedName(subtitle, NAME_MAX_PX, NAME_MIN_PX, refit);

  const mark = (
    <>
      {/* The mark alone, not the full lockup: the wordmark is illegible at this size. */}
      <span className="relative flex items-center justify-center">
        <img
          src="/brand/iskcon-icon.svg"
          alt=""
          aria-hidden="true"
          className="h-16 w-16 flex-none object-contain"
        />
        {/* Absolute, so the chevron cannot pull the mark off centre. */}
        {many && (
          <span aria-hidden className="absolute right-0 text-xs text-ink-muted">
            ▾
          </span>
        )}
      </span>
      {/*
        The temple’s own name, and nothing above it. "Temple Kitchen" used to sit here in the
        primary weight with the temple demoted to a grey line underneath — which told every user
        the name of the software they were already looking at, and whispered the one thing that
        actually identifies where they are. This is somebody’s temple, not a product.

        Stacked and centred under the mark, never truncated: {@link fitToWidth} measures it and
        scales it to the width it actually has, and wraps it between words when even the floor size
        will not fit on one line. No `truncate`, `whitespace-nowrap` or `overflow-hidden` here on
        purpose — each of those turns a failed fit into missing letters rather than an extra line.
      */}
      <span
        ref={name}
        // min-w-0 is load-bearing, not tidying. Measuring lays the name out on one line at 28px
        // first, and without it a grid item's min-width:auto lets that momentarily-wide text push
        // its own track wider — so clientWidth reads the width the name just created rather than
        // the width it actually has, and the fit is computed against a lie.
        className="block w-full min-w-0 text-balance text-center text-lg font-medium leading-tight text-ink"
      >
        {subtitle}
      </span>
    </>
  );

  if (!many) {
    return <div className="grid justify-items-center gap-1 px-2">{mark}</div>;
  }

  return (
    <div className="relative grid">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        aria-label={`${subtitle}. Switch temple`}
        className="grid w-full justify-items-center gap-1 rounded px-2 py-1 transition-colors duration-state hover:bg-sunken"
      >
        {mark}
      </button>

      {open && (
        <div className="dropdown absolute left-2 right-0 top-full z-10 mt-1 overflow-hidden">
          <span className="block bg-sunken px-3 py-1 text-xs uppercase tracking-eyebrow text-ink-muted">
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
                <span className="text-xs uppercase tracking-eyebrow text-accent-text">Home</span>
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
 *
 * <p><b>Below the `lg` breakpoint (1024px) the column becomes a drawer (T-225).</b> A 280px column
 * beside the page left a 390px phone about 110px for the page itself, and a portrait tablet (768px)
 * under 500 — neither is room for an inventory table or a recipe. So under 1024px the column is
 * hidden, a slim bar with the temple's name and a "Menu" button sits at the top of the page, and the
 * button opens this same column over the page from the left. 1024 rather than the design system's
 * 768 because Rajeev asked for portrait tablets to get the page's full width too; from 1024 up there
 * is room for both, and the column is exactly what it always was.
 *
 * <p>It is one menu, not two. The drawer is the very same element, re-positioned, so the
 * destinations, the person at the foot and Sign out are never duplicated and cannot drift apart.
 * The slide-in is an entrance (200ms, the design system's entrance time), not the between-pages
 * motion the paragraph above rules out, and it is dropped entirely for anyone who has asked their
 * device for less motion.
 *
 * <p>Every page mounts its own copy of this component inside a `flex min-h-screen` row. The bar has
 * to sit above the page rather than beside it, and a child cannot turn its parent's row into a
 * column — so `globals.css` does that for any row holding `.app-topbar`, below `lg` only. That is
 * one rule rather than an edit to sixty pages, and it goes when the menu moves into a shared layout.
 */
export function Sidebar({ activeHref }: { activeHref: string }) {
  const [open, setOpen] = useState(false);
  const menuButton = useRef<HTMLButtonElement>(null);
  const closeButton = useRef<HTMLButtonElement>(null);
  const drawer = useRef<HTMLDivElement>(null);
  // Only a close the person asked for sends focus back to the Menu button. Choosing a destination
  // also closes the drawer, but the page is about to be replaced and focus belongs to the next one.
  const returnFocus = useRef(false);

  const close = useCallback((restoreFocus: boolean) => {
    returnFocus.current = restoreFocus;
    setOpen(false);
  }, []);

  // While the drawer is open: Escape closes it, the page behind cannot be scrolled or reached, and
  // focus starts on Close. `inert` on everything beside the drawer is what really keeps the
  // keyboard and a screen reader inside it; the Tab handler below is the belt to its braces, for
  // the older browsers (and jsdom) that do not know `inert`.
  useEffect(() => {
    if (!open) {
      if (returnFocus.current) {
        returnFocus.current = false;
        menuButton.current?.focus();
      }
      return;
    }
    const panel = drawer.current;
    const behind = panel?.parentElement
      ? Array.from(panel.parentElement.children).filter(
          (el) => el !== panel && !el.hasAttribute("data-menu-backdrop"),
        )
      : [];
    behind.forEach((el) => el.setAttribute("inert", ""));
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    closeButton.current?.focus();

    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        close(true);
      }
    }
    document.addEventListener("keydown", onKey);
    // Crossing into the laptop layout with the drawer open would leave a dialog, a locked page and
    // an inert main behind a column that no longer needs opening. So it closes itself.
    const wide = typeof window.matchMedia === "function" ? window.matchMedia("(min-width: 1024px)") : null;
    const onWide = (event: MediaQueryListEvent) => {
      if (event.matches) close(false);
    };
    wide?.addEventListener?.("change", onWide);

    return () => {
      behind.forEach((el) => el.removeAttribute("inert"));
      document.body.style.overflow = previousOverflow;
      document.removeEventListener("keydown", onKey);
      wide?.removeEventListener?.("change", onWide);
    };
  }, [open, close]);

  function trapTab(event: React.KeyboardEvent<HTMLDivElement>) {
    if (!open || event.key !== "Tab" || !drawer.current) {
      return;
    }
    const focusable = Array.from(
      drawer.current.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])',
      ),
    );
    if (focusable.length === 0) {
      return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  // Any link chosen inside the drawer closes it — a destination, or My profile in the person's
  // panel. Caught here once rather than wired onto every link, so a link added later cannot forget.
  function closeOnLink(event: React.MouseEvent<HTMLDivElement>) {
    if (open && (event.target as HTMLElement).closest("a[href]")) {
      close(false);
    }
  }

  const { appUser } = useAuth();
  // The person as well as the role (Epic 12): a cook whose kitchen does not plan its meals here is
  // not offered the planner, because its page would only refuse them.
  //
  // And the temple's own arrangement of the menu, third (Settings → Menu, Rajeev 2026-09-19). It
  // comes off the session rather than from a request of this component's own: every page mounts its
  // own copy of this menu, so a menu that fetched its arrangement would ask for it once per
  // navigation and show the standard order for a frame each time it did. `navForRole` arranges
  // first and filters by role after, so nothing here changes who may reach what.
  const groups = navForRole(appUser?.role, appUser, appUser?.menuLayout ?? null);
  // The temple's own name, from whoami. A platform operator belongs to no temple and runs the
  // platform itself, so they are told so rather than shown an empty line.
  const subtitle =
    appUser?.role === "SUPER_ADMIN" ? "Platform" : appUser?.tenantName?.trim() || "Your temple";

  const scroller = useRef<HTMLDivElement>(null);

  // Also on opening: on a phone the list is `display: none` when the page mounts it, and a hidden
  // element cannot be scrolled, so the position is put back when the drawer is first shown.
  useBeforePaint(() => {
    const list = scroller.current;
    if (!list) {
      return;
    }
    const saved = Number(sessionStorage.getItem(SIDEBAR_SCROLL_KEY) ?? "0");
    if (saved > 0) {
      list.scrollTop = saved;
    }
  }, [open]);

  function rememberScroll(event: React.UIEvent<HTMLDivElement>) {
    sessionStorage.setItem(SIDEBAR_SCROLL_KEY, String(event.currentTarget.scrollTop));
  }

  return (
    <>
      {/* The drawer comes first in the source, so a page's own "the first image", "the first
          scrolling list" still mean the menu's, as they always have; `order-first` puts the bar
          visually above it. Below `lg` only — from 1024 up the bar does not exist. */}
      <div
        ref={drawer}
        id="app-menu"
        role={open ? "dialog" : undefined}
        aria-modal={open ? true : undefined}
        aria-label={open ? "Menu" : undefined}
        onKeyDown={trapTab}
        onClickCapture={closeOnLink}
        // Closed, below lg: gone. Open, below lg: pinned to the left edge over the page. From lg up
        // it is `display: contents`, so the <nav> inside is the page row's own child exactly as it
        // was before this wrapper existed, and its sticky column behaves as it always did.
        className={
          open
            ? "fixed inset-y-0 left-0 z-50 flex shadow-overlay motion-safe:animate-drawer-in lg:contents"
            : "hidden lg:contents"
        }
      >
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
          //
          // In the drawer it is the drawer's height instead (`max-lg:h-full`): 100vh on a phone is the
          // height with the browser's address bar hidden, which would put Sign out under the bar.
          className="sidebar-surface sticky top-0 flex h-screen w-sidebar shrink-0 flex-col gap-4 overflow-hidden px-4 py-6 max-lg:h-full"
        >
          {open && (
            <button
              ref={closeButton}
              type="button"
              onClick={() => close(true)}
              className="absolute right-3 top-3 z-10 flex min-h-touch items-center gap-2 rounded-control px-3 text-sm text-ink-secondary transition-colors duration-state hover:bg-sunken hover:text-ink lg:hidden"
            >
              <i className="ti ti-x text-lg" aria-hidden="true" />
              Close
            </button>
          )}
          <TempleHeader subtitle={subtitle} refit={open} />

          {/* Every page mounts its own copy of this menu, so choosing a destination unmounts the list
              and mounts a fresh one — which starts at the top, throwing away where you were. Until the
              menu lives in a layout that survives navigation, it remembers its own position and puts
              itself back before the first paint. */}
          <div
            ref={scroller}
            onScroll={rememberScroll}
            // `-mx-3 px-3` looks like it cancels itself and does not: it widens the box that clips
            // without moving anything inside it. `overflow-y: auto` forces `overflow-x` to `auto` too —
            // the two axes cannot disagree — so this element clips horizontally whether or not anybody
            // asked it to, and the destinations were sitting flush against both of its edges (measured:
            // an item 16→264 inside a scroller 16→264, no slack at all). Anything a menu item painted
            // outside its own box — a lift shadow, a ring, a glow — was sliced off at the left and the
            // right and survived only along the top and bottom, which reads as a broken box rather than
            // as depth. Nothing paints out there today; this is so that the next thing that does can.
            className="-mx-3 grid min-h-0 flex-1 content-start gap-6 overflow-y-auto px-3"
          >
            {groups.map((group) => (
              // Keyed on the group's permanent id, not its heading. A temple arranges its own menu
              // now, and two groups can carry the same words, or none at all — "main" was a fine key
              // while the seven groups were fixed and is a collision waiting to happen once a
              // heading is something an admin types.
              <div key={group.id} className="grid gap-1">
                {group.title && (
                  <span className="mb-1 px-3 text-xs uppercase tracking-eyebrow text-ink-muted">
                    {group.title}
                  </span>
                )}
                {group.items.map((item) => {
                  const active = item.href === activeHref;
                  return (
                    <Link
                      // The id, for the same reason as the group above it: two items share the
                      // address `/notices` (the operator's and the admin's), and an id is the one
                      // thing about a destination that is guaranteed unique.
                      key={item.id}
                      href={item.href}
                      aria-current={active ? "page" : undefined}
                      className={[
                        "flex min-h-touch items-center gap-3 rounded px-3 text-nav",
                        "transition-[transform,box-shadow,background-color,color] duration-state ease-out",
                        active
                          ? "bg-accent-bg font-semibold text-accent-text"
                          // Lifts under the pointer, like the tiles. A menu item is passed over dozens
                          // of times a day, which is exactly the tier where motion has to be nearly
                          // imperceptible or not there at all — so it is two pixels and a step of tone,
                          // and nothing else. No shadow: see the note in ds/StatTile.
                          //
                          // This does not contradict the design system's "no animation" on navigation
                          // (§4). That rule is about *moving between* pages — the blur-and-slide
                          // Stripe does, which was rejected for being sluggish on a mid-range Android.
                          // Answering the pointer is a different thing.
                          //
                          // The active item deliberately does not lift. It is where you already are,
                          // not somewhere you can go, and lifting it would offer a journey that ends
                          // where it starts.
                          : "text-ink-secondary hover:-translate-y-0.5 hover:bg-sunken hover:text-ink hover:shadow-lift",
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
      </div>

      {open && (
        // The page behind, dimmed; pressing it closes the menu. Not a button of its own — Close and
        // Escape are the keyboard's ways out, and a second "close" stop in the tab order is noise.
        <div
          aria-hidden="true"
          data-menu-backdrop=""
          onClick={() => close(true)}
          className="fixed inset-0 z-40 bg-ink/40 motion-safe:animate-scrim-in lg:hidden"
        />
      )}

      <TopBar
        subtitle={subtitle}
        open={open}
        onOpen={() => setOpen(true)}
        buttonRef={menuButton}
      />
    </>
  );
}

/**
 * The phone and portrait-tablet header: Menu, then whose kitchen this is (T-225).
 *
 * <p>Menu leads, at the edge the thumb and the eye both start from, and says what it does in a word
 * beside its icon — the design system keeps icons for navigation and never without text. The
 * temple's name is fitted to the room left, the same way the column's is, rather than cut off
 * with an ellipsis.
 */
function TopBar({
  subtitle,
  open,
  onOpen,
  buttonRef,
}: {
  subtitle: string;
  open: boolean;
  onOpen: () => void;
  buttonRef: React.RefObject<HTMLButtonElement>;
}) {
  const name = useFittedName(subtitle, TOPBAR_NAME_MAX_PX, TOPBAR_NAME_MIN_PX);
  return (
    <header className="app-topbar topbar-surface sticky top-0 z-30 order-first flex min-h-14 items-center gap-3 px-4 py-1 lg:hidden">
      <button
        ref={buttonRef}
        type="button"
        onClick={onOpen}
        aria-expanded={open}
        aria-controls="app-menu"
        className="-ms-3 flex min-h-touch flex-none items-center gap-2 rounded-control px-3 text-base font-medium text-ink transition-colors duration-state hover:bg-sunken"
      >
        <i className="ti ti-menu-2 text-lg" aria-hidden="true" />
        Menu
      </button>
      {/* The name alone, no lotus. The mark lives in the menu, and the rule elsewhere in the app is
          one lotus on a screen (a test on /donate holds it); a second one in a 56px bar would
          also cost the name the width it most needs on a narrow phone. */}
      <span
        ref={name}
        // As the column's name: one line when it fits, two when it does not, never cut (T-284).
        className="block min-w-0 flex-1 text-balance text-lg font-medium leading-tight text-ink"
      >
        {subtitle}
      </span>
    </header>
  );
}

/** The bar's name tops out at `lg` on the type scale: a label for the page, not a heading on it. */
const TOPBAR_NAME_MAX_PX = 18;
/** `sm`: a phone bar is narrow enough that a long name should shrink a little before it wraps. */
const TOPBAR_NAME_MIN_PX = 14;

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
        <div className="grid max-h-[50vh] gap-3 overflow-y-auto rounded-card border border-hairline bg-sunken px-4 py-4">
          <div className="flex items-center gap-3">
            <span className="btn btn-primary flex h-11 w-11 flex-none items-center justify-center rounded-full text-base font-medium">
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
            {/* Wraps between words rather than truncating (T-284): "Karuna Murti Das" is 102px of
                text in a 99px column, and "Karuna Murti D…" is not anybody's name. Balanced, so
                a second line carries a real part of the name rather than one orphaned word. */}
            <span className="text-balance text-sm font-medium text-ink">{name}</span>
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
  // Missing until T-284, so a Kitchen Manager read "KITCHEN_MANAGER" under their own name. The words
  // are the Staff screen's (ACCESS_LABELS in components/staff/labels.ts), so the access an admin
  // grants there is named the same way here.
  KITCHEN_MANAGER: "Kitchen manager",
  KITCHEN_STAFF: "Kitchen staff",
  VOLUNTEER: "Volunteer",
};
