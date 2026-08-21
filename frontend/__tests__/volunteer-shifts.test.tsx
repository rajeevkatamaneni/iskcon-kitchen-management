import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
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

  it("warns when a shift with a roster was moved, because nobody was told", () => {
    paramsRef.current = new URLSearchParams("saved=Sunday%20prep&moved=s1");
    render(<VolunteerShiftsPage />);
    expect(screen.getByText(/have not been told/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /send them an update/i })).toHaveAttribute(
      "href",
      "/volunteers/s1"
    );
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

  it("saves through updateShift, in minutes", async () => {
    render(<EditShiftPage />);
    const form = screen.getByRole("form", { name: /edit a shift/i });

    fireEvent.change(form.querySelector('input[name="title"]')!, { target: { value: "Sunday cooking" } });
    fireEvent.change(form.querySelector('input[name="reminderHours"]')!, { target: { value: "24, 48" } });
    fireEvent.submit(form);

    await waitFor(() => expect(updateShiftMock).toHaveBeenCalled());
    const [id, input] = updateShiftMock.mock.calls[0];
    expect(id).toBe("s1");
    expect(input.title).toBe("Sunday cooking");
    expect(input.reminderOffsetsMinutes).toEqual([1440, 2880]);
    expect(pushMock).toHaveBeenCalledWith("/volunteers?saved=Sunday%20cooking");
  });

  it("carries the warning back to the list when the shift moved under a roster", async () => {
    render(<EditShiftPage />);
    const form = screen.getByRole("form", { name: /edit a shift/i });

    fireEvent.change(form.querySelector('input[name="startTime"]')!, { target: { value: "16:00" } });
    fireEvent.submit(form);

    await waitFor(() => expect(updateShiftMock).toHaveBeenCalled());
    expect(pushMock).toHaveBeenCalledWith("/volunteers?saved=Sunday%20prep&moved=s1");
  });

  it("stays quiet when an edit leaves the time alone", async () => {
    render(<EditShiftPage />);
    const form = screen.getByRole("form", { name: /edit a shift/i });

    fireEvent.change(form.querySelector('input[name="location"]')!, { target: { value: "Prep area" } });
    fireEvent.submit(form);

    await waitFor(() => expect(updateShiftMock).toHaveBeenCalled());
    expect(pushMock).toHaveBeenCalledWith("/volunteers?saved=Sunday%20prep");
  });
});
