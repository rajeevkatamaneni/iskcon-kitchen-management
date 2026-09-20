import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";

vi.mock("next/navigation", () => ({ usePathname: () => "/today" }));

/**
 * The temple's own arrangement of the menu, as the session carries it (T-421). Mutable so that one
 * test can hand the drawer an arrangement; every other test here runs on `null`, which is the
 * standard menu, exactly as before.
 */
let menuLayout: MenuLayout | null = null;

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({
    appUser: {
      userId: "u1",
      fullName: "Admin One",
      role: "TEMPLE_ADMIN",
      tenantName: "ISKCON South Bengaluru",
      tenantSlug: "iskcon-south-bengaluru",
      temples: [],
      menuLayout,
    },
    signOut: vi.fn(),
    switchTemple: vi.fn(),
  }),
}));

import { Sidebar } from "@/components/Sidebar";
import { standardMenu } from "@/lib/nav";
import type { MenuLayout } from "@/lib/api";

/**
 * The phone and portrait-tablet menu (T-225). jsdom does no layout and applies no media queries, so
 * nothing here can show the column hidden below 1024px or the page at full width — that was checked
 * by eye at 390, 768, 1280 and 1920 (see docs/work/proof/T-225.md). What these hold in place is the
 * behaviour: how it opens, every way it closes, where focus goes, and what a screen reader is told.
 */
function renderShell() {
  return render(
    <div className="flex min-h-screen">
      <Sidebar activeHref="/today" />
      <main>
        <button type="button">Something on the page</button>
      </main>
    </div>,
  );
}

function menuButton() {
  return screen.getByRole("button", { name: "Menu" });
}

describe("the phone menu", () => {
  beforeEach(() => {
    sessionStorage.clear();
    document.body.style.overflow = "";
    menuLayout = null;
  });

  it("starts closed, and its button says so and names what it controls", () => {
    renderShell();
    const button = menuButton();
    expect(button).toHaveAttribute("aria-expanded", "false");
    expect(button).toHaveAttribute("aria-controls", "app-menu");
    expect(document.getElementById("app-menu")).not.toBeNull();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    // The bar names the temple beside the button.
    const bar = button.closest("header")!;
    expect(within(bar).getByText("ISKCON South Bengaluru")).toBeInTheDocument();
  });

  it("opens as a named dialog, moves focus into it, and locks the page behind", () => {
    renderShell();
    fireEvent.click(menuButton());

    const dialog = screen.getByRole("dialog", { name: "Menu" });
    expect(dialog).toHaveAttribute("id", "app-menu");
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(menuButton()).toHaveAttribute("aria-expanded", "true");
    // The menu's own contents, not a copy of them.
    expect(within(dialog).getByRole("navigation", { name: "Main" })).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: /sign out/i })).toBeInTheDocument();

    expect(document.activeElement).toBe(within(dialog).getByRole("button", { name: "Close" }));
    expect(document.body.style.overflow).toBe("hidden");
    expect(screen.getByRole("main", { hidden: true })).toHaveAttribute("inert");
  });

  it("closes on Close and gives focus back to the Menu button", () => {
    renderShell();
    fireEvent.click(menuButton());
    fireEvent.click(screen.getByRole("button", { name: "Close" }));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(document.activeElement).toBe(menuButton());
    expect(menuButton()).toHaveAttribute("aria-expanded", "false");
    expect(document.body.style.overflow).toBe("");
    expect(screen.getByRole("main")).not.toHaveAttribute("inert");
  });

  it("closes on Escape, with focus back on Menu", () => {
    renderShell();
    fireEvent.click(menuButton());
    fireEvent.keyDown(document, { key: "Escape" });

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(document.activeElement).toBe(menuButton());
  });

  it("closes when the dimmed page behind it is pressed", () => {
    const { container } = renderShell();
    fireEvent.click(menuButton());
    const backdrop = container.querySelector("[data-menu-backdrop]") as HTMLElement;
    // The backdrop must stay pressable: everything else beside the drawer is inert, it is not.
    expect(backdrop).not.toHaveAttribute("inert");
    fireEvent.click(backdrop);

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(document.activeElement).toBe(menuButton());
  });

  it("closes when a destination is chosen", () => {
    renderShell();
    fireEvent.click(menuButton());
    const dialog = screen.getByRole("dialog", { name: "Menu" });
    fireEvent.click(within(dialog).getAllByRole("link")[0]);

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(document.body.style.overflow).toBe("");
  });

  it("keeps Tab inside the drawer while it is open", () => {
    renderShell();
    fireEvent.click(menuButton());
    const dialog = screen.getByRole("dialog", { name: "Menu" });
    const close = within(dialog).getByRole("button", { name: "Close" });
    const signOut = within(dialog).getByRole("button", { name: /sign out/i });

    // Forward off the last stop wraps to the first…
    signOut.focus();
    fireEvent.keyDown(signOut, { key: "Tab" });
    expect(document.activeElement).toBe(close);

    // …and backward off the first wraps to the last.
    fireEvent.keyDown(close, { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(signOut);
  });

  it("shows the temple's own arrangement, because the drawer is the same menu (T-421)", () => {
    // The drawer is not a second copy of the menu — it is the very same <nav>, repositioned — so an
    // arrangement reaching the column reaches the drawer too. That is the claim; this measures it
    // rather than assuming it, by reading the drawer's own links after opening it.
    menuLayout = {
      version: 1,
      groups: standardMenu().map((g) => ({
        id: g.id,
        title: g.id === "ordering" ? "Buying" : (g.title ?? null),
        items: g.id === "main" ? ["cost-per-serving", "today"] : g.items.map((i) => i.id),
      })),
    };
    renderShell();
    fireEvent.click(menuButton());
    const dialog = screen.getByRole("dialog", { name: "Menu" });
    const links = within(dialog).getAllByRole("link").map((a) => a.textContent);

    // The temple put Cost per serving first, and the three the arrangement left out of the first
    // group follow it in their standard order.
    expect(links.slice(0, 5)).toEqual([
      "Cost per serving", "Today", "Vaishnava calendar", "Meal planner", "Reuse a plan",
    ]);
    // And its own heading, in the drawer, above the group it renamed.
    expect(within(dialog).getByText("Buying")).toBeInTheDocument();
    expect(within(dialog).queryByText("Ordering")).not.toBeInTheDocument();
  });

  it("slides in only for people who have not asked for less motion", () => {
    renderShell();
    fireEvent.click(menuButton());
    const dialog = screen.getByRole("dialog", { name: "Menu" });
    expect(dialog.className).toContain("motion-safe:animate-drawer-in");
    expect(dialog.className).not.toMatch(/(^|\s)animate-drawer-in/);
  });
});
