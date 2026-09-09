import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { RosterSignup, RosterView } from "@/lib/api";

/**
 * The roster screen's B7 half: marking who turned up, and taking a named volunteer off.
 *
 * <p>The test that earns its place here is {@code unmarked reads as unmarked}. Every other
 * assertion in this file would pass just as happily against a screen that rendered a null
 * `attended` as "Did not come" — which is the one way this feature can be worse than not having it,
 * because it accuses somebody.
 *
 * <p>The third block is T-079: changing a mark. Its second test is the one that matters most — the
 * volunteer a partial marking left out, who before this could never be marked at all, and who is
 * the case a reader would assume the first test already covered.
 */

const {
  authRef,
  queryRef,
  reloadMock,
  recordAttendanceMock,
  correctAttendanceMock,
  releaseVolunteerMock,
} = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  queryRef: { current: { data: null as RosterView | null, error: null, loading: false } },
  reloadMock: vi.fn(),
  recordAttendanceMock: vi.fn(),
  correctAttendanceMock: vi.fn(),
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
      correctShiftAttendance: correctAttendanceMock,
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

/**
 * The clock, pinned, because attendance is now a question about time (T-085): the roster below is
 * dated 2026-12-06 and the control only exists once that shift has started. Left to the real clock
 * every marking test here would pass until 6 December 2026 and then quietly stop testing anything.
 *
 * <p>The offset is written out for the reason `reuse-plan.test.tsx` gives at length: a literal with
 * no offset is parsed in the machine's zone, so the suite would mean a different instant depending
 * on where it ran. 09:30 in the temple is an hour and a half into an 08:00 shift.
 *
 * <p>Only `Date` is faked — RTL's `findBy*` polls on real timers — and the clock is handed back in
 * `afterAll`.
 */
const AFTER_THE_SHIFT_STARTED = new Date("2026-12-06T09:30:00+05:30");

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
  beforeAll(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(AFTER_THE_SHIFT_STARTED);
  });

  afterAll(() => {
    vi.useRealTimers();
  });

  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    reloadMock.mockReset();
    recordAttendanceMock.mockReset().mockResolvedValue(undefined);
    correctAttendanceMock.mockReset().mockResolvedValue(undefined);
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

  it("offers no marking of a shift that has not started, and says why", () => {
    // T-085. The tick is `defaultChecked` and marking is once per shift with nothing in the product
    // to change a mark afterwards, so a coordinator opening tomorrow's roster and pressing Save
    // recorded the whole crew as having come to a shift that had not happened — for good. The
    // server refuses it with KMS-400144; the screen must not offer the press in the first place.
    vi.setSystemTime(new Date("2026-12-05T09:30:00+05:30")); // the day before

    render(<ShiftRosterPage />);

    expect(screen.queryByRole("checkbox", { name: /came/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /save attendance/i })).not.toBeInTheDocument();
    // Two assertions of an absence, so this one says what is there instead: a refusal a coordinator
    // can read, rather than a table that has silently lost its controls.
    expect(screen.getByText(/attendance can be marked once this shift has started/i)).toBeInTheDocument();

    vi.setSystemTime(AFTER_THE_SHIFT_STARTED);
  });

  it("offers marking from the minute the shift starts", () => {
    // The positive control for the test above, and the boundary the server draws in the same place:
    // `start.isAfter(now)` is false at exactly 08:00, so 08:00 is markable. Without this, a screen
    // that had simply lost its attendance controls altogether would pass the not-started test.
    vi.setSystemTime(new Date("2026-12-06T08:00:00+05:30"));

    render(<ShiftRosterPage />);

    expect(screen.getByRole("checkbox", { name: /radha devi came/i })).toBeChecked();
    expect(screen.getByRole("button", { name: /save attendance/i })).toBeInTheDocument();

    vi.setSystemTime(AFTER_THE_SHIFT_STARTED);
  });
});

describe("changing a mark once attendance is recorded", () => {
  // T-079. Pinned for the same reason as the block above: the correction controls exist only on a
  // shift that has started, so against the real clock every test here would stop testing anything on
  // 6 December 2026 rather than fail.
  beforeAll(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(AFTER_THE_SHIFT_STARTED);
  });

  afterAll(() => {
    vi.useRealTimers();
  });

  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    reloadMock.mockReset();
    recordAttendanceMock.mockReset().mockResolvedValue(undefined);
    correctAttendanceMock.mockReset().mockResolvedValue(undefined);
    releaseVolunteerMock.mockReset().mockResolvedValue(undefined);
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
  });

  function row(name: string): HTMLElement {
    return screen.getByText(name).closest("tr") as HTMLElement;
  }

  it("offers each row the answer it does not already hold", () => {
    render(<ShiftRosterPage />);

    // Radha came, so the only press on her row is the other answer; Gopal did not, so the only press
    // on his is the first. A button offering the answer a row already gives would be a press that
    // does nothing, and on a screen of near-identical rows that is how somebody presses the wrong one.
    expect(within(row("Radha Devi")).getByRole("button", { name: /^mark as did not come$/i }))
      .toBeInTheDocument();
    expect(within(row("Radha Devi")).queryByRole("button", { name: /^mark as came$/i }))
      .not.toBeInTheDocument();
    expect(within(row("Gopal Das")).getByRole("button", { name: /^mark as came$/i }))
      .toBeInTheDocument();
    expect(within(row("Gopal Das")).queryByRole("button", { name: /^mark as did not come$/i }))
      .not.toBeInTheDocument();
  });

  it("sends the change for the person whose row was pressed, and says what it now says", async () => {
    render(<ShiftRosterPage />);
    fireEvent.click(within(row("Radha Devi")).getByRole("button", { name: /^mark as did not come$/i }));

    await waitFor(() => expect(correctAttendanceMock).toHaveBeenCalled());
    expect(correctAttendanceMock.mock.calls[0][0]).toBe("shift-1");
    expect(correctAttendanceMock.mock.calls[0][1]).toBe("u1");
    expect(correctAttendanceMock.mock.calls[0][2]).toBe(false);
    // Nobody else's mark travels with it: this is one person and one answer, which is the whole
    // difference from the blanket marking.
    expect(recordAttendanceMock).not.toHaveBeenCalled();
    await waitFor(() => expect(reloadMock).toHaveBeenCalled());
    expect(await screen.findByText(/radha devi is now marked as not having come/i)).toBeInTheDocument();
  });

  it("offers both answers to somebody the marking left out, and marks them", async () => {
    // The partial-list case, which is why this task subsumed T-085's rejected count(*) rule. Gopal
    // was not in the `marks` list, so he carries no answer at all — and any mark on the shift makes a
    // second blanket marking KMS-400139, so before this screen offered these two buttons there was
    // no way, anywhere in the product, for anybody to say whether he came.
    queryRef.current = {
      data: roster([
        signup({ attended: true, attendanceRecordedAt: "2026-12-06T12:30:00Z" }),
        signup({ userId: "u2", fullName: "Gopal Das" }),
      ]),
      error: null,
      loading: false,
    };
    render(<ShiftRosterPage />);

    const gopal = within(row("Gopal Das"));
    expect(gopal.getByText("Not marked")).toBeInTheDocument();
    expect(gopal.getByRole("button", { name: /^mark as came$/i })).toBeInTheDocument();
    expect(gopal.getByRole("button", { name: /^mark as did not come$/i })).toBeInTheDocument();

    fireEvent.click(gopal.getByRole("button", { name: /^mark as came$/i }));

    await waitFor(() => expect(correctAttendanceMock).toHaveBeenCalled());
    expect(correctAttendanceMock.mock.calls[0][1]).toBe("u2");
    expect(correctAttendanceMock.mock.calls[0][2]).toBe(true);
    expect(await screen.findByText(/gopal das is now marked as having come/i)).toBeInTheDocument();
  });

  it("offers no change while the marking itself is still to be done", () => {
    // An assertion of an absence, and it needs the positive one beside it: with nothing recorded the
    // screen is offering the ticks and the one Save, and a row of change buttons there would be a
    // second way to do the same thing with different rules.
    queryRef.current = {
      data: roster([signup(), signup({ userId: "u2", fullName: "Gopal Das" })]),
      error: null,
      loading: false,
    };
    render(<ShiftRosterPage />);

    expect(screen.queryByRole("button", { name: /^mark as came$/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^mark as did not come$/i })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /save attendance/i })).toBeInTheDocument();
  });

  it("offers no change on a cancelled shift", () => {
    queryRef.current = {
      data: roster(
        [signup({ attended: true, attendanceRecordedAt: "2026-12-06T12:30:00Z" })],
        "CANCELLED"
      ),
      error: null,
      loading: false,
    };
    render(<ShiftRosterPage />);

    expect(screen.getByText("Came")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^mark as did not come$/i })).not.toBeInTheDocument();
  });
});

describe("taking a volunteer off a roster", () => {
  // Pinned here too, and not only in the marking block: "no attendance tick beside a released
  // volunteer" is an assertion of an absence, and against the real clock this roster is a future
  // shift with no ticks on it at all (T-085) — the test would pass without ever reaching the
  // released-row rule it exists for.
  beforeAll(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(AFTER_THE_SHIFT_STARTED);
  });

  afterAll(() => {
    vi.useRealTimers();
  });

  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    reloadMock.mockReset();
    recordAttendanceMock.mockReset().mockResolvedValue(undefined);
    correctAttendanceMock.mockReset().mockResolvedValue(undefined);
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
