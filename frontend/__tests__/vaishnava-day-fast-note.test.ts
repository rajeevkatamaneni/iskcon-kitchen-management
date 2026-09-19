import { describe, expect, it } from "vitest";
import { kitchenNote } from "@/lib/vaishnava-day";
import type { CalendarDayView } from "@/lib/api";

// Rajeev, 2026-09-18: only an Ekadashi-type fast gets the no-grains, a-third-of-servings instruction;
// a fast until noon, sunset or moonrise ends in a feast and says only its own line.
function day(fields: Partial<CalendarDayView>): CalendarDayView {
  return { isEkadashi: false, fastType: null, festivals: [], mahadvadashi: null, ...fields } as CalendarDayView;
}

describe("kitchenNote on a fast", () => {
  it("gives an Ekadashi the full instruction", () => {
    expect(kitchenNote(day({ isEkadashi: true, fastType: "EKADASI" }))?.text).toMatch(/no grains, dal or beans/);
  });

  it("gives a full-day fast the full instruction", () => {
    expect(kitchenNote(day({ fastType: "FULL_DAY" }))?.text).toMatch(/a third of the usual servings/);
  });

  it("gives a fast until noon only its own line", () => {
    const note = kitchenNote(day({ fastType: "NOON" }));
    expect(note?.text).toBe("Fast until noon. Plan the feast for after.");
    expect(note?.text).not.toMatch(/grains|third/);
  });
});
