import { describe, expect, it } from "vitest";
import { repeatsPack } from "@/lib/format";

/**
 * The one rule for when the stock amount beside a pack count only says the pack again (T-303).
 *
 * <p>T-299 set it on the Deliveries screen and T-302 copied it into the order page, the invoice page
 * and the invoice form; the Billed qty box on the form still said "500 gm" under "1 × 500 gm". It
 * is now one exported helper, and these are the cases it has to keep.
 */
describe("repeatsPack", () => {
  it("one pack with no name repeats its size", () => {
    expect(repeatsPack("500 gm", 1)).toBe(true);
  });

  it("counts packs, not text: a '0.5 Kg' label over a '500 gm' total is still one pack", () => {
    expect(repeatsPack("0.5 Kg", 1)).toBe(true);
  });

  it("two unnamed packs keep their total ('2 × 500 gm' over '1 Kg')", () => {
    expect(repeatsPack("500 gm", 2)).toBe(false);
  });

  it("part of a pack, or more than one, keeps the stock amount", () => {
    expect(repeatsPack("500 gm", 0.5)).toBe(false);
    expect(repeatsPack("500 gm", 1.5)).toBe(false);
    expect(repeatsPack("500 gm", 0)).toBe(false);
  });

  it("a named pack keeps its size, even for one ('1 × Bag (25 Kg)' over '25 Kg')", () => {
    expect(repeatsPack("Bag (25 Kg)", 1)).toBe(false);
    expect(repeatsPack("Tin (15 L)", 1)).toBe(false);
  });

  it("compares the count exactly, as the caller writes it: 1.0004 typed in a box is not 1", () => {
    // A view that writes counts to 3 places passes the rounded count; a box passes what was typed,
    // and "1.0004" beside "× 500 gm" is 500.2 gm, which is worth saying.
    expect(repeatsPack("500 gm", 1.0004)).toBe(false);
    expect(repeatsPack("500 gm", Number((1.0004).toFixed(3)))).toBe(true);
  });
});
