import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

/**
 * The confirmation a meal's screen leaves for the planner (T-311).
 *
 * <p>Saving a new meal sends the person back with `?saved=planned`. The planner said "The meal was
 * planned."; the separate day page put the flag straight into its own sentence and said "planned was
 * saved." Both pages now ask one function for the sentence, and these tests hold the two pages to the
 * same words for the same save, for both writers of the flag: the composer (`planned`) and the edit
 * screen (the meal's own name).
 */

const { authRef, searchRef, routeRef } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", userId: "me", fullName: "Radha Devi", tenantName: "ISKCON Bengaluru" },
    } as { status: string; appUser: Record<string, unknown> | null },
  },
  searchRef: { current: new URLSearchParams() },
  routeRef: { current: { date: "2026-10-05" } as Record<string, string> },
}));

vi.mock("next/navigation", () => ({
  useParams: () => routeRef.current,
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn(), refresh: vi.fn() }),
  useSearchParams: () => searchRef.current,
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "t" }),
}));
// Nothing planned anywhere: the notice is all these tests look at, and an empty plan is the simplest
// screen both pages can draw around it.
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ data: [], error: null, loading: false, reload: vi.fn() }),
}));

import PlannerDayPage from "@/app/planner/[date]/page";
import PlannerPage from "@/app/planner/page";
import { savedNotice } from "@/components/planner/savedNotice";

function arriveWith(flag: string) {
  searchRef.current = new URLSearchParams({ saved: flag });
}

describe("the confirmation after a save reads the same on the day page and the planner", () => {
  beforeEach(() => {
    searchRef.current = new URLSearchParams();
  });

  // The composer's flag. This is the defect: the day page said "planned was saved."
  it("says a new meal was planned on the day page", async () => {
    arriveWith("planned");
    render(<PlannerDayPage />);
    expect(await screen.findByText("The meal was planned.")).toBeInTheDocument();
    expect(screen.queryByText(/planned was saved/)).not.toBeInTheDocument();
  });

  it("says a new meal was planned on the planner, in the same words", async () => {
    arriveWith("planned");
    render(<PlannerPage />);
    expect(await screen.findByText("The meal was planned.")).toBeInTheDocument();
    expect(screen.queryByText(/planned was saved/)).not.toBeInTheDocument();
  });

  // The edit screen's flag: the meal's own name, a meal kind or an event's name.
  it.each(["Lunch", "Janmashtami feast"])("says %s was saved on both pages", async (name) => {
    arriveWith(name);
    const day = render(<PlannerDayPage />);
    expect(await screen.findByText(`${name} was saved.`)).toBeInTheDocument();
    day.unmount();

    render(<PlannerPage />);
    expect(await screen.findByText(`${name} was saved.`)).toBeInTheDocument();
  });

  it("says nothing on either page when nothing was saved", () => {
    const day = render(<PlannerDayPage />);
    expect(screen.queryByText(/was (saved|planned)\./)).not.toBeInTheDocument();
    day.unmount();
    render(<PlannerPage />);
    expect(screen.queryByText(/was (saved|planned)\./)).not.toBeInTheDocument();
  });
});

describe("savedNotice", () => {
  it("turns each flag the planner is sent into a whole sentence", () => {
    expect(savedNotice("planned")).toBe("The meal was planned.");
    expect(savedNotice("Lunch")).toBe("Lunch was saved.");
    expect(savedNotice("Janmashtami feast")).toBe("Janmashtami feast was saved.");
  });

  // A blank flag would otherwise read " was saved." with nothing in front of it.
  it("has nothing to say for an absent or blank flag", () => {
    expect(savedNotice(null)).toBeNull();
    expect(savedNotice(undefined)).toBeNull();
    expect(savedNotice("")).toBeNull();
    expect(savedNotice("   ")).toBeNull();
  });
});
