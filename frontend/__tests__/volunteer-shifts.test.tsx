import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ApiError, ShiftView } from "@/lib/api";

const { authRef, queryRef, reloadMock, updateShiftMock, createShiftMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  // The list asks for many shifts and the edit screen for one, and both go through the same stub.
  queryRef: {
    current: { data: [] as ShiftView[] | ShiftView | null, error: null as ApiError | null, loading: false },
  },
  reloadMock: vi.fn(),
  updateShiftMock: vi.fn(),
  createShiftMock: vi.fn(),
}));

// The screen reads its own address bar now (item 22), so the stub has to answer both halves of
// next/navigation: what the URL says, and what a click asks the router to do with it.
const { pushMock, replaceMock, paramsRef } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
  paramsRef: { current: new URLSearchParams() },
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  useSearchParams: () => paramsRef.current,
  useParams: () => ({ id: "id-1" }),
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
    api: { ...actual.api, updateShift: updateShiftMock, createShift: createShiftMock },
  };
});

import VolunteerShiftsPage from "@/app/volunteers/page";
import NewShiftPage from "@/app/volunteers/new/page";
import EditShiftPage from "@/app/volunteers/[id]/edit/page";
import { todayIso } from "@/lib/format";

/**
 * This year, from the temple's own clock. The meal label writes a year only for a day outside the
 * current year (`dayRange`'s rule), so a fixture dated this year reads "15 September" in any year the
 * suite runs, rather than passing until December and then failing on a "2026".
 */
const YEAR = todayIso().slice(0, 4);

function shift(o: Partial<ShiftView> = {}): ShiftView {
  return {
    id: "s1",
    title: "Sunday prep",
    description: null,
    shiftDate: "2026-12-06",
    startTime: "08:00:00",
    endTime: "12:00:00",
    location: "Main kitchen",
    capacity: 5,
    reminderOffsetsMinutes: [1440],
    status: "OPEN",
    cancelReason: null,
    signedUpCount: 3,
    waitlistCount: 1,
    createdAt: "2026-08-01T00:00:00Z",
    mealId: null,
    mealKind: null,
    mealEventName: null,
    ...o,
  };
}

describe("volunteer shift management", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    queryRef.current = { data: [shift()], error: null, loading: false };
    reloadMock.mockReset();
    updateShiftMock.mockReset().mockResolvedValue(undefined);
    createShiftMock.mockReset().mockResolvedValue({ id: "new" });
    paramsRef.current = new URLSearchParams();
    pushMock.mockReset();
    replaceMock.mockReset();
  });

  it("lists posted shifts with fill counts, and sends both forms to their own screens", () => {
    render(<VolunteerShiftsPage />);
    expect(screen.getByRole("heading", { name: /volunteer shifts/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Sunday prep" })).toBeInTheDocument();
    expect(screen.getByText(/3\/5/)).toBeInTheDocument();
    // Eight fields either way, so neither form is a panel over this list any more.
    expect(screen.getByRole("link", { name: /post a shift/i })).toHaveAttribute("href", "/volunteers/new");
    expect(screen.getByRole("link", { name: "Edit" })).toHaveAttribute("href", "/volunteers/s1/edit");
  });

  it("shows the confirmation a posted shift comes back with", () => {
    paramsRef.current = new URLSearchParams("posted=Sunday%20prep");
    render(<VolunteerShiftsPage />);
    expect(screen.getByText(/Sunday prep is posted\./i)).toBeInTheDocument();
    expect(replaceMock).toHaveBeenCalledWith("/volunteers");
  });

  it("warns, with the count and the way to tell them, when a shift's date changed under a roster", () => {
    // Kept by D-27 for a new date: the server tells nobody then, so the list must say so.
    queryRef.current = { data: [shift({ signedUpCount: 2 })], error: null, loading: false };
    paramsRef.current = new URLSearchParams("saved=Sunday%20prep&moved=s1");
    render(<VolunteerShiftsPage />);
    expect(
      screen.getByText(/That shift moved, and the 2 volunteers already signed up have not been told\./)
    ).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /send them an update/i })).toHaveAttribute("href", "/volunteers/s1");
  });

  it("shows only the confirmation when the save carried no warning", () => {
    paramsRef.current = new URLSearchParams("saved=Sunday%20prep");
    render(<VolunteerShiftsPage />);
    expect(screen.getByText(/Sunday prep was saved\./i)).toBeInTheDocument();
    expect(screen.queryByText(/have not been told/i)).not.toBeInTheDocument();
  });

  it("labels a meal shift with its meal and day, an event by its own name, and a plain shift not at all (D-27)", () => {
    queryRef.current = {
      data: [
        shift({ id: "m1", title: "Lunch preparation", shiftDate: `${YEAR}-09-15`, mealId: "meal-lunch", mealKind: "Lunch" }),
        shift({
          id: "e1",
          title: "Festival cooking",
          shiftDate: `${YEAR}-09-16`,
          mealId: "meal-janmashtami",
          mealKind: "Event",
          mealEventName: "Janmashtami",
        }),
        shift({ id: "p1", title: "Garland making", shiftDate: `${YEAR}-09-15` }),
      ],
      error: null,
      loading: false,
    };
    render(<VolunteerShiftsPage />);

    const row = (title: string) => screen.getByRole("link", { name: title }).closest("tr") as HTMLElement;
    expect(within(row("Lunch preparation")).getByText("For Lunch, 15 September")).toBeInTheDocument();
    // An event is named by its own name, never as another "Event".
    expect(within(row("Festival cooking")).getByText("For Janmashtami, 16 September")).toBeInTheDocument();
    expect(row("Festival cooking").textContent).not.toMatch(/For Event/);
    // A shift not for a meal carries no label at all.
    expect(row("Garland making").textContent).not.toMatch(/\bFor /);
  });

  it("adds the year to a meal shift's label only where the date formatter does: outside this year", () => {
    queryRef.current = {
      data: [shift({ title: "Lunch preparation", shiftDate: `${Number(YEAR) + 1}-01-02`, mealId: "m", mealKind: "Lunch" })],
      error: null,
      loading: false,
    };
    render(<VolunteerShiftsPage />);
    expect(screen.getByText(`For Lunch, 2 January ${Number(YEAR) + 1}`)).toBeInTheDocument();
  });

  it("says when a posted shift runs through midnight (T-146)", () => {
    // "20:00–02:00" read cold is a shift that ends sixteen hours before it begins. The coordinator
    // scanning this list should not have to work out which of the two readings the temple meant.
    queryRef.current = {
      data: [shift({ title: "Janmashtami midnight offering", startTime: "20:00:00", endTime: "02:00:00" })],
      error: null,
      loading: false,
    };
    render(<VolunteerShiftsPage />);
    expect(screen.getByText(/20:00–02:00 \(next day\)/)).toBeInTheDocument();
  });

  it("leaves an ordinary shift's hours alone", () => {
    render(<VolunteerShiftsPage />);
    expect(screen.getByText(/08:00–12:00/)).toBeInTheDocument();
    expect(screen.queryByText(/next day/i)).not.toBeInTheDocument();
  });

  it("offers no duplicate action — the feature was withdrawn", () => {
    render(<VolunteerShiftsPage />);
    expect(screen.queryByRole("button", { name: /duplicate/i })).not.toBeInTheDocument();
  });

  it("refuses a volunteer", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    render(<VolunteerShiftsPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});

describe("posting a shift", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    queryRef.current = { data: null, error: null, loading: false };
    createShiftMock.mockReset().mockResolvedValue({ id: "new" });
    pushMock.mockReset();
  });

  it("asks for exactly the eight ruled fields, in the ruled order, with no meal box (D-27)", () => {
    render(<NewShiftPage />);
    const form = screen.getByRole("form", { name: /post a shift/i });
    const inputs = Array.from(form.querySelectorAll("input"));
    // Each box's own label, in the order the boxes are on the form.
    expect(inputs.map((i) => i.labels?.[0]?.textContent?.trim())).toEqual([
      "Title",
      "Date",
      "Volunteers requested",
      "Start time",
      "End time",
      "Location",
      "Reminder hours before",
      "Description",
    ]);
    expect(inputs.map((i) => i.name)).toEqual([
      "title", "shiftDate", "capacity", "startTime", "endTime", "location", "reminderHours", "description",
    ]);
    // No "is this for a meal" check box and no drop-down of meals, anywhere on the screen.
    expect(document.querySelectorAll('input[type="checkbox"], select')).toHaveLength(0);
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    expect(screen.queryByText("Capacity")).not.toBeInTheDocument();
  });

  it("says where kitchen help for a meal is asked for, with a link to the planner (D-27)", () => {
    render(<NewShiftPage />);
    const sentence = screen.getByText((_, el) =>
      el?.tagName === "P" && el.textContent === "Kitchen help for a meal? Ask from that meal in the planner."
    );
    const link = within(sentence).getByRole("link", { name: "in the planner" });
    expect(link).toHaveAttribute("href", "/planner");
  });

  it("posts a request with no meal field in it (D-27)", async () => {
    render(<NewShiftPage />);
    const form = screen.getByRole("form", { name: /post a shift/i });
    fireEvent.change(form.querySelector('input[name="title"]')!, { target: { value: "Garland making" } });
    fireEvent.change(form.querySelector('input[name="shiftDate"]')!, { target: { value: "2026-12-06" } });
    fireEvent.change(form.querySelector('input[name="startTime"]')!, { target: { value: "06:00" } });
    fireEvent.change(form.querySelector('input[name="endTime"]')!, { target: { value: "09:00" } });
    fireEvent.click(screen.getByRole("button", { name: /post shift/i }));

    await waitFor(() => expect(createShiftMock).toHaveBeenCalledTimes(1));
    const keys = Object.keys(createShiftMock.mock.calls[0][0]);
    expect(keys.sort()).toEqual(
      ["capacity", "description", "endTime", "location", "reminderOffsetsMinutes", "shiftDate", "startTime", "title"]
    );
    expect(keys.filter((k) => /meal/i.test(k))).toEqual([]);
  });

  it("commits from the header and returns to the list with the confirmation", async () => {
    render(<NewShiftPage />);
    const form = screen.getByRole("form", { name: /post a shift/i });

    fireEvent.change(form.querySelector('input[name="title"]')!, { target: { value: "Sunday prep" } });
    fireEvent.change(form.querySelector('input[name="shiftDate"]')!, { target: { value: "2026-12-06" } });
    fireEvent.change(form.querySelector('input[name="startTime"]')!, { target: { value: "08:00" } });
    fireEvent.change(form.querySelector('input[name="endTime"]')!, { target: { value: "12:00" } });
    // The commit button is in the sticky header, outside the form, and reaches it by name.
    fireEvent.click(screen.getByRole("button", { name: /post shift/i }));

    await waitFor(() => expect(createShiftMock).toHaveBeenCalled());
    expect(createShiftMock.mock.calls[0][0].title).toBe("Sunday prep");
    expect(pushMock).toHaveBeenCalledWith("/volunteers?posted=Sunday%20prep");
  });

  it("says the shift runs into the next morning as soon as the times say so (T-146)", () => {
    render(<NewShiftPage />);
    const form = screen.getByRole("form", { name: /post a shift/i });

    fireEvent.change(form.querySelector('input[name="startTime"]')!, { target: { value: "20:00" } });
    expect(screen.queryByText(/next day/i)).not.toBeInTheDocument();
    fireEvent.change(form.querySelector('input[name="endTime"]')!, { target: { value: "02:00" } });

    expect(screen.getByText(/Ends the next day/i)).toBeInTheDocument();
  });

  it("refuses a shift that starts and ends at the same time, without calling the API (T-146)", async () => {
    render(<NewShiftPage />);
    const form = screen.getByRole("form", { name: /post a shift/i });

    fireEvent.change(form.querySelector('input[name="title"]')!, { target: { value: "Nothing at all" } });
    fireEvent.change(form.querySelector('input[name="shiftDate"]')!, { target: { value: "2026-12-06" } });
    fireEvent.change(form.querySelector('input[name="startTime"]')!, { target: { value: "20:00" } });
    fireEvent.change(form.querySelector('input[name="endTime"]')!, { target: { value: "20:00" } });

    // 20:00 to 20:00 is either a shift of no length or one of twenty-four hours, and nothing can
    // say which. The server refuses it too (KMS-400001 naming the field) — this is that same rule
    // said before the press, because the failure notice on this screen shows the code's sentence
    // and highlights nothing.
    expect(screen.getByRole("alert")).toHaveTextContent(/cannot start and end at the same time/i);

    fireEvent.click(screen.getByRole("button", { name: /post shift/i }));
    await waitFor(() => expect(screen.getByRole("alert")).toBeInTheDocument());
    expect(createShiftMock).not.toHaveBeenCalled();
    expect(pushMock).not.toHaveBeenCalled();
  });

  it("lets the shift through once the end time is a different time", async () => {
    render(<NewShiftPage />);
    const form = screen.getByRole("form", { name: /post a shift/i });

    fireEvent.change(form.querySelector('input[name="title"]')!, { target: { value: "Midnight offering" } });
    fireEvent.change(form.querySelector('input[name="shiftDate"]')!, { target: { value: "2026-12-06" } });
    fireEvent.change(form.querySelector('input[name="startTime"]')!, { target: { value: "20:00" } });
    fireEvent.change(form.querySelector('input[name="endTime"]')!, { target: { value: "02:00" } });
    fireEvent.click(screen.getByRole("button", { name: /post shift/i }));

    await waitFor(() => expect(createShiftMock).toHaveBeenCalled());
    // Sent exactly as typed. There is no "next day" field to fill in — the rule is the arithmetic,
    // and the server, the database and this screen each read it from the same two times.
    expect(createShiftMock.mock.calls[0][0].startTime).toBe("20:00");
    expect(createShiftMock.mock.calls[0][0].endTime).toBe("02:00");
  });

  it("keeps the date editable: only the planner's layer fixes it (T-155)", () => {
    render(<NewShiftPage />);
    const date = screen
      .getByRole("form", { name: /post a shift/i })
      .querySelector('input[name="shiftDate"]') as HTMLInputElement;
    expect(date.readOnly).toBe(false);
    fireEvent.change(date, { target: { value: "2026-12-07" } });
    expect(date.value).toBe("2026-12-07");
  });

  it("offers Cancel rather than a back-link", () => {
    render(<NewShiftPage />);
    expect(screen.getByRole("link", { name: "Cancel" })).toHaveAttribute("href", "/volunteers");
    expect(screen.queryByText(/←/)).not.toBeInTheDocument();
  });
});

describe("correcting a shift", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    queryRef.current = { data: shift(), error: null, loading: false };
    updateShiftMock.mockReset().mockResolvedValue(undefined);
    pushMock.mockReset();
  });

  it("prefills from the shift, with times the input can read", () => {
    render(<EditShiftPage />);
    const form = screen.getByRole("form", { name: /edit a shift/i });
    expect(screen.getByRole("heading", { name: /edit a shift/i })).toBeInTheDocument();
    expect(screen.getByText(/3 volunteers signed up/i)).toBeInTheDocument();
    expect(form.querySelector('input[name="title"]')).toHaveValue("Sunday prep");
    // "08:00:00" would be rejected by a time input, which then renders empty.
    expect(form.querySelector('input[name="startTime"]')).toHaveValue("08:00");
    expect(form.querySelector('input[name="endTime"]')).toHaveValue("12:00");
    expect(form.querySelector('input[name="capacity"]')).toHaveValue(5);
    // Offsets are stored in minutes and edited in hours.
    expect(form.querySelector('input[name="reminderHours"]')).toHaveValue("24");
  });

  it("keeps the date editable when correcting a shift (T-155)", () => {
    render(<EditShiftPage />);
    const date = screen
      .getByRole("form", { name: /edit a shift/i })
      .querySelector('input[name="shiftDate"]') as HTMLInputElement;
    expect(date.readOnly).toBe(false);
    expect(date.value).toBe("2026-12-06");
    fireEvent.change(date, { target: { value: "2026-12-07" } });
    expect(date.value).toBe("2026-12-07");
  });

  it("saves through updateShift, in minutes", async () => {
    render(<EditShiftPage />);
    const form = screen.getByRole("form", { name: /edit a shift/i });

    fireEvent.change(form.querySelector('input[name="title"]')!, { target: { value: "Sunday cooking" } });
    fireEvent.change(form.querySelector('input[name="reminderHours"]')!, { target: { value: "24, 48" } });
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(updateShiftMock).toHaveBeenCalled());
    const [id, input] = updateShiftMock.mock.calls[0];
    expect(id).toBe("s1");
    expect(input.title).toBe("Sunday cooking");
    expect(input.reminderOffsetsMinutes).toEqual([1440, 2880]);
    expect(pushMock).toHaveBeenCalledWith("/volunteers?saved=Sunday%20cooking");
  });

  it("lets a shift not for a meal change its date, and sends the new one", async () => {
    queryRef.current = { data: shift({ signedUpCount: 0 }), error: null, loading: false };
    render(<EditShiftPage />);
    const form = screen.getByRole("form", { name: /edit a shift/i });

    fireEvent.change(form.querySelector('input[name="shiftDate"]')!, { target: { value: "2026-12-07" } });
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(updateShiftMock).toHaveBeenCalledTimes(1));
    expect(updateShiftMock.mock.calls[0][1].shiftDate).toBe("2026-12-07");
    expect(updateShiftMock.mock.calls[0][1].mealId).toBeNull();
    // A new date with the times left alone is not the times-changed case, so nothing stops the save.
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(pushMock).toHaveBeenCalledWith("/volunteers?saved=Sunday%20prep");
  });

  it("stays quiet when an edit leaves the time alone", async () => {
    render(<EditShiftPage />);
    const form = screen.getByRole("form", { name: /edit a shift/i });

    fireEvent.change(form.querySelector('input[name="location"]')!, { target: { value: "Prep area" } });
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(updateShiftMock).toHaveBeenCalled());
    expect(pushMock).toHaveBeenCalledWith("/volunteers?saved=Sunday%20prep");
  });
});

/**
 * T-165: the shift form is a `Form`, so a refused box is named in red beside it and nothing is
 * sent. The same `ShiftFields` is mounted by the planner's layer, which planner-shift.test.tsx
 * covers; these two are the volunteers screens, each committing from its header with `form=`.
 */
describe("a blank shift form names each box it refused (T-165)", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    queryRef.current = { data: shift(), error: null, loading: false };
    createShiftMock.mockReset().mockResolvedValue(shift());
    updateShiftMock.mockReset().mockResolvedValue(undefined);
    pushMock.mockReset();
  });

  it("posting: names the title, date and both times beside their boxes, and posts nothing", async () => {
    render(<NewShiftPage />);
    fireEvent.click(screen.getByRole("button", { name: /post shift/i }));

    const form = screen.getByRole("form", { name: /post a shift/i });
    const expected: [string, string][] = [
      ["title", "Title is required"],
      ["shiftDate", "Date is required"],
      ["startTime", "Start time is required"],
      ["endTime", "End time is required"],
    ];
    for (const [name, sentence] of expected) {
      const said = await screen.findByText(sentence);
      expect(form.querySelector(`input[name="${name}"]`)!.getAttribute("aria-describedby")).toContain(said.id);
    }
    // Capacity opens on 1, which passes, so it says nothing.
    expect(screen.getAllByText(/ is required$/)).toHaveLength(4);
    expect(createShiftMock).not.toHaveBeenCalled();
    expect(pushMock).not.toHaveBeenCalled();
  });

  it("posting: a blank title and equal times each say their own thing, and nothing is posted", async () => {
    render(<NewShiftPage />);
    const form = screen.getByRole("form", { name: /post a shift/i });
    fireEvent.change(form.querySelector('input[name="shiftDate"]')!, { target: { value: "2026-12-06" } });
    fireEvent.change(form.querySelector('input[name="startTime"]')!, { target: { value: "20:00" } });
    fireEvent.change(form.querySelector('input[name="endTime"]')!, { target: { value: "20:00" } });
    fireEvent.click(screen.getByRole("button", { name: /post shift/i }));

    expect(await screen.findByText("Title is required")).toBeInTheDocument();
    // The same-time refusal is the form's own rule (T-146), shown as it was, beside the new sentence.
    expect(screen.getByRole("alert")).toHaveTextContent(/cannot start and end at the same time/i);
    expect(screen.getAllByText(/ is required$/)).toHaveLength(1);
    expect(createShiftMock).not.toHaveBeenCalled();
  });

  it("correcting: names a cleared title beside its box, and saves nothing", async () => {
    render(<EditShiftPage />);
    const form = screen.getByRole("form", { name: /edit a shift/i });
    const title = form.querySelector('input[name="title"]') as HTMLInputElement;
    fireEvent.change(title, { target: { value: "" } });
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    const said = await screen.findByText("Title is required");
    expect(title.getAttribute("aria-describedby")).toContain(said.id);
    expect(screen.getAllByText(/ is required$/)).toHaveLength(1);
    expect(updateShiftMock).not.toHaveBeenCalled();
    expect(pushMock).not.toHaveBeenCalled();
  });
});
