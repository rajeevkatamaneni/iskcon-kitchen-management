"use client";

import { useEffect } from "react";
import { useAuth } from "./auth-context";
import {
  applyPalette,
  isCompletePalette,
  THEME_CACHE_KEY,
  type CachedTheme,
} from "./theme";

/**
 * Paints the temple's chosen colours onto the document.
 *
 * <p>It renders nothing. Everything it does is to the `style` attribute of the document element,
 * where a custom property set by script beats the one compiled into the stylesheet — so a temple
 * that has chosen wears its own palette and one that has not wears the application's, with no
 * branch anywhere in the component tree.
 *
 * <p><b>Where the palette comes from.</b> `/whoami`, which every session already calls. It is not
 * a request of its own because every person at a temple sees the same colours whatever their role,
 * so the one call that establishes who somebody is should also establish what their application
 * looks like. Switching temples re-runs `whoami`, and the repaint follows for free.
 *
 * <p><b>What happens on the way out.</b> Signing out clears both the cache and the properties. The
 * sign-in screen belongs to no temple, and a shared terminal in a temple office should not greet
 * the next person in the last one's colours.
 */
export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const { appUser, status } = useAuth();
  const theme = appUser?.theme ?? null;

  // The pack itself, so this re-runs when the temple changes its choice or the person switches
  // temple, and not on every unrelated render of the auth context.
  const slug = theme?.slug ?? null;
  const palette = theme?.palette ?? null;

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
    if (!palette || !slug) {
      return;
    }

    // A pack that is missing a role, or carries something that is not a colour, would paint most
    // of the screen and leave the rest behind. Better to stay entirely on the default, which at
    // least looks like a decision somebody made.
    if (!isCompletePalette(palette)) {
      return;
    }

    applyPalette(root, palette);
    safelyRemember({ tenantId: appUser?.tenantId ?? null, slug, palette });
  }, [status, slug, palette, appUser?.tenantId]);

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
