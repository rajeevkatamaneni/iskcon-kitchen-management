import { describe, expect, it } from "vitest";
import { dayEvents, ekadashiSpelling, recipeTagLabel, recipeTagLabels } from "@/lib/vaishnava-day";
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
  it("does not say Ekadashi twice", () => {
    // The engine stores the whole name, so appending the word again read as
    // "Pavitraropana Ekadashi Ekadashi" on every Ekadashi the calendar had ever shown.
    const [event] = dayEvents(day({ isEkadashi: true, ekadashiName: "Pavitraropana Ekadashi" }));
    expect(event.label).toBe("Pavitraropana Ekadashi");
  });

  it("spells the engine's stored Ekadasi as Ekadashi, and still only once", () => {
    // GCAL's transliteration is what the engine stores; the app says "Ekadashi" (Rajeev, 2026-09-18).
    const [event] = dayEvents(day({ isEkadashi: true, ekadashiName: "Pavitraropana Ekadasi" }));
    expect(event.label).toBe("Pavitraropana Ekadashi");
  });

  it("respells a festival text that carries the word", () => {
    const [, festival] = dayEvents(
      day({ isEkadashi: true, festivals: [{ text: "(Fasting for Ekadasi)", priority: 500 }] })
    );
    expect(festival.label).toBe("(Fasting for Ekadashi)");
  });

  it("leaves Ekadashi alone and respells every Ekadasi", () => {
    expect(ekadashiSpelling("Ekadashi")).toBe("Ekadashi");
    expect(ekadashiSpelling("Ekadasi fast, two Ekadasis")).toBe("Ekadashi fast, two Ekadashis");
  });

  it("adds the word when the name arrives without it", () => {
    const [event] = dayEvents(day({ isEkadashi: true, ekadashiName: "Pavitraropana" }));
    expect(event.label).toBe("Pavitraropana Ekadashi");
  });

  it("falls back to the bare word when there is no name", () => {
    const [event] = dayEvents(day({ isEkadashi: true, ekadashiName: null }));
    expect(event.label).toBe("Ekadashi");
  });

  it("names an Ekadashi fast code in the app's spelling", () => {
    const [event] = dayEvents(day({ fastType: "EKADASI" }));
    expect(event.label).toBe("Ekadashi fast");
  });

  it("says a Mahadvadashi is one, on the Ekadashi day itself", () => {
    // It used to be named only on the non-Ekadashi branch, so a Vyanjuli day never said it was
    // one — and the parana window on those is minutes long, which is the day somebody needs telling.
    const [event] = dayEvents(
      day({ isEkadashi: true, ekadashiName: "Pavitraropana Ekadasi", mahadvadashi: "VYANJULI" })
    );
    expect(event.note).toMatch(/Vyanjuli Mahadvadashi/);
    expect(event.note).toMatch(/Break the fast early; the window is short\./);
  });

  it("does not shout the Mahadvadashi's name back", () => {
    const [event] = dayEvents(day({ fastType: "FULL_DAY", mahadvadashi: "VYANJULI" }));
    expect(event.note).toBe("Vyanjuli Mahadvadashi");
  });
});

describe("what a recipe safe on a fasting day is called", () => {
  it("reads the library's Ekadashi-safe tag as Ekadashi-friendly", () => {
    expect(recipeTagLabel("Ekadashi-safe")).toBe("Ekadashi-friendly");
    expect(recipeTagLabel("Jain-safe")).toBe("Jain-safe");
  });

  it("does not repeat the badge the recipe already shows", () => {
    expect(recipeTagLabels(["Vegan", "Ekadashi-safe"], ["Ekadashi-friendly"])).toEqual(["Vegan"]);
    expect(recipeTagLabels(["Vegan", "Ekadashi-safe"])).toEqual(["Vegan", "Ekadashi-friendly"]);
  });
});
