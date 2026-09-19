import { describe, expect, it } from "vitest";
import { cooksQuantity, money, quantity } from "@/lib/format";

/**
 * Indian digit grouping at the lakh and crore boundaries (T-279, Rajeev's review item F6).
 *
 * The backend printed ₹1 lakh as "₹100,000" because the JDK's en-IN NumberFormat groups in threes.
 * The screen formats through Intl.NumberFormat("en-IN"), which does group in lakhs; this pins that
 * it does, at the same boundaries the backend's IndianNumbersTest holds, so the two cannot quietly
 * disagree again. Below a lakh the two formatters always agreed, which is why nothing caught it.
 */
describe("money() groups lakhs and crores the Indian way", () => {
  it("at the lakh boundary", () => {
    expect(money(99999, "INR")).toBe("₹99,999");
    expect(money(100000, "INR")).toBe("₹1,00,000");
    expect(money(1000000, "INR")).toBe("₹10,00,000");
    expect(money(9999999, "INR")).toBe("₹99,99,999");
  });

  it("at the crore boundary", () => {
    expect(money(10000000, "INR")).toBe("₹1,00,00,000");
    expect(money(123456789, "INR")).toBe("₹12,34,56,789");
  });

  it("with paise, which are shown only when there are any", () => {
    expect(money(1234567.5, "INR")).toBe("₹12,34,567.50");
    expect(money(100000.05, "INR")).toBe("₹1,00,000.05");
  });

  it("negatives put the sign before the ₹", () => {
    expect(money(-99999, "INR")).toBe("-₹99,999");
    expect(money(-100000, "INR")).toBe("-₹1,00,000");
    expect(money(-12345678.5, "INR")).toBe("-₹1,23,45,678.50");
  });
});

/**
 * The quantity vectors the backend's QuantitiesTest added for T-279, mirrored here as that file's
 * header asks: a vector in one table and not the other is how the two copies drifted over zero.
 */
describe("a quantity of a lakh or more is grouped the Indian way", () => {
  it("matches QuantitiesTest's lakh vectors", () => {
    expect(quantity(99999, "PIECES")).toBe("99,999 pieces");
    expect(quantity(150000, "PIECES")).toBe("1,50,000 pieces");
    expect(cooksQuantity(150000, "PIECES")).toBe("1,50,000 pieces");
    expect(quantity(1234567.5, "KG")).toBe("12,34,567.5 Kg");
    expect(quantity(10000000, "L")).toBe("1,00,00,000 L");
    expect(quantity(-150000, "KG")).toBe("-1,50,000 Kg");
  });
});
