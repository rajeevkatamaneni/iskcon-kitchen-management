"use client";

import { useEffect, useRef } from "react";
import { useAuth } from "./auth-context";
import { applyPalette, crossfadeTheme, THEME_CACHE_KEY, type CachedTheme } from "./theme";
import { themePackById } from "./theme-packs";

/**
 * Paints the temple's chosen colours onto the document.
 *
 * <p>It renders nothing. Everything it does is to the `style` attribute of the document element,
 * where a custom property set by script beats the one compiled into the stylesheet — so a temple
 * that has chosen wears its own palette and one that has not wears the application's, with no
 * branch anywhere in the component tree.
 *
 * <p><b>Where the palette comes from.</b> `/whoami` carries the chosen theme's identifier — just
 * the identifier, because the browser already holds every palette. It is not a request of its own
 * because every person at a temple sees the same colours whatever their role, so the one call that
 * establishes who somebody is should also establish what their application looks like. Switching
 * temples re-runs `whoami`, and the repaint follows for free.
 *
 * <p>An identifier nothing matches — a pack removed outright rather than retired — resolves to the
 * default, so a temple in that position sees the application's own colours and can choose again.
 *
 * <p><b>What happens on the way out.</b> Signing out clears both the cache and the properties. The
 * sign-in screen belongs to no temple, and a shared terminal in a temple office should not greet
 * the next person in the last one's colours.
 */
export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const { appUser, status } = useAuth();
  const themeId = appUser?.themeId ?? null;
  const tenantId = appUser?.tenantId ?? null;
  // The first application of a session is not a change: the pre-paint script has already put this
  // palette up, and there is nothing for a crossfade to cross from. Switching temples later is.
  const painted = useRef(false);

  useEffect(() => {
    const root = document.documentElement;

    if (status === "signed-out") {
      applyPalette(root, null);
      safelyForget();
      return;
    }

    // "loading" is the state between a page load and whoami answering. Leaving whatever the
    // pre-paint script put there is the whole point of having cached it — clearing it here would
    // reintroduce the flash from the other direction.
    if (status !== "signed-in") {
      return;
    }

    const pack = themePackById(themeId);
    const paint = () => applyPalette(root, pack.palette, pack.surfaces ?? null);
    if (painted.current) {
      crossfadeTheme(paint);
    } else {
      paint();
      painted.current = true;
    }
    safelyRemember({
      tenantId,
      themeId: pack.id,
      palette: pack.palette,
      surfaces: pack.surfaces,
    });
  }, [status, themeId, tenantId]);

  return <>{children}</>;
}

/**
 * Both of these swallow everything they touch.
 *
 * <p>`localStorage` is not merely empty in a browser set to block site data — reading it throws,
 * and so does writing. It is a convenience that saves a flash on the next load, and nothing about
 * it is worth taking a screen down for.
 */
function safelyRemember(theme: CachedTheme) {
  try {
    window.localStorage.setItem(THEME_CACHE_KEY, JSON.stringify(theme));
  } catch {
    // The next load flashes. That is the whole cost.
  }
}

function safelyForget() {
  try {
    window.localStorage.removeItem(THEME_CACHE_KEY);
  } catch {
    // Nothing to do, and nothing that depends on it having worked.
  }
}
