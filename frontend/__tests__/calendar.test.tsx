import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import type { ApiError, CalendarDayView } from "@/lib/api";

const { authRef, queryRef } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", fullName: "Gopal Das", tenantName: "ISKCON Bengaluru" },
    } as { status: string; appUser: { role: string; fullName?: string; tenantName?: string } | null },
  },
  queryRef: {
    current: { data: null as CalendarDayView[] | null, error: null as ApiError | null, loading: false },
  },
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token", signOut: vi.fn() }),
}));
vi.mock("@/lib/use-authed-query", () => ({ useAuthedQuery: () => queryRef.current }));
// The screen anchors on the temple's today; pin it so the month under test never moves.
vi.mock("@/lib/format", async (orig) => {
  const actual = await orig<typeof import("@/lib/format")>();
  return { ...actual, todayIso: () => "2026-08-15" };
});

import CalendarPage from "@/app/calendar/page";

function day(overrides: Partial<CalendarDayView> & { date: string }): CalendarDayView {
  return {
    tithi: 16,
    paksa: 1,
    masa: 3,
    gaurabdaYear: 540,
    naksatra: 10,
    isEkadashi: false,
    ekadashiName: null,
    mahadvadashi: null,
    fastType: null,
    sunrise: "06:07:00",
    sunset: "18:41:00",
    festivals: [],
    overridden: false,
    overrideReason: null,
    ...overrides,
  };
}

const MONTH: CalendarDayView[] = [
  day({ date: "2026-08-15" }),
  day({ date: "2026-08-23", isEkadashi: true, ekadashiName: "Pavitropana" }),
  day({
    date: "2026-08-28",
    festivals: [{ text: "Appearance of Lord Balarama", priority: 100 }],
  }),
];

describe("the Vaishnava calendar", () => {
  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", fullName: "Gopal Das", tenantName: "ISKCON Bengaluru" },
    };
    queryRef.current = { data: MONTH, error: null, loading: false };
  });

  it("opens on this month, with the legend that explains the colours", () => {
    render(<CalendarPage />);

    expect(screen.getByRole("heading", { name: /vaishnava calendar/i })).toBeInTheDocument();
    expect(screen.getByText("August 2026")).toBeInTheDocument();
    expect(screen.getByText("Ekadasi")).toBeInTheDocument();
    expect(screen.getByText("Festival or feast")).toBeInTheDocument();
  });

  it("names the day it opens on, in the temple's own terms", () => {
    render(<CalendarPage />);

    // Gaura Dvitiya · Purva-phalguni naksatra · Sridhara masa — read off the day panel, since the
    // page subtitle names the masa too.
    const panel = screen.getByRole("region", { name: /15 august 2026|august 15, 2026/i });
    expect(within(panel).getByText(/gaura dvitiya · purva-phalguni naksatra · sridhara masa/i)).toBeInTheDocument();
    expect(within(panel).getByText("06:07")).toBeInTheDocument();
  });

  it("says what an ordinary day asks of the kitchen — nothing", () => {
    render(<CalendarPage />);
    expect(screen.getByText(/an ordinary day/i)).toBeInTheDocument();
  });

  it("tells the kitchen what a fasting day changes, when one is selected", () => {
    render(<CalendarPage />);

    fireEvent.click(screen.getByRole("button", { name: /^23 ◑/ }));

    expect(screen.getAllByText(/pavitropana ekadasi/i).length).toBeGreaterThan(0);
    expect(screen.getByText(/no grains, no dal, no beans/i)).toBeInTheDocument();
  });

  it("treats a feast day as a feast, with the plate count that implies", () => {
    render(<CalendarPage />);

    fireEvent.click(screen.getByRole("button", { name: /^28 ◑ 2 Appearance of Lord Balarama/ }));

    expect(screen.getAllByText(/appearance of lord balarama/i).length).toBeGreaterThan(0);
    expect(screen.getByText(/three to four times the usual plates/i)).toBeInTheDocument();
  });

  it("ends the day panel in the one thing to do about the day", () => {
    render(<CalendarPage />);

    const links = screen.getAllByRole("link", { name: /plan this day.s menu/i });
    expect(links[0]).toHaveAttribute("href", "/planner?date=2026-08-15");
  });

  it("colours only the feasts, not every acarya's day", () => {
    queryRef.current = {
      data: [
        ...MONTH,
        day({ date: "2026-08-16", festivals: [{ text: "Sri Vamsidasa Babaji -- Disappearance", priority: 500 }] }),
      ],
      error: null,
      loading: false,
    };
    render(<CalendarPage />);

    // Read out with a proper dash, and marked as an observance rather than a feast: it changes
    // nothing in the kitchen.
    fireEvent.click(screen.getByRole("button", { name: /^16 ◑ 2 Sri Vamsidasa Babaji — Disappearance/ }));
    expect(screen.queryByText(/three to four times the usual plates/i)).not.toBeInTheDocument();
  });

  it("switches to the week and the year", () => {
    render(<CalendarPage />);

    fireEvent.click(screen.getByRole("tab", { name: "Week" }));
    expect(screen.getByRole("tab", { name: "Week" })).toHaveAttribute("aria-selected", "true");

    fireEvent.click(screen.getByRole("tab", { name: "Year" }));
    expect(screen.getByText("Festivals and fasts")).toBeInTheDocument();
    expect(screen.getByText(/2 marked days in 2026/)).toBeInTheDocument();
  });

  it("moves a month at a time, and comes back to today", () => {
    render(<CalendarPage />);

    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(screen.getByText("September 2026")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Today" }));
    expect(screen.getByText("August 2026")).toBeInTheDocument();
  });

  it("says so plainly where the calendar has not been computed", () => {
    queryRef.current = { data: [], error: null, loading: false };
    render(<CalendarPage />);

    expect(screen.getByText(/has not been computed this far ahead/i)).toBeInTheDocument();
  });

  it("shows the error contract when the calendar cannot load", () => {
    queryRef.current = {
      data: null,
      loading: false,
      error: { code: "KMS-0000", message: "We couldn't load this.", action: "Try again." } as ApiError,
    };
    render(<CalendarPage />);

    expect(screen.getByRole("alert")).toBeInTheDocument();
  });

  it("is reachable from the menu, right below Today", () => {
    render(<CalendarPage />);

    const nav = screen.getByRole("navigation", { name: /main/i });
    const links = within(nav).getAllByRole("link");
    const hrefs = links.map((l) => l.getAttribute("href"));
    expect(hrefs[0]).toBe("/today");
    expect(hrefs[1]).toBe("/calendar");
  });
});
