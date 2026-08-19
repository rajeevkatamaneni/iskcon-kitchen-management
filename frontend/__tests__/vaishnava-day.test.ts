import { describe, expect, it } from "vitest";
import { dayEvents } from "@/lib/vaishnava-day";
import type { CalendarDayView } from "@/lib/api";

function day(o: Partial<CalendarDayView>): CalendarDayView {
  return {
    date: "2026-08-24",
    isEkadashi: false,
    ekadashiName: null,
    fastType: null,
    mahadvadashi: null,
    festivals: [],
    ...o,
  } as CalendarDayView;
}

describe("what a day is called", () => {
  it("does not say Ekadasi twice", () => {
    // The engine stores the whole name, so appending the word again read as
    // "Pavitraropana Ekadasi Ekadasi" on every Ekadashi the calendar had ever shown.
    const [event] = dayEvents(day({ isEkadashi: true, ekadashiName: "Pavitraropana Ekadasi" }));
    expect(event.label).toBe("Pavitraropana Ekadasi");
  });

  it("adds the word when the name arrives without it", () => {
    const [event] = dayEvents(day({ isEkadashi: true, ekadashiName: "Pavitraropana" }));
    expect(event.label).toBe("Pavitraropana Ekadasi");
  });

  it("falls back to the bare word when there is no name", () => {
    const [event] = dayEvents(day({ isEkadashi: true, ekadashiName: null }));
    expect(event.label).toBe("Ekadasi");
  });

  it("says a Mahadvadashi is one, on the Ekadashi day itself", () => {
    // It used to be named only on the non-Ekadashi branch, so a Vyanjuli day never said it was
    // one — and the parana window on those is minutes long, which is the day somebody needs telling.
    const [event] = dayEvents(
      day({ isEkadashi: true, ekadashiName: "Pavitraropana Ekadasi", mahadvadashi: "VYANJULI" })
    );
    expect(event.note).toMatch(/Vyanjuli Mahadvadashi/);
    expect(event.note).toMatch(/parana window is short/);
  });

  it("does not shout the Mahadvadashi's name back", () => {
    const [event] = dayEvents(day({ fastType: "FULL_DAY", mahadvadashi: "VYANJULI" }));
    expect(event.note).toBe("Vyanjuli Mahadvadashi");
  });
});
