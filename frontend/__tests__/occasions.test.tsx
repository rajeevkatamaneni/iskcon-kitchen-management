import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ApiError, type OccasionView } from "@/lib/api";

/**
 * The festival occasion catalogue a temple curates for itself (T-004).
 *
 * <p>`useAuthedQuery` is deliberately **not** mocked here, unlike most screen tests. The acceptance
 * this file exists to prove is that a create, a rename and a delete each show up in the list
 * without the page being reloaded, and a mocked query hook can only ever prove that `reload()` was
 * called — the very step where the claim could fail. So the real hook runs against a fake server
 * whose catalogue the mutations actually change, and each test asserts the row on the screen.
 *
 * <p>The auth object is one stable reference for the same reason `role-refusals.test.tsx` gives:
 * `useAuthedQuery` lists `getToken` in the dependencies of the effect that fetches, so a mock
 * rebuilding its closure every render re-fetches forever.
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
  catalogue: { current: [] as OccasionView[] },
  listMock: vi.fn(),
  createMock: vi.fn(),
  updateMock: vi.fn(),
  deleteMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/settings/occasions",
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      listOccasions: listMock,
      createOccasion: createMock,
      updateOccasion: updateMock,
      deleteOccasion: deleteMock,
    },
  };
});

import OccasionsPage from "@/app/settings/occasions/page";

function occasion(overrides: Partial<OccasionView> = {}): OccasionView {
  return {
    id: "o1",
    name: "Sri Krsna Janmastami",
    type: "COMPUTED",
    matchText: "Janmastami",
    fixedMonth: null,
    fixedDay: null,
    defaultServings: 2000,
    notes: null,
    seeded: true,
    ...overrides,
  };
}

const anniversary = occasion({
  id: "o2",
  name: "Temple Anniversary",
  type: "MANUAL",
  matchText: null,
  fixedMonth: 8,
  fixedDay: 14,
  defaultServings: 400,
  notes: null,
  seeded: false,
});

/**
 * Label queries are anchored with `^` wherever a field carries a hint. The hint is a focusable "i"
 * beside the label whose own accessible name is "More about <label>", so an unanchored pattern
 * matches the input and the button both and the query fails on two elements rather than none.
 */

/** The screen once its first fetch has landed. */
async function open() {
  render(<OccasionsPage />);
  await screen.findByRole("cell", { name: /sri krsna janmastami/i });
}

describe("festival occasions", () => {
  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", fullName: "Test Person" },
      getToken: async () => "test-token",
      refresh: () => {},
    };
    catalogue.current = [occasion(), anniversary];
    // The fake server: every read answers from the catalogue as it stands, so a mutation that
    // changed it is visible to the next read and to nothing else.
    listMock.mockReset().mockImplementation(async () => [...catalogue.current]);
    createMock.mockReset().mockImplementation(async (input) => {
      catalogue.current = [
        ...catalogue.current,
        occasion({ id: "new", seeded: false, defaultServings: null, ...input }),
      ];
      return { id: "new" };
    });
    updateMock.mockReset().mockImplementation(async (id: string, input) => {
      catalogue.current = catalogue.current.map((o) => (o.id === id ? { ...o, ...input } : o));
    });
    deleteMock.mockReset().mockImplementation(async (id: string) => {
      catalogue.current = catalogue.current.filter((o) => o.id !== id);
    });
  });

  it("lists what the temple observes, and how each date is found", async () => {
    await open();

    expect(screen.getByRole("heading", { name: /festival occasions/i })).toBeInTheDocument();
    // A computed occasion says which calendar wording it follows; a fixed one says its date, in
    // the day-first form the rest of the app writes dates in.
    expect(screen.getByRole("cell", { name: /whatever day the calendar gives .Janmastami./i })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: /14 August, every year/i })).toBeInTheDocument();
    // Seeded and temple-added are told apart, because deleting one of each is a different act.
    const seededCell = screen.getByRole("cell", { name: /sri krsna janmastami/i });
    expect(within(seededCell).getByText("Standard")).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: /^Temple Anniversary$/ })).toBeInTheDocument();
  });

  it("adds a fixed-date occasion and shows it in the list without a reload", async () => {
    await open();

    const form = screen.getByRole("form", { name: /add an occasion/i });
    fireEvent.click(within(form).getByLabelText(/the same date every year/i));
    fireEvent.change(within(form).getByLabelText(/^name/i), { target: { value: "Deity Installation Day" } });
    fireEvent.change(within(form).getByLabelText(/month/i), { target: { value: "11" } });
    fireEvent.change(within(form).getByLabelText(/^day/i), { target: { value: "3" } });
    fireEvent.change(within(form).getByLabelText(/^usual servings/i), { target: { value: "600" } });
    fireEvent.click(within(form).getByRole("button", { name: /add occasion/i }));

    await waitFor(() =>
      expect(createMock).toHaveBeenCalledWith(
        {
          name: "Deity Installation Day",
          type: "MANUAL",
          matchText: null,
          fixedMonth: 11,
          fixedDay: 3,
          defaultServings: 600,
          notes: null,
        },
        "test-token"
      )
    );

    // The row is on the screen, from a refetch rather than a page load.
    expect(await screen.findByRole("cell", { name: /^Deity Installation Day$/ })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: /3 November, every year/i })).toBeInTheDocument();
    expect(screen.getByText(/deity installation day was added/i)).toBeInTheDocument();
    // …and the form is empty again, ready for the next one.
    expect(within(form).getByLabelText(/^name/i)).toHaveValue("");
  });

  it("adds a calendar occasion by the wording the calendar prints", async () => {
    await open();

    const form = screen.getByRole("form", { name: /add an occasion/i });
    fireEvent.change(within(form).getByLabelText(/^name/i), { target: { value: "Vamana Dvadasi" } });
    fireEvent.change(within(form).getByLabelText(/^wording in the calendar/i), {
      target: { value: "Vamana Dvadasi" },
    });
    fireEvent.click(within(form).getByRole("button", { name: /add occasion/i }));

    await waitFor(() =>
      expect(createMock).toHaveBeenCalledWith(
        expect.objectContaining({
          type: "COMPUTED",
          matchText: "Vamana Dvadasi",
          fixedMonth: null,
          fixedDay: null,
        }),
        "test-token"
      )
    );
    expect(await screen.findByRole("cell", { name: /^Vamana Dvadasi$/ })).toBeInTheDocument();
  });

  it("renames one, and never sends a type on the edit", async () => {
    await open();

    const row = screen.getByRole("cell", { name: /^Temple Anniversary$/ }).closest("tr")!;
    fireEvent.click(within(row).getByRole("button", { name: /edit/i }));

    const dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/^name/i), {
      target: { value: "Temple Anniversary (Bengaluru)" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: /save occasion/i }));

    await waitFor(() => expect(updateMock).toHaveBeenCalled());
    const [id, input] = updateMock.mock.calls[0];
    expect(id).toBe("o2");
    // The backend's UpdateOccasionRequest has no type field. Sending one would post a field the
    // endpoint does not declare, which is why the two input types are separate.
    expect(input).not.toHaveProperty("type");
    expect(input).toMatchObject({ name: "Temple Anniversary (Bengaluru)", fixedMonth: 8, fixedDay: 14 });

    expect(await screen.findByRole("cell", { name: /^Temple Anniversary \(Bengaluru\)$/ })).toBeInTheDocument();
  });

  it("does not offer to convert one kind of occasion into the other", async () => {
    await open();

    const row = screen.getByRole("cell", { name: /^Temple Anniversary$/ }).closest("tr")!;
    fireEvent.click(within(row).getByRole("button", { name: /edit/i }));

    const dialog = screen.getByRole("dialog");
    expect(within(dialog).queryByLabelText(/the vaishnava calendar decides/i)).not.toBeInTheDocument();
    expect(within(dialog).queryByLabelText(/the same date every year/i)).not.toBeInTheDocument();
    // What it says instead of offering the control it cannot honour.
    expect(within(dialog).getByText(/add it again as a new occasion and remove this one/i)).toBeInTheDocument();
  });

  it("says what a removal changes before it happens, then removes it", async () => {
    await open();

    const row = screen.getByRole("cell", { name: /^Temple Anniversary$/ }).closest("tr")!;
    fireEvent.click(within(row).getByRole("button", { name: /delete/i }));

    const dialog = screen.getByRole("dialog");
    // The consequence for a day the planner has already resolved against: plans already made keep
    // the name they were saved with, and only future days stop being marked.
    expect(within(dialog).getByText(/keep the name they were saved with/i)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: /remove occasion/i }));

    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith("o2", "test-token"));
    await waitFor(() =>
      expect(screen.queryByRole("cell", { name: /^Temple Anniversary$/ })).not.toBeInTheDocument()
    );
    expect(screen.getByText(/temple anniversary was removed/i)).toBeInTheDocument();
  });

  it("warns that a standard festival will not come back on its own", async () => {
    await open();

    const row = screen.getByRole("cell", { name: /sri krsna janmastami/i }).closest("tr")!;
    fireEvent.click(within(row).getByRole("button", { name: /delete/i }));

    expect(screen.getByText(/does not come back on its own/i)).toBeInTheDocument();
  });

  it("shows a refused removal in the reader's own words, and keeps the dialog open", async () => {
    deleteMock.mockRejectedValueOnce(
      new ApiError(
        {
          code: "KMS-400109",
          message: "This occasion is on a meal plan that has already been cooked.",
          action: "Remove it from that plan first.",
          fieldErrors: [],
        },
        409
      )
    );
    await open();

    const row = screen.getByRole("cell", { name: /^Temple Anniversary$/ }).closest("tr")!;
    fireEvent.click(within(row).getByRole("button", { name: /delete/i }));
    fireEvent.click(screen.getByRole("button", { name: /remove occasion/i }));

    const alert = await screen.findByRole("alert");
    expect(within(alert).getByText(/already been cooked/i)).toBeInTheDocument();
    expect(within(alert).getByText(/remove it from that plan first/i)).toBeInTheDocument();
    expect(within(alert).getByText("KMS-400109")).toBeInTheDocument();
    // Nothing raw reaches the reader, and the row is still there to try again on.
    expect(screen.queryByText(/409/)).not.toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: /^Temple Anniversary$/ })).toBeInTheDocument();
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
    render(<OccasionsPage />);

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
      const { unmount } = render(<OccasionsPage />);
      expect(screen.getByText(/not your page/i)).toBeInTheDocument();
      expect(screen.queryByRole("form", { name: /add an occasion/i })).not.toBeInTheDocument();
      unmount();
    }
    expect(listMock).not.toHaveBeenCalled();
  });
});
