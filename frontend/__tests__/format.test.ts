import { afterEach, describe, expect, it, vi } from "vitest";
import { LEAD_BUFFER_DAYS, leadTimeWarning, moment, money, templeDay, todayIso } from "@/lib/format";

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

describe("the notice a vendor is given", () => {
  // Fixed against the temple's own day. A reader in California asking for something "tomorrow"
  // means the temple's tomorrow, and a helper that read the device clock would warn a day out.
  const TODAY = "2026-08-31";

  it("says nothing about a date with enough notice in it", () => {
    expect(leadTimeWarning("2026-09-05", TODAY)).toBeNull();
  });

  it("says nothing on the buffer's own boundary — two days is the notice, not less than it", () => {
    expect(leadTimeWarning("2026-09-02", TODAY)).toBeNull();
  });

  it("warns, and does not refuse, inside the buffer", () => {
    // A temple that genuinely needs rice tomorrow may ask for it tomorrow. The screen says what it
    // is asking for; nothing here stops it being asked.
    expect(leadTimeWarning("2026-09-01", TODAY)).toBe(
      `Sooner than the ${LEAD_BUFFER_DAYS} days a vendor usually gets`
    );
    expect(leadTimeWarning(TODAY, TODAY)).toBe(
      `Sooner than the ${LEAD_BUFFER_DAYS} days a vendor usually gets`
    );
  });

  it("says so plainly when the day has already gone", () => {
    expect(leadTimeWarning("2026-08-30", TODAY)).toBe("That day has already gone");
  });
});

describe("a moment off the server", () => {
  it("is the temple's clock, worded like every other date", () => {
    // 09:00 UTC is half past two in the afternoon in Bengaluru.
    expect(moment("2026-08-20T09:00:00Z")).toBe("20 Aug 2026, 14:30");
  });

  it("keeps an evening in India on the evening, not the next morning", () => {
    expect(moment("2026-08-20T18:30:00Z")).toBe("21 Aug 2026, 00:00");
  });

  it("says no seconds, because nothing in this application does", () => {
    expect(moment("2026-08-20T09:00:41Z")).not.toContain(":41");
  });
});

describe("the temple's day, from an instant", () => {
  it("reads the day in India and not the day in UTC", () => {
    // The whole reason this helper exists: iso.slice(0, 10) says "20 Aug" here, and the
    // storekeeper who wrote it was standing in a kitchen where it was already the 21st.
    expect("2026-08-20T22:00:00Z".slice(0, 10)).toBe("2026-08-20");
    expect(templeDay("2026-08-20T22:00:00Z")).toBe("21 Aug 2026");
  });

  it("is written like dateWithYear, because it is one", () => {
    expect(templeDay("2026-03-12T06:00:00Z")).toBe("12 Mar 2026");
  });
});

describe("the temple's money", () => {
  it("groups in lakhs whoever is reading it", () => {
    // The defect this pinning fixes: on an en-US machine this printed ₹11,50,000 on the wish
    // list and ₹1,150,000 one screen away, for the same rupees.
    expect(money(1150000, "INR")).toBe("₹11,50,000");
  });

  it("shows paise only when there are any", () => {
    expect(money(18000, "INR")).toBe("₹18,000");
    expect(money(18432.5, "INR")).toBe("₹18,432.50");
  });

  it("is an em dash for money nobody has a figure for, never ₹0", () => {
    expect(money(null, "INR")).toBe("—");
    expect(money(0, "INR")).toBe("₹0");
  });
});
