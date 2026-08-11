import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { WeekScheduleView } from "@/lib/api";

// staff-schedule issues three useAuthedQuery calls in order: week, profiles, users.
const { authRef, returnsRef, reloadMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
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

import StaffSchedulePage from "@/app/staff-schedule/page";

const WEEK: WeekScheduleView = {
  weekStart: "2026-08-31",
  staff: [
    {
      staffProfileId: "p1",
      userId: "u1",
      fullName: "Head Cook A",
      designation: "Head Cook",
      days: [
        { date: "2026-08-31", dayOfWeek: 1, working: true, startTime: "09:00:00", endTime: "17:00:00", fromException: false },
        { date: "2026-09-01", dayOfWeek: 2, working: false, startTime: null, endTime: null, fromException: true },
        { date: "2026-09-02", dayOfWeek: 3, working: true, startTime: "09:00:00", endTime: "17:00:00", fromException: false },
        { date: "2026-09-03", dayOfWeek: 4, working: true, startTime: "09:00:00", endTime: "17:00:00", fromException: false },
        { date: "2026-09-04", dayOfWeek: 5, working: true, startTime: "09:00:00", endTime: "17:00:00", fromException: false },
        { date: "2026-09-05", dayOfWeek: 6, working: false, startTime: null, endTime: null, fromException: false },
        { date: "2026-09-06", dayOfWeek: 7, working: false, startTime: null, endTime: null, fromException: false },
      ],
    },
  ],
};

describe("staff schedule", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    returnsRef.current = [
      { data: WEEK, error: null, loading: false },
      { data: [], error: null, loading: false },
      { data: [], error: null, loading: false },
    ];
    returnsRef.i = 0;
    reloadMock.mockReset();
  });

  it("renders the weekly grid with hours and an off day from an exception", () => {
    render(<StaffSchedulePage />);
    expect(screen.getByRole("heading", { name: /staff schedule/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Head Cook A" })).toBeInTheDocument();
    expect(screen.getAllByText("09:00–17:00").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Off").length).toBeGreaterThan(0);
  });

  it("refuses a non-admin", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<StaffSchedulePage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
