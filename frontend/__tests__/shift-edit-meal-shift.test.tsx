import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ApiError, type ShiftView, type UpdateShiftInput } from "@/lib/api";
import { todayIso } from "@/lib/format";

/**
 * T-199: correcting a shift on `/volunteers/[id]/edit` after D-27.
 *
 * <p>Two rulings are held here. **A meal shift keeps its meal and its date** (answer 4): the screen
 * shows both as words with a link to the meal, offers no box for either and no way to make it a shift
 * not for a meal, and sends the date back exactly as it read it. **New times under a roster are warned
 * about before the save** (answer 6), in the words the planner uses, never "moved". **A plain shift's
 * new date under a roster is not warned about before the save**, because the server tells nobody then;
 * the list says so afterwards through `?moved=`.
 *
 * <p>`shift-edit-keeps-meal-link.test.tsx` holds the link itself; `volunteer-shifts.test.tsx` holds the
 * plain shift's editable date and the list.
 */

const { queryRef, updateShiftMock, pushMock } = vi.hoisted(() => ({
  queryRef: { current: { data: null as ShiftView | null, error: null, loading: false } },
  updateShiftMock: vi.fn(),
  pushMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({ id: "s1" }),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({
    status: "signed-in",
    appUser: { role: "KITCHEN_MANAGER", userId: "me" },
    getToken: async () => "test-token",
  }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ ...queryRef.current, reload: vi.fn() }),
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, updateShift: updateShiftMock } };
});

import EditShiftPage from "@/app/volunteers/[id]/edit/page";

/** This year, so "15 September" carries no year whenever the suite runs (`dayRange`'s rule). */
const YEAR = todayIso().slice(0, 4);
const DATE = `${YEAR}-09-15`;

const WARNING_3 = "3 volunteers are signed up. They’ll be told the new times.";
const WARNING_1 = "1 volunteer is signed up. They’ll be told the new times.";

function shift(o: Partial<ShiftView> = {}): ShiftView {
  return {
    id: "s1",
    title: "Lunch preparation",
    description: null,
    shiftDate: DATE,
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
    mealId: "meal-lunch",
    mealKind: "Lunch",
    mealEventName: null,
    ...o,
  };
}

function form(): HTMLFormElement {
  return screen.getByRole("form", { name: /edit a shift/i }) as HTMLFormElement;
}

function field(name: string): HTMLInputElement {
  return form().querySelector(`input[name="${name}"]`) as HTMLInputElement;
}

function pressSave() {
  fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
}

async function sent(): Promise<UpdateShiftInput> {
  await waitFor(() => expect(updateShiftMock).toHaveBeenCalledTimes(1));
  const [id, input] = updateShiftMock.mock.calls[0];
  expect(id).toBe("s1");
  return input as UpdateShiftInput;
}

beforeEach(() => {
  queryRef.current = { data: shift(), error: null, loading: false };
  updateShiftMock.mockReset().mockResolvedValue(undefined);
  pushMock.mockReset();
});

describe("a meal shift edited from the Volunteer shifts page (D-27 answer 4)", () => {
  it("shows the date and the meal as words, not boxes, with a link to the meal", () => {
    render(<EditShiftPage />);

    // No date box of any kind: not an input, not a read-only one, not a hidden one.
    expect(form().querySelector('input[name="shiftDate"]')).toBeNull();
    expect(form().querySelectorAll('input[type="date"]')).toHaveLength(0);
    expect(within(form()).getByText("15 September")).toBeInTheDocument();
    expect(within(form()).getByText(/For Lunch\./)).toBeInTheDocument();

    const link = within(form()).getByRole("link", { name: "Open this meal" });
    expect(link).toHaveAttribute("href", "/planner/meal/meal-lunch");
  });

  it("offers no way to make it a shift not for a meal", () => {
    render(<EditShiftPage />);
    expect(document.querySelectorAll('input[type="checkbox"], select')).toHaveLength(0);
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    expect(screen.queryByText(/not for a meal/i)).not.toBeInTheDocument();
  });

  it("names an event by its own name", () => {
    queryRef.current = {
      data: shift({ mealId: "meal-janmashtami", mealKind: "Event", mealEventName: "Janmashtami" }),
      error: null,
      loading: false,
    };
    render(<EditShiftPage />);
    expect(within(form()).getByText(/For Janmashtami\./)).toBeInTheDocument();
    expect(within(form()).getByRole("link", { name: "Open this meal" })).toHaveAttribute(
      "href",
      "/planner/meal/meal-janmashtami"
    );
  });

  it("still lets the other six change, and saves at once with the date and meal as they were", async () => {
    render(<EditShiftPage />);
    fireEvent.change(field("title"), { target: { value: "Cutting vegetables" } });
    fireEvent.change(field("capacity"), { target: { value: "7" } });
    fireEvent.change(field("location"), { target: { value: "Back kitchen" } });
    fireEvent.change(field("reminderHours"), { target: { value: "2" } });
    fireEvent.change(field("description"), { target: { value: "Bring an apron" } });
    pressSave();

    // At once: no dialog in the way, one request, straight back to the list.
    const input = await sent();
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(input).toMatchObject({
      title: "Cutting vegetables",
      capacity: 7,
      location: "Back kitchen",
      reminderOffsetsMinutes: [120],
      description: "Bring an apron",
    });
    // No date change: the date goes back exactly as it was read, and so does the meal.
    expect(Object.keys(input)).toContain("shiftDate");
    expect(input.shiftDate).toBe(DATE);
    expect(input.mealId).toBe("meal-lunch");
    expect(pushMock).toHaveBeenCalledWith("/volunteers?saved=Cutting%20vegetables");
  });

  it("shows the server's refusal of a date or meal change through the normal error notice", async () => {
    updateShiftMock.mockRejectedValue(
      new ApiError(
        {
          code: "KMS-400153",
          message: "A shift for a meal stays on that meal's day.",
          action: "Cancel this shift and ask for volunteers from the right meal in the planner.",
          fieldErrors: [],
        },
        409
      )
    );
    render(<EditShiftPage />);
    fireEvent.change(field("location"), { target: { value: "Back kitchen" } });
    pressSave();

    expect(await screen.findByText(/stays on that meal's day/i)).toBeInTheDocument();
    expect(screen.getByText(/KMS-400153/)).toBeInTheDocument();
    expect(pushMock).not.toHaveBeenCalled();
  });
});

describe("new times on a shift people signed up for (D-27 answer 6)", () => {
  it("warns in the ruled words before any request, and saves only once confirmed", async () => {
    render(<EditShiftPage />);
    fireEvent.change(field("startTime"), { target: { value: "09:00" } });
    pressSave();

    const warning = await screen.findByRole("alertdialog");
    expect(within(warning).getByText(WARNING_3)).toBeInTheDocument();
    expect(updateShiftMock).not.toHaveBeenCalled();
    expect(pushMock).not.toHaveBeenCalled();

    fireEvent.click(within(warning).getByRole("button", { name: "Save changes" }));
    const input = await sent();
    expect(input.startTime).toBe("09:00");
    expect(input.endTime).toBe("12:00");
    expect(input.shiftDate).toBe(DATE);
    expect(pushMock).toHaveBeenCalledWith("/volunteers?saved=Lunch%20preparation");
  });

  it("warns for a changed end time too", async () => {
    render(<EditShiftPage />);
    fireEvent.change(field("endTime"), { target: { value: "13:00" } });
    pressSave();

    expect(within(await screen.findByRole("alertdialog")).getByText(WARNING_3)).toBeInTheDocument();
    expect(updateShiftMock).not.toHaveBeenCalled();
  });

  it("says it properly for one volunteer", async () => {
    queryRef.current = { data: shift({ signedUpCount: 1 }), error: null, loading: false };
    render(<EditShiftPage />);
    fireEvent.change(field("startTime"), { target: { value: "09:00" } });
    pressSave();

    expect(within(await screen.findByRole("alertdialog")).getByText(WARNING_1)).toBeInTheDocument();
    expect(updateShiftMock).not.toHaveBeenCalled();
  });

  it("sends nothing when the editor goes back", async () => {
    render(<EditShiftPage />);
    fireEvent.change(field("startTime"), { target: { value: "09:00" } });
    pressSave();
    fireEvent.click(within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Go back" }));

    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(updateShiftMock).not.toHaveBeenCalled();
    expect(pushMock).not.toHaveBeenCalled();
  });

  it("does not warn when nobody is signed up", async () => {
    queryRef.current = { data: shift({ signedUpCount: 0, waitlistCount: 2 }), error: null, loading: false };
    render(<EditShiftPage />);
    fireEvent.change(field("startTime"), { target: { value: "09:00" } });
    pressSave();

    const input = await sent();
    expect(input.startTime).toBe("09:00");
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  it("does not warn when the times are unchanged, however many are signed up", async () => {
    render(<EditShiftPage />);
    fireEvent.change(field("capacity"), { target: { value: "9" } });
    pressSave();

    await sent();
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  it("warns the same way on a shift not for a meal, whose volunteers are told too", async () => {
    queryRef.current = {
      data: shift({ title: "Garland making", mealId: null, mealKind: null, mealEventName: null }),
      error: null,
      loading: false,
    };
    render(<EditShiftPage />);
    fireEvent.change(field("endTime"), { target: { value: "11:00" } });
    pressSave();

    expect(within(await screen.findByRole("alertdialog")).getByText(WARNING_3)).toBeInTheDocument();
    expect(updateShiftMock).not.toHaveBeenCalled();
  });

  it("never calls a change of times a move, anywhere on the screen", async () => {
    render(<EditShiftPage />);
    expect(document.body.textContent).not.toMatch(/\bmoved?\b/i);
    fireEvent.change(field("startTime"), { target: { value: "09:00" } });
    pressSave();
    await screen.findByRole("alertdialog");
    expect(document.body.textContent).not.toMatch(/\bmoved?\b/i);
  });
});

describe("a plain shift's new date under a roster: told to nobody, so said after the save (D-27)", () => {
  function plain(o: Partial<ShiftView> = {}): ShiftView {
    return shift({ title: "Garland making", mealId: null, mealKind: null, mealEventName: null, signedUpCount: 2, ...o });
  }

  it("saves a new date with no dialog, and carries the notice back to the list", async () => {
    queryRef.current = { data: plain(), error: null, loading: false };
    render(<EditShiftPage />);
    fireEvent.change(field("shiftDate"), { target: { value: `${YEAR}-09-16` } });
    pressSave();

    const input = await sent();
    expect(input.shiftDate).toBe(`${YEAR}-09-16`);
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(pushMock).toHaveBeenCalledWith("/volunteers?saved=Garland%20making&moved=s1");
  });

  it("gives the notice and no times warning when the date and the times both changed", async () => {
    queryRef.current = { data: plain(), error: null, loading: false };
    render(<EditShiftPage />);
    fireEvent.change(field("shiftDate"), { target: { value: `${YEAR}-09-16` } });
    fireEvent.change(field("startTime"), { target: { value: "09:00" } });
    pressSave();

    const input = await sent();
    expect(input.startTime).toBe("09:00");
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(pushMock).toHaveBeenCalledWith("/volunteers?saved=Garland%20making&moved=s1");
  });

  it("gives the times warning and no notice when only the times changed", async () => {
    queryRef.current = { data: plain(), error: null, loading: false };
    render(<EditShiftPage />);
    fireEvent.change(field("startTime"), { target: { value: "09:00" } });
    pressSave();

    const warning = await screen.findByRole("alertdialog");
    expect(within(warning).getByText("2 volunteers are signed up. They’ll be told the new times.")).toBeInTheDocument();
    fireEvent.click(within(warning).getByRole("button", { name: "Save changes" }));
    await sent();
    expect(pushMock).toHaveBeenCalledWith("/volunteers?saved=Garland%20making");
  });

  it("gives no notice for a new date when nobody is signed up", async () => {
    queryRef.current = { data: plain({ signedUpCount: 0 }), error: null, loading: false };
    render(<EditShiftPage />);
    fireEvent.change(field("shiftDate"), { target: { value: `${YEAR}-09-16` } });
    pressSave();

    await sent();
    expect(pushMock).toHaveBeenCalledWith("/volunteers?saved=Garland%20making");
  });

  it("never gives a meal shift the notice, whatever changes", async () => {
    render(<EditShiftPage />);
    fireEvent.change(field("startTime"), { target: { value: "09:00" } });
    pressSave();
    fireEvent.click(within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Save changes" }));

    await sent();
    expect(pushMock).toHaveBeenCalledTimes(1);
    expect(pushMock.mock.calls[0][0]).not.toMatch(/moved=/);
  });
});
