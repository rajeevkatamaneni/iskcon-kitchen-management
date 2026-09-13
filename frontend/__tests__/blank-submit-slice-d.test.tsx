import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ResolvedDay, WeekScheduleView } from "@/lib/api";
import { dateWithYear } from "@/lib/format";
import { CATEGORIES, TITLES, ban, former, member, pay } from "./staff-fixtures";

/**
 * Blank required boxes on the staff, pay, ban, conduct-note, schedule and leave forms are named in
 * red (T-164).
 *
 * <p>Rajeev, 2026-09-11: *"Required fields should carry `required` on the element and if left
 * unfilled, we should at least show 'Required' in red on form submit. Ideally, we should say
 * 'Quantity is required' OR 'Note is required'."* T-160 built `Form` to do that; this slice swaps the
 * twelve forms of eight files onto it, and this file is the proof that each one now says which box is
 * wrong, beside that box, and sends nothing.
 *
 * <p>Every test presses the form's real submit button, never `fireEvent.submit`, because jsdom
 * refuses a click-driven submit from a form without `novalidate`: a click is what tells a `Form`
 * apart from a plain `<form>`. A dispatched submit event skips that check and would pass against
 * either. The one exception is the conduct note, whose Save stays disabled until something is typed,
 * and its test says why.
 *
 * <p>Out-of-range values are typed with `fireEvent.change`, never seeded through `defaultValue`,
 * because jsdom does not step- or range-check a value that arrived as the `value` attribute.
 *
 * <p><b>Nested forms.</b> None of these forms sits inside another, in the DOM or in React's tree.
 * The brief expected `TerminateForm` to mount forms from `Ban` and `PayPanel`; it does not.
 * `BanOnTermination` is a fieldset whose two boxes belong to the termination form, and `PayPanel` is
 * imported there only for its list of payment modes. What does share a screen is sibling forms: the
 * two on the pay page, the three in a schedule cell, and a ban correction beside the conduct-note
 * panel. Each of those has a test that one form's blank box neither blocks nor triggers the other.
 */

const { authRef, paramsRef, refs, reloadMock, mocks } = vi.hoisted(() => {
  const empty = () => ({ current: { data: null as unknown, error: null, loading: false } });
  return {
    authRef: {
      current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
        status: string;
        appUser: { role: string; userId: string } | null;
      },
    },
    paramsRef: { current: { id: "s1" } },
    refs: {
      register: empty(),
      pay: empty(),
      titles: empty(),
      devotees: empty(),
      categories: empty(),
      bans: empty(),
      conduct: empty(),
      week: empty(),
      coverage: empty(),
      profile: empty(),
    },
    reloadMock: vi.fn(),
    mocks: {
      hireStaff: vi.fn(),
      updateStaffMember: vi.fn(),
      endEmployment: vi.fn(),
      recordStaffPayment: vi.fn(),
      recordStaffAdvance: vi.fn(),
      amendBan: vi.fn(),
      retractBan: vi.fn(),
      addStaffConductNote: vi.fn(),
      setStaffException: vi.fn(),
      swapStaffShift: vi.fn(),
      recordLeave: vi.fn(),
      setStaffTemplate: vi.fn(),
    },
  };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => paramsRef.current,
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/",
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
// Every screen here reads through the same hook, so each query is told apart by what its callback
// asks the api for — the same stub the staff tests already use, widened to all eight screens.
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fn: (t: string | undefined) => Promise<unknown>) => {
    const asks = fn.toString();
    const ref = asks.includes("staffConductNotes")
      ? refs.conduct
      : asks.includes("staffRegister")
        ? refs.register
        : asks.includes("staffPay")
          ? refs.pay
          : asks.includes("jobTitles")
            ? refs.titles
            : asks.includes("listUsers")
              ? refs.devotees
              : asks.includes("banCategories")
                ? refs.categories
                : asks.includes("templeBans")
                  ? refs.bans
                  : asks.includes("staffWeek")
                    ? refs.week
                    : asks.includes("crewCoverage")
                      ? refs.coverage
                      : refs.profile;
    return { ...ref.current, reload: reloadMock };
  },
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, ...mocks } };
});

import HireStaffPage from "@/app/staff/new/page";
import EditStaffPage from "@/app/staff/[id]/edit/page";
import TerminateStaffPage from "@/app/staff/[id]/terminate/page";
import StaffPayPage from "@/app/staff/[id]/pay/page";
import StaffRecordPage from "@/app/staff/[id]/page";
import StaffSchedulePage from "@/app/staff-schedule/page";
import StaffTemplatePage from "@/app/staff-schedule/[id]/page";
import RecordLeavePage from "@/app/leave/record/page";
import { ConductNotes } from "@/components/staff/ConductNotes";

const PLAIN = {
  exceptionId: null,
  swapLinkId: null,
  leaveId: null,
  leaveType: null,
  leaveLabel: null,
  halfDayLeave: false,
} satisfies Partial<ResolvedDay>;

const WEEK: WeekScheduleView = {
  weekStart: "2026-08-31",
  staff: [
    {
      staffProfileId: "p1",
      userId: "u1",
      fullName: "Head Cook A",
      jobTitleLabel: "Head Cook",
      days: [
        { ...PLAIN, date: "2026-08-31", dayOfWeek: 1, working: true, startTime: "09:00:00", endTime: "17:00:00", fromException: false },
      ],
    },
  ],
  counts: [],
};

/**
 * Finds the sentence, and proves it is red and beside the box it names: straight after the box, or
 * after the label that wraps it, and wired to the box through `aria-describedby`.
 */
async function refused(scope: HTMLElement, selector: string, sentence: string) {
  const box = scope.querySelector(selector);
  expect(box, `no box ${selector}`).not.toBeNull();
  const note = await within(scope).findByText(sentence);
  expect(note).toHaveClass("text-danger");
  expect(box).toHaveAttribute("aria-invalid", "true");
  expect(box!.getAttribute("aria-describedby") ?? "").toContain(note.id);
  const before = note.parentElement?.previousElementSibling;
  expect(before === box || Boolean(before?.contains(box))).toBe(true);
}

function type(scope: HTMLElement, selector: string, value: string) {
  fireEvent.change(scope.querySelector(selector)!, { target: { value } });
}

beforeEach(() => {
  authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
  paramsRef.current = { id: "s1" };
  const set = (ref: { current: { data: unknown } }, data: unknown) => {
    ref.current = { data, error: null, loading: false } as typeof ref.current;
  };
  set(refs.register, {
    current: [member()],
    former: [former({ id: "s2", fullName: "Madhava Das", employmentStatus: "TERMINATED", lastWorkingDay: "2026-08-15" })],
  });
  set(refs.pay, pay());
  set(refs.titles, TITLES);
  set(refs.devotees, []);
  set(refs.categories, CATEGORIES);
  set(refs.bans, []);
  set(refs.conduct, []);
  set(refs.week, WEEK);
  set(refs.coverage, []);
  set(refs.profile, {
    profile: { ...member({ id: "p1", fullName: "Head Cook A" }) },
    template: [],
  });
  reloadMock.mockReset();
  Object.values(mocks).forEach((m) => m.mockReset().mockResolvedValue({ id: "new", checkId: null, findings: [] }));
});

describe("hiring and updating a staff member (StaffForm)", () => {
  it("names both blank required boxes when Hire is pressed, and hires nobody", async () => {
    render(<HireStaffPage />);
    const form = screen.getByRole("form", { name: /hire a staff member/i });
    // The header button sits outside the form and reaches it through form="staff-form".
    fireEvent.click(screen.getByRole("button", { name: "Hire" }));

    await refused(form, 'input[name="fullName"]', "Full name is required");
    await refused(form, 'input[name="dateOfJoining"]', "Date of joining is required");
    expect(mocks.hireStaff).not.toHaveBeenCalled();
  });

  it("asks for the temple's own words for a title of Other", async () => {
    render(<HireStaffPage />);
    const form = screen.getByRole("form", { name: /hire a staff member/i });
    type(form, 'input[name="fullName"]', "Ramesh Kumar");
    type(form, 'input[name="dateOfJoining"]', "2026-03-01");
    type(form, 'select[name="jobTitle"]', "OTHER");
    fireEvent.click(screen.getByRole("button", { name: "Hire" }));

    await refused(form, 'input[name="jobTitleOther"]', "What does your temple call this job? is required");
    expect(mocks.hireStaff).not.toHaveBeenCalled();
  });

  it("refuses a salary of 0 against min=1, and hires nobody", async () => {
    render(<HireStaffPage />);
    const form = screen.getByRole("form", { name: /hire a staff member/i });
    type(form, 'input[name="fullName"]', "Ramesh Kumar");
    type(form, 'input[name="dateOfJoining"]', "2026-03-01");
    type(form, 'input[name="monthlySalary"]', "0");
    fireEvent.click(screen.getByRole("button", { name: "Hire" }));

    await refused(form, 'input[name="monthlySalary"]', "Monthly salary must be at least 1");
    expect(mocks.hireStaff).not.toHaveBeenCalled();
  });

  it("lets a phone typed with spaces through, and sends it bare (T-157 untouched)", async () => {
    render(<HireStaffPage />);
    const form = screen.getByRole("form", { name: /hire a staff member/i });
    type(form, 'input[name="fullName"]', "Ramesh Kumar");
    type(form, 'input[name="dateOfJoining"]', "2026-03-01");
    type(form, 'input[name="phone"]', "+91 98765 43210");
    fireEvent.click(screen.getByRole("button", { name: "Hire" }));

    await waitFor(() => expect(mocks.hireStaff).toHaveBeenCalled());
    expect(mocks.hireStaff.mock.calls[0][0].phone).toBe("+919876543210");
    expect(within(form).queryByText(/is required/)).toBeNull();
  });

  it("names a cleared name on the update screen, and saves nothing", async () => {
    render(<EditStaffPage />);
    const form = screen.getByRole("form", { name: /edit a staff member/i });
    type(form, 'input[name="fullName"]', "");
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    await refused(form, 'input[name="fullName"]', "Full name is required");
    expect(mocks.updateStaffMember).not.toHaveBeenCalled();
  });
});

describe("ending an employment (TerminateForm, with the ban fieldset inside it)", () => {
  it("names a blank last working day when Terminate is pressed, and ends nothing", async () => {
    render(<TerminateStaffPage />);
    const form = screen.getByRole("form", { name: /terminate employment/i });
    fireEvent.click(screen.getByRole("button", { name: "Terminate" }));

    await refused(form, 'input[name="lastWorkingDay"]', "Last working day is required");
    expect(mocks.endEmployment).not.toHaveBeenCalled();
    expect(mocks.recordStaffPayment).not.toHaveBeenCalled();
  });

  it("refuses a settlement with three decimal places, and pays nothing", async () => {
    render(<TerminateStaffPage />);
    const form = screen.getByRole("form", { name: /terminate employment/i });
    type(form, 'input[name="lastWorkingDay"]', "2026-09-12");
    type(form, 'input[name="settlementAmount"]', "12.345");
    fireEvent.click(screen.getByRole("button", { name: "Terminate" }));

    await refused(form, 'input[name="settlementAmount"]', "Amount can have at most 2 decimal places");
    expect(mocks.recordStaffPayment).not.toHaveBeenCalled();
    expect(mocks.endEmployment).not.toHaveBeenCalled();
  });

  it("checks the ban's two boxes as part of this form once it is ticked, then sends them with it", async () => {
    render(<TerminateStaffPage />);
    const form = screen.getByRole("form", { name: /terminate employment/i });
    type(form, 'input[name="lastWorkingDay"]', "2026-09-12");
    fireEvent.click(form.querySelector('input[name="raiseBan"]')!);
    fireEvent.click(screen.getByRole("button", { name: "Terminate" }));

    await refused(form, 'select[name="banCategory"]', "What kind of thing was it? is required");
    await refused(form, 'textarea[name="banAccount"]', "What happened, in your own words? is required");
    expect(mocks.endEmployment).not.toHaveBeenCalled();

    type(form, 'select[name="banCategory"]', "HARASSMENT");
    type(form, 'textarea[name="banAccount"]', "Two written warnings, then a third incident.");
    fireEvent.click(screen.getByRole("button", { name: "Terminate" }));

    await waitFor(() => expect(mocks.endEmployment).toHaveBeenCalled());
    expect(mocks.endEmployment.mock.calls[0][1]).toMatchObject({
      ban: { category: "HARASSMENT", account: "Two written warnings, then a third incident." },
    });
  });
});

describe("a ban record, corrected or taken back (Ban.tsx), beside the conduct notes", () => {
  beforeEach(() => {
    paramsRef.current = { id: "s2" };
    refs.bans.current = { data: [ban()], error: null, loading: false };
  });

  it("names a cleared account on the correction, saves nothing, and leaves the note form free", async () => {
    render(<StaffRecordPage />);
    fireEvent.click(screen.getByRole("button", { name: /correct what it says/i }));
    const amend = screen.getByRole("form", { name: /correct this record/i });
    type(amend, 'textarea[name="account"]', "");
    fireEvent.click(within(amend).getByRole("button", { name: "Save the correction" }));

    await refused(amend, 'textarea[name="account"]', "What happened, in your own words? is required");
    expect(mocks.amendBan).not.toHaveBeenCalled();

    // A sibling form on the same screen: not blocked by the refused box above, and not refused by it.
    const notes = screen.getByRole("form", { name: /add a conduct note/i });
    expect(within(notes).queryByText(/is required/)).toBeNull();
    fireEvent.change(within(notes).getByLabelText("Add a note"), { target: { value: "Late twice this week." } });
    fireEvent.click(within(notes).getByRole("button", { name: "Save note" }));
    await waitFor(() =>
      expect(mocks.addStaffConductNote).toHaveBeenCalledWith("s2", "Late twice this week.", "test-token")
    );
    expect(mocks.amendBan).not.toHaveBeenCalled();
  });

  it("takes a record back with a blank reason, because nothing on that form is required", async () => {
    render(<StaffRecordPage />);
    fireEvent.click(screen.getByRole("button", { name: /take it back/i }));
    const retract = screen.getByRole("form", { name: /take this record back/i });
    fireEvent.click(within(retract).getByRole("button", { name: "Take it back" }));

    await waitFor(() => expect(mocks.retractBan).toHaveBeenCalledWith("b1", null, "test-token"));
    expect(within(retract).queryByText(/is required/)).toBeNull();
  });
});

describe("paying somebody (PayPanel's two forms)", () => {
  function forms() {
    render(<StaffPayPage />);
    return {
      payment: screen.getByRole("form", { name: /record a payment/i }),
      advance: screen.getByRole("form", { name: /record an advance/i }),
    };
  }

  it("names a blank payment's date and amount, records nothing, and leaves the advance form free", async () => {
    const { payment, advance } = forms();
    fireEvent.click(within(payment).getByRole("button", { name: "Record payment" }));

    await refused(payment, 'input[name="paidOn"]', "Date is required");
    await refused(payment, 'input[name="amount"]', "Amount before deductions is required");
    expect(mocks.recordStaffPayment).not.toHaveBeenCalled();
    expect(within(advance).queryByText(/is required/)).toBeNull();

    type(advance, 'input[name="advancePaidOn"]', "2026-08-02");
    type(advance, 'input[name="advanceAmount"]', "2500");
    fireEvent.click(within(advance).getByRole("button", { name: "Record advance" }));
    await waitFor(() => expect(mocks.recordStaffAdvance).toHaveBeenCalled());
    expect(mocks.recordStaffPayment).not.toHaveBeenCalled();
  });

  it("asks for a reference once the payment is a cheque", async () => {
    const { payment } = forms();
    type(payment, 'input[name="paidOn"]', "2026-08-31");
    type(payment, 'input[name="amount"]', "18000");
    type(payment, 'select[name="mode"]', "CHEQUE");
    fireEvent.click(within(payment).getByRole("button", { name: "Record payment" }));

    await refused(payment, 'input[name="reference"]', "Reference is required");
    expect(mocks.recordStaffPayment).not.toHaveBeenCalled();
  });

  it("refuses an amount of 0 and a recovery above what is outstanding", async () => {
    const { payment } = forms();
    type(payment, 'input[name="paidOn"]', "2026-08-31");
    type(payment, 'input[name="amount"]', "0");
    type(payment, 'input[name="deduct-a1"]', "3500");
    fireEvent.click(within(payment).getByRole("button", { name: "Record payment" }));

    await refused(payment, 'input[name="amount"]', "Amount before deductions must be at least 1");
    // The box has no question of its own: its label is the advance's outstanding figure, so that is
    // the name. Listed in the proof as a label that reads poorly.
    await refused(payment, 'input[name="deduct-a1"]', "₹3,000 still outstanding can be at most 3000");
    expect(mocks.recordStaffPayment).not.toHaveBeenCalled();
  });

  it("names a blank advance's date and amount, and a third decimal place, and records nothing", async () => {
    const { advance, payment } = forms();
    fireEvent.click(within(advance).getByRole("button", { name: "Record advance" }));

    await refused(advance, 'input[name="advancePaidOn"]', "Date is required");
    await refused(advance, 'input[name="advanceAmount"]', "Amount is required");
    expect(within(payment).queryByText(/is required/)).toBeNull();

    type(advance, 'input[name="advancePaidOn"]', "2026-08-02");
    type(advance, 'input[name="advanceAmount"]', "2500.555");
    fireEvent.click(within(advance).getByRole("button", { name: "Record advance" }));
    await refused(advance, 'input[name="advanceAmount"]', "Amount can have at most 2 decimal places");
    expect(mocks.recordStaffAdvance).not.toHaveBeenCalled();
  });
});

describe("a conduct note (ConductNotes)", () => {
  it("names a blank note when Save note is pressed, and saves nothing", async () => {
    render(<ConductNotes staffId="s1" />);
    const form = screen.getByRole("form", { name: /add a conduct note/i });
    // Until T-172 Save stayed disabled until something was typed, and this test had to submit the
    // form directly. It is pressable now, so the real press is what names the blank note.
    const save = within(form).getByRole("button", { name: "Save note" });
    expect(save).toBeEnabled();
    fireEvent.click(save);

    await refused(form, 'textarea[name="body"]', "Add a note is required");
    expect(mocks.addStaffConductNote).not.toHaveBeenCalled();
  });
});

describe("a day on the staff schedule (three forms in one cell)", () => {
  function openMonday() {
    render(<StaffSchedulePage />);
    fireEvent.click(screen.getByRole("button", { name: "Head Cook A, 2026-08-31" }));
  }

  it("names a cleared start time, and changes nothing", async () => {
    openMonday();
    const hours = screen.getByRole("form", { name: /change the hours/i });
    type(hours, 'input[name="startTime"]', "");
    fireEvent.click(within(hours).getByRole("button", { name: "Change the hours" }));

    await refused(hours, 'input[name="startTime"]', "From is required");
    expect(mocks.setStaffException).not.toHaveBeenCalled();
  });

  it("names a blank swap day, swaps nothing, and still marks them off from the form beside it", async () => {
    openMonday();
    const swap = screen.getByRole("form", { name: /swap this day/i });
    fireEvent.click(within(swap).getByRole("button", { name: "Swap" }));

    await refused(swap, 'input[name="toDate"]', "Work this day instead is required");
    expect(mocks.swapStaffShift).not.toHaveBeenCalled();
    expect(within(screen.getByRole("form", { name: /change the hours/i })).queryByText(/is required/)).toBeNull();

    // Mark them off has no required box, so it goes straight through.
    const markOff = screen.getByRole("form", { name: /mark them off/i });
    fireEvent.click(within(markOff).getByRole("button", { name: "Mark them off" }));
    await waitFor(() => expect(mocks.recordLeave).toHaveBeenCalled());
    expect(mocks.swapStaffShift).not.toHaveBeenCalled();
    expect(mocks.setStaffException).not.toHaveBeenCalled();
  });
});

describe("one person's weekly template", () => {
  it("saves with every box blank, because nothing on the template is required", async () => {
    paramsRef.current = { id: "p1" };
    render(<StaffTemplatePage />);
    const form = screen.getByRole("form", { name: "Weekly template" });
    fireEvent.click(within(form).getByRole("button", { name: "Save template" }));

    await waitFor(() => expect(mocks.setStaffTemplate).toHaveBeenCalled());
    expect(within(form).queryByText(/is required/)).toBeNull();
  });
});

describe("recording leave for somebody (T-164)", () => {
  it("names a blank staff member when Record it is pressed, and records nothing", async () => {
    refs.week.current = { data: { ...WEEK, staff: [] }, error: null, loading: false };
    render(<RecordLeavePage />);
    const form = screen.getByRole("form", { name: "Record leave" });
    fireEvent.click(screen.getByRole("button", { name: "Record it" }));

    await refused(form, 'select[name="staffProfileId"]', "Staff member is required");
    expect(mocks.recordLeave).not.toHaveBeenCalled();
  });

  it("names a cleared first day, and records nothing", async () => {
    render(<RecordLeavePage />);
    const form = screen.getByRole("form", { name: "Record leave" });
    type(form, 'input[name="fromDate"]', "");
    fireEvent.click(screen.getByRole("button", { name: "Record it" }));

    await refused(form, 'input[name="fromDate"]', "First day is required");
    expect(mocks.recordLeave).not.toHaveBeenCalled();
  });

  it("refuses a last day before the first, and records nothing", async () => {
    render(<RecordLeavePage />);
    const form = screen.getByRole("form", { name: "Record leave" });
    type(form, 'input[name="fromDate"]', "2026-09-10");
    type(form, 'input[name="toDate"]', "2026-09-08");
    fireEvent.click(screen.getByRole("button", { name: "Record it" }));

    await refused(form, 'input[name="toDate"]', `Last day must be on or after ${dateWithYear("2026-09-10")}`);
    expect(mocks.recordLeave).not.toHaveBeenCalled();
  });
});
