import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { MyShiftView, MyWaitlistView } from "@/lib/api";

// my-shifts issues two useAuthedQuery calls in order: my shifts, my waitlist.
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

describe("my shifts", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    returnsRef.current = [
      { data: [SHIFT], error: null, loading: false },
      { data: [WAIT], error: null, loading: false },
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

  it("shows an empty state with nothing signed up", () => {
    returnsRef.current = [
      { data: [], error: null, loading: false },
      { data: [], error: null, loading: false },
    ];
    returnsRef.i = 0;
    render(<MyShiftsPage />);
    expect(screen.getByText(/no upcoming shifts/i)).toBeInTheDocument();
  });
});
