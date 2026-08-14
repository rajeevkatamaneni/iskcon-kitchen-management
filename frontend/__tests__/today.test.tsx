import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import type { ApiError, TodayView } from "@/lib/api";

// Today is role-gated and reads a single assembled payload. Drive the guard and the query from
// mutable refs so each case states only what it is about.
const { authRef, queryRef } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", fullName: "Radha Devi", tenantName: "ISKCON Bengaluru" },
    } as { status: string; appUser: { role: string; fullName?: string; tenantName?: string } | null },
  },
  queryRef: {
    current: { data: null as TodayView | null, error: null as ApiError | null, loading: false },
  },
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token", signOut: vi.fn() }),
}));
vi.mock("@/lib/use-authed-query", () => ({ useAuthedQuery: () => queryRef.current }));

import TodayPage from "@/app/today/page";

function today(overrides: Partial<TodayView> = {}): TodayView {
  return {
    date: "2026-08-14",
    calendar: {
      fastingToday: false,
      fastingTomorrow: false,
      todayName: null,
      tomorrowName: null,
      sunrise: "06:05:00",
    },
    meals: [
      {
        id: "m1",
        mealKind: "Lunch",
        readyBy: "12:00:00",
        recipeName: "Khichdi",
        targetServings: 820,
        status: "PLANNED",
        occasionName: null,
      },
      {
        id: "m2",
        mealKind: "Dinner",
        readyBy: "19:30:00",
        recipeName: "Upma",
        targetServings: 420,
        status: "COOKED",
        occasionName: null,
      },
    ],
    platesToday: 1240,
    itemsBelowThreshold: 6,
    unfilledShiftSpots: 3,
    nextUnfilledShift: "Sunday feast, 07:00",
    giving: { monthToDate: 240000, since: "2026-08-01" },
    deliveries: [
      {
        purchaseOrderId: "po1",
        poNumber: "PO-2026-0001",
        vendorName: "Govind Wholesale",
        neededBy: "2026-08-14",
        state: "AWAITED",
      },
    ],
    ...overrides,
  };
}

describe("today", () => {
  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", fullName: "Radha Devi", tenantName: "ISKCON Bengaluru" },
    };
    queryRef.current = { data: today(), error: null, loading: false };
  });

  it("opens on the date, and says what the day holds", () => {
    render(<TodayPage />);

    // The date renders in the reader's own locale ("14 August 2026" in India, "August 14, 2026"
    // in a US-defaulted test runner), so assert the parts rather than one country's order.
    const heading = screen.getByRole("heading", { level: 1 });
    expect(heading).toHaveTextContent(/friday/i);
    expect(heading).toHaveTextContent(/august/i);
    expect(heading).toHaveTextContent(/14/);
    expect(heading).toHaveTextContent(/2026/);
    expect(screen.getByText(/1,240 plates across 2 meals/i)).toBeInTheDocument();
  });

  it("answers the four questions, each as a way into the screen that acts on it", () => {
    render(<TodayPage />);

    expect(screen.getByRole("link", { name: /plates today/i })).toHaveAttribute("href", "/planner");
    expect(screen.getByRole("link", { name: /items below par/i })).toHaveAttribute("href", "/inventory");
    expect(screen.getByRole("link", { name: /shifts unfilled/i })).toHaveAttribute("href", "/volunteers");
    expect(screen.getByRole("link", { name: /given this month/i })).toHaveAttribute("href", "/ledger");

    expect(screen.getByRole("link", { name: /shifts unfilled/i })).toHaveTextContent("Sunday feast, 07:00");
    expect(screen.getByRole("link", { name: /given this month/i })).toHaveTextContent("₹2,40,000");
  });

  it("lists the meals in the order the kitchen has to have them ready, with their state", () => {
    render(<TodayPage />);

    const meals = screen.getByRole("region", { name: /meals in the kitchen/i });
    const rows = within(meals).getAllByText(/khichdi|upma/i);
    expect(rows[0]).toHaveTextContent("Lunch — Khichdi");
    expect(within(meals).getByText("12:00")).toBeInTheDocument();
    expect(within(meals).getByText("Cooked")).toBeInTheDocument();
  });

  it("shows a fasting day as a banner, because it changes every menu on it", () => {
    queryRef.current = {
      data: today({
        calendar: {
          fastingToday: false,
          fastingTomorrow: true,
          todayName: null,
          tomorrowName: "Ekadashi",
          sunrise: "06:05:00",
        },
      }),
      error: null,
      loading: false,
    };
    render(<TodayPage />);

    expect(screen.getByText(/tomorrow is a fasting day \(ekadashi\)/i)).toBeInTheDocument();
  });

  it("says nothing about fasting when the temple has no calendar computed", () => {
    queryRef.current = { data: today({ calendar: null }), error: null, loading: false };
    render(<TodayPage />);

    expect(screen.queryByText(/fasting day/i)).not.toBeInTheDocument();
  });

  it("withholds giving from kitchen staff rather than showing them a zero", () => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", fullName: "Gopal Das", tenantName: "ISKCON Bengaluru" },
    };
    queryRef.current = { data: today({ giving: null }), error: null, loading: false };
    render(<TodayPage />);

    expect(screen.queryByText(/given this month/i)).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: /plates today/i })).toBeInTheDocument();
  });

  it("tells a temple with nothing in it what would fill the screen", () => {
    queryRef.current = {
      data: today({ meals: [], platesToday: 0, deliveries: [], itemsBelowThreshold: 0 }),
      error: null,
      loading: false,
    };
    render(<TodayPage />);

    expect(screen.getByText(/nothing planned for today/i)).toBeInTheDocument();
    expect(screen.getByText(/nothing due today/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /open the planner/i })).toBeInTheDocument();
  });

  it("shows the error contract when the screen cannot load", () => {
    queryRef.current = {
      data: null,
      loading: false,
      error: { code: "KMS-0000", message: "We couldn't load this.", action: "Try again." } as ApiError,
    };
    render(<TodayPage />);

    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByText("KMS-0000")).toBeInTheDocument();
  });

  it("refuses a volunteer, who has no temple day to run", () => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "VOLUNTEER", fullName: "A Devotee", tenantName: "ISKCON Bengaluru" },
    };
    render(<TodayPage />);

    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });

  it("names the temple in the menu", () => {
    render(<TodayPage />);

    const nav = screen.getByRole("navigation", { name: /main/i });
    expect(within(nav).getByText("ISKCON Bengaluru")).toBeInTheDocument();
  });
});
