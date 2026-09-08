import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ApiError, type MealKindView } from "@/lib/api";

/**
 * The kinds of meal a temple curates for itself (T-005).
 *
 * <p>This file exists because three endpoints shipped with no caller. `POST`, `PUT` and `DELETE`
 * on `/api/v1/meal-kinds` were reachable by nothing in the frontend, and T-038's whole rename
 * cascade — a migration, a service and its own IT suite — could not be triggered by any user. So
 * the assertions here are mostly about *what reaches the server*, not only about what appears on
 * the screen: the call, its arguments, and the server's own refusal rendered in the server's own
 * words.
 *
 * <p>`useAuthedQuery` is deliberately **not** mocked, for the reason `occasions.test.tsx` gives:
 * the claim being proved is that an add, a rename and a delete each show up in the list without the
 * page being reloaded, and a mocked query hook can only ever prove that `reload()` was called —
 * the very step where the claim could fail. The real hook runs against a fake server whose
 * catalogue the mutations actually change.
 *
 * <p>The auth object is one stable reference, because `useAuthedQuery` lists `getToken` in the
 * dependencies of the effect that fetches: a mock rebuilding its closure every render re-fetches
 * forever.
 */
const { authRef, catalogue, listMock, createMock, updateMock, deleteMock } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", fullName: "Test Person" },
      getToken: async () => "test-token",
      refresh: () => {},
    } as {
      status: string;
      appUser: { role: string; fullName?: string } | null;
      getToken: () => Promise<string>;
      refresh: () => void;
    },
  },
  catalogue: { current: [] as MealKindView[] },
  listMock: vi.fn(),
  createMock: vi.fn(),
  updateMock: vi.fn(),
  deleteMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/settings/meal-kinds",
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      listMealKinds: listMock,
      createMealKind: createMock,
      updateMealKind: updateMock,
      deleteMealKind: deleteMock,
    },
  };
});

import MealKindsPage from "@/app/settings/meal-kinds/page";

function kind(overrides: Partial<MealKindView> = {}): MealKindView {
  return {
    id: "k1",
    name: "Breakfast",
    sortOrder: 10,
    defaultReadyTime: "07:30:00",
    isEvent: false,
    needsOccasion: false,
    ...overrides,
  };
}

const lunch = kind({ id: "k2", name: "Lunch", sortOrder: 20, defaultReadyTime: "12:00:00" });
const feast = kind({
  id: "k3",
  name: "Festival feast",
  sortOrder: 35,
  defaultReadyTime: null,
  needsOccasion: true,
});
const event = kind({
  id: "k4",
  name: "Event",
  sortOrder: 50,
  defaultReadyTime: null,
  isEvent: true,
});

/**
 * The two refusals this screen exists to render properly, copied from `ErrorCode.java` rather than
 * paraphrased. If either sentence ever changes on the server it should change here too, by hand —
 * that is the point of writing them out.
 */
const IN_USE = new ApiError(
  {
    code: "KMS-400126",
    message: "Meals have already been planned or recorded as this kind.",
    action: "Rename it instead. Everything recorded under it takes the new name.",
    fieldErrors: [],
  },
  409
);

const ALREADY_EXISTS = new ApiError(
  {
    code: "KMS-400047",
    message: "That kind of meal already exists.",
    action: "Use the existing one, or choose a different name.",
    fieldErrors: [],
  },
  409
);

/**
 * Label queries are anchored with `^` wherever a field carries a hint. The hint is a focusable "i"
 * beside the label whose own accessible name is "More about <label>", so an unanchored pattern
 * matches the input and the button both and the query fails on two elements rather than none.
 */

/** The screen once its first fetch has landed. */
async function open() {
  render(<MealKindsPage />);
  await screen.findByRole("cell", { name: /^Breakfast$/ });
}

describe("meal kinds", () => {
  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", fullName: "Test Person" },
      getToken: async () => "test-token",
      refresh: () => {},
    };
    catalogue.current = [kind(), lunch, feast, event];
    // The fake server: every read answers from the catalogue as it stands, so a mutation that
    // changed it is visible to the next read and to nothing else.
    listMock.mockReset().mockImplementation(async () => [...catalogue.current]);
    createMock.mockReset().mockImplementation(async (input) => {
      catalogue.current = [...catalogue.current, kind({ id: "new", ...input })];
      return { id: "new" };
    });
    updateMock.mockReset().mockImplementation(async (id: string, input) => {
      catalogue.current = catalogue.current.map((k) => (k.id === id ? { ...k, ...input } : k));
    });
    deleteMock.mockReset().mockImplementation(async (id: string) => {
      catalogue.current = catalogue.current.filter((k) => k.id !== id);
    });
  });

  it("lists what the temple cooks, when each is due, and what the planner will ask for", async () => {
    await open();

    expect(screen.getByRole("heading", { name: /^meal kinds$/i })).toBeInTheDocument();
    // Seconds are noise on a screen — a meal is due at 07:30, not 07:30:00 (INT-8).
    expect(screen.getByRole("cell", { name: "07:30" })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "12:00" })).toBeInTheDocument();
    // A kind with no default time is a decision, not missing data, so it is said in words.
    expect(screen.getAllByRole("cell", { name: /asked every time/i })).toHaveLength(2);
    // The two flags are rendered as the questions the planner will put, not as field names.
    expect(screen.getByRole("cell", { name: /which festival it is for/i })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: /the event.s name, and whether it is going outside/i })).toBeInTheDocument();
    expect(screen.getAllByRole("cell", { name: /nothing beyond the dishes/i })).toHaveLength(2);
  });

  it("adds a kind, defaulting it to last in the day and to no fixed time", async () => {
    await open();

    const form = screen.getByRole("form", { name: /add a kind of meal/i });
    fireEvent.change(within(form).getByLabelText(/^name/i), { target: { value: "Bhoga offering" } });
    fireEvent.click(within(form).getByRole("button", { name: /add meal kind/i }));

    await waitFor(() => expect(createMock).toHaveBeenCalled());
    expect(createMock).toHaveBeenCalledWith(
      {
        name: "Bhoga offering",
        // Ten past the highest the temple already has, so it lands after Event (50) rather than
        // colliding with anything, and there is room to slot something in between later.
        sortOrder: 60,
        // Blank is null and not "00:00" — the planner asks for a time every time.
        defaultReadyTime: null,
        isEvent: false,
        needsOccasion: false,
      },
      "test-token"
    );

    // The row is on the screen, from a refetch rather than a page load.
    expect(await screen.findByRole("cell", { name: /^Bhoga offering$/ })).toBeInTheDocument();
    expect(screen.getByText(/bhoga offering was added/i)).toBeInTheDocument();
    // …and the form is empty again, ready for the next one.
    expect(within(form).getByLabelText(/^name/i)).toHaveValue("");
  });

  it("sends the time and the place in the day exactly as they were typed", async () => {
    await open();

    const form = screen.getByRole("form", { name: /add a kind of meal/i });
    fireEvent.change(within(form).getByLabelText(/^name/i), { target: { value: "Evening prasadam" } });
    fireEvent.change(within(form).getByLabelText(/^usually ready by/i), { target: { value: "18:45" } });
    fireEvent.change(within(form).getByLabelText(/^order in the day/i), { target: { value: "25" } });
    fireEvent.click(within(form).getByRole("button", { name: /add meal kind/i }));

    await waitFor(() =>
      expect(createMock).toHaveBeenCalledWith(
        expect.objectContaining({
          name: "Evening prasadam",
          sortOrder: 25,
          defaultReadyTime: "18:45",
        }),
        "test-token"
      )
    );
  });

  it("renames one, and carries both flags so the edit cannot silently clear them", async () => {
    await open();

    const row = screen.getByRole("cell", { name: /^Event$/ }).closest("tr")!;
    fireEvent.click(within(row).getByRole("button", { name: /edit/i }));

    const dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/^name/i), {
      target: { value: "Catering event" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: /save meal kind/i }));

    await waitFor(() => expect(updateMock).toHaveBeenCalled());
    const [id, input] = updateMock.mock.calls[0];
    expect(id).toBe("k4");

    // Inspected as keys and *then* as a value. The PUT writes the whole record, so the defect this
    // guards against is an omitted `isEvent` — and an omitted key and an explicit `false` read
    // identically to `objectContaining`, which is the trap `docs/work/README.md` names.
    expect(Object.keys(input).sort()).toEqual(
      ["defaultReadyTime", "isEvent", "name", "needsOccasion", "sortOrder"]
    );
    expect(input.isEvent).toBe(true);
    expect(input.needsOccasion).toBe(false);
    expect(input.name).toBe("Catering event");
    // The order and the time it already had are preserved rather than reset.
    expect(input.sortOrder).toBe(50);
    expect(input.defaultReadyTime).toBeNull();

    expect(await screen.findByRole("cell", { name: /^Catering event$/ })).toBeInTheDocument();
  });

  it("says what a rename does to history, and only once the name has actually changed", async () => {
    await open();

    const row = screen.getByRole("cell", { name: /^Lunch$/ }).closest("tr")!;
    fireEvent.click(within(row).getByRole("button", { name: /edit/i }));

    const dialog = screen.getByRole("dialog");
    // Nothing typed yet: no warning, because nothing is being rewritten.
    expect(within(dialog).queryByText(/take the name/i)).not.toBeInTheDocument();

    fireEvent.change(within(dialog).getByLabelText(/^name/i), { target: { value: "Raj Bhog" } });

    // T-038's cascade, stated to the person about to trigger it: the three tables store the kind by
    // name, so a rename rewrites rows this reader is not looking at.
    expect(within(dialog).getByText(/everything recorded as Lunch is renamed too/i)).toBeInTheDocument();
    expect(within(dialog).getByText(/volunteer shifts posted for it/i)).toBeInTheDocument();
  });

  it("shows a duplicate name in the server's own words when renaming", async () => {
    updateMock.mockRejectedValueOnce(ALREADY_EXISTS);
    await open();

    const row = screen.getByRole("cell", { name: /^Lunch$/ }).closest("tr")!;
    fireEvent.click(within(row).getByRole("button", { name: /edit/i }));

    const dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/^name/i), { target: { value: "DINNER" } });
    fireEvent.click(within(dialog).getByRole("button", { name: /save meal kind/i }));

    const alert = await screen.findByRole("alert");
    // KMS-400047's own message and its own next step, not a paraphrase and not a generic failure.
    expect(within(alert).getByText("That kind of meal already exists.")).toBeInTheDocument();
    expect(
      within(alert).getByText("Use the existing one, or choose a different name.")
    ).toBeInTheDocument();
    expect(within(alert).getByText("KMS-400047")).toBeInTheDocument();
    // Nothing raw reaches the reader, and the dialog stays open to correct the name in.
    expect(screen.queryByText(/409/)).not.toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("shows a duplicate name in the server's own words when adding", async () => {
    createMock.mockRejectedValueOnce(ALREADY_EXISTS);
    await open();

    const form = screen.getByRole("form", { name: /add a kind of meal/i });
    fireEvent.change(within(form).getByLabelText(/^name/i), { target: { value: "lunch" } });
    fireEvent.click(within(form).getByRole("button", { name: /add meal kind/i }));

    const alert = await screen.findByRole("alert");
    expect(within(alert).getByText("That kind of meal already exists.")).toBeInTheDocument();
    expect(
      within(alert).getByText("Use the existing one, or choose a different name.")
    ).toBeInTheDocument();
    expect(within(alert).getByText("KMS-400047")).toBeInTheDocument();
  });

  it("removes a kind nothing has been planned as", async () => {
    await open();

    const row = screen.getByRole("cell", { name: /^Festival feast$/ }).closest("tr")!;
    fireEvent.click(within(row).getByRole("button", { name: /delete/i }));

    const dialog = screen.getByRole("dialog");
    // The refusal is forecast before the press, and the alternative is named.
    expect(within(dialog).getByText(/planned, cooked or rostered as/i)).toBeInTheDocument();
    expect(within(dialog).getByText(/change the name instead/i)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: /remove meal kind/i }));

    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith("k3", "test-token"));
    await waitFor(() =>
      expect(screen.queryByRole("cell", { name: /^Festival feast$/ })).not.toBeInTheDocument()
    );
    expect(screen.getByText(/festival feast was removed/i)).toBeInTheDocument();
  });

  it("renders a refused removal as KMS-400126's own words, and keeps the kind", async () => {
    deleteMock.mockRejectedValueOnce(IN_USE);
    await open();

    const row = screen.getByRole("cell", { name: /^Lunch$/ }).closest("tr")!;
    fireEvent.click(within(row).getByRole("button", { name: /delete/i }));
    fireEvent.click(screen.getByRole("button", { name: /remove meal kind/i }));

    const alert = await screen.findByRole("alert");
    // The whole point of the task: the server's sentence and the server's next step, verbatim.
    // A test asserting only that "an error appeared" would pass against a generic failure.
    expect(
      within(alert).getByText("Meals have already been planned or recorded as this kind.")
    ).toBeInTheDocument();
    expect(
      within(alert).getByText("Rename it instead. Everything recorded under it takes the new name.")
    ).toBeInTheDocument();
    expect(within(alert).getByText("KMS-400126")).toBeInTheDocument();

    // Nothing raw reaches the reader, and the row is still there to rename instead.
    expect(screen.queryByText(/409/)).not.toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: /^Lunch$/ })).toBeInTheDocument();
  });

  it("does not raise when the kind was already gone — the server answers 204", async () => {
    // MealKindService.delete() returns silently for an id that is not there, so a second click
    // must close the dialog like the first rather than surfacing a failure.
    deleteMock.mockReset().mockResolvedValue(undefined);
    await open();

    const row = screen.getByRole("cell", { name: /^Festival feast$/ }).closest("tr")!;
    fireEvent.click(within(row).getByRole("button", { name: /delete/i }));
    fireEvent.click(screen.getByRole("button", { name: /remove meal kind/i }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("says so readably when the list itself cannot be loaded", async () => {
    listMock.mockRejectedValue(
      new ApiError(
        {
          code: "KMS-500001",
          message: "Something went wrong at our end.",
          action: "Try again in a few minutes.",
          fieldErrors: [],
        },
        500
      )
    );
    render(<MealKindsPage />);

    const alert = await screen.findByRole("alert");
    expect(within(alert).getByText(/something went wrong at our end/i)).toBeInTheDocument();
    expect(within(alert).getByText("KMS-500001")).toBeInTheDocument();
  });

  it("refuses every role but Temple Admin", async () => {
    for (const role of ["KITCHEN_MANAGER", "KITCHEN_STAFF", "VOLUNTEER", "ACCOUNTANT", "SUPER_ADMIN"]) {
      authRef.current = {
        ...authRef.current,
        appUser: { role, fullName: "Test Person" },
      };
      const { unmount } = render(<MealKindsPage />);
      expect(screen.getByText(/not your page/i)).toBeInTheDocument();
      expect(screen.queryByRole("form", { name: /add a kind of meal/i })).not.toBeInTheDocument();
      unmount();
    }
    expect(listMock).not.toHaveBeenCalled();
  });
});
