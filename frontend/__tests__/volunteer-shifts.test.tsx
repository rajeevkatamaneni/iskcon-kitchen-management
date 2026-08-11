import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { ApiError, ShiftView } from "@/lib/api";

const { authRef, queryRef, reloadMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  queryRef: { current: { data: [] as ShiftView[] | null, error: null as ApiError | null, loading: false } },
  reloadMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ ...queryRef.current, reload: reloadMock }),
}));

import VolunteerShiftsPage from "@/app/volunteers/page";

function shift(o: Partial<ShiftView>): ShiftView {
  return {
    id: "s1",
    title: "Sunday prep",
    description: null,
    shiftDate: "2026-12-06",
    startTime: "08:00",
    endTime: "12:00",
    location: "Main kitchen",
    capacity: 5,
    reminderOffsetsMinutes: [1440],
    status: "OPEN",
    cancelReason: null,
    signedUpCount: 3,
    waitlistCount: 1,
    createdAt: "2026-08-01T00:00:00Z",
    ...o,
  };
}

describe("volunteer shift management", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    queryRef.current = { data: [shift({})], error: null, loading: false };
    reloadMock.mockReset();
  });

  it("lists posted shifts with fill counts and a post control", () => {
    render(<VolunteerShiftsPage />);
    expect(screen.getByRole("heading", { name: /volunteer shifts/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Sunday prep" })).toBeInTheDocument();
    expect(screen.getByText(/3\/5/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /post a shift/i })).toBeInTheDocument();
  });

  it("refuses a volunteer", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    render(<VolunteerShiftsPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
