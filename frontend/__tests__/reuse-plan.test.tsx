import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

const { previewReuse, reusePlan, pushed } = vi.hoisted(() => ({
  previewReuse: vi.fn(),
  reusePlan: vi.fn(async () => ({
    copied: 0, daysWritten: 0, daysLeftAlone: 0, notCopied: 0, sourceWasEmpty: false,
  })),
  pushed: { current: [] as string[] },
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: (href: string) => pushed.current.push(href) }),
  useSearchParams: () => new URLSearchParams(""),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ getToken: async () => "t" }),
}));
vi.mock("@/components/RequireRole", () => ({
  RequireRole: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, previewReuse, reusePlan } };
});

import ReusePlanPage from "@/app/planner/reuse/page";
import { todayIso } from "@/lib/format";

/** What the server says is in the source window, in the shape the screen reads. */
function preview(over: Record<string, unknown> = {}) {
  return {
    sourceWasEmpty: false,
    kinds: [
      { mealKind: "Breakfast", dayCount: 15, mealCount: 15 },
      { mealKind: "Lunch", dayCount: 15, mealCount: 15 },
    ],
    events: [
      { eventName: "Bhajan Prasadam", occurrences: 2, outside: false, lastSeen: "2026-09-12" },
      { eventName: "Sharma wedding delivery", occurrences: 1, outside: true, lastSeen: "2026-09-08" },
    ],
    excluded: [
      {
        label: "Festival feast · Janmashtami",
        reason: "A feast takes its occasion from the calendar on the day it is cooked.",
        on: "2026-09-04",
      },
    ],
    headCounts: [{ mealKind: "Lunch", adults: 150, children: 30, seniors: 0 }],
    days: [],
    totals: { meals: 30, daysWritten: 15, daysLeftAlone: 0, notCopied: 0 },
    ...over,
  };
}

function landing() {
  return screen.getByLabelText(/landing on/i, { selector: "input" }) as HTMLInputElement;
}

describe("reusing a plan", () => {
  beforeEach(() => {
    previewReuse.mockReset();
    previewReuse.mockResolvedValue(preview());
    reusePlan.mockClear();
    pushed.current = [];
  });

  /**
   * The landing day is derived, not typed.
   *
   * <p>Rajeev, 2026-09-05: it "must be automatically moved to the earliest possible date to land on
   * based on Starting from and How many days", and a user "should NOT be allowed to manually pick an
   * impossible date". Both are the same rule — a copy cannot land inside the days it is reading,
   * because the overlap would be found already planned and silently left alone.
   */
  it("moves the landing day whenever the window moves", async () => {
    render(<ReusePlanPage />);
    await screen.findByText(/main meals/i);

    const from = screen.getByLabelText(/starting from/i, { selector: "input" });
    fireEvent.change(from, { target: { value: "2026-09-01" } });
    // Seven days from the 1st ends on the 7th, so the 8th is the first day that can take a copy.
    expect(landing()).toHaveValue("2026-09-08");

    fireEvent.change(screen.getByLabelText(/how many days/i, { selector: "input" }), {
      target: { value: "15" },
    });
    expect(landing()).toHaveValue("2026-09-16");
  });

  it("will not take a landing day inside the window it is reading", async () => {
    render(<ReusePlanPage />);
    await screen.findByText(/main meals/i);

    fireEvent.change(screen.getByLabelText(/starting from/i, { selector: "input" }), {
      target: { value: "2026-09-01" },
    });
    fireEvent.change(screen.getByLabelText(/how many days/i, { selector: "input" }), {
      target: { value: "15" },
    });

    // The picker will not offer one…
    expect(landing()).toHaveAttribute("min", "2026-09-16");
    // …and one typed straight into the field snaps forward rather than being accepted.
    fireEvent.change(landing(), { target: { value: "2026-09-05" } });
    expect(landing()).toHaveValue("2026-09-16");
  });

  it("will not land a copy on days that have already gone", async () => {
    render(<ReusePlanPage />);
    await screen.findByText(/main meals/i);

    // A window well in the past still lands from today at the earliest: meals nobody can cook are
    // the other impossible answer, and the planner already refuses to add to a past day.
    fireEvent.change(screen.getByLabelText(/starting from/i, { selector: "input" }), {
      target: { value: "2020-01-01" },
    });
    expect(landing().value >= todayIso()).toBe(true);
    expect(landing()).toHaveAttribute("min", todayIso());
  });

  it("offers what it found, and says what it will not offer", async () => {
    render(<ReusePlanPage />);
    await screen.findByText(/main meals/i);

    // Main meals are ticked; an event has to be asked for by name, and the count is the only honest
    // signal for "does this repeat?" since nothing in the schema records it.
    expect(screen.getByRole("checkbox", { name: /breakfast/i })).toBeChecked();
    expect(screen.getByText(/happened 2×/i)).toBeInTheDocument();
    // Two of them say it: the badge on the event, and the hint explaining what the counts mean.
    expect(screen.getAllByText(/happened once/i).length).toBeGreaterThan(0);

    // A feast is never on offer, and the screen says why rather than staying quiet about it.
    expect(screen.getByText(/Festival feast · Janmashtami/)).toBeInTheDocument();
    expect(screen.getByText(/takes its occasion from the calendar/i)).toBeInTheDocument();
  });

  it("says how many it will copy, and refuses to run when that is none", async () => {
    render(<ReusePlanPage />);
    expect(await screen.findByRole("button", { name: /copy 30 meals/i })).toBeEnabled();

    previewReuse.mockResolvedValue(preview({ totals: { meals: 0, daysWritten: 0, daysLeftAlone: 3, notCopied: 0 } }));
    fireEvent.click(screen.getByRole("checkbox", { name: /breakfast/i }));

    expect(await screen.findByRole("button", { name: /nothing to copy/i })).toBeDisabled();
  });

  it("an empty window says so rather than offering an empty list to tick", async () => {
    previewReuse.mockResolvedValue(preview({ sourceWasEmpty: true, kinds: [], events: [], excluded: [] }));
    render(<ReusePlanPage />);

    expect(await screen.findByText(/nothing is planned in those days/i)).toBeInTheDocument();
    expect(screen.queryByText(/main meals/i)).not.toBeInTheDocument();
  });

  it("lands back on the day it copied to, so somebody can see what arrived", async () => {
    render(<ReusePlanPage />);
    await screen.findByText(/main meals/i);
    const to = landing().value;

    fireEvent.click(screen.getByRole("button", { name: /copy 30 meals/i }));
    await vi.waitFor(() => expect(reusePlan).toHaveBeenCalledTimes(1));
    await vi.waitFor(() => expect(pushed.current[0]).toContain(`date=${to}`));
  });
});
