import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ShiftInput, ShiftView } from "@/lib/api";

/**
 * T-158. Saving a shift on `/volunteers/[id]/edit` must not take its meal link off.
 *
 * <p>`PUT /api/v1/shifts/{id}` replaces the whole shift, the D-14 link included, and an update that
 * leaves the three link fields out unlinks it — that is deliberate on the server and pinned by
 * `ShiftMealLinkIT.anEditCanUnlinkAShift`. The edit screen has no box for the link, so it used to
 * send the eight form fields and nothing else: a shift raised from the planner, once retitled here,
 * stopped counting toward its meal and vanished from the planner.
 *
 * <p>Every "keeps the link" test reads `Object.keys` before the values, because the defect was a key
 * that was never sent and `objectContaining({ mealDate: undefined })` passes against a missing key.
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
    appUser: { role: "TEMPLE_ADMIN", userId: "me" },
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

const LINK_KEYS = ["mealDate", "mealKind", "mealEventName"] as const;

/** A shift as `GET /shifts/{id}` returns it: the server always sends all three link fields. */
function shift(o: Partial<ShiftView> = {}): ShiftView {
  return {
    id: "s1",
    title: "Lunch preparation on Sunday, 6 December 2026",
    description: null,
    shiftDate: "2026-12-06",
    startTime: "08:00:00",
    endTime: "12:00:00",
    location: "Main kitchen",
    capacity: 5,
    reminderOffsetsMinutes: [1440],
    status: "OPEN",
    cancelReason: null,
    signedUpCount: 2,
    waitlistCount: 0,
    createdAt: "2026-08-01T00:00:00Z",
    mealDate: "2026-12-06",
    mealKind: "Lunch",
    mealEventName: null,
    ...o,
  };
}

function field(name: string): HTMLInputElement {
  const form = screen.getByRole("form", { name: /edit a shift/i });
  return form.querySelector(`input[name="${name}"]`) as HTMLInputElement;
}

async function saved(): Promise<ShiftInput> {
  // The header's own button, which reaches the form through `form=` (T-165).
  fireEvent.click(screen.getByRole("button", { name: /save changes/i }));
  await waitFor(() => expect(updateShiftMock).toHaveBeenCalledTimes(1));
  const [id, input] = updateShiftMock.mock.calls[0];
  expect(id).toBe("s1");
  return input as ShiftInput;
}

describe("editing a shift on the volunteers screen keeps its meal link (T-158)", () => {
  beforeEach(() => {
    updateShiftMock.mockReset().mockResolvedValue(undefined);
    pushMock.mockReset();
  });

  it("keeps a meal link through a change of title and capacity", async () => {
    queryRef.current = { data: shift(), error: null, loading: false };
    render(<EditShiftPage />);

    fireEvent.change(field("title"), { target: { value: "Cutting vegetables for lunch" } });
    fireEvent.change(field("capacity"), { target: { value: "8" } });
    const input = await saved();

    // The keys first: a link that was never sent is the defect, and only this can see it.
    const keys = Object.keys(input);
    for (const key of LINK_KEYS) expect(keys).toContain(key);
    expect(input.mealDate).toBe("2026-12-06");
    expect(input.mealKind).toBe("Lunch");
    expect(input.mealEventName).toBeNull();
    // And the edit itself still went through.
    expect(input.title).toBe("Cutting vegetables for lunch");
    expect(input.capacity).toBe(8);
  });

  it("keeps an event's link, event name included", async () => {
    queryRef.current = {
      data: shift({ title: "Janmashtami lunch prep", mealEventName: "Janmashtami" }),
      error: null,
      loading: false,
    };
    render(<EditShiftPage />);

    fireEvent.change(field("location"), { target: { value: "Festival tent" } });
    const input = await saved();

    const keys = Object.keys(input);
    for (const key of LINK_KEYS) expect(keys).toContain(key);
    expect(input.mealDate).toBe("2026-12-06");
    expect(input.mealKind).toBe("Lunch");
    expect(input.mealEventName).toBe("Janmashtami");
    expect(input.location).toBe("Festival tent");
  });

  it("keeps the shift on its original meal when the date is moved, as the planner layer does", async () => {
    // A matter of taste recorded for Rajeev: the link stays with the meal it was made for. T-155's
    // layer does the same, so the two screens agree.
    queryRef.current = { data: shift(), error: null, loading: false };
    render(<EditShiftPage />);

    fireEvent.change(field("shiftDate"), { target: { value: "2026-12-05" } });
    const input = await saved();

    expect(input.shiftDate).toBe("2026-12-05");
    const keys = Object.keys(input);
    for (const key of LINK_KEYS) expect(keys).toContain(key);
    expect(input.mealDate).toBe("2026-12-06");
    expect(input.mealKind).toBe("Lunch");
    expect(input.mealEventName).toBeNull();
  });

  it("leaves an unlinked shift unlinked, sending the three fields as null", async () => {
    // Nulls rather than absent keys: the server treats the two identically (every field of the
    // request record is null either way), and all three null is the one shape its all-or-nothing
    // check accepts as "no link". Sending them explicitly makes the save say what it means.
    queryRef.current = {
      data: shift({ title: "Saturday morning seva", mealDate: null, mealKind: null, mealEventName: null }),
      error: null,
      loading: false,
    };
    render(<EditShiftPage />);

    fireEvent.change(field("title"), { target: { value: "Saturday cleaning seva" } });
    const input = await saved();

    const keys = Object.keys(input);
    for (const key of LINK_KEYS) expect(keys).toContain(key);
    expect(input.mealDate).toBeNull();
    expect(input.mealKind).toBeNull();
    expect(input.mealEventName).toBeNull();
  });

  it("treats a shift whose view carries no link fields at all as unlinked, not as half a link", async () => {
    // `ShiftView` declares the three optional, so a caller can hold one without them. Undefined must
    // come out as null on all three, never as a mix the server would refuse with KMS-400125.
    const bare = shift({ title: "Saturday morning seva" });
    delete bare.mealDate;
    delete bare.mealKind;
    delete bare.mealEventName;
    queryRef.current = { data: bare, error: null, loading: false };
    render(<EditShiftPage />);

    const input = await saved();

    expect(input.mealDate).toBeNull();
    expect(input.mealKind).toBeNull();
    expect(input.mealEventName).toBeNull();
  });
});
