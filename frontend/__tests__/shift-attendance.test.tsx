import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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
    fireEvent.submit(screen.getByRole("form", { name: /take a volunteer off this shift/i }));
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
