import { describe, expect, it } from "vitest";
import {
  cooksQuantity,
  lastCountedPhrase,
  oneUnitFor,
  quantity,
  stockCoverPhrase,
  stockRunsOutSoon,
  unitLabel,
  unitLabelFor,
} from "@/lib/format";

/**
 * The vector table for the one display rule (E11-S3).
 *
 * <p>This table is duplicated, deliberately and identically, in the backend's `QuantitiesTest`.
 * The rule has to exist twice — the screens are TypeScript and the job card, recipe card and work
 * order are rendered in Java — and two implementations of one rule drift silently unless something
 * holds them to the same answers. These are those answers. Change one file and the other fails.
 *
 * <p>Before this existed there was no test of `quantity()` at all, which is how the codebase came
 * to hold two half-versions of the rule that disagreed with each other about both direction and
 * casing.
 */
describe("a quantity, said the way a person says it", () => {
  describe("the ledger form — exact, because somebody reconciles against it", () => {
    it("steps down into the smaller unit rather than printing a fraction", () => {
      // The half of the rule that was missing: quantity() only ever promoted upward, so a scaled
      // recipe asking for 600 grams of rice printed "0.6 Kg".
      expect(quantity(0.6, "KG")).toBe("600 gm");
      expect(quantity(0.6, "L")).toBe("600 ml");
      expect(quantity(0.02, "KG")).toBe("20 gm");
      expect(quantity(0.2, "L")).toBe("200 ml");
      expect(quantity(0.15, "KG")).toBe("150 gm");
    });

    it("promotes into the larger unit once there is a whole one of them", () => {
      expect(quantity(173542, "ML")).toBe("173.542 L");
      expect(quantity(1500, "GM")).toBe("1.5 Kg");
      expect(quantity(999, "GM")).toBe("999 gm");
      expect(quantity(5, "KG")).toBe("5 Kg");
    });

    it("leaves a count alone — it is a whole thing measured in itself", () => {
      expect(quantity(3, "PIECES")).toBe("3 pieces");
    });

    it("says one of a counted thing in the singular (T-108)", () => {
      // What Rajeev saw on a purchase order: one plastic stool, printed "1 pieces". It was on
      // every screen in the application at once, because the unit label was chosen without ever
      // being shown the number it was going to sit beside.
      expect(quantity(1, "PIECES")).toBe("1 piece");
      expect(cooksQuantity(1, "PIECES")).toBe("1 piece");
      // Rounded down to one, which is the case a job card actually hits.
      expect(cooksQuantity(1.2, "PIECES")).toBe("1 piece");
      // And a reversal of the single stool that was received in error.
      expect(quantity(-1, "PIECES")).toBe("-1 piece");
    });

    it("pluralises nothing else, because nothing else is counted", () => {
      // "1 Kgs" is not English and neither is "1 mls". A mass and a volume are written the same
      // at every number; only a unit that names the things themselves has a singular to get wrong.
      expect(quantity(1, "KG")).toBe("1 Kg");
      expect(quantity(1, "GM")).toBe("1 gm");
      expect(quantity(1, "L")).toBe("1 L");
      expect(quantity(1, "ML")).toBe("1 ml");
      // Including when the promotion is what produced the one: 1000 gm is one kilo.
      expect(quantity(1000, "GM")).toBe("1 Kg");
      expect(cooksQuantity(999.6, "GM")).toBe("1 Kg");
    });

    it("keeps the exact figure, so inventory rows still add up to the balance", () => {
      // E3-S1: "stock shown always equals the sum of movements". Rounding these independently
      // would stop the rows summing to the total on the one screen whose job is that they do.
      expect(quantity(10.08, "KG")).toBe("10.08 Kg");
      expect(quantity(134.4, "GM")).toBe("134.4 gm");
    });

    it("says nothing rather than zero when there is no figure", () => {
      expect(quantity(null, "L")).toBe("—");
      expect(quantity(undefined, "KG")).toBe("—");
    });

    it("says zero in the unit the thing is kept in", () => {
      // Curd's stock page read "0 ml" on hand against a reorder level of 15 L, on an item kept in
      // litres (staging, 2026-09-09). The step-down rule exists to stop a fraction being printed —
      // 0.6 Kg is 600 gm — and zero has no fraction to step away from, so all the rule did there
      // was change the subject and make the reader convert before they could compare the two
      // figures in front of them.
      expect(quantity(0, "L")).toBe("0 L");
      expect(quantity(0, "KG")).toBe("0 Kg");
      expect(quantity(0, "ML")).toBe("0 ml");
      expect(quantity(0, "GM")).toBe("0 gm");
      expect(quantity(0, "PIECES")).toBe("0 pieces");
      // And the cook's form says it the same way — a job card line of nothing is still nothing of
      // whatever the recipe measures in.
      expect(cooksQuantity(0, "L")).toBe("0 L");
      expect(cooksQuantity(0, "KG")).toBe("0 Kg");
    });
  });

  describe("the cook's form — rounded, because somebody weighs against it", () => {
    it("rounds the way a person would, on a step that grows with the number", () => {
      // Rajeev's own five, 2026-08-30. "10.08 KG and 10 KG are the same for practical cooking
      // purposes. We are not measuring gold here."
      expect(cooksQuantity(10.08, "KG")).toBe("10 Kg");
      expect(cooksQuantity(134.4, "GM")).toBe("135 gm");
      expect(cooksQuantity(50.4, "GM")).toBe("50 gm");
      expect(cooksQuantity(5.04, "GM")).toBe("5 gm");
      expect(cooksQuantity(840, "GM")).toBe("840 gm");
    });

    it("keeps half a gram where half a gram is the honest step", () => {
      // Camphor and saffron are weighed by the half-gram; rounding them to the nearest whole
      // would be a 10% error on a 5 gm line.
      expect(cooksQuantity(4.7, "GM")).toBe("4.5 gm");
      expect(cooksQuantity(0.3, "GM")).toBe("0.3 gm");
    });

    it("picks the readable unit first and rounds second", () => {
      expect(cooksQuantity(0.1344, "KG")).toBe("135 gm");
      expect(cooksQuantity(0.6, "KG")).toBe("600 gm");
      expect(cooksQuantity(173542, "ML")).toBe("175 L");
    });

    it("promotes again when rounding carries it over a whole unit", () => {
      // 999.6 gm rounds to 1000 gm, which is a kilo and should say so rather than printing a
      // four-figure gram count nobody would use.
      expect(cooksQuantity(999.6, "GM")).toBe("1 Kg");
    });

    it("never gives half a piece", () => {
      expect(cooksQuantity(3.4, "PIECES")).toBe("3 pieces");
    });
  });

  describe("rounding cannot compound, because it happens last", () => {
    it("twelve rounded lines do not drift the recipe they came from", () => {
      // The worry Rajeev raised: "rounding can add a bigger than expected error". It can — if you
      // round and then compute. Each line here is rounded for display only; the total is summed
      // from the stored values and rounded once, at the end.
      const lines = [0.1344, 0.0504, 0.00504, 0.84, 1.2, 0.333, 2.5, 0.075, 0.019, 4.2, 0.66, 0.008];

      const exactTotal = lines.reduce((a, b) => a + b, 0);
      expect(cooksQuantity(exactTotal, "KG")).toBe("10 Kg");

      // Every line still displays sensibly on its own, and none of that touched the total above.
      expect(cooksQuantity(lines[0], "KG")).toBe("135 gm");
      expect(cooksQuantity(lines[2], "KG")).toBe("5 gm");
      expect(lines.reduce((a, b) => a + b, 0)).toBe(exactTotal);
    });
  });
});

describe("the label beside a number, and the label on its own", () => {
  // Two different questions, and the reason both functions exist. A screen that renders the figure
  // and the unit as separate elements is still printing one phrase and needs the agreeing label;
  // a column heading, a dropdown option or the adornment on a box somebody types into is naming
  // the unit with no number anywhere near it, and there "pieces" is right.
  it("agrees with the number when there is a number", () => {
    expect(unitLabelFor(1, "PIECES")).toBe("piece");
    expect(unitLabelFor(2, "PIECES")).toBe("pieces");
    expect(unitLabelFor(0, "PIECES")).toBe("pieces");
    expect(unitLabelFor(1.5, "PIECES")).toBe("pieces");
    expect(unitLabelFor(1, "KG")).toBe("Kg");
    expect(unitLabelFor(1, null)).toBe("");
  });

  it("stays plural where the unit is being named rather than counted", () => {
    expect(unitLabel("PIECES")).toBe("pieces");
    expect(unitLabel("KG")).toBe("Kg");
    expect(unitLabel(null)).toBe("");
  });
});

/**
 * One row, one unit (T-432).
 *
 * <p>The row Rajeev found on staging: **2.06 Kg on hand, 2.04 Kg committed, 20 gm available**. Every
 * figure was right by the table above — `quantity()` promotes from 1,000 up and is asked one figure
 * at a time — and the row was unreadable, because the subtraction the three columns exist to show
 * changed scale in the middle of itself.
 *
 * <p>The first test in this block is therefore the one that matters most: the table above must go on
 * saying exactly what it says. `quantity()` has about forty callers and a figure standing on its own
 * should still be said the way a person says it. This is a second entry point, not a new rule for
 * the old one.
 */
describe("several figures about one thing, said in one unit", () => {
  it("leaves quantity() alone — a figure on its own is still promoted and demoted as before", () => {
    expect(quantity(0.02, "KG")).toBe("20 gm");
    expect(quantity(2.06, "KG")).toBe("2.06 Kg");
  });

  it("says the row Rajeev found in one unit, and the available figure no longer switches to grams", () => {
    const say = oneUnitFor("KG", [2.06, 2.04, 0.02]);
    expect(say(2.06)).toBe("2.06 Kg");
    expect(say(2.04)).toBe("2.04 Kg");
    expect(say(0.02)).toBe("0.02 Kg");
  });

  it("takes its unit from the biggest figure, so a small row stays in the small unit", () => {
    // Nothing here is a kilogram's worth, so kilograms would print three leading zeroes on every
    // figure. The largest figure is what says how big the quantities in this row are.
    const say = oneUnitFor("KG", [0.85, 0.35, 0.02]);
    expect(say(0.85)).toBe("850 gm");
    expect(say(0.02)).toBe("20 gm");
  });

  it("promotes the whole row as soon as one figure is a kilogram", () => {
    const say = oneUnitFor("KG", [1.2, 0.85, 0.35]);
    expect(say(1.2)).toBe("1.2 Kg");
    expect(say(0.85)).toBe("0.85 Kg");
  });

  it("keeps the stored unit when every figure is nothing, as a lone zero already does", () => {
    expect(oneUnitFor("L", [0, 0, 0])(0)).toBe("0 L");
    expect(quantity(0, "L")).toBe("0 L");
  });

  it("never prints a figure that exists as a zero, however small it is beside the others", () => {
    // 0.4 gm forced into kilograms is 0.0004, which does not fit the three decimals a ledger figure
    // is given — and "0 Kg" would say the shelf is empty when it is not.
    const say = oneUnitFor("KG", [500, 0.0004]);
    expect(say(0.0004)).toBe("0.0004 Kg");
    expect(say(500)).toBe("500 Kg");
  });

  it("rounds nothing away — this is the ledger form and the figures have to add up", () => {
    // Found by measuring the real item screen: a draw of 418.2 gm beside a lot of 4.664 Kg. Three
    // decimals is a gram of a kilo, so it would have printed 0.418 Kg and lost two hundred
    // milligrams out of a figure somebody reconciles against.
    const say = oneUnitFor("GM", [4664, 418.2]);
    expect(say(418.2)).toBe("0.4182 Kg");
    expect(say(4664)).toBe("4.664 Kg");
  });

  it("carries a negative through on the row's scale — available may be less than nothing", () => {
    const say = oneUnitFor("KG", [1.96, 2.04, -0.08]);
    expect(say(-0.08)).toBe("-0.08 Kg");
  });

  it("has nothing to convert for a count, and hands back what quantity() would say", () => {
    const say = oneUnitFor("PIECES", [1200, 1]);
    expect(say(1200)).toBe("1,200 pieces");
    expect(say(1)).toBe("1 piece");
  });

  it("says a figure nobody has with a dash, as quantity() does", () => {
    expect(oneUnitFor("KG", [5])(null)).toBe("\u2014");
  });

  it("is not thrown by a row where every figure is missing", () => {
    expect(oneUnitFor("KG", [null, undefined])(2)).toBe("2 Kg");
  });
});

/**
 * How long it lasts, and the honesty it is required to keep (Rajeev, 2026-09-20).
 *
 * <p>He asked for an approximate figure and stipulated the word "Approximately", and that where
 * there is not enough history to judge it must say so rather than guess.
 */
describe("what the screen will claim about running out", () => {
  const cover = (days: number, beyondWindow = false) => ({ days, beyondWindow });

  it("uses his word on every figure it gives", () => {
    expect(stockCoverPhrase(cover(12), 50)).toBe("Approximately 12 days");
    expect(stockCoverPhrase(cover(1), 4)).toBe("Approximately 1 day");
  });

  it("says there is not enough history rather than guessing, and never leaves the cell blank", () => {
    expect(stockCoverPhrase(null, 50)).toBe("Not enough history");
    expect(stockCoverPhrase(undefined, 50)).toBe("Not enough history");
  });

  it("says the shelf is empty rather than 'approximately 0 days'", () => {
    expect(stockCoverPhrase(cover(0), 0)).toBe("None left");
    expect(stockCoverPhrase(cover(0), -3)).toBe("None left");
  });

  it("says less than a day where there is stock and today will finish it", () => {
    expect(stockCoverPhrase(cover(0), 2)).toBe("Less than a day");
  });

  it("will not forecast past the evidence it was made from", () => {
    expect(stockCoverPhrase(cover(90, true), 4000)).toBe("More than 3 months");
  });

  it("warns in amber only inside a week, and never about an absence of judgement", () => {
    expect(stockRunsOutSoon(cover(3), 12)).toBe(true);
    expect(stockRunsOutSoon(cover(7), 28)).toBe(true);
    expect(stockRunsOutSoon(cover(8), 32)).toBe(false);
    expect(stockRunsOutSoon(cover(90, true), 4000)).toBe(false);
    expect(stockRunsOutSoon(null, 50)).toBe(false);
  });

  it("says nobody has counted it rather than printing a dash that reads as a failed load", () => {
    expect(lastCountedPhrase(null)).toBe("Never counted");
    expect(lastCountedPhrase("2026-09-14")).toBe("14 Sept 2026");
  });
});
