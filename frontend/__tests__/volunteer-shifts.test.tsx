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
  queryRef: { current: { data: [] as ShiftView[] | null, error: null as ApiError | null, loading: false } },
  reloadMock: vi.fn(),
  updateShiftMock: vi.fn(),
  createShiftMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
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
  });

  it("lists posted shifts with fill counts and a post control", () => {
    render(<VolunteerShiftsPage />);
    expect(screen.getByRole("heading", { name: /volunteer shifts/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Sunday prep" })).toBeInTheDocument();
    expect(screen.getByText(/3\/5/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /post a shift/i })).toBeInTheDocument();
  });

  it("offers no duplicate action — the feature was withdrawn", () => {
    render(<VolunteerShiftsPage />);
    expect(screen.queryByRole("button", { name: /duplicate/i })).not.toBeInTheDocument();
  });

  it("opens an edit form prefilled from the shift, with times the input can read", () => {
    render(<VolunteerShiftsPage />);
    fireEvent.click(screen.getByRole("button", { name: "Edit" }));

    const form = screen.getByRole("form", { name: /edit a shift/i });
    expect(screen.getByRole("heading", { name: /edit shift/i })).toBeInTheDocument();
    expect(form.querySelector('input[name="title"]')).toHaveValue("Sunday prep");
    // "08:00:00" would be rejected by a time input, which then renders empty.
    expect(form.querySelector('input[name="startTime"]')).toHaveValue("08:00");
    expect(form.querySelector('input[name="endTime"]')).toHaveValue("12:00");
    expect(form.querySelector('input[name="capacity"]')).toHaveValue(5);
    // Offsets are stored in minutes and edited in hours.
    expect(form.querySelector('input[name="reminderHours"]')).toHaveValue("24");
  });

  it("saves an edit through updateShift, in minutes", async () => {
    render(<VolunteerShiftsPage />);
    fireEvent.click(screen.getByRole("button", { name: "Edit" }));
    const form = screen.getByRole("form", { name: /edit a shift/i });

    fireEvent.change(form.querySelector('input[name="title"]')!, { target: { value: "Sunday cooking" } });
    fireEvent.change(form.querySelector('input[name="reminderHours"]')!, { target: { value: "24, 48" } });
    fireEvent.submit(form);

    await waitFor(() => expect(updateShiftMock).toHaveBeenCalled());
    const [id, input] = updateShiftMock.mock.calls[0];
    expect(id).toBe("s1");
    expect(input.title).toBe("Sunday cooking");
    expect(input.reminderOffsetsMinutes).toEqual([1440, 2880]);
    expect(reloadMock).toHaveBeenCalled();
  });

  it("warns when a shift with a roster is moved, because nobody was told", async () => {
    render(<VolunteerShiftsPage />);
    fireEvent.click(screen.getByRole("button", { name: "Edit" }));
    const form = screen.getByRole("form", { name: /edit a shift/i });

    fireEvent.change(form.querySelector('input[name="startTime"]')!, { target: { value: "16:00" } });
    fireEvent.submit(form);

    await waitFor(() => expect(screen.getByText(/have not been told/i)).toBeInTheDocument());
    expect(screen.getByRole("link", { name: /send them an update/i })).toHaveAttribute(
      "href",
      "/volunteers/s1"
    );
  });

  it("stays quiet when an edit leaves the time alone", async () => {
    render(<VolunteerShiftsPage />);
    fireEvent.click(screen.getByRole("button", { name: "Edit" }));
    const form = screen.getByRole("form", { name: /edit a shift/i });

    fireEvent.change(form.querySelector('input[name="location"]')!, { target: { value: "Prep area" } });
    fireEvent.submit(form);

    await waitFor(() => expect(updateShiftMock).toHaveBeenCalled());
    expect(screen.queryByText(/have not been told/i)).not.toBeInTheDocument();
  });

  it("refuses a volunteer", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    render(<VolunteerShiftsPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
