import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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
 *
 * <p>The fourth block is T-080, which turns the removal from one press into a small form. Two of
 * its tests are negatives and are written to fail rather than pass vacuously: nobody is removed on
 * an empty form, and the reason list is read off the rendered DOM rather than off the page's own
 * constant, which would only assert that the page agrees with itself.
 */

const {
  authRef,
  queryRef,
  reloadMock,
  recordAttendanceMock,
  correctAttendanceMock,
  releaseVolunteerMock,
  broadcastShiftMock,
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
  broadcastShiftMock: vi.fn(),
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
      broadcastShift: broadcastShiftMock,
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
    releasedReason: null,
    releasedNote: null,
    attended: null,
    attendanceRecordedAt: null,
    attendanceCorrectedAt: null,
    attendanceCorrectedByName: null,
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

/**
 * The coordinator's internal note (T-080), written to be the kind of sentence that must never reach
 * the person it is about. It is asserted as present on the roster and absent from everything the
 * page would send, so it is deliberately unlike any copy the product itself produces.
 */
const NOTE = "He has missed three Sundays without telling anyone.";

function roster(
  signups: RosterSignup[],
  status: "OPEN" | "CANCELLED" = "OPEN",
  hours: { startTime: string; endTime: string } = { startTime: "08:00:00", endTime: "12:00:00" }
): RosterView {
  return {
    shift: {
      id: "shift-1",
      title: "Sunday prep",
      description: null,
      mealId: null,
      mealKind: null,
      mealEventName: null,
      shiftDate: "2026-12-06",
      startTime: hours.startTime,
      endTime: hours.endTime,
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

  it("says in the roster heading when the shift runs through midnight (T-146)", () => {
    queryRef.current = {
      data: roster([signup()], "OPEN", { startTime: "20:00:00", endTime: "02:00:00" }),
      error: null,
      loading: false,
    };
    render(<ShiftRosterPage />);
    // The coordinator's own copy of the shift's hours. It sits above the attendance controls, and
    // "20:00–02:00" alone would leave the person marking who turned up to work out for themselves
    // which morning the shift ended on.
    expect(screen.getByText(/20:00–02:00 \(next day\)/)).toBeInTheDocument();
  });

  it("labels a meal shift's roster with its meal and day, and a plain one not at all (D-27)", () => {
    const meal = roster([signup()]);
    meal.shift = { ...meal.shift, mealId: "meal-lunch", mealKind: "Lunch" };
    queryRef.current = { data: meal, error: null, loading: false };
    const { unmount } = render(<ShiftRosterPage />);
    // The clock is pinned to 2026 above, so "6 December" is this year and carries no year.
    expect(screen.getByText("For Lunch, 6 December")).toBeInTheDocument();
    unmount();

    queryRef.current = { data: roster([signup()]), error: null, loading: false };
    render(<ShiftRosterPage />);
    expect(screen.queryByText(/^For /)).not.toBeInTheDocument();
  });

  it("leaves an ordinary roster heading as it was", () => {
    render(<ShiftRosterPage />);
    expect(screen.getByText(/08:00–12:00/)).toBeInTheDocument();
    expect(screen.queryByText(/next day/i)).not.toBeInTheDocument();
  });

  it("offers a tick per volunteer, ticked to start", () => {
    render(<ShiftRosterPage />);
    expect(screen.getByRole("checkbox", { name: /radha devi came/i })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: /gopal das came/i })).toBeChecked();
  });

  it("sends everyone on the roster, the unticked as absent", async () => {
    render(<ShiftRosterPage />);
    fireEvent.click(screen.getByRole("checkbox", { name: /gopal das came/i }));
    // Pressed rather than `fireEvent.submit`, since T-165: the form is a `Form`, and the press is
    // what a coordinator does.
    fireEvent.click(screen.getByRole("button", { name: /save attendance/i }));

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
    // A Kitchen Manager, not the cook the other two blocks sign in as. T-106 took correcting off
    // MANAGE_VOLUNTEER_SHIFTS and put it on CORRECT_RECORDED_ATTENDANCE, which kitchen staff do not
    // hold — so every test in this block would now be asserting against a screen that rightly offers
    // nothing. The cook's side of that split is its own test at the foot of this block.
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_MANAGER", userId: "me" } };
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

  // T-099, finishing T-079: the columns `SignupService.correctAttendance` writes reached the
  // roster's own audit trail last wave, but nothing read them onto this screen — the fact was
  // recorded and invisible. Three states, and each of the three tests below is the one reader who
  // would notice if two of them collapsed into each other.
  it("reads a corrected mark: who changed it, and when", () => {
    queryRef.current = {
      data: roster([
        signup({
          attended: false,
          attendanceRecordedAt: "2026-12-06T12:30:00Z",
          attendanceCorrectedAt: "2026-12-07T05:00:00Z",
          attendanceCorrectedByName: "Anand",
        }),
        signup({ userId: "u2", fullName: "Gopal Das", attended: false, attendanceRecordedAt: "2026-12-06T12:30:00Z" }),
      ]),
      error: null,
      loading: false,
    };
    render(<ShiftRosterPage />);

    // House idiom (MealServices' "Corrected by X on Y."), not a new pattern: who, then when, in
    // the temple's own day rather than the row's raw ISO instant.
    expect(within(row("Radha Devi")).getByText(/^corrected by anand on 7 dec 2026\.$/i)).toBeInTheDocument();
  });

  it("says nothing extra about a mark that was corrected before but stands as given now", () => {
    // A row that has never been corrected must not say so, even sitting right beside one that has —
    // a screen that always rendered a "Corrected …" line once any row on the roster carried one
    // would be a false attribution scoped to the wrong row.
    queryRef.current = {
      data: roster([
        signup({
          attended: false,
          attendanceRecordedAt: "2026-12-06T12:30:00Z",
          attendanceCorrectedAt: "2026-12-07T05:00:00Z",
          attendanceCorrectedByName: "Anand",
        }),
        signup({ userId: "u2", fullName: "Gopal Das", attended: true, attendanceRecordedAt: "2026-12-06T12:30:00Z" }),
      ]),
      error: null,
      loading: false,
    };
    render(<ShiftRosterPage />);

    expect(within(row("Radha Devi")).getByText(/corrected/i)).toBeInTheDocument();
    expect(within(row("Gopal Das")).queryByText(/corrected/i)).not.toBeInTheDocument();
  });

  it("does not label a first answer given late as a correction", () => {
    // The distinction the whole task turns on: SignupService.correctAttendance is also the only
    // door a first mark can arrive through once a partial marking has left somebody out, and that
    // is a first answer, not a change of one — the service leaves both correction columns null for
    // exactly this row. A screen that read "attended != null" as "was corrected" would mislabel it.
    queryRef.current = {
      data: roster([
        signup({ attended: true, attendanceRecordedAt: "2026-12-06T12:30:00Z" }),
        signup({
          userId: "u2",
          fullName: "Gopal Das",
          attended: true,
          attendanceRecordedAt: "2026-12-06T12:30:00Z",
          attendanceCorrectedAt: null,
          attendanceCorrectedByName: null,
        }),
      ]),
      error: null,
      loading: false,
    };
    render(<ShiftRosterPage />);

    expect(within(row("Gopal Das")).getByText("Came")).toBeInTheDocument();
    expect(within(row("Gopal Das")).queryByText(/corrected/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/corrected/i)).not.toBeInTheDocument();
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

  it("offers a cook no change at all, and a temple admin the same as a manager", () => {
    // T-106. The API refuses a cook this call, so a screen that still drew the buttons would teach
    // them the refusal by pressing one — worse than not offering it, because the row they pressed is
    // a named colleague and the failure looks like the app losing their correction.
    //
    // Asserted by role against the identical roster, rather than as a bare absence: a test that only
    // checked the buttons were gone would pass just as happily on the day somebody deletes the
    // control outright, which is a different bug and not this fix.
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<ShiftRosterPage />);

    expect(screen.queryByRole("button", { name: /^mark as came$/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^mark as did not come$/i })).not.toBeInTheDocument();
    // The marks themselves are still readable — this narrows who may change one, not who may see it.
    expect(within(row("Radha Devi")).getByText("Came")).toBeInTheDocument();
    // And the sentence under the table names who can, instead of leaving a cook to think a wrong
    // mark is permanent.
    expect(screen.getByText(/ask a kitchen manager if a mark is wrong/i)).toBeInTheDocument();

    // The other half of the grant, on the same fixture, so a policy edit that dropped either role
    // fails here rather than in only one of two places. Unmounted first — two roster tables in one
    // container would let the admin's buttons be found on the cook's rows and vice versa.
    cleanup();
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    render(<ShiftRosterPage />);
    expect(screen.getAllByRole("button", { name: /^mark as did not come$/i }).length).toBeGreaterThan(0);
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

  /** Opens the removal form on one person's row and fills it in (T-080). */
  async function openRemovalFor(name: RegExp, reason: string, note: string) {
    const rows = screen.getAllByRole("button", { name: /^remove$/i });
    fireEvent.click(rows[name.test("Gopal Das") ? 1 : 0]);
    fireEvent.change(await screen.findByLabelText(/why .* is coming off the shift/i), {
      target: { value: reason },
    });
    fireEvent.change(screen.getByLabelText(/note about taking .* off the shift/i), {
      target: { value: note },
    });
    // The row's own button, which reaches the removal form through `form=` (T-165).
    fireEvent.click(screen.getByRole("button", { name: /take off shift/i }));
  }

  it("names the person being removed, and sends the reason and the note with it", async () => {
    render(<ShiftRosterPage />);
    await openRemovalFor(/Gopal Das/, "ROTA_CHANGED", NOTE);

    await waitFor(() => expect(releaseVolunteerMock).toHaveBeenCalled());
    expect(releaseVolunteerMock.mock.calls[0][0]).toBe("shift-1");
    expect(releaseVolunteerMock.mock.calls[0][1]).toBe("u2");
    expect(releaseVolunteerMock.mock.calls[0][2]).toEqual({
      reason: "ROTA_CHANGED",
      internalNote: NOTE,
    });
    await waitFor(() => expect(reloadMock).toHaveBeenCalled());
    expect(
      await screen.findByText(/gopal das was taken off this shift, and has been told why/i)
    ).toBeInTheDocument();
  });

  it("will not remove anyone until both halves are given", async () => {
    render(<ShiftRosterPage />);
    fireEvent.click(screen.getAllByRole("button", { name: /^remove$/i })[1]);

    // Pressed, with the form untouched — the button and not a synthetic submit event, because both
    // fields are `required` and it is the browser's constraint validation that has to stop this. A
    // `fireEvent.submit` would skip that step and prove nothing about what a coordinator's press
    // actually does. The assertion that matters is the negative one: a screen that removed somebody
    // on an empty note would be the defect this task exists to fix, wearing a passing test.
    fireEvent.click(screen.getByRole("button", { name: /take off shift/i }));
    await waitFor(() => expect(reloadMock).not.toHaveBeenCalled());
    expect(releaseVolunteerMock).not.toHaveBeenCalled();
  });

  it("offers the four reasons Rajeev named, and no free text among them", async () => {
    render(<ShiftRosterPage />);
    fireEvent.click(screen.getAllByRole("button", { name: /^remove$/i })[0]);

    const select = await screen.findByLabelText(/why .* is coming off the shift/i);
    // The placeholder is disabled and is not one of the four, so the options a coordinator can
    // actually pick are exactly Rajeev's list. Read off the DOM rather than off the constant the
    // page exports, or the test would agree with whatever the page happened to contain.
    const choosable = within(select)
      .getAllByRole("option")
      .filter((o) => !(o as HTMLOptionElement).disabled)
      .map((o) => (o as HTMLOptionElement).value);
    expect(choosable).toEqual(["SHIFT_CANCELLED", "NO_LONGER_NEEDED", "ROTA_CHANGED", "OTHER"]);
  });

  /**
   * T-058, item 6b. The form shipped inside the row's Actions cell, and a cell is the one place on
   * this screen that cannot hold a form: every column carries `whitespace-nowrap` from
   * `components/ds/table.ts`, so a column is never squeezed below what its contents ask for. The
   * cell took the width its widest control wanted, the table outgrew the page, and the
   * `overflow-x-auto` wrapper turned that into sideways scrolling inside the table.
   *
   * <p>Measured by Rajeev on the deployed build at a 1470px desktop: the table wanted 1342px inside
   * a 1124px box, putting the reason picker 101px and the note field 185px past the right edge. The
   * note is the field a coordinator is *required* to fill in, so the mandatory half of the form was
   * the half off the screen.
   *
   * <p>jsdom has no layout engine, so this cannot assert those pixels and does not pretend to. What
   * it asserts is the structural fact that produced them: the controls are no longer in a cell that
   * can widen one column, but in a cell that spans every column and so can only be as wide as the
   * table already is. That is the property a future edit would have to break to bring the defect
   * back, and it fails against the markup as it shipped.
   */
  it("puts the removal form in a row spanning every column, not in the actions cell", async () => {
    render(<ShiftRosterPage />);
    fireEvent.click(screen.getAllByRole("button", { name: /^remove$/i })[0]);

    const note = await screen.findByLabelText(/note about taking .* off the shift/i);
    const reason = screen.getByLabelText(/why .* is coming off the shift/i);

    const cell = note.closest("td");
    expect(cell).not.toBeNull();
    expect(reason.closest("td")).toBe(cell);

    // Read the column count off the rendered header rather than hard-coding 4, so that a column
    // added or removed later moves this assertion with it instead of quietly invalidating it.
    const columns = within(screen.getByRole("table")).getAllByRole("columnheader").length;
    expect(columns).toBeGreaterThan(1);
    expect(cell!.colSpan).toBe(columns);

    // And it is genuinely a second row, not the volunteer's own: a colSpan cell sitting in the row
    // beside the name would be malformed markup that happened to satisfy the line above.
    expect(cell!.closest("tr")).not.toBe(screen.getByText("Radha Devi").closest("tr"));
    expect(cell!.closest("tr")).not.toBe(screen.getByText("Gopal Das").closest("tr"));
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
    // No reason on this row, so it reads as the volunteer's own release — they stepped off, and
    // nobody is asked to justify that.
    expect(screen.getByText(/gopal das — released/i)).toBeInTheDocument();
    expect(screen.queryByRole("checkbox", { name: /gopal das came/i })).not.toBeInTheDocument();
  });

  it("shows the reason and the internal note against someone the temple took off", () => {
    queryRef.current = {
      data: roster([
        signup(),
        signup({
          userId: "u2",
          fullName: "Gopal Das",
          releasedAt: "2026-11-20T06:00:00Z",
          releasedReason: "ROTA_CHANGED",
          releasedNote: NOTE,
        }),
      ]),
      error: null,
      loading: false,
    };
    render(<ShiftRosterPage />);

    // "taken off" and not "released": the presence of a reason is how this screen tells the
    // temple's act from the devotee's, and saying "released" for both was the ambiguity T-080
    // removes on the way past.
    expect(screen.getByText(/gopal das — taken off/i)).toBeInTheDocument();
    expect(screen.getByText(/rota changed/i)).toBeInTheDocument();
    // The note is on the roster, and is labelled as the half the volunteer was not told — a
    // coordinator reading this months later must not have to remember which of the two was sent.
    expect(screen.getByText(/not sent to them/i)).toBeInTheDocument();
    expect(screen.getByText(new RegExp(NOTE, "i"))).toBeInTheDocument();
  });

  it("offers no removal on a cancelled shift", () => {
    queryRef.current = { data: roster([signup()], "CANCELLED"), error: null, loading: false };
    render(<ShiftRosterPage />);
    expect(screen.queryByRole("button", { name: /remove/i })).not.toBeInTheDocument();
  });
});

/**
 * T-165: the roster's three forms name a refused box in red beside it, and one form's blank box
 * never stops another. The case worth proving is the removal form: its two boxes sit inside the
 * attendance form's table but belong to the removal form through `form=`, so a blank note must stop
 * a removal and must not stop "Save attendance".
 *
 * <p>The attendance form has no `required` box, so there is no "is required" sentence to assert for
 * it. What is true of it is that it saves while the other two forms sit open and blank.
 */
describe("a blank box on the roster names itself, and stops only its own form (T-165)", () => {
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
    broadcastShiftMock.mockReset().mockResolvedValue({ recipients: 2 });
    queryRef.current = {
      data: roster([signup(), signup({ userId: "u2", fullName: "Gopal Das" })]),
      error: null,
      loading: false,
    };
  });

  it("names a blank update beside its box, and sends nothing", async () => {
    render(<ShiftRosterPage />);
    fireEvent.click(screen.getByRole("button", { name: /send update to all/i }));
    fireEvent.click(screen.getByRole("button", { name: /send now/i }));

    // The box has a placeholder and no label, so the only name the page gives it is its `name`
    // attribute. That reads badly and is recorded in T-165's proof rather than changed here.
    const said = await screen.findByText("message is required");
    const box = screen.getByPlaceholderText("e.g. Gate B today, not A");
    expect(box.getAttribute("aria-describedby")).toContain(said.id);
    expect(box.nextElementSibling).toBe(said.parentElement);
    expect(broadcastShiftMock).not.toHaveBeenCalled();
  });

  it("names both blank halves of a removal beside their boxes, takes nobody off, then takes them off once given", async () => {
    render(<ShiftRosterPage />);
    fireEvent.click(screen.getAllByRole("button", { name: /^remove$/i })[1]);
    fireEvent.click(screen.getByRole("button", { name: /take off shift/i }));

    const reasonSaid = await screen.findByText("Why Gopal Das is coming off the shift is required");
    const noteSaid = screen.getByText("Note about taking Gopal Das off the shift is required");
    const reason = screen.getByLabelText(/why gopal das is coming off the shift/i);
    const note = screen.getByLabelText(/note about taking gopal das off the shift/i);
    expect(reason.getAttribute("aria-describedby")).toContain(reasonSaid.id);
    expect(note.getAttribute("aria-describedby")).toContain(noteSaid.id);
    expect(releaseVolunteerMock).not.toHaveBeenCalled();
    // A refused removal is not an attendance save either, though its boxes sit in that form's table.
    expect(recordAttendanceMock).not.toHaveBeenCalled();

    fireEvent.change(reason, { target: { value: "ROTA_CHANGED" } });
    fireEvent.change(note, { target: { value: NOTE } });
    fireEvent.click(screen.getByRole("button", { name: /take off shift/i }));
    await waitFor(() => expect(releaseVolunteerMock).toHaveBeenCalled());
    expect(screen.queryByText(/ is required$/)).not.toBeInTheDocument();
  });

  it("saves attendance while a removal form sits open and blank in the table", async () => {
    render(<ShiftRosterPage />);
    fireEvent.click(screen.getAllByRole("button", { name: /^remove$/i })[1]);
    await screen.findByLabelText(/why gopal das is coming off the shift/i);

    fireEvent.click(screen.getByRole("button", { name: /save attendance/i }));
    await waitFor(() => expect(recordAttendanceMock).toHaveBeenCalled());
    expect(screen.queryByText(/ is required$/)).not.toBeInTheDocument();
    expect(releaseVolunteerMock).not.toHaveBeenCalled();
  });

  it("saves attendance while the update box above it is open and blank", async () => {
    render(<ShiftRosterPage />);
    fireEvent.click(screen.getByRole("button", { name: /send update to all/i }));

    fireEvent.click(screen.getByRole("button", { name: /save attendance/i }));
    await waitFor(() => expect(recordAttendanceMock).toHaveBeenCalled());
    expect(screen.queryByText(/ is required$/)).not.toBeInTheDocument();
    expect(broadcastShiftMock).not.toHaveBeenCalled();
  });
});

/**
 * T-175: a refused removal's sentences survive the attendance table re-rendering around them.
 *
 * <p>Why this needs its own proof. The removal form is an empty `Form` reached through `form=`, so
 * the slots it places for its sentences sit inside the *attendance* form's table, and `Form`'s
 * `MutationObserver` only watches its own (empty) form element. T-165 read that as a risk: the table
 * re-renders, and nothing re-seats a sentence it disturbed. These tests cause the re-renders a
 * coordinator can cause with the sentences showing, and check each sentence is still there, once,
 * right after its box's label, with `aria-invalid` and `aria-describedby` still pointing at it.
 *
 * <p>Two kinds of re-render, because the page has two:
 *
 * <ul>
 *   <li>Ones that keep the table mounted: ticking somebody's attendance (no React render at all, but
 *       a `change` event every `Form` on the page hears), opening "Send update to all" (the whole
 *       page re-renders and a section is inserted above the table), and "Save attendance" (the page
 *       goes busy, which re-renders the removal row itself: its own buttons turn disabled and back,
 *       beside the slots, and a notice is inserted above).</li>
 *   <li>The data refresh. The real `useAuthedQuery` sets `loading` before every re-fetch, and the
 *       page answers `loading` with a spinner in place of the roster, so a refresh does not re-render
 *       the table around the sentence — it unmounts the table, both boxes and the removal form, and
 *       mounts them again. The sentence cannot survive that and should not: the box it was about is
 *       gone. What is asserted is that nothing is left behind or doubled, and the next press names
 *       the boxes once. The mock hook is driven through the same two legs the real one takes.</li>
 * </ul>
 */
describe("a refused removal's sentences survive the attendance table re-rendering (T-175)", () => {
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
    broadcastShiftMock.mockReset().mockResolvedValue({ recipients: 2 });
    queryRef.current = {
      data: roster([signup(), signup({ userId: "u2", fullName: "Gopal Das" })]),
      error: null,
      loading: false,
    };
  });

  const REASON_SAID = "Why Gopal Das is coming off the shift is required";
  const NOTE_SAID = "Note about taking Gopal Das off the shift is required";

  /** Opens Gopal Das's removal row and presses "Take off shift" with both boxes blank. */
  async function refuseABlankRemoval() {
    fireEvent.click(screen.getAllByRole("button", { name: /^remove$/i })[1]);
    fireEvent.click(screen.getByRole("button", { name: /take off shift/i }));
    await screen.findByText(REASON_SAID);
    return {
      reason: screen.getByLabelText(/why gopal das is coming off the shift/i),
      note: screen.getByLabelText(/note about taking gopal das off the shift/i),
    };
  }

  /**
   * One sentence, exactly once, in the slot straight after the label that wraps its box, and the box
   * still marked invalid and described by it. "Straight after the label" is the whole question: a
   * slot React had displaced would still hold its text somewhere in the table.
   */
  function expectBeside(box: HTMLElement, text: string) {
    const said = screen.getAllByText(text);
    expect(said).toHaveLength(1);
    const slot = said[0].parentElement!;
    expect(slot.hasAttribute("data-form-error-slot")).toBe(true);
    expect(box.closest("label")!.nextElementSibling).toBe(slot);
    expect(box.getAttribute("aria-invalid")).toBe("true");
    expect((box.getAttribute("aria-describedby") ?? "").split(/\s+/)).toContain(said[0].id);
  }

  function slotsOnPage() {
    return document.querySelectorAll("[data-form-error-slot]").length;
  }

  it("keeps both sentences beside their boxes through a tick, the update box opening and a save, then clears each once its box is given", async () => {
    render(<ShiftRosterPage />);
    const { reason, note } = await refuseABlankRemoval();
    expectBeside(reason, REASON_SAID);
    expectBeside(note, NOTE_SAID);
    expect(slotsOnPage()).toBe(2);

    // 1. Unticking Radha Devi. An uncontrolled checkbox, so React renders nothing, but the `change`
    // it fires reaches every Form's document listener, the removal form's included.
    fireEvent.click(screen.getByRole("checkbox", { name: /radha devi came/i }));
    expectBeside(reason, REASON_SAID);
    expectBeside(note, NOTE_SAID);

    // 2. Opening "Send update to all": the whole page re-renders, and a section with a Form of its
    // own is inserted above the roster.
    fireEvent.click(screen.getByRole("button", { name: /send update to all/i }));
    expect(screen.getByRole("form", { name: "Send an update" })).toBeInTheDocument();
    expectBeside(reason, REASON_SAID);
    expectBeside(note, NOTE_SAID);

    // 3. "Save attendance", held open so the busy render can be looked at. Busy disables the removal
    // row's own buttons, which sit in the same block as the two slots: React re-rendering the row
    // the sentences are in, not somewhere else on the page.
    let finish!: () => void;
    recordAttendanceMock.mockImplementation(() => new Promise<void>((resolve) => (finish = resolve)));
    fireEvent.click(screen.getByRole("button", { name: /save attendance/i }));
    await waitFor(() => expect(recordAttendanceMock).toHaveBeenCalled());
    expect(screen.getByRole("button", { name: /take off shift/i })).toBeDisabled();
    expectBeside(reason, REASON_SAID);
    expectBeside(note, NOTE_SAID);

    await act(async () => finish());
    await screen.findByText("Attendance recorded.");
    expect(screen.getByRole("button", { name: /take off shift/i })).toBeEnabled();
    expectBeside(reason, REASON_SAID);
    expectBeside(note, NOTE_SAID);
    expect(slotsOnPage()).toBe(2);
    // The refused removal was not sent by any of that.
    expect(releaseVolunteerMock).not.toHaveBeenCalled();

    // Putting each box right clears its own sentence and gives the box back as it was.
    fireEvent.change(reason, { target: { value: "ROTA_CHANGED" } });
    expect(screen.queryByText(REASON_SAID)).not.toBeInTheDocument();
    expect(reason.hasAttribute("aria-invalid")).toBe(false);
    expect(reason.hasAttribute("aria-describedby")).toBe(false);
    expectBeside(note, NOTE_SAID);

    fireEvent.change(note, { target: { value: NOTE } });
    expect(screen.queryByText(NOTE_SAID)).not.toBeInTheDocument();
    expect(note.hasAttribute("aria-invalid")).toBe(false);
    expect(slotsOnPage()).toBe(0);

    fireEvent.click(screen.getByRole("button", { name: /take off shift/i }));
    await waitFor(() => expect(releaseVolunteerMock).toHaveBeenCalled());
    expect(releaseVolunteerMock.mock.calls[0][1]).toBe("u2");
    expect(releaseVolunteerMock.mock.calls[0][2]).toEqual({ reason: "ROTA_CHANGED", internalNote: NOTE });
  });

  it("a refresh takes the boxes down with their sentences, leaves nothing behind, and the next press names each box once", async () => {
    const { rerender } = render(<ShiftRosterPage />);
    await refuseABlankRemoval();

    // The save that triggers the page's refresh.
    fireEvent.click(screen.getByRole("button", { name: /save attendance/i }));
    await waitFor(() => expect(reloadMock).toHaveBeenCalled());

    // The real hook's first leg: `loading` while the roster is fetched again.
    queryRef.current = { ...queryRef.current, loading: true };
    rerender(<ShiftRosterPage />);
    expect(screen.getByText("Loading roster…")).toBeInTheDocument();
    expect(screen.queryByText(/ is required$/)).not.toBeInTheDocument();
    expect(slotsOnPage()).toBe(0);

    // Its second leg: the roster back, as a new object.
    queryRef.current = {
      data: roster([signup(), signup({ userId: "u2", fullName: "Gopal Das" })]),
      error: null,
      loading: false,
    };
    rerender(<ShiftRosterPage />);
    // Gopal Das's removal row is open again (the page still holds whose it is), blank, and quiet.
    const reason = screen.getByLabelText(/why gopal das is coming off the shift/i);
    const note = screen.getByLabelText(/note about taking gopal das off the shift/i);
    expect(screen.queryByText(/ is required$/)).not.toBeInTheDocument();
    expect(reason.hasAttribute("aria-invalid")).toBe(false);
    expect(slotsOnPage()).toBe(0);

    fireEvent.click(screen.getByRole("button", { name: /take off shift/i }));
    await screen.findByText(REASON_SAID);
    expectBeside(reason, REASON_SAID);
    expectBeside(note, NOTE_SAID);
    expect(slotsOnPage()).toBe(2);
    expect(releaseVolunteerMock).not.toHaveBeenCalled();
  });
});
