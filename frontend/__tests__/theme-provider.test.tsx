import { beforeEach, describe, expect, it, vi } from "vitest";
import { render } from "@testing-library/react";

const { authRef } = vi.hoisted(() => ({
  authRef: {
    current: { status: "loading", appUser: null } as {
      status: string;
      appUser: { tenantId: string | null; themeId: string | null } | null;
    },
  },
}));

vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));

import { ThemeProvider } from "@/lib/theme-context";
import { THEME_CACHE_KEY } from "@/lib/theme";
import { DEFAULT_THEME_PACK, THEME_PACKS } from "@/lib/theme-packs";

/**
 * The rule the whole feature comes down to, stated once and checked here:
 *
 * <p><b>Colour follows the temple, not the person.</b> A devotee who serves at three temples sees
 * three different applications, and the one they are looking at is the one they are signed in to.
 * Switching temples switches colours with it, without a reload and without anybody arranging for
 * it — which is the case most likely to be quietly broken by a later change, because it is the one
 * nobody thinks to try.
 */

const OCELLUS = THEME_PACKS.find((p) => p.id === "ocellus")!;

function accent() {
  return document.documentElement.style.getPropertyValue("--kms-accent");
}

function channelsOf(hex: string) {
  const n = parseInt(hex.slice(1), 16);
  return `${(n >> 16) & 255} ${(n >> 8) & 255} ${n & 255}`;
}

describe("the theme a session paints", () => {
  beforeEach(() => {
    document.documentElement.removeAttribute("style");
    window.localStorage.clear();
    authRef.current = { status: "loading", appUser: null };
  });

  it("paints the temple's chosen colours once whoami has answered", () => {
    authRef.current = {
      status: "signed-in",
      appUser: { tenantId: "temple-a", themeId: OCELLUS.id },
    };
    render(<ThemeProvider>{null}</ThemeProvider>);

    expect(accent()).toBe(channelsOf(OCELLUS.palette.accent));
  });

  it("paints the default for a temple that has never chosen", () => {
    authRef.current = { status: "signed-in", appUser: { tenantId: "temple-a", themeId: null } };
    render(<ThemeProvider>{null}</ThemeProvider>);

    expect(accent()).toBe(channelsOf(DEFAULT_THEME_PACK.palette.accent));
  });

  it("paints the default for a platform operator, who belongs to no temple", () => {
    authRef.current = { status: "signed-in", appUser: { tenantId: null, themeId: null } };
    render(<ThemeProvider>{null}</ThemeProvider>);

    expect(accent()).toBe(channelsOf(DEFAULT_THEME_PACK.palette.accent));
  });

  it("paints the default when the temple's choice no longer names anything", () => {
    // What a pack removed outright rather than retired leaves behind. The temple sees the
    // application's own colours and can choose again, rather than a half-painted screen.
    authRef.current = {
      status: "signed-in",
      appUser: { tenantId: "temple-a", themeId: "a-pack-that-was-deleted" },
    };
    render(<ThemeProvider>{null}</ThemeProvider>);

    expect(accent()).toBe(channelsOf(DEFAULT_THEME_PACK.palette.accent));
  });

  it("follows a devotee who switches to another temple, without a reload", () => {
    // Switching temples re-runs whoami, which is what changes both of these at once.
    authRef.current = {
      status: "signed-in",
      appUser: { tenantId: "temple-a", themeId: OCELLUS.id },
    };
    const view = render(<ThemeProvider>{null}</ThemeProvider>);
    expect(accent()).toBe(channelsOf(OCELLUS.palette.accent));

    authRef.current = {
      status: "signed-in",
      appUser: { tenantId: "temple-b", themeId: DEFAULT_THEME_PACK.id },
    };
    view.rerender(<ThemeProvider>{null}</ThemeProvider>);

    expect(accent()).toBe(channelsOf(DEFAULT_THEME_PACK.palette.accent));
  });

  it("leaves the pre-painted colours alone while whoami is still answering", () => {
    // "loading" is the gap between a page load and the server saying who this is. The pre-paint
    // script has already put the last-seen palette up; clearing it here would reintroduce the
    // flash from the other direction.
    document.documentElement.style.setProperty("--kms-accent", "1 2 3");
    authRef.current = { status: "loading", appUser: null };
    render(<ThemeProvider>{null}</ThemeProvider>);

    expect(accent()).toBe("1 2 3");
  });

  it("takes the temple's colours off on the way out", () => {
    // A shared terminal in a temple office should not greet the next person in the last one's
    // colours, and the sign-in screen belongs to no temple.
    authRef.current = {
      status: "signed-in",
      appUser: { tenantId: "temple-a", themeId: OCELLUS.id },
    };
    const view = render(<ThemeProvider>{null}</ThemeProvider>);
    expect(window.localStorage.getItem(THEME_CACHE_KEY)).not.toBeNull();

    authRef.current = { status: "signed-out", appUser: null };
    view.rerender(<ThemeProvider>{null}</ThemeProvider>);

    expect(accent()).toBe("");
    expect(window.localStorage.getItem(THEME_CACHE_KEY)).toBeNull();
  });

  it("remembers what it painted, so the next load does not flash", () => {
    authRef.current = {
      status: "signed-in",
      appUser: { tenantId: "temple-a", themeId: OCELLUS.id },
    };
    render(<ThemeProvider>{null}</ThemeProvider>);

    const cached = JSON.parse(window.localStorage.getItem(THEME_CACHE_KEY)!);
    expect(cached.themeId).toBe(OCELLUS.id);
    expect(cached.tenantId).toBe("temple-a");
    expect(cached.palette.accent).toBe(OCELLUS.palette.accent);
  });
});
