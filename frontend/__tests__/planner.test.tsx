import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";

const { authRef, queryRef } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", userId: "me", fullName: "Gopal Das" },
    } as { status: string; appUser: { role: string; userId: string; fullName?: string } | null },
  },
  // Every planner query goes through the one hook, so one array feeds them all. Empty by default,
  // which is what the shell, the views and the period nav are asserted against; a test that needs a
  // calendar day sets it and the meal and sufficiency lookups simply find nothing in it.
  queryRef: { current: [] as unknown[] },
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ data: queryRef.current, error: null, loading: false, reload: vi.fn() }),
}));

import PlannerPage from "@/app/planner/page";
import { todayIso } from "@/lib/format";

/** Today's cell — a past day is deliberately read-only, so tests that plan must open this one. */
function todaysCell() {
  // The temple's day, as the planner anchors on — not the test machine's, which is a day behind
  // for a good part of every IST morning and would land the click on a read-only past day.
  const label = new Date(`${todayIso()}T00:00:00`).toLocaleDateString(undefined, {
    weekday: "long", day: "numeric", month: "long", year: "numeric",
  });
  return screen.getByRole("button", { name: new RegExp(`^${label}, nothing planned$`) });
}

const MONTHS = ["January","February","March","April","May","June","July","August","September","October","November","December"];

describe("meal planner", () => {
  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", userId: "me", fullName: "Gopal Das" },
    };
    queryRef.current = [];
  });

  it("keeps a long festival name inside its month cell, with the whole name on hover", () => {
    // The name that broke it: the month box is narrow, and this ran straight out of the side of it.
    const name = "Sri Raghunandana Thakura -- Disappearance";
    queryRef.current = [
      {
        date: todayIso(),
        tithi: 0, paksa: 0, masa: 0, gaurabdaYear: null, naksatra: null,
        isEkadashi: false, ekadashiName: null, mahadvadashi: null, fastType: null,
        sunrise: null, sunset: null,
        festivals: [{ text: name, priority: 1 }],
        overridden: false, overrideReason: null,
      },
    ];
    render(<PlannerPage />);
    fireEvent.click(
      within(screen.getByRole("tablist", { name: /calendar view/i })).getByRole("tab", { name: "Month" })
    );

    const label = screen.getAllByTitle(name)[0];
    expect(label).toHaveTextContent(name);
    // Truncation, and a width cap that can actually bite inside a flex row — `truncate` on its own
    // could not, because a flex item's min-width is auto and the name grew to its content.
    expect(label.className).toContain("truncate");
    expect(label.className).toContain("min-w-0");
    expect(label.className).toContain("max-w-full");
  });

  it("opens on the day, because a day is where the work is", () => {
    render(<PlannerPage />);
    expect(screen.getByRole("heading", { name: /meal planner/i })).toBeInTheDocument();

    const tabs = screen.getByRole("tablist", { name: /calendar view/i });
    expect(within(tabs).getByRole("tab", { name: "Day" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByText(/nothing planned for this day yet/i)).toBeInTheDocument();
  });

  it("names today on the pill, and the date once you have moved off it", () => {
    render(<PlannerPage />);
    expect(screen.getByRole("button", { name: /^today$/i })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /next day/i }));
    expect(screen.queryByRole("button", { name: /^today$/i })).not.toBeInTheDocument();

    // The pill is also the way back: it names the day you are on, and returns you to today.
    fireEvent.click(screen.getByRole("button", { name: /back to today/i }));
    expect(screen.getByRole("button", { name: /^today$/i })).toBeInTheDocument();
  });

  it("offers day, week and month views, and switches between them", () => {
    render(<PlannerPage />);
    const tabs = screen.getByRole("tablist", { name: /calendar view/i });

    fireEvent.click(within(tabs).getByRole("tab", { name: "Month" }));
    expect(screen.getByText("Sun")).toBeInTheDocument();
    const now = new Date();
    expect(
      screen.getByText(new RegExp(`${MONTHS[now.getMonth()]} ${now.getFullYear()}`))
    ).toBeInTheDocument();

    fireEvent.click(within(tabs).getByRole("tab", { name: "Week" }));
    expect(screen.getByText(/week of/i)).toBeInTheDocument();
    // Seven days, each its own card — where the month draws six weeks of them.
    expect(screen.getAllByRole("button", { name: /nothing planned$/i })).toHaveLength(7);
  });

  it("only Day carries the date navigator — the others name their period instead", () => {
    render(<PlannerPage />);
    const tabs = screen.getByRole("tablist", { name: /calendar view/i });

    expect(screen.getByRole("button", { name: /next day/i })).toBeInTheDocument();
    fireEvent.click(within(tabs).getByRole("tab", { name: "Month" }));
    expect(screen.queryByRole("button", { name: /next day/i })).not.toBeInTheDocument();
  });

  it("a click in the month lands on that day, rather than opening a panel over the grid", () => {
    render(<PlannerPage />);
    fireEvent.click(within(screen.getByRole("tablist", { name: /calendar view/i })).getByRole("tab", { name: "Month" }));

    fireEvent.click(todaysCell());

    const tabs = screen.getByRole("tablist", { name: /calendar view/i });
    expect(within(tabs).getByRole("tab", { name: "Day" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("button", { name: /^today$/i })).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("a click in the week does the same", () => {
    render(<PlannerPage />);
    fireEvent.click(within(screen.getByRole("tablist", { name: /calendar view/i })).getByRole("tab", { name: "Week" }));

    fireEvent.click(todaysCell());

    const tabs = screen.getByRole("tablist", { name: /calendar view/i });
    expect(within(tabs).getByRole("tab", { name: "Day" })).toHaveAttribute("aria-selected", "true");
  });

  it("adding a meal composes in place, not in a panel over the page", () => {
    render(<PlannerPage />);

    fireEvent.click(screen.getByRole("button", { name: /add a meal/i }));

    // The composer takes the place of the button, under the day it belongs to. (With no recipes
    // loaded it says so — what matters here is that nothing opened over the page.)
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /add a meal/i })).not.toBeInTheDocument();
    expect(screen.getByText(/no recipes yet/i)).toBeInTheDocument();
  });

  it("tells a planner with no recipes what to do instead of offering an empty list", () => {
    render(<PlannerPage />);
    fireEvent.click(screen.getByRole("button", { name: /add a meal/i }));

    expect(screen.getByText(/no recipes yet/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /add a recipe/i })).toBeInTheDocument();
  });

  it("offers the calendar correction to a temple admin only", () => {
    render(<PlannerPage />);
    // Kitchen staff: no correction. (With no calendar data the panel says so, and never offers it.)
    expect(screen.queryByRole("button", { name: /correct this date/i })).not.toBeInTheDocument();
  });

  it("shows a past day as read-only rather than offering to plan on it", () => {
    render(<PlannerPage />);

    fireEvent.click(screen.getByRole("button", { name: /previous day/i }));
    expect(screen.queryByRole("button", { name: /add a meal/i })).not.toBeInTheDocument();
  });

  it("refuses a role without meal-plan access", () => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "VOLUNTEER", userId: "me", fullName: "Nitai Das" },
    };
    render(<PlannerPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
