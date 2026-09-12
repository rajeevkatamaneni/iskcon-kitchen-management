import { afterEach, describe, expect, it, vi } from "vitest";
import {
  crossesMidnight,
  leadTimeWarning,
  moment,
  money,
  shiftWindow,
  templeDay,
  todayIso,
} from "@/lib/format";

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
    expect(leadTimeWarning("2026-09-05", 2, TODAY)).toBeNull();
  });

  it("says nothing on the vendor's own boundary — two days is the notice, not less than it", () => {
    expect(leadTimeWarning("2026-09-02", 2, TODAY)).toBeNull();
  });

  it("warns, and does not refuse, inside the notice this vendor asked for", () => {
    // A temple that genuinely needs rice tomorrow may ask for it tomorrow. The screen says what it
    // is asking for; nothing here stops it being asked. What is enforced is D-25's cutoff, on the
    // server, at Mark sent — and with an override, because it is a favour we are asking.
    expect(leadTimeWarning("2026-09-01", 2, TODAY)).toBe(
      "Sooner than the 2 days’ notice this vendor asked for"
    );
    expect(leadTimeWarning(TODAY, 2, TODAY)).toBe(
      "Sooner than the 2 days’ notice this vendor asked for"
    );
  });

  it("uses the vendor's own number, not a figure of its own (T-137)", () => {
    // The whole point of the change: the same date is fine for a dairy that wants two days' notice
    // and not for a wholesaler who wants five. One number, per vendor, from the server — the
    // hard-coded two days was a second answer to the question T-137 exists to make single.
    expect(leadTimeWarning("2026-09-03", 2, TODAY)).toBeNull();
    expect(leadTimeWarning("2026-09-03", 5, TODAY)).toBe(
      "Sooner than the 5 days’ notice this vendor asked for"
    );
    expect(leadTimeWarning("2026-09-01", 1, TODAY)).toBeNull();
    expect(leadTimeWarning(TODAY, 1, TODAY)).toBe(
      "Sooner than the 1 day’s notice this vendor asked for"
    );
  });

  it("says nothing about notice for a vendor who has never asked for any", () => {
    // Rajeev's rule: no recorded lead time means no cutoff — no nudge, no warning, nothing held
    // against anybody. Silence, and emphatically not the two days this used to assume.
    expect(leadTimeWarning(TODAY, null, TODAY)).toBeNull();
    expect(leadTimeWarning("2026-09-01", null, TODAY)).toBeNull();
    // Zero is a real answer and means cash and carry: the goods come back with the person.
    expect(leadTimeWarning(TODAY, 0, TODAY)).toBeNull();
  });

  it("says so plainly when the day has already gone, whatever the vendor asked for", () => {
    // Ahead of the notice question, and true with no lead time recorded at all: a date behind today
    // is not a request anybody can act on.
    expect(leadTimeWarning("2026-08-30", 2, TODAY)).toBe("That day has already gone");
    expect(leadTimeWarning("2026-08-30", null, TODAY)).toBe("That day has already gone");
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

describe("a shift that runs through midnight", () => {
  // `shiftDate` is the date a shift STARTS, so an end time at or before the start belongs to the
  // next morning. The same rule is written in SQL (shift_ends_at, V127) and in Java (ShiftWindow);
  // these are the cases that prove this copy of it agrees with them.

  it("is an ordinary shift when it ends later the same day", () => {
    expect(crossesMidnight("08:00:00", "12:00:00")).toBe(false);
    expect(shiftWindow("08:00:00", "12:00:00")).toBe("08:00–12:00");
  });

  it("says so for the Janmashtami midnight offering", () => {
    expect(crossesMidnight("20:00:00", "02:00:00")).toBe(true);
    // The whole point: "20:00–02:00" read cold is a shift that ends sixteen hours before it begins.
    expect(shiftWindow("20:00:00", "02:00:00")).toBe("20:00–02:00 (next day)");
  });

  it("reads a form's HH:mm the same as the API's HH:mm:ss", () => {
    expect(crossesMidnight("20:00", "02:00")).toBe(true);
    expect(shiftWindow("23:00", "01:00")).toBe("23:00–01:00 (next day)");
  });

  it("treats a shift starting at midnight as an ordinary early one", () => {
    // The comparison is on the end, never on the start: 00:00–04:00 ends the same day.
    expect(crossesMidnight("00:00:00", "04:00:00")).toBe(false);
    expect(shiftWindow("00:00:00", "04:00:00")).toBe("00:00–04:00");
  });

  it("treats a shift ending exactly at midnight as running into the next day", () => {
    expect(crossesMidnight("20:00:00", "00:00:00")).toBe(true);
  });

  it("claims nothing when a time is missing", () => {
    // Half a window is not an overnight shift. Unreachable through the API — every shift view sends
    // both times and neither is nullable — but a helper that answered `true` to a half-filled form
    // would put "(next day)" under a box somebody has not finished typing in.
    expect(crossesMidnight(null, "02:00")).toBe(false);
    expect(crossesMidnight("20:00", undefined)).toBe(false);
  });
});
