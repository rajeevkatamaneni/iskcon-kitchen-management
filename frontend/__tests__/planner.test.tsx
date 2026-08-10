import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

const { authRef, reloadMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  reloadMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
// Every planner query returns empty; we assert the calendar shell, month nav, and the guard.
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ data: [], error: null, loading: false, reload: reloadMock }),
}));

import PlannerPage from "@/app/planner/page";

const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];

describe("meal planner", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    reloadMock.mockReset();
  });

  it("renders the calendar with weekday headers and the current month", () => {
    render(<PlannerPage />);
    expect(screen.getByRole("heading", { name: /meal plan/i })).toBeInTheDocument();
    expect(screen.getByText("Sun")).toBeInTheDocument();
    expect(screen.getByText("Sat")).toBeInTheDocument();
    const now = new Date();
    expect(screen.getByText(new RegExp(`${MONTHS[now.getMonth()]} ${now.getFullYear()}`))).toBeInTheDocument();
  });

  it("moves to the next month", () => {
    render(<PlannerPage />);
    const now = new Date();
    const next = new Date(now.getFullYear(), now.getMonth() + 1, 1);
    fireEvent.click(screen.getByRole("button", { name: /next month/i }));
    expect(screen.getByText(new RegExp(`${MONTHS[next.getMonth()]} ${next.getFullYear()}`))).toBeInTheDocument();
  });

  it("refuses a role without meal-plan access", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    render(<PlannerPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
