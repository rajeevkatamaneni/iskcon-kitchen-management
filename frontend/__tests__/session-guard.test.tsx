import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, screen } from "@testing-library/react";
import { fireEvent } from "@testing-library/dom";

// The guard reads the signed-in state and the sign-out action from the auth context. Both are
// driven from a mutable ref so a test can change the state without a Firebase session.
const { authRef, signOutMock } = vi.hoisted(() => ({
  authRef: { current: { status: "signed-in" } as { status: string } },
  signOutMock: vi.fn(async () => {}),
}));

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, signOut: signOutMock }),
}));

import { SessionGuard } from "@/components/SessionGuard";
import { ACTIVITY_KEY, IDLE_LIMIT_MS, WARN_BEFORE_MS } from "@/lib/session-timeout";

const START = 1_770_000_000_000;

/**
 * Moves time on. Fake timers move `Date.now()` along with the interval, which is what makes this
 * work: the guard polls on a timer but decides from timestamps, so both have to agree.
 */
async function advance(ms: number) {
  await act(async () => {
    vi.advanceTimersByTime(ms);
  });
}

describe("SessionGuard", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(START));
    window.localStorage.clear();
    authRef.current = { status: "signed-in" };
    signOutMock.mockClear();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("says nothing while someone is working", async () => {
    render(<SessionGuard />);
    await advance(30 * 60 * 1000);

    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(signOutMock).not.toHaveBeenCalled();
  });

  it("warns before signing anyone out", async () => {
    render(<SessionGuard />);
    await advance(IDLE_LIMIT_MS - WARN_BEFORE_MS);

    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
    expect(screen.getByText(/sign you out in/i)).toBeInTheDocument();
    expect(signOutMock).not.toHaveBeenCalled();
  });

  it("signs an idle person out at sixty minutes, and only asks once", async () => {
    render(<SessionGuard />);
    await advance(IDLE_LIMIT_MS);

    expect(signOutMock).toHaveBeenCalledTimes(1);

    await advance(5 * 60 * 1000);
    expect(signOutMock).toHaveBeenCalledTimes(1);
  });

  it("keeps the session when the person says they are still there", async () => {
    render(<SessionGuard />);
    await advance(IDLE_LIMIT_MS - WARN_BEFORE_MS);

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /stay signed in/i }));
    });

    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();

    await advance(IDLE_LIMIT_MS - WARN_BEFORE_MS - 1000);
    expect(signOutMock).not.toHaveBeenCalled();
  });

  it("treats a real key press as activity", async () => {
    render(<SessionGuard />);
    await advance(50 * 60 * 1000);

    await act(async () => {
      fireEvent.keyDown(window, { key: "a" });
    });

    await advance(50 * 60 * 1000);
    expect(signOutMock).not.toHaveBeenCalled();
  });

  it("is not held open by a screen polling on a timer", async () => {
    // Nothing but time passing — no pointer, key or touch. Traffic alone must not count.
    render(<SessionGuard />);
    await advance(IDLE_LIMIT_MS);

    expect(signOutMock).toHaveBeenCalledTimes(1);
  });

  it("follows the clock another tab has been keeping", async () => {
    render(<SessionGuard />);
    await advance(50 * 60 * 1000);

    // The other tab was being used five minutes ago; this one has sat idle the whole time.
    window.localStorage.setItem(ACTIVITY_KEY, String(Date.now() - 5 * 60 * 1000));

    await advance(20 * 60 * 1000);
    expect(signOutMock).not.toHaveBeenCalled();
  });

  it("does nothing at all for someone who is not signed in", async () => {
    authRef.current = { status: "signed-out" };
    render(<SessionGuard />);
    await advance(3 * IDLE_LIMIT_MS);

    expect(signOutMock).not.toHaveBeenCalled();
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });
});
