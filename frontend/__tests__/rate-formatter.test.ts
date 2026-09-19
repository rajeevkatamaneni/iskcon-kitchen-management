import { describe, expect, it } from "vitest";
import { ratePackWord, rateUnit, readablePackRate, readableRate } from "@/lib/format";

/**
 * `readableRate`, the one formatter for a price per unit (T-288, VERIFY-B D-8).
 *
 * The conductor's rule (2026-09-19): a rate follows the readable unit. A weight is priced per Kg
 * and a volume per L whatever the quantity beside it, a count per piece, and a pack line says the
 * pack price first: "₹1,500 / bag · ₹60 / Kg".
 */
describe("readableRate", () => {
  it("says a price per gram per Kg: ₹0.30 a gram is ₹300 / Kg", () => {
    expect(readableRate(0.3, "GM")).toBe("₹300 / Kg");
    expect(readableRate(0.06, "GM")).toBe("₹60 / Kg");
  });

  it("says a price per millilitre per L", () => {
    expect(readableRate(0.25, "ML")).toBe("₹250 / L");
  });

  it("leaves a price already per Kg or per L as it is", () => {
    expect(readableRate(60, "KG")).toBe("₹60 / Kg");
    expect(readableRate(180, "L")).toBe("₹180 / L");
  });

  it("says a count per piece, in the singular", () => {
    expect(readableRate(4, "PIECES")).toBe("₹4 / piece");
    expect(readableRate(249.5, "PIECES")).toBe("₹249.50 / piece");
  });

  it("rounds to paise, so a four-place price per gram reads ₹71.20 / Kg and not float dust", () => {
    // 0.0712 × 1000 is 71.19999999999999 in floating point.
    expect(readableRate(0.0712, "GM")).toBe("₹71.20 / Kg");
    // 0.07 × 1000 is 70.00000000000001: a whole rupee figure, with no ".00".
    expect(readableRate(0.07, "GM")).toBe("₹70 / Kg");
    expect(readableRate(0.068, "GM")).toBe("₹68 / Kg");
  });

  it("writes rupees with Indian grouping", () => {
    expect(readableRate(1.5, "GM")).toBe("₹1,500 / Kg");
    expect(readableRate(125000, "KG")).toBe("₹1,25,000 / Kg");
  });

  it("says a pack line per pack, then per readable unit: ₹1,500 / bag · ₹60 / Kg", () => {
    expect(readableRate(60, "KG", { label: "Bag (25 Kg)", quantity: 25 })).toBe("₹1,500 / bag · ₹60 / Kg");
    expect(readableRate(0.06, "GM", { label: "Bag (25 Kg)", quantity: 25000 })).toBe("₹1,500 / bag · ₹60 / Kg");
  });

  it("says a small pack's rate per Kg, never per gm: the D-8 tea pack reads ₹250 / 500 gm · ₹500 / Kg", () => {
    expect(readableRate(0.5, "GM", { label: "500 gm", quantity: 500 })).toBe("₹250 / 500 gm · ₹500 / Kg");
    expect(readableRate(0.5, "GM", { label: "500 gm", quantity: 500 })).not.toMatch(/\/ gm$/);
  });

  it("says a tin of oil per tin and per L", () => {
    expect(readableRate(0.18, "ML", { label: "Tin (15 L)", quantity: 15000 })).toBe("₹2,700 / tin · ₹180 / L");
  });

  it("rounds a pack price to paise too: ₹33.3333 a Kg in a 3 Kg bag is ₹100 / bag", () => {
    expect(readableRate(100 / 3, "KG", { label: "Bag (3 Kg)", quantity: 3 })).toBe("₹100 / bag · ₹33.33 / Kg");
  });

  it("says nothing where there is no price, never ₹0", () => {
    expect(readableRate(null, "GM")).toBeNull();
    expect(readableRate(undefined, "KG")).toBeNull();
    expect(readableRate(Number.NaN, "KG")).toBeNull();
  });

  it("accepts a unit in lower case, as some views hold it", () => {
    expect(readableRate(0.3, "gm")).toBe("₹300 / Kg");
  });
});

/**
 * The pack form for a view that holds the pack price as its own figure (T-293): the vendor's quoted
 * price per bag, an invoice's amount over its count. Same words as readableRate's pack form.
 */
describe("readablePackRate", () => {
  it("prints the pack price as quoted and the rate per Kg: ₹1,500 / bag · ₹60 / Kg", () => {
    expect(readablePackRate(1500, "Bag = 25 Kg", 0.06, "GM")).toBe("₹1,500 / bag · ₹60 / Kg");
    expect(readablePackRate(1500, "Bag (25 Kg)", 60, "KG")).toBe("₹1,500 / bag · ₹60 / Kg");
  });

  it("keeps a quoted ₹1,000 bag at ₹1,000, where rebuilding it from ₹0.3333 a gram gives ₹999.90", () => {
    expect(readablePackRate(1000, "Bag = 3 Kg", 0.3333, "GM")).toBe("₹1,000 / bag · ₹333.30 / Kg");
  });

  it("says a small pack's rate per Kg, never per gm", () => {
    expect(readablePackRate(100, "250 gm", 0.4, "GM")).toBe("₹100 / 250 gm · ₹400 / Kg");
  });

  it("says what it has when half is missing, and nothing when both are", () => {
    expect(readablePackRate(1500, "Bag = 25 Kg", null, "GM")).toBe("₹1,500 / bag");
    expect(readablePackRate(null, "Bag = 25 Kg", 0.06, "GM")).toBe("₹60 / Kg");
    expect(readablePackRate(null, "", null, "GM")).toBeNull();
  });

  it("agrees with readableRate's own pack form", () => {
    expect(readablePackRate(60 * 25, "Bag (25 Kg)", 60, "KG")).toBe(readableRate(60, "KG", { label: "Bag (25 Kg)", quantity: 25 }));
  });
});

describe("ratePackWord and rateUnit", () => {
  it("reads a pack's word from either label the server writes, or a bare word, or a plain size", () => {
    expect(ratePackWord("Bag (25 Kg)")).toBe("bag");
    expect(ratePackWord("Bag = 25 Kg")).toBe("bag");
    expect(ratePackWord("bag")).toBe("bag");
    expect(ratePackWord("500 gm")).toBe("500 gm");
  });

  it("says a gram price per Kg and a millilitre price per L, and anything else per itself", () => {
    expect(rateUnit("GM")).toBe("KG");
    expect(rateUnit("ml")).toBe("L");
    expect(rateUnit("KG")).toBe("KG");
    expect(rateUnit("PIECES")).toBe("PIECES");
  });
});
