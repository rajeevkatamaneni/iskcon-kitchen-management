import { beforeEach, describe, expect, it } from "vitest";
import {
  ACTIVITY_KEY,
  IDLE_LIMIT_MS,
  REASON_VALID_FOR_MS,
  SIGNED_OUT_REASON_KEY,
  WARN_BEFORE_MS,
  idleState,
  lastActivityAt,
  noteAutomaticSignOut,
  recordActivity,
  secondsUntilSignOut,
  takeAutomaticSignOutNote,
} from "@/lib/session-timeout";

// The rules of the idle clock (E1-S16), tested as arithmetic rather than by waiting an hour.
describe("the idle clock", () => {
  const now = 1_770_000_000_000;

  it("holds the session while someone is working", () => {
    expect(idleState(now, now)).toBe("active");
    expect(idleState(now, now - 45 * 60 * 1000)).toBe("active");
  });

  it("warns a minute before, not at the moment of sign-out", () => {
    expect(idleState(now, now - (IDLE_LIMIT_MS - WARN_BEFORE_MS))).toBe("warning");
    expect(idleState(now, now - (IDLE_LIMIT_MS - WARN_BEFORE_MS - 1))).toBe("active");
  });

  it("expires at sixty minutes, and stays expired", () => {
    expect(idleState(now, now - IDLE_LIMIT_MS)).toBe("expired");
    expect(idleState(now, now - 3 * IDLE_LIMIT_MS)).toBe("expired");
  });

  it("counts down in whole seconds, and never below zero", () => {
    expect(secondsUntilSignOut(now, now - (IDLE_LIMIT_MS - 30_000))).toBe(30);
    expect(secondsUntilSignOut(now, now - 2 * IDLE_LIMIT_MS)).toBe(0);
  });

  it("is sixty minutes — the figure the story fixed", () => {
    expect(IDLE_LIMIT_MS).toBe(60 * 60 * 1000);
  });
});

describe("the clock shared between tabs", () => {
  beforeEach(() => window.localStorage.clear());

  it("reads back what another tab wrote", () => {
    recordActivity(1234);
    expect(window.localStorage.getItem(ACTIVITY_KEY)).toBe("1234");
    expect(lastActivityAt(0)).toBe(1234);
  });

  it("starts the clock rather than expiring it when nothing is stored", () => {
    expect(lastActivityAt(999)).toBe(999);
  });

  it("ignores a value it cannot read as a time", () => {
    window.localStorage.setItem(ACTIVITY_KEY, "not-a-time");
    expect(lastActivityAt(999)).toBe(999);
  });
});

describe("the note left for the sign-in screen", () => {
  const now = 1_770_000_000_000;

  beforeEach(() => window.localStorage.clear());

  it("explains one sign-in, then is gone", () => {
    noteAutomaticSignOut(now);

    expect(takeAutomaticSignOutNote(now + 1000)).toBe(true);
    expect(takeAutomaticSignOutNote(now + 2000)).toBe(false);
    expect(window.localStorage.getItem(SIGNED_OUT_REASON_KEY)).toBeNull();
  });

  it("says nothing when the person signed out themselves", () => {
    expect(takeAutomaticSignOutNote(now)).toBe(false);
  });

  it("does not explain today's sign-in with last week's timeout", () => {
    noteAutomaticSignOut(now);
    expect(takeAutomaticSignOutNote(now + REASON_VALID_FOR_MS + 1)).toBe(false);
  });

  it("survives a note it cannot parse", () => {
    window.localStorage.setItem(SIGNED_OUT_REASON_KEY, "{oops");
    expect(takeAutomaticSignOutNote(now)).toBe(false);
  });
});
