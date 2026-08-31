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

// The screen reads its own address bar now (item 22), so the stub has to answer both halves of
// next/navigation: what the URL says, and what a click asks the router to do with it.
const { pushMock, replaceMock, paramsRef } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
  paramsRef: { current: new URLSearchParams() },
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  useSearchParams: () => paramsRef.current,
  useParams: () => ({ id: "id-1" }),
}));
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
    paramsRef.current = new URLSearchParams();
    pushMock.mockReset();
    replaceMock.mockReset();
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
    // Item 22: the open day is in the address bar, so this is a deep link rather than a click.
    paramsRef.current = new URLSearchParams("day=2026-08-23");
    render(<CalendarPage />);

    expect(screen.getAllByText(/pavitropana ekadasi/i).length).toBeGreaterThan(0);
    expect(screen.getByText(/no grains, no dal, no beans/i)).toBeInTheDocument();
  });

  it("treats a feast day as a feast, with the plate count that implies", () => {
    paramsRef.current = new URLSearchParams("day=2026-08-28");
    render(<CalendarPage />);

    expect(screen.getAllByText(/appearance of lord balarama/i).length).toBeGreaterThan(0);
    expect(screen.getByText(/three to four times the usual servings/i)).toBeInTheDocument();
  });

  // Opening a day narrows the panel inside the month already on screen, so it replaces rather than
  // pushes: reading down a month one day at a time would otherwise leave thirty entries behind it.
  it("puts the open day in the URL, and replaces so a month of clicks leaves no trail", () => {
    render(<CalendarPage />);
    fireEvent.click(screen.getByRole("button", { name: /^23 ◑/ }));
    expect(replaceMock).toHaveBeenCalledWith("/calendar?day=2026-08-23");
    expect(pushMock).not.toHaveBeenCalled();
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
    expect(screen.queryByText(/three to four times the usual servings/i)).not.toBeInTheDocument();
  });

  // A view is a change of what is shown, so it is pushed: back returns to the view before it
  // rather than throwing somebody off the calendar entirely.
  it("puts the view in the URL, and pushes so back returns to the one before", () => {
    render(<CalendarPage />);
    fireEvent.click(screen.getByRole("tab", { name: "Week" }));
    expect(pushMock).toHaveBeenCalledWith("/calendar?view=week");
  });

  it("opens in the view a deep link names", () => {
    paramsRef.current = new URLSearchParams("view=week");
    render(<CalendarPage />);
    expect(screen.getByRole("tab", { name: "Week" })).toHaveAttribute("aria-selected", "true");

    paramsRef.current = new URLSearchParams("view=year");
    render(<CalendarPage />);
    expect(screen.getAllByText("Festivals and fasts").length).toBeGreaterThan(0);
    expect(screen.getAllByText(/2 marked days in 2026/).length).toBeGreaterThan(0);
  });

  it("moves a month at a time, and comes back to today", () => {
    render(<CalendarPage />);

    // A month on, on the same day — the shared stepper keeps the day you were reading rather than
    // dropping you on the 1st, and clamps where the next month is shorter.
    fireEvent.click(screen.getByRole("button", { name: /next month/i }));
    expect(pushMock).toHaveBeenCalledWith("/calendar?date=2026-09-15");

    // …and the month a deep link names is the month that renders.
    paramsRef.current = new URLSearchParams("date=2026-09-01");
    render(<CalendarPage />);
    expect(screen.getAllByText("September 2026").length).toBeGreaterThan(0);

    pushMock.mockReset();
    fireEvent.click(screen.getAllByRole("button", { name: "Today" })[0]);
    expect(pushMock).toHaveBeenCalledWith("/calendar");
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
