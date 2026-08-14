import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";

const { authRef } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", userId: "me", fullName: "Gopal Das" },
    } as { status: string; appUser: { role: string; userId: string; fullName?: string } | null },
  },
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
// Every planner query returns empty; we assert the shell, the views, the period nav and the guard.
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ data: [], error: null, loading: false, reload: vi.fn() }),
}));

import PlannerPage from "@/app/planner/page";

/** Today's cell — a past day is deliberately read-only, so tests that plan must open this one. */
function todaysCell() {
  const label = new Date().toLocaleDateString(undefined, {
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
  });

  it("opens on the month, with weekday headers and the current period", () => {
    render(<PlannerPage />);
    expect(screen.getByRole("heading", { name: /meal plan/i })).toBeInTheDocument();
    expect(screen.getByText("Sun")).toBeInTheDocument();
    expect(screen.getByText("Sat")).toBeInTheDocument();
    const now = new Date();
    expect(
      screen.getByText(new RegExp(`${MONTHS[now.getMonth()]} ${now.getFullYear()}`))
    ).toBeInTheDocument();
  });

  it("moves to the next period and back", () => {
    render(<PlannerPage />);
    const now = new Date();
    const next = new Date(now.getFullYear(), now.getMonth() + 1, 1);

    fireEvent.click(screen.getByRole("button", { name: /next/i }));
    expect(
      screen.getByText(new RegExp(`${MONTHS[next.getMonth()]} ${next.getFullYear()}`))
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /previous/i }));
    expect(
      screen.getByText(new RegExp(`${MONTHS[now.getMonth()]} ${now.getFullYear()}`))
    ).toBeInTheDocument();
  });

  it("offers day, week and month views, and switches between them", () => {
    render(<PlannerPage />);
    const tabs = screen.getByRole("tablist", { name: /calendar view/i });

    expect(within(tabs).getByRole("tab", { name: "Month" })).toHaveAttribute("aria-selected", "true");

    fireEvent.click(within(tabs).getByRole("tab", { name: "Week" }));
    expect(within(tabs).getByRole("tab", { name: "Week" })).toHaveAttribute("aria-selected", "true");
    // The month grid's weekday header row is gone in the week view.
    expect(screen.queryByText("Sun")).not.toBeInTheDocument();

    fireEvent.click(within(tabs).getByRole("tab", { name: "Day" }));
    expect(screen.getByText(/nothing planned for this day yet/i)).toBeInTheDocument();
  });

  it("opens a day when its cell is clicked — the whole cell is the target", () => {
    render(<PlannerPage />);

    // Every day cell is one button, labelled with the date and what is planned on it.
    fireEvent.click(todaysCell());

    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /close/i })).toBeInTheDocument();
  });

  it("closes the day view on Escape", () => {
    render(<PlannerPage />);
    fireEvent.click(todaysCell());
    expect(screen.getByRole("dialog")).toBeInTheDocument();

    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("tells a planner with no recipes what to do instead of offering an empty list", () => {
    render(<PlannerPage />);
    fireEvent.click(todaysCell());

    expect(screen.getByText(/no recipes yet/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /add a recipe/i })).toBeInTheDocument();
  });

  it("offers the calendar correction to a temple admin only", () => {
    render(<PlannerPage />);
    fireEvent.click(todaysCell());
    // Kitchen staff: no correction. (With no calendar data the panel says so, and never offers it.)
    expect(screen.queryByRole("button", { name: /correct this date/i })).not.toBeInTheDocument();
  });

  it("shows a past day as read-only rather than offering to plan on it", () => {
    render(<PlannerPage />);
    // The first cell of the month grid is in the past for any date after the 1st.
    const cells = screen.getAllByRole("button", { name: /nothing planned$/i });
    fireEvent.click(cells[0]);

    const dialog = screen.getByRole("dialog");
    if (new Date().getDate() > 1) {
      expect(within(dialog).getByText(/this day has passed/i)).toBeInTheDocument();
      expect(within(dialog).queryByRole("button", { name: /add to the plan/i })).not.toBeInTheDocument();
    }
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
