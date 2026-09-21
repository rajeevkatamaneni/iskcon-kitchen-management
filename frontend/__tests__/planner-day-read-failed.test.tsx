import React from "react";
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

/**
 * A day that could not be read does not claim to be empty.
 *
 * <p>The planner's day said "Nothing planned for this day" whether the temple had planned nothing,
 * or the read had been refused, or it had failed outright. Rajeev objected to that sentence on
 * 2026-09-19 when an undated card put an event under the wrong day — *"which is not entirely
 * true"* — and it was able to say the same untrue thing again for a different reason.
 *
 * <p>`useAuthedQuery` has always handed back an `error` and a `reload`; `MealServices` was the one
 * screen that dropped them on the floor. So these tests drive the three answers the hook can give —
 * a day with meals, an empty day, and a failed read — through the real component.
 */

const queryRef = vi.hoisted(() => ({
  current: { data: null as unknown, error: null as unknown, loading: false },
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn(), refresh: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ getToken: async () => "t", appUser: { role: "KITCHEN_MANAGER" } }),
}));

const reload = vi.fn();
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ ...queryRef.current, reload }),
}));

import { MealServices } from "@/components/planner/MealServices";

const DATE = "2026-09-28";

/** The shape `toApiError` produces, which is all `ErrorNotice` reads. */
const REFUSED = {
  code: "KMS-400183",
  message: "Your kitchen doesn't plan its meals here.",
  action: "Ask your temple administrator which kitchen you belong to.",
  status: 403,
};

function day() {
  return render(
    <MealServices
      date={DATE}
      sufficiency={new Map()}
      recipes={[] as never}
      readOnly={false}
      onChanged={vi.fn()}
      onError={vi.fn()}
    />
  );
}

describe("a planner day that could not be read", () => {
  it("says what went wrong instead of calling the day empty", () => {
    queryRef.current = { data: null, error: REFUSED, loading: false };
    day();

    expect(screen.queryByText("Nothing planned for this day")).toBeNull();
    expect(screen.getByText("Your kitchen doesn't plan its meals here.")).toBeTruthy();
    expect(screen.getByText(/Ask your temple administrator/)).toBeTruthy();
    expect(screen.getByText("KMS-400183")).toBeTruthy();
  });

  it("offers to read the day again, and does", () => {
    queryRef.current = { data: null, error: REFUSED, loading: false };
    reload.mockClear();
    day();

    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it("still calls a genuinely empty day empty", () => {
    queryRef.current = { data: [], error: null, loading: false };
    day();

    expect(screen.getByText("Nothing planned for this day")).toBeTruthy();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("shows nothing at all while the day is still being read", () => {
    queryRef.current = { data: null, error: null, loading: true };
    const { container } = day();

    expect(container.textContent).toBe("");
  });
});
