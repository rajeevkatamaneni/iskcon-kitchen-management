import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import type { DayCoverage, ResolvedDay, WeekScheduleView } from "@/lib/api";

/*
 * The staff schedule on a phone (T-235): the days stack, one card each, the way the Vaishnava
 * calendar's Week view does, instead of 884px of grid scrolling sideways in a 324px box.
 *
 * jsdom has no layout, so what is proved here is the switch and the content — that below `md` the
 * page draws seven day cards and no table, that each card carries its shortfall and its people, and
 * that pressing a person's day opens the editor inside that day's card. The widths were measured in
 * Chrome; see docs/work/proof/T-235.md.
 */

const { returnsRef } = vi.hoisted(() => ({
  returnsRef: { current: [] as Array<{ data: unknown; error: null; loading: boolean }>, i: 0 },
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({
    status: "signed-in",
    appUser: { role: "KITCHEN_MANAGER", userId: "me" },
    getToken: async () => "test-token",
  }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => {
    const list = returnsRef.current;
    const value = list[returnsRef.i % list.length];
    returnsRef.i += 1;
    return { ...value, reload: vi.fn() };
  },
}));

import StaffSchedulePage from "@/app/staff-schedule/page";

const PLAIN = {
  exceptionId: null,
  swapLinkId: null,
  leaveId: null,
  leaveType: null,
  leaveLabel: null,
  halfDayLeave: false,
  fromException: false,
} satisfies Partial<ResolvedDay>;

const DATES = ["2026-08-31", "2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04", "2026-09-05", "2026-09-06"];

const WEEK: WeekScheduleView = {
  weekStart: "2026-08-31",
  staff: [
    {
      staffProfileId: "p1",
      userId: "u1",
      fullName: "Head Cook A",
      jobTitleLabel: "Head Cook",
      days: DATES.map((date, i) => ({
        ...PLAIN,
        date,
        dayOfWeek: i + 1,
        working: i < 5,
        startTime: i < 5 ? "09:00:00" : null,
        endTime: i < 5 ? "17:00:00" : null,
      })),
    },
  ],
  counts: DATES.map((date, i) => ({ date, staffIn: i < 5 ? 1 : 0, volunteers: i === 0 ? 4 : 0 })),
};

const QUIET: DayCoverage = {
  date: "",
  staffIn: 0,
  volunteers: 0,
  state: "NOTHING_PLANNED",
  shortBy: 0,
  shortAt: null,
  shortAtReadyBy: null,
  shortAtRequired: null,
  shortAtRostered: null,
};

const COVERAGE: DayCoverage[] = DATES.map((date, i) =>
  i === 0
    ? { ...QUIET, date, staffIn: 1, volunteers: 4, state: "SHORT", shortBy: 3, shortAt: "Dinner",
        shortAtReadyBy: "19:30:00", shortAtRequired: 5, shortAtRostered: 2 }
    : { ...QUIET, date }
);

function stubWidth(narrow: boolean) {
  window.matchMedia = ((query: string) => ({
    matches: narrow && query.includes("max-width: 767.98px"),
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
}

describe("staff schedule on a phone", () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date("2026-09-02T06:00:00Z"));
    returnsRef.current = [
      { data: WEEK, error: null, loading: false },
      { data: COVERAGE, error: null, loading: false },
      { data: [], error: null, loading: false },
    ];
    returnsRef.i = 0;
  });

  afterEach(() => {
    vi.useRealTimers();
    // jsdom has no matchMedia of its own; put it back to absent so no other file inherits a phone.
    delete (window as { matchMedia?: unknown }).matchMedia;
  });

  it("stacks the seven days as cards instead of drawing the week table", () => {
    stubWidth(true);
    render(<StaffSchedulePage />);

    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    const days = screen.getAllByRole("heading", { level: 2 }).filter((h) => /^(Mon|Tue|Wed|Thu|Fri|Sat|Sun)\b/.test(h.textContent ?? ""));
    expect(days.map((h) => (h.textContent ?? "").slice(0, 3))).toEqual(["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]);
    expect(days).toHaveLength(7);

    // Monday's card says it is short, how many are in, and who, with the same cell the grid draws.
    const monday = days[0].closest("section") as HTMLElement;
    expect(within(monday).getByText("In that day: 1 staff, 4 volunteers")).toBeInTheDocument();
    expect(within(monday).getByRole("link", { name: "Head Cook A" })).toBeInTheDocument();
    expect(within(monday).getByRole("button", { name: "Head Cook A, 2026-08-31" })).toHaveTextContent("09:00–17:00");
  });

  it("opens the day's editor inside the card it was pressed in", () => {
    stubWidth(true);
    render(<StaffSchedulePage />);

    const cell = screen.getByRole("button", { name: "Head Cook A, 2026-09-02" });
    fireEvent.click(cell);

    const wednesday = cell.closest("section") as HTMLElement;
    expect(within(wednesday).getByRole("form", { name: /change the hours/i })).toBeInTheDocument();
  });

  it("keeps the week table at tablet width and up", () => {
    stubWidth(false);
    render(<StaffSchedulePage />);
    expect(screen.getByRole("table")).toBeInTheDocument();
  });
});
