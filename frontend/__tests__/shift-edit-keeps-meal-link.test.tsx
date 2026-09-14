import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ShiftView, UpdateShiftInput } from "@/lib/api";

/**
 * T-158, carried onto D-27. Saving a shift on `/volunteers/[id]/edit` must not take its meal link off.
 *
 * <p>Before D-27 the link was three copied fields — the meal's date, kind and event name — and an
 * update that left them out unlinked the shift. Since D-27 it is one `mealId`, and the server refuses
 * an edit that drops or changes it (KMS-400153). The edit screen has no box for the link, so it must
 * send back exactly what it read.
 *
 * <p>Every "keeps the link" test reads `Object.keys` before the value, because the defect this file
 * exists for was a key that was never sent, and `objectContaining({ mealId: undefined })` passes
 * against a missing key.
 *
 * <p>What the edit screen shows for a meal shift — the date and meal read-only, the times-changed
 * warning — is in `shift-edit-meal-shift.test.tsx` (T-199). This file is kept to the link.
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

/** A shift as `GET /shifts/{id}` returns it: the server always sends the link, null or not. */
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
    mealId: "meal-lunch-6-dec",
    mealKind: "Lunch",
    mealEventName: null,
    ...o,
  };
}

function field(name: string): HTMLInputElement {
  const form = screen.getByRole("form", { name: /edit a shift/i });
  return form.querySelector(`input[name="${name}"]`) as HTMLInputElement;
}

async function saved(): Promise<UpdateShiftInput> {
  // The header's own button, which reaches the form through `form=` (T-165).
  fireEvent.click(screen.getByRole("button", { name: /save changes/i }));
  await waitFor(() => expect(updateShiftMock).toHaveBeenCalledTimes(1));
  const [id, input] = updateShiftMock.mock.calls[0];
  expect(id).toBe("s1");
  return input as UpdateShiftInput;
}

describe("editing a shift on the volunteers screen keeps its meal link (T-158, D-27)", () => {
  beforeEach(() => {
    updateShiftMock.mockReset().mockResolvedValue(undefined);
    pushMock.mockReset();
  });

  it("keeps a meal link through a change of title and volunteers requested", async () => {
    queryRef.current = { data: shift(), error: null, loading: false };
    render(<EditShiftPage />);

    fireEvent.change(field("title"), { target: { value: "Cutting vegetables for lunch" } });
    fireEvent.change(field("capacity"), { target: { value: "8" } });
    const input = await saved();

    // The key first: a link that was never sent is the defect, and only this can see it.
    expect(Object.keys(input)).toContain("mealId");
    expect(input.mealId).toBe("meal-lunch-6-dec");
    // And the edit itself still went through.
    expect(input.title).toBe("Cutting vegetables for lunch");
    expect(input.capacity).toBe(8);
  });

  it("keeps an event's link, which is the same one id as any other meal's", async () => {
    queryRef.current = {
      data: shift({ title: "Janmashtami lunch prep", mealId: "meal-janmashtami", mealEventName: "Janmashtami" }),
      error: null,
      loading: false,
    };
    render(<EditShiftPage />);

    fireEvent.change(field("location"), { target: { value: "Festival tent" } });
    const input = await saved();

    expect(Object.keys(input)).toContain("mealId");
    expect(input.mealId).toBe("meal-janmashtami");
    expect(input.location).toBe("Festival tent");
    // The kind and the event's name are read through the id and are not the link, so they are not
    // sent: the server would ignore them, and sending them would suggest they could move the shift.
    expect(Object.keys(input)).not.toContain("mealKind");
    expect(Object.keys(input)).not.toContain("mealEventName");
  });

  it("leaves a shift not for a meal as one, sending its link as null rather than leaving it out", async () => {
    queryRef.current = {
      data: shift({ title: "Saturday morning seva", mealId: null, mealKind: null, mealEventName: null }),
      error: null,
      loading: false,
    };
    render(<EditShiftPage />);

    fireEvent.change(field("title"), { target: { value: "Saturday cleaning seva" } });
    const input = await saved();

    expect(Object.keys(input)).toContain("mealId");
    expect(input.mealId).toBeNull();
  });
});
