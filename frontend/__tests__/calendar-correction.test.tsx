import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import type { CalendarDayView } from "@/lib/api";

/**
 * Correcting what the calendar says about a day, on the Vaishnava calendar (T-219).
 *
 * <p>It lived on the planner's page for one day until 2026-09-17, where a Temple Admin reached it
 * only by landing there by accident from a meal's Cancel. Rajeev moved it here, the screen about what
 * day it is: same permission (a Temple Admin, on a day that has not passed), same API calls, same
 * form and same words. The blank-reason test came with it from planner-day-routes.test.tsx (T-165).
 */

const { authRef, queryRef, reload, api } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", fullName: "Radha Devi", tenantName: "ISKCON Bengaluru" },
    } as { status: string; appUser: { role: string; fullName?: string; tenantName?: string } | null },
  },
  queryRef: { current: { data: null as CalendarDayView[] | null } },
  reload: vi.fn(),
  api: {
    setCalendarOverride: vi.fn(async (_date: string, _input: Record<string, unknown>, _t?: string) => undefined),
    revertCalendarOverride: vi.fn(async (_date: string, _t?: string) => undefined),
  },
}));

const { paramsRef } = vi.hoisted(() => ({ paramsRef: { current: new URLSearchParams() } }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useSearchParams: () => paramsRef.current,
  useParams: () => ({}),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token", signOut: vi.fn() }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ data: queryRef.current.data, error: null, loading: false, reload }),
}));
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, ...api } };
});
// The screen anchors on the temple's today; pin it so the month under test never moves.
vi.mock("@/lib/format", async (orig) => {
  const actual = await orig<typeof import("@/lib/format")>();
  return { ...actual, todayIso: () => "2026-08-15" };
});

import CalendarPage from "@/app/calendar/page";

function day(overrides: Partial<CalendarDayView> & { date: string }): CalendarDayView {
  return {
    tithi: 16, paksa: 1, masa: 3, gaurabdaYear: 540, naksatra: 10,
    isEkadashi: false, ekadashiName: null, mahadvadashi: null, fastType: null,
    sunrise: "06:07:00", sunset: "18:41:00", festivals: [],
    overridden: false, overrideReason: null,
    ...overrides,
  };
}

const MONTH: CalendarDayView[] = [
  day({ date: "2026-08-10" }),
  day({ date: "2026-08-15" }),
  day({ date: "2026-08-20", overridden: true, overrideReason: "The temple's panchang says the 21st" }),
];

function asRole(role: string) {
  authRef.current = { status: "signed-in", appUser: { role, fullName: "Someone", tenantName: "ISKCON Bengaluru" } };
}

describe("correcting a date on the Vaishnava calendar (T-219)", () => {
  beforeEach(() => {
    asRole("TEMPLE_ADMIN");
    queryRef.current = { data: MONTH };
    paramsRef.current = new URLSearchParams();
    reload.mockClear();
    api.setCalendarOverride.mockClear();
    api.revertCalendarOverride.mockClear();
  });

  it("offers a Temple Admin Correct this date on the open day", () => {
    render(<CalendarPage />);
    expect(screen.getByRole("button", { name: /correct this date/i })).toBeInTheDocument();
  });

  it("puts Correct this date and Plan this day's menu side by side, one size, the correction in amber (T-237)", () => {
    // They were a small button and a large one on two rows (Rajeev, 2026-09-18). jsdom has no
    // layout, so this pins the markup the widths were measured on in Chrome: one grid row holding both,
    // the same size class on each, and the warning look on the correction.
    render(<CalendarPage />);
    const correct = screen.getByRole("button", { name: /correct this date/i });
    const plan = screen.getByRole("link", { name: /plan this day’s menu/i });
    expect(correct.parentElement).toBe(plan.parentElement);
    // Exactly equal: two 1fr columns, one size class, the same narrowed padding on each.
    expect(correct.parentElement!.className).toContain("grid-cols-2");
    for (const el of [correct, plan]) {
      expect(el.className).toContain("min-h-9 px-3 text-sm");
      expect(el.className).toContain("!px-2 py-1 text-center");
    }
    expect(correct.className).toContain("bg-warning-bg text-warning");
  });

  it.each(["KITCHEN_MANAGER", "KITCHEN_STAFF"])("offers %s nothing to correct or undo", (role) => {
    asRole(role);
    paramsRef.current = new URLSearchParams("day=2026-08-20");
    render(<CalendarPage />);

    // They still read that the day was corrected, and why.
    expect(screen.getByText(/corrected by hand — the temple's panchang says the 21st/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /correct this date/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /undo the correction/i })).not.toBeInTheDocument();
  });

  it("offers nothing on a day that has passed, as the planner did", () => {
    paramsRef.current = new URLSearchParams("day=2026-08-10");
    render(<CalendarPage />);
    expect(screen.queryByRole("button", { name: /correct this date/i })).not.toBeInTheDocument();
  });

  it("names a blank reason beside its box, corrects nothing, then corrects and re-reads the month", async () => {
    render(<CalendarPage />);
    fireEvent.click(screen.getByRole("button", { name: /correct this date/i }));
    fireEvent.click(screen.getByRole("button", { name: /save correction/i }));

    const said = await screen.findByText("Why are you correcting this? is required");
    const form = screen.getByRole("form", { name: /correct this date/i });
    const reason = form.querySelector('[name="reason"]') as HTMLTextAreaElement;
    expect(reason.getAttribute("aria-describedby")).toContain(said.id);
    expect(api.setCalendarOverride).not.toHaveBeenCalled();

    fireEvent.change(reason, { target: { value: "The temple's panchang says tomorrow" } });
    fireEvent.click(within(form).getByRole("button", { name: /save correction/i }));
    await vi.waitFor(() => expect(api.setCalendarOverride).toHaveBeenCalledTimes(1));
    expect(api.setCalendarOverride.mock.calls[0][0]).toBe("2026-08-15");
    expect(api.setCalendarOverride.mock.calls[0][1]).toMatchObject({
      reason: "The temple's panchang says tomorrow",
      tithi: 16,
      isEkadashi: false,
    });
    await vi.waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
  });

  it("undoes a correction on a corrected day and re-reads the month", async () => {
    paramsRef.current = new URLSearchParams("day=2026-08-20");
    render(<CalendarPage />);

    fireEvent.click(screen.getByRole("button", { name: /undo the correction/i }));
    await vi.waitFor(() => expect(api.revertCalendarOverride).toHaveBeenCalledWith("2026-08-20", "test-token"));
    await vi.waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
  });
});
