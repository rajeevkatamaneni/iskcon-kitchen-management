import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { ApiError, AvailableShiftView } from "@/lib/api";

const { authRef, queryRef, reloadMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  queryRef: { current: { data: [] as AvailableShiftView[] | null, error: null as ApiError | null, loading: false } },
  reloadMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ ...queryRef.current, reload: reloadMock }),
}));

import AvailableShiftsPage from "@/app/shifts/page";

function shift(o: Partial<AvailableShiftView>): AvailableShiftView {
  return {
    id: "s1",
    title: "Sunday prep",
    description: null,
    shiftDate: "2026-12-06",
    startTime: "08:00",
    endTime: "12:00",
    location: "Main kitchen",
    capacity: 5,
    signedUpCount: 2,
    waitlistCount: 0,
    callerState: "AVAILABLE",
    ...o,
  };
}

describe("available shifts", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    queryRef.current = { data: [shift({})], error: null, loading: false };
    reloadMock.mockReset();
  });

  it("shows an open shift with a sign-up action and capacity", () => {
    render(<AvailableShiftsPage />);
    expect(screen.getByRole("heading", { name: /available shifts/i })).toBeInTheDocument();
    expect(screen.getByText("Sunday prep")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /sign up/i })).toBeInTheDocument();
    expect(screen.getByText(/2\/5 filled/i)).toBeInTheDocument();
  });

  it("offers the waitlist on a full shift", () => {
    queryRef.current = { data: [shift({ callerState: "FULL", signedUpCount: 5 })], error: null, loading: false };
    render(<AvailableShiftsPage />);
    expect(screen.getByRole("button", { name: /join waitlist/i })).toBeInTheDocument();
  });

  it("shows you're in when already signed up", () => {
    queryRef.current = { data: [shift({ callerState: "SIGNED_UP" })], error: null, loading: false };
    render(<AvailableShiftsPage />);
    expect(screen.getByText(/you’re in/i)).toBeInTheDocument();
  });

  it("refuses a non-volunteer", () => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    render(<AvailableShiftsPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
