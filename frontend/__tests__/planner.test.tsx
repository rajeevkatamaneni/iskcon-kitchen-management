import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";

const { authRef, queryRef, urlRef } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", userId: "me", fullName: "Gopal Das" },
    } as { status: string; appUser: { role: string; userId: string; fullName?: string } | null },
  },
  // Every planner query goes through the one hook, so one array feeds them all. Empty by default,
  // which is what the shell, the views and the period nav are asserted against; a test that needs a
  // calendar day or a meal sets it, and the lookups that do not recognise the shape find nothing.
  queryRef: { current: [] as unknown[] },
  // The planner's view and date live in the address now (item 22), so the address has to be a real
  // thing in these tests rather than a stub — pushing to it is how the screen changes.
  urlRef: { current: null as null | { read: () => string; write: (search: string) => void } },
}));

vi.mock("next/navigation", async () => {
  const { useSyncExternalStore } = await import("react");
  const listeners = new Set<() => void>();
  const state = { search: "" };
  const store = {
    subscribe: (l: () => void) => {
      listeners.add(l);
      return () => {
        listeners.delete(l);
      };
    },
    read: () => state.search,
    write: (search: string) => {
      state.search = search;
      listeners.forEach((l) => l());
    },
  };
  urlRef.current = store;
  const go = (href: string) => store.write(href.split("?")[1] ?? "");
  return {
    useRouter: () => ({ push: go, replace: go, back: vi.fn(), refresh: vi.fn() }),
    useSearchParams: () =>
      new URLSearchParams(useSyncExternalStore(store.subscribe, store.read, store.read)),
  };
});
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ data: queryRef.current, error: null, loading: false, reload: vi.fn() }),
}));

import PlannerPage from "@/app/planner/page";
import { longDate, todayIso } from "@/lib/format";

/** Today's cell — a past day is deliberately read-only, so tests that plan must open this one. */
function todaysCell() {
  // The temple's day, as the planner anchors on — not the test machine's, which is a day behind
  // for a good part of every IST morning and would land the click on a read-only past day.
  // Written the way the application writes it, which is the Indian way whatever this machine's
  // locale says. Building the expected label with the default locale put the test on "Monday,
  // September 1, 2026" while the screen said "Monday, 1 September 2026".
  const label = longDate(todayIso());
  return screen.getByRole("button", { name: new RegExp(`^${label}, nothing planned$`) });
}

const MONTHS = ["January","February","March","April","May","June","July","August","September","October","November","December"];

/**
 * A day of the calendar, in the shape the one shared query hands to every lookup on the screen.
 *
 * <p>`dishes` is there so the meal grouping recognises the object and drops it: a day with no
 * preparations is not a meal, and the grids must say nothing about it rather than draw a ghost.
 */
function calendarDay(fields: Record<string, unknown> = {}) {
  return {
    date: todayIso(),
    tithi: 0, paksa: 0, masa: 0, gaurabdaYear: null, naksatra: null,
    isEkadashi: false, ekadashiName: null, mahadvadashi: null, fastType: null,
    sunrise: null, sunset: null,
    festivals: [],
    overridden: false, overrideReason: null,
    dishes: [],
    ...fields,
  };
}

/** One meal, as `mealServices` returns it: a kind, a time, its plates and its preparations. */
function meal(fields: Record<string, unknown> = {}) {
  return {
    serviceId: null,
    planDate: todayIso(),
    date: todayIso(),
    mealKind: "Lunch",
    readyBy: "12:00:00",
    adults: 120, children: 20, seniors: 0,
    plates: 133,
    crewRequired: null,
    dayType: "REGULAR",
    occasionName: null,
    eventName: null, contactName: null, contactPhone: null, deliveryAddress: null,
    purpose: null,
    kitchenNotes: null,
    cardNumber: null, cardIssuedAt: null,
    recorded: false, recordedAt: null, recordedByName: null, recordingNote: null,
    festivals: [],
    dishes: [
      preparation("m1", "Bisi Bele Bath"),
      preparation("m2", "Kesari Bath"),
      preparation("m3", "Majjige"),
    ],
    ...fields,
  };
}

function preparation(id: string, recipeName: string) {
  return {
    id, planDate: todayIso(), mealKind: "Lunch", readyBy: "12:00:00",
    recipeId: `r-${id}`, recipeName, targetYield: 133,
    dayType: "REGULAR", occasionName: null, status: "PLANNED",
    eventName: null, isOutside: false, handover: null, contactName: null, contactPhone: null,
    deliveryAddress: null, guestsEatAt: null, purpose: null,
    adults: 120, children: 20, seniors: 0, crewRequired: null,
    kitchenNotes: null, actualServings: null, notMade: false, cookedAt: null,
    ekadashiAcknowledged: false, createdAt: "2026-08-20T10:00:00Z",
  };
}

function views() {
  return screen.getByRole("tablist", { name: /planner view/i });
}

/** The commitments list is fed by the same shared query as everything else here — see the mock. */
function commitmentsList(): HTMLElement | null {
  return screen.queryByRole("region", { name: /upcoming outside commitments/i });
}

function outsideTheCommitmentsList(matches: HTMLElement[]): HTMLElement[] {
  const list = commitmentsList();
  return list ? matches.filter((el) => !list.contains(el)) : matches;
}

describe("meal planner", () => {
  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", userId: "me", fullName: "Gopal Das" },
    };
    queryRef.current = [];
    urlRef.current?.write("");
  });

  it("shows staff and volunteers as two pebbles on the day, never added together", () => {
    // The one query hook feeds every planner query, so an object carrying both a `date` and the
    // workforce fields is found by the calendar lookup and the workforce lookup alike. That is
    // enough here: the pebbles are what this is about (B3).
    queryRef.current = [calendarDay({ staffIn: 4, volunteers: 3 })];
    render(<PlannerPage />);

    // A cook and a two-hour evening volunteer are not interchangeable, so there is no "7".
    expect(screen.getByText("4")).toBeInTheDocument();
    expect(screen.getByText("3")).toBeInTheDocument();
    expect(screen.queryByText("7")).not.toBeInTheDocument();
    expect(screen.getByText(/staff in/i)).toBeInTheDocument();
    expect(screen.getByText(/volunteers signed up/i)).toBeInTheDocument();
  });

  it("carries the same two pebbles on each weekly tile, and none on the month", () => {
    queryRef.current = [calendarDay({ staffIn: 4, volunteers: 3 })];
    render(<PlannerPage />);

    fireEvent.click(within(views()).getByRole("tab", { name: "Week" }));
    expect(screen.getAllByText(/staff in/i).length).toBeGreaterThan(0);

    // The month grid is already fighting for room; clicking a day there opens the day view, which
    // carries the count.
    fireEvent.click(within(views()).getByRole("tab", { name: "Month" }));
    expect(screen.queryByText(/staff in/i)).not.toBeInTheDocument();
  });

  it("keeps a long festival name inside its month cell, with the whole name on hover", () => {
    // The name that broke it: the month box is narrow, and this ran straight out of the side of it.
    const name = "Sri Raghunandana Thakura -- Disappearance";
    queryRef.current = [calendarDay({ festivals: [{ text: name, priority: 1 }] })];
    render(<PlannerPage />);
    fireEvent.click(within(views()).getByRole("tab", { name: "Month" }));

    const label = screen.getAllByTitle(name)[0];
    expect(label).toHaveTextContent(name);
    // Truncation, and a width cap that can actually bite inside a flex row — `truncate` on its own
    // could not, because a flex item's min-width is auto and the name grew to its content.
    expect(label.className).toContain("truncate");
    expect(label.className).toContain("min-w-0");
    expect(label.className).toContain("max-w-full");
  });

  it("opens on the day, because a day is where the work is", () => {
    render(<PlannerPage />);
    expect(screen.getByRole("heading", { name: /meal planner/i })).toBeInTheDocument();

    expect(within(views()).getByRole("tab", { name: "Day" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByText(/nothing planned for this day/i)).toBeInTheDocument();
  });

  it("offers day, week and month views, and switches between them", () => {
    render(<PlannerPage />);

    fireEvent.click(within(views()).getByRole("tab", { name: "Month" }));
    expect(screen.getByText("Sun")).toBeInTheDocument();
    // The heading follows the planner's own day, which is the temple's — not this machine's.
    // Reading `new Date()` here put the test an evening ahead of the app every night in the US.
    const [year, month] = todayIso().split("-").map(Number);
    expect(
      screen.getByText(new RegExp(`${MONTHS[month - 1]} ${year}`))
    ).toBeInTheDocument();

    fireEvent.click(within(views()).getByRole("tab", { name: "Week" }));
    expect(
      screen.getByText(/^\d{1,2} [A-Z][a-z]{2} – \d{1,2} [A-Z][a-z]{2} \d{4}$/)
    ).toBeInTheDocument();
    // Seven days, each its own card — where the month draws six weeks of them.
    expect(screen.getAllByRole("button", { name: /nothing planned$/i })).toHaveLength(7);
  });

  it("a click in the month lands on that day, rather than opening a panel over the grid", () => {
    render(<PlannerPage />);
    fireEvent.click(within(views()).getByRole("tab", { name: "Month" }));

    fireEvent.click(todaysCell());

    expect(within(views()).getByRole("tab", { name: "Day" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByText(dayHeading(todayIso()))).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("a click in the week does the same", () => {
    render(<PlannerPage />);
    fireEvent.click(within(views()).getByRole("tab", { name: "Week" }));

    fireEvent.click(todaysCell());

    expect(within(views()).getByRole("tab", { name: "Day" })).toHaveAttribute("aria-selected", "true");
  });

  it("adding a meal composes in place, not in a panel over the page", () => {
    render(<PlannerPage />);

    fireEvent.click(screen.getByRole("button", { name: /add a meal/i }));

    // The composer takes the place of the button, under the day it belongs to. (With no recipes
    // loaded it says so — what matters here is that nothing opened over the page.)
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /add a meal/i })).not.toBeInTheDocument();
    expect(screen.getByText(/no recipes yet/i)).toBeInTheDocument();
  });

  it("tells a planner with no recipes what to do instead of offering an empty list", () => {
    render(<PlannerPage />);
    fireEvent.click(screen.getByRole("button", { name: /add a meal/i }));

    expect(screen.getByText(/no recipes yet/i)).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: /add a recipe/i }).length).toBeGreaterThan(0);
  });

  it("shows a past day as read-only rather than offering to plan on it", () => {
    render(<PlannerPage />);

    fireEvent.click(screen.getByRole("button", { name: /previous day/i }));
    expect(screen.queryByRole("button", { name: /add a meal/i })).not.toBeInTheDocument();
  });

  it("refuses a role without meal-plan access", () => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "VOLUNTEER", userId: "me", fullName: "Nitai Das" },
    };
    render(<PlannerPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});

// --- item 15: a meal is what gets planned, in all three views ----------------

describe("a meal is the unit of planning", () => {
  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", userId: "me", fullName: "Gopal Das" },
    };
    queryRef.current = [meal()];
    urlRef.current?.write("");
  });

  it("draws the day as one block per meal, with its preparations inside it", () => {
    render(<PlannerPage />);

    // One lunch, not three. The block names the meal and the plates it scales to; the three
    // preparations sit beneath it rather than beside two more copies of "Lunch".
    //
    // Counted outside the commitments table, which every query in this file feeds from the one
    // shared array (see the mock above) and which would otherwise put a second "Lunch" on the
    // screen that the planner itself never draws.
    expect(outsideTheCommitmentsList(screen.getAllByText("Lunch"))).toHaveLength(1);
    expect(screen.getByText(/133 servings/)).toBeInTheDocument();
    expect(screen.getByText("Bisi Bele Bath")).toBeInTheDocument();
    expect(screen.getByText("Majjige")).toBeInTheDocument();
    // The whole meal is edited as one, at its own address.
    expect(screen.getByRole("link", { name: /^edit$/i })).toHaveAttribute(
      "href",
      `/planner/${todayIso()}/Lunch`
    );
  });

  it("counts a week's tile in meals, and names the preparations beneath", () => {
    render(<PlannerPage />);
    fireEvent.click(within(views()).getByRole("tab", { name: "Week" }));

    // "12:00 Lunch · 3 preparations · 133 servings" — one line, not the same line three times.
    expect(screen.getByText(/12:00 Lunch/)).toBeInTheDocument();
    expect(screen.getByText(/3 preparations · 133 servings/)).toBeInTheDocument();
    expect(screen.getByText("Kesari Bath")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /1 meal planned$/i })
    ).toBeInTheDocument();
  });

  it("gives a month cell one line per meal kind and no preparation names — there is no room", () => {
    render(<PlannerPage />);
    fireEvent.click(within(views()).getByRole("tab", { name: "Month" }));

    expect(screen.getByText(/12:00 Lunch/)).toBeInTheDocument();
    expect(screen.queryByText("Kesari Bath")).not.toBeInTheDocument();
  });
});

// --- item 25: every view can move --------------------------------------------

describe("moving through the plan", () => {
  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", userId: "me", fullName: "Gopal Das" },
    };
    queryRef.current = [];
    urlRef.current?.write("");
  });

  function date() {
    return new URLSearchParams(urlRef.current?.read() ?? "").get("date");
  }

  it("always names the period between the arrows, and keeps Today as a separate way back", () => {
    render(<PlannerPage />);

    // The middle is a label, never a button, and it names the day on screen even when that day is
    // today. It used to be a button that said "Today" whenever you were in the current period, so
    // the view a planner uses most never said which day, week or month it was on — which is how
    // the same navigation defect kept coming back.
    expect(screen.getByText(dayHeading(todayIso()))).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /next day/i }));
    expect(screen.getByText(dayHeading(shiftDays(todayIso(), 1)))).toBeInTheDocument();

    // No Today button on the planner in any view (Rajeev, 2026-08-23) — the screen is one accent
    // action, and the arrows are how you move.
    expect(screen.queryByRole("button", { name: /^today$/i })).not.toBeInTheDocument();
  });

  it("steps a day in Day, a week in Week and a month in Month", () => {
    render(<PlannerPage />);

    fireEvent.click(screen.getByRole("button", { name: /next day/i }));
    const oneDayOn = date();
    expect(daysBetween(todayIso(), oneDayOn)).toBe(1);

    // Week view was the one a person switches to because they are planning ahead, and it could
    // only ever show this week.
    fireEvent.click(within(views()).getByRole("tab", { name: "Week" }));
    fireEvent.click(screen.getByRole("button", { name: /next week/i }));
    expect(daysBetween(oneDayOn, date())).toBe(7);

    fireEvent.click(within(views()).getByRole("tab", { name: "Month" }));
    const beforeMonth = date() as string;
    fireEvent.click(screen.getByRole("button", { name: /previous month/i }));
    expect(Number((date() as string).slice(5, 7))).toBe(previousMonth(beforeMonth));
  });

  it("names the week and the month it is on, in the words the calendar uses", () => {
    render(<PlannerPage />);
    fireEvent.click(within(views()).getByRole("tab", { name: "Week" }));
    fireEvent.click(screen.getByRole("button", { name: /next week/i }));

    // "24 Aug – 30 Aug 2026" — a range, because a week has no name of its own. Written exactly as
    // the Vaishnava calendar writes it, because both screens draw it with the same component.
    expect(
      screen.getByText(/^\d{1,2} [A-Z][a-z]{2} – \d{1,2} [A-Z][a-z]{2} \d{4}$/)
    ).toBeInTheDocument();

    fireEvent.click(within(views()).getByRole("tab", { name: "Month" }));
    fireEvent.click(screen.getByRole("button", { name: /next month/i }));
    expect(screen.getByText(new RegExp(`^(${MONTHS.join("|")}) \\d{4}$`))).toBeInTheDocument();
  });

  it("keeps Duplicate last week in week view, beside the control rather than instead of it", () => {
    render(<PlannerPage />);
    expect(screen.queryByRole("button", { name: /duplicate last week/i })).not.toBeInTheDocument();

    fireEvent.click(within(views()).getByRole("tab", { name: "Week" }));
    expect(screen.getByRole("button", { name: /duplicate last week/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /next week/i })).toBeInTheDocument();
  });
});

// --- item 22: what you are looking at is in the address -----------------------

describe("where now is, in every view", () => {
  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", userId: "me", fullName: "Gopal Das" },
    };
    queryRef.current = [];
    urlRef.current?.write("");
  });

  /**
   * The heading is a pill when the period on screen is the current one.
   *
   * <p>Read off the element that holds the words rather than off a test id: the point of the change
   * is that the *heading itself* is marked, not that a separate marker appeared beside it.
   */
  function pill(heading: HTMLElement) {
    return { coloured: heading.className.includes("bg-accent-bg"), current: heading.getAttribute("aria-current") };
  }

  it("marks today without taking the date away", () => {
    render(<PlannerPage />);

    // Rajeev, 2026-09-05: a stepper that says "Today" has stopped telling you the date. The words
    // never change; the ground under them does.
    const heading = screen.getByText(dayHeading(todayIso()));
    expect(pill(heading)).toEqual({ coloured: true, current: "date" });
    // And the fact is said in words for anyone who cannot see the colour.
    expect(within(heading).getByText("— Today", { exact: false })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /next day/i }));

    const tomorrow = screen.getByText(dayHeading(shiftDays(todayIso(), 1)));
    expect(pill(tomorrow)).toEqual({ coloured: false, current: null });
  });

  it("marks this week, and drops the mark a week either side of it", () => {
    render(<PlannerPage />);
    fireEvent.click(within(views()).getByRole("tab", { name: "Week" }));

    const week = /^\d{1,2} [A-Z][a-z]{2} – \d{1,2} [A-Z][a-z]{2} \d{4}$/;
    const thisWeek = screen.getByText(week);
    expect(pill(thisWeek)).toEqual({ coloured: true, current: "date" });
    expect(within(thisWeek).getByText("— This week", { exact: false })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /next week/i }));
    expect(pill(screen.getByText(week))).toEqual({ coloured: false, current: null });

    // Back onto it and past it the other way — a week is a range, so the mark has to follow the
    // week the anchor falls in rather than a single date matching.
    fireEvent.click(screen.getByRole("button", { name: /previous week/i }));
    expect(pill(screen.getByText(week))).toEqual({ coloured: true, current: "date" });
    fireEvent.click(screen.getByRole("button", { name: /previous week/i }));
    expect(pill(screen.getByText(week))).toEqual({ coloured: false, current: null });
  });

  it("marks this month, and drops the mark on the next one", () => {
    render(<PlannerPage />);
    fireEvent.click(within(views()).getByRole("tab", { name: "Month" }));

    const [year, month] = todayIso().split("-").map(Number);
    const thisMonth = screen.getByText(`${MONTHS[month - 1]} ${year}`);
    expect(pill(thisMonth)).toEqual({ coloured: true, current: "date" });
    expect(within(thisMonth).getByText("— This month", { exact: false })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /next month/i }));

    const next = month === 12 ? `${MONTHS[0]} ${year + 1}` : `${MONTHS[month]} ${year}`;
    expect(pill(screen.getByText(next))).toEqual({ coloured: false, current: null });
  });
});

describe("the planner's address", () => {
  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", userId: "me", fullName: "Gopal Das" },
    };
    queryRef.current = [];
    urlRef.current?.write("");
  });

  it("lands a deep link on the date it names, rather than on today", () => {
    // The bug behind this: `anchor` was `useState(todayIso())` and nothing read the query string,
    // so Today's "open that day in the planner" had been landing on today all along.
    urlRef.current?.write("date=2026-09-15");
    render(<PlannerPage />);

    expect(screen.getByText(/15 September/)).toBeInTheDocument();
    // What says where you are is the heading between the arrows, and on a deep link it says the
    // date the link named rather than today's.
    expect(screen.getByText(dayHeading("2026-09-15"))).toBeInTheDocument();
  });

  it("opens on the view its address names", () => {
    urlRef.current?.write("view=week&date=2026-09-15");
    render(<PlannerPage />);

    expect(within(views()).getByRole("tab", { name: "Week" })).toHaveAttribute("aria-selected", "true");
    expect(
      screen.getByText(/^\d{1,2} [A-Z][a-z]{2} – \d{1,2} [A-Z][a-z]{2} \d{4}$/)
    ).toBeInTheDocument();
  });

  it("writes the view and the date into the address as they change", () => {
    render(<PlannerPage />);
    fireEvent.click(within(views()).getByRole("tab", { name: "Week" }));

    const q = new URLSearchParams(urlRef.current?.read() ?? "");
    expect(q.get("view")).toBe("week");
    expect(q.get("date")).toBe(todayIso());
  });

  it("ignores a date it cannot read rather than working from NaN", () => {
    urlRef.current?.write("date=yesterday");
    render(<PlannerPage />);
    // A date nothing can parse is no date at all, so the screen opens on today rather than on NaN.
    expect(screen.getByText(dayHeading(todayIso()))).toBeInTheDocument();
  });
});

/**
 * The heading the day view puts between the arrows — the same words the shared nav writes.
 *
 * <p>Pinned `en-GB` because the nav is: every date in this application is written the Indian way
 * whoever is reading it. Left to the machine's locale, this helper expected "Tue, Sep 1, 2026"
 * while the screen said "Tue, 1 Sept 2026".
 */
function dayHeading(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString("en-GB", {
    weekday: "short", day: "numeric", month: "short", year: "numeric",
  });
}

function shiftDays(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00`);
  d.setDate(d.getDate() + days);
  return [d.getFullYear(), String(d.getMonth() + 1).padStart(2, "0"), String(d.getDate()).padStart(2, "0")].join("-");
}

function daysBetween(a: string | null, b: string | null): number {
  if (!a || !b) return NaN;
  return Math.round(
    (new Date(`${b}T00:00:00`).getTime() - new Date(`${a}T00:00:00`).getTime()) / 86_400_000
  );
}

function previousMonth(iso: string): number {
  const m = Number(iso.slice(5, 7));
  return m === 1 ? 12 : m - 1;
}

/**
 * Upcoming outside commitments (E4-S15 D4).
 *
 * <p>The idea behind the *Upcoming catering* table that was designed and never built was right —
 * nobody should discover a booking on the morning — and this covers more, because it is keyed off
 * *is this going outside* rather than *is this catering*. The school delivery and the community
 * programme are on it beside the wedding.
 *
 * <p>What is future, what order they come in and which are dropped is the server's answer (it
 * returns future, uncancelled rows in date order); what this asserts is that the screen draws that
 * answer whole, and draws nothing at all when there is none.
 */
describe("upcoming outside commitments", () => {
  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", userId: "me", fullName: "Gopal Das" },
    };
    urlRef.current?.write("");
  });

  it("lists what is leaving the temple, with enough to act on without opening anything", () => {
    queryRef.current = [
      commitment(),
      commitment({
        planDate: "2026-09-19",
        eventName: "Vidyaranyapura School Gita Reading",
        handover: "PICKUP",
        contactName: "Mrs Latha Rao",
        contactPhone: "+91 98862 30011",
        deliveryAddress: null,
        guestsEatAt: null,
      }),
    ];
    render(<PlannerPage />);

    const list = commitmentsList();
    expect(list).not.toBeNull();
    // When it is, what it is called, who to ring, and where it is going.
    expect(within(list as HTMLElement).getByText("Bhajan Prasadam at the school")).toBeInTheDocument();
    expect(within(list as HTMLElement).getByText("Mrs Latha Rao")).toBeInTheDocument();
    expect(
      within(list as HTMLElement).getByText("Hare Krishna Hill, Rajajinagar 560010")
    ).toBeInTheDocument();
    expect(within(list as HTMLElement).getByText("We deliver it")).toBeInTheDocument();
    expect(within(list as HTMLElement).getByText("Collected")).toBeInTheDocument();
  });

  it("draws nothing at all when nothing is going out", () => {
    queryRef.current = [];
    render(<PlannerPage />);
    // An empty table on every planner screen is furniture saying nothing, and a temple that does no
    // outside cooking would carry it for ever.
    expect(commitmentsList()).toBeNull();
  });
});

/** One commitment, as `outside-commitments` returns it. `dishes` keeps the meal grids out of it. */
function commitment(fields: Record<string, unknown> = {}) {
  return {
    planDate: "2026-09-12",
    eventName: "Bhajan Prasadam at the school",
    mealKind: "Event",
    handover: "DELIVERY",
    contactName: "Sri Anand Rao",
    contactPhone: "+91 98450 11223",
    deliveryAddress: "Hare Krishna Hill, Rajajinagar 560010",
    readyBy: "11:00:00",
    guestsEatAt: "13:00:00",
    preparations: 2,
    dishes: [],
    ...fields,
  };
}
