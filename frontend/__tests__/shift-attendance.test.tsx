import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { RosterSignup, RosterView } from "@/lib/api";

/**
 * The roster screen's B7 half: marking who turned up, and taking a named volunteer off.
 *
 * <p>The test that earns its place here is {@code unmarked reads as unmarked}. Every other
 * assertion in this file would pass just as happily against a screen that rendered a null
 * `attended` as "Did not come" — which is the one way this feature can be worse than not having it,
 * because it accuses somebody.
 */

const { authRef, queryRef, reloadMock, recordAttendanceMock, releaseVolunteerMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  queryRef: { current: { data: null as RosterView | null, error: null, loading: false } },
  reloadMock: vi.fn(),
  recordAttendanceMock: vi.fn(),
  releaseVolunteerMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useParams: () => ({ id: "shift-1" }),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ ...queryRef.current, reload: reloadMock }),
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      recordShiftAttendance: recordAttendanceMock,
      releaseVolunteerFromShift: releaseVolunteerMock,
    },
  };
});

import ShiftRosterPage from "@/app/volunteers/[id]/page";

function signup(o: Partial<RosterSignup> = {}): RosterSignup {
  return {
    userId: "u1",
    fullName: "Radha Devi",
    source: "SIGNUP",
    signedUpAt: "2026-11-01T04:00:00Z",
    releasedAt: null,
    attended: null,
    attendanceRecordedAt: null,
    reminders: [],
    ...o,
  };
}

function roster(signups: RosterSignup[], status: "OPEN" | "CANCELLED" = "OPEN"): RosterView {
  return {
    shift: {
      id: "shift-1",
      title: "Sunday prep",
      description: null,
      shiftDate: "2026-12-06",
      startTime: "08:00:00",
      endTime: "12:00:00",
      location: "Main kitchen",
      capacity: 5,
      reminderOffsetsMinutes: [1440],
      status,
      cancelReason: null,
      signedUpCount: signups.filter((s) => !s.releasedAt).length,
      waitlistCount: 0,
      createdAt: "2026-10-01T04:00:00Z",
    },
    signups,
    waitlist: [],
    broadcasts: [],
  };
}

describe("marking attendance on a roster", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    reloadMock.mockReset();
    recordAttendanceMock.mockReset().mockResolvedValue(undefined);
    releaseVolunteerMock.mockReset().mockResolvedValue(undefined);
    queryRef.current = {
      data: roster([
        signup(),
        signup({ userId: "u2", fullName: "Gopal Das" }),
      ]),
      error: null,
      loading: false,
    };
  });

  it("offers a tick per volunteer, ticked to start", () => {
    render(<ShiftRosterPage />);
    expect(screen.getByRole("checkbox", { name: /radha devi came/i })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: /gopal das came/i })).toBeChecked();
  });

  it("sends everyone on the roster, the unticked as absent", async () => {
    render(<ShiftRosterPage />);
    fireEvent.click(screen.getByRole("checkbox", { name: /gopal das came/i }));
    fireEvent.submit(screen.getByRole("form", { name: /attendance/i }));

    await waitFor(() => expect(recordAttendanceMock).toHaveBeenCalled());
    expect(recordAttendanceMock.mock.calls[0][0]).toBe("shift-1");
    // Both people are named. Leaving the unticked one out would leave them *unmarked*, which is a
    // different fact from "did not come" and the one this screen has just been told is untrue.
    expect(recordAttendanceMock.mock.calls[0][1]).toEqual({
      marks: [
        { userId: "u1", attended: true },
        { userId: "u2", attended: false },
      ],
    });
    await waitFor(() => expect(reloadMock).toHaveBeenCalled());
  });

  it("shows the answer once it is recorded, and offers no second marking", () => {
    queryRef.current = {
      data: roster([
        signup({ attended: true, attendanceRecordedAt: "2026-12-06T12:30:00Z" }),
        signup({
          userId: "u2",
          fullName: "Gopal Das",
          attended: false,
          attendanceRecordedAt: "2026-12-06T12:30:00Z",
        }),
      ]),
      error: null,
      loading: false,
    };
    render(<ShiftRosterPage />);

    expect(screen.queryByRole("checkbox", { name: /came/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /save attendance/i })).not.toBeInTheDocument();
    expect(screen.getByText("Came")).toBeInTheDocument();
    expect(screen.getByText("Did not come")).toBeInTheDocument();
  });

  it("reads an unmarked signup as unmarked, never as an absence", () => {
    // Somebody signed up after the shift was marked. `attended` is null for them and false for the
    // person beside them, and the screen must not blur the two.
    queryRef.current = {
      data: roster([
        signup({ attended: false, attendanceRecordedAt: "2026-12-06T12:30:00Z" }),
        signup({ userId: "u2", fullName: "Gopal Das" }),
      ]),
      error: null,
      loading: false,
    };
    render(<ShiftRosterPage />);

    expect(screen.getByText("Not marked")).toBeInTheDocument();
    // Exactly one accusation on the screen, and it belongs to the person who was actually marked.
    expect(screen.getAllByText("Did not come")).toHaveLength(1);
  });
});

describe("taking a volunteer off a roster", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    reloadMock.mockReset();
    recordAttendanceMock.mockReset().mockResolvedValue(undefined);
    releaseVolunteerMock.mockReset().mockResolvedValue(undefined);
    queryRef.current = {
      data: roster([signup(), signup({ userId: "u2", fullName: "Gopal Das" })]),
      error: null,
      loading: false,
    };
  });

  it("names the person being removed, and says so afterwards", async () => {
    render(<ShiftRosterPage />);
    fireEvent.click(screen.getAllByRole("button", { name: /remove/i })[1]);

    await waitFor(() => expect(releaseVolunteerMock).toHaveBeenCalled());
    expect(releaseVolunteerMock.mock.calls[0][0]).toBe("shift-1");
    expect(releaseVolunteerMock.mock.calls[0][1]).toBe("u2");
    await waitFor(() => expect(reloadMock).toHaveBeenCalled());
    expect(await screen.findByText(/gopal das was taken off this shift/i)).toBeInTheDocument();
  });

  it("shows a released volunteer under Released, with no attendance tick", () => {
    queryRef.current = {
      data: roster([
        signup(),
        signup({ userId: "u2", fullName: "Gopal Das", releasedAt: "2026-11-20T06:00:00Z" }),
      ]),
      error: null,
      loading: false,
    };
    render(<ShiftRosterPage />);

    expect(screen.getByRole("heading", { name: /released/i })).toBeInTheDocument();
    expect(screen.getByText(/gopal das — released/i)).toBeInTheDocument();
    expect(screen.queryByRole("checkbox", { name: /gopal das came/i })).not.toBeInTheDocument();
  });

  it("offers no removal on a cancelled shift", () => {
    queryRef.current = { data: roster([signup()], "CANCELLED"), error: null, loading: false };
    render(<ShiftRosterPage />);
    expect(screen.queryByRole("button", { name: /remove/i })).not.toBeInTheDocument();
  });
});
