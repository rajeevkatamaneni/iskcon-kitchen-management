import { afterEach, describe, expect, it, vi } from "vitest";
import { todayIso } from "@/lib/format";

describe("the temple's today", () => {
  afterEach(() => vi.useRealTimers());

  it("is the day in India, not the day on the device", () => {
    // 23:40 UTC on 14 August is already the 15th in a temple kitchen. A reader in the Americas
    // must not see the planner mark one day as today while Today calls it another.
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-08-14T23:40:00Z"));

    expect(todayIso()).toBe("2026-08-15");
  });

  it("agrees with the device when the device is in India", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-08-14T06:00:00Z"));

    expect(todayIso()).toBe("2026-08-14");
  });
});
