import { describe, expect, it } from "vitest";
import { cooksQuantity, quantity } from "@/lib/format";

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
