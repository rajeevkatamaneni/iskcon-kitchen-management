import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { MyReleasedShiftView, MyShiftView, MyWaitlistView } from "@/lib/api";

// my-shifts issues three useAuthedQuery calls in order: my shifts, my waitlist, taken off (T-149).
const { authRef, returnsRef, reloadMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  returnsRef: { current: [] as Array<{ data: unknown; error: null; loading: boolean }>, i: 0 },
  reloadMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => {
    const list = returnsRef.current;
    const value = list[returnsRef.i % list.length];
    returnsRef.i += 1;
    return { ...value, reload: reloadMock };
  },
}));

import MyShiftsPage from "@/app/my-shifts/page";

const SHIFT: MyShiftView = {
  signupId: "su1",
  shiftId: "s1",
  title: "Sunday prep",
  shiftDate: "2026-12-06",
  startTime: "08:00",
  endTime: "12:00",
  location: "Main kitchen",
  source: "SIGNUP",
  signedUpAt: "2026-08-01T00:00:00Z",
};

const WAIT: MyWaitlistView = {
  shiftId: "s2",
  title: "Festival cooking",
  shiftDate: "2026-12-07",
  startTime: "06:00",
  endTime: "10:00",
  location: null,
  position: 2,
  joinedAt: "2026-08-01T00:00:00Z",
};

const REMOVED: MyReleasedShiftView = {
  signupId: "su9",
  shiftId: "s9",
  title: "Janmashtami midnight offering",
  shiftDate: "2026-12-10",
  startTime: "20:00",
  endTime: "02:00",
  location: "Temple hall",
  releasedAt: "2026-09-10T06:00:00Z",
  reason: "ROTA_CHANGED",
};

describe("my shifts", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    returnsRef.current = [
      { data: [SHIFT], error: null, loading: false },
      { data: [WAIT], error: null, loading: false },
      { data: [], error: null, loading: false },
    ];
    returnsRef.i = 0;
    reloadMock.mockReset();
  });

  it("lists upcoming shifts with a release action and the waitlist with positions", () => {
    render(<MyShiftsPage />);
    expect(screen.getByRole("heading", { name: /my shifts/i })).toBeInTheDocument();
    expect(screen.getByText("Sunday prep")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /release my spot/i })).toBeInTheDocument();
    expect(screen.getByText("Festival cooking")).toBeInTheDocument();
    expect(screen.getByText(/position 2/i)).toBeInTheDocument();
  });

  it("tells a volunteer their shift runs into the next morning (T-146)", () => {
    // The half a tester notices first. A devotee looking at "20:00–02:00" on their own list of
    // commitments is the person who most needs to be told which day they finish on.
    returnsRef.current = [
      { data: [{ ...SHIFT, title: "Midnight offering", startTime: "20:00", endTime: "02:00" }], error: null, loading: false },
      { data: [{ ...WAIT, startTime: "23:00", endTime: "03:00" }], error: null, loading: false },
      { data: [], error: null, loading: false },
    ];
    returnsRef.i = 0;
    render(<MyShiftsPage />);

    expect(screen.getByText(/20:00–02:00 \(next day\)/)).toBeInTheDocument();
    // And the waitlist half of the same screen, which reads its times from a different view type
    // and had its own copy of the dash.
    expect(screen.getByText(/23:00–03:00 \(next day\)/)).toBeInTheDocument();
  });

  it("shows an empty state with nothing signed up", () => {
    returnsRef.current = [
      { data: [], error: null, loading: false },
      { data: [], error: null, loading: false },
      { data: [], error: null, loading: false },
    ];
    returnsRef.i = 0;
    render(<MyShiftsPage />);
    expect(screen.getByText(/no upcoming shifts/i)).toBeInTheDocument();
  });

  it("lists the shifts a volunteer was taken off, with the day, the hours and the reason (T-149)", () => {
    returnsRef.current = [
      { data: [SHIFT], error: null, loading: false },
      { data: [], error: null, loading: false },
      {
        data: [REMOVED, { ...REMOVED, signupId: "su10", shiftId: "s10", title: "Ekadashi prep", startTime: "06:00", endTime: "09:00", location: null, reason: "SHIFT_CANCELLED" }],
        error: null,
        loading: false,
      },
    ];
    returnsRef.i = 0;
    render(<MyShiftsPage />);

    const section = screen.getByRole("heading", { name: /taken off in the last week/i }).closest("section")!;
    expect(section).toHaveTextContent("Janmashtami midnight offering");
    expect(section).toHaveTextContent("10 Dec 2026 · 20:00–02:00 (next day)");
    expect(section).toHaveTextContent("Temple hall");
    // The volunteer's words, as the removal message prints them after "Reason:", not the
    // coordinator's label.
    expect(section).toHaveTextContent("Reason: the rota changed.");
    expect(section).toHaveTextContent("Ekadashi prep");
    expect(section).toHaveTextContent("Reason: the shift was cancelled.");
    // Nothing to act on: they are no longer on it, so no release button rides along. The one
    // button on the page belongs to the upcoming shift above.
    expect(screen.getAllByRole("button", { name: /release my spot/i })).toHaveLength(1);
  });

  it("says nothing about being taken off when nobody was", () => {
    render(<MyShiftsPage />);
    expect(screen.getByText("Sunday prep")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /taken off/i })).not.toBeInTheDocument();
  });
});
