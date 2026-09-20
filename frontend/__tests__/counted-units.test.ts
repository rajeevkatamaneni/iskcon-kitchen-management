import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

import {
  FOOD_UNITS,
  convertQuantity,
  inputModeForUnit,
  isCountedUnit,
  portionUnitsFor,
  stepForUnit,
  unitFamily,
} from "@/lib/format";
import { wholeNumber, wholeNumberProblem } from "@/components/ds/formMessages";

/**
 * A counted thing cannot be had in halves (T-424).
 *
 * <p>The defect these were written against: seeding staging produced 7.2 LPG cylinders, 3.6 brooms,
 * 2.4 mops and a return of 1.5 aprons, and left an apron sitting at 88.5 in stock. The application
 * knew every one of those was measured in `PIECES`. Nothing in it knew that a piece is indivisible,
 * because the only way the frontend could tell a count from a weight was that `PIECES` was **missing
 * from the conversion table** — a rule written as an absence, which is a rule nobody reads.
 *
 * <p>So the rule is written down, in the shape the server writes it (`Unit.Family` in
 * `backend/.../ingredient/Unit.java`), and these hold it to three things: that it says what it
 * means, that it cannot drift away from the conversion table beside it, and that no quantity box in
 * the application quietly goes back to `step="any"`.
 */

describe("which units are counted", () => {
  it("says PIECES is a count and the four measures are not", () => {
    expect(isCountedUnit("PIECES")).toBe(true);
    for (const u of ["KG", "GM", "L", "ML"]) expect(isCountedUnit(u)).toBe(false);
  });

  it("names the family of every unit in the vocabulary", () => {
    expect(FOOD_UNITS.map((u) => [u, unitFamily(u)])).toEqual([
      ["KG", "MASS"],
      ["GM", "MASS"],
      ["L", "VOLUME"],
      ["ML", "VOLUME"],
      ["PIECES", "COUNT"],
    ]);
  });

  it("reads a unit however it is cased, as every other unit helper does", () => {
    expect(isCountedUnit("pieces")).toBe(true);
    expect(isCountedUnit("Pieces")).toBe(true);
  });

  /*
   * The permissive edge, and the reason it is permissive: a form where no ingredient has been chosen
   * yet has no unit to go on, and a box that refused a fraction because its unit was blank would be
   * a new defect in place of the old one.
   */
  it("treats a missing or unknown unit as not counted, so no box refuses on a blank unit", () => {
    for (const u of [null, undefined, "", "   ", "SERVINGS", "DOZEN"]) {
      expect(isCountedUnit(u)).toBe(false);
      expect(stepForUnit(u)).toBe("any");
    }
  });
});

describe("what a box is given", () => {
  it("steps a counted box by 1 and leaves every other box on any", () => {
    expect(stepForUnit("PIECES")).toBe("1");
    for (const u of ["KG", "GM", "L", "ML"]) expect(stepForUnit(u)).toBe("any");
  });

  /*
   * `step="1"` changes what the browser accepts but not what a phone offers to type with, so without
   * this a counted box would present a decimal point it then refuses.
   */
  it("asks a phone for a digits-only keypad on a counted box", () => {
    expect(inputModeForUnit("PIECES")).toBe("numeric");
    expect(inputModeForUnit("KG")).toBe("decimal");
    expect(inputModeForUnit(null)).toBe("decimal");
  });
});

/**
 * The drift guard. `format.ts` holds two tables — the families, and the large/small conversion pairs
 * — and the second one is the one that used to carry this rule by omission. If somebody adds a unit
 * to one and forgets the other, this fails rather than the application quietly deciding a crate is
 * divisible.
 *
 * <p>Checked through the exported behaviour (`convertQuantity`, `portionUnitsFor`) rather than by
 * exporting the private table, so what is pinned is what callers actually see.
 */
describe("the family table and the conversion table agree", () => {
  it("converts between two units exactly when they share a divisible family", () => {
    for (const a of FOOD_UNITS) {
      for (const b of FOOD_UNITS) {
        if (a === b) continue;
        const convertible = convertQuantity(1, a, b) !== null;
        const shouldConvert = unitFamily(a) === unitFamily(b) && unitFamily(a) !== "COUNT";
        expect([a, b, convertible]).toEqual([a, b, shouldConvert]);
      }
    }
  });

  it("offers a counted recipe only its own unit as a portion, and a measured one a pair", () => {
    for (const u of FOOD_UNITS) {
      expect([u, portionUnitsFor(u).length]).toEqual([u, isCountedUnit(u) ? 1 : 2]);
    }
  });
});

describe("the sentence for a box outside the shared Form", () => {
  it("is the very sentence Form says, from the same function", () => {
    expect(wholeNumberProblem("Quantity", "1", "1.5")).toBe(wholeNumber("Quantity"));
    expect(wholeNumberProblem("Quantity", "1", "1.5")).toBe("Quantity must be a whole number");
  });

  it("says nothing about a box that is not counted", () => {
    expect(wholeNumberProblem("Quantity", "any", "1.5")).toBeNull();
    expect(wholeNumberProblem("Quantity", "0.01", "1.005")).toBeNull();
  });

  it("says nothing about a whole number, however it arrives", () => {
    for (const v of ["2", " 2 ", 2, "2.0", 0, "0", -3]) {
      expect([v, wholeNumberProblem("Quantity", "1", v)]).toEqual([v, null]);
    }
  });

  /*
   * A blank or half-typed box is left to the rules that own it — "must be a whole number" is no help
   * to somebody whose box holds nothing. This is the order `messageFor` checks things in, kept.
   */
  it("says nothing about a blank or unusable box", () => {
    for (const v of ["", "   ", null, undefined, "abc", "1e"]) {
      expect([String(v), wholeNumberProblem("Quantity", "1", v)]).toEqual([String(v), null]);
    }
  });

  it("refuses a negative fraction as readily as a positive one", () => {
    expect(wholeNumberProblem("Change", "1", "-2.4")).toBe("Change must be a whole number");
  });
});

/**
 * The sweep that stops the 26th box being added with `step="any"`.
 *
 * <p>Every quantity box in the application now asks `stepForUnit` what step to carry. The ones still
 * written `step="any"` are the ones that are **not** quantities — money, coordinates — and they are
 * listed here by file with the reason. A new file that holds a `step="any"` box fails this test and
 * has to either go through the helper or be named below, which is the conversation worth having.
 */
const NOT_A_QUANTITY: Record<string, string> = {
  "app/donations/new/page.tsx": "estimated value of goods (₹) and the cash amount (₹)",
  "app/invoices/[id]/page.tsx": "the credit on a bill (₹)",
  "app/orders/new/page.tsx": "expected price (₹)",
  "app/tenants/new/page.tsx": "latitude and longitude",
  "app/vendors/[id]/page.tsx": "the vendor's rate (₹ per unit or per pack)",
  "app/wishlist/new/page.tsx": "price (₹)",
  "components/InventoryItemForm.tsx": "what it would cost to buy today (₹ per unit)",
  "components/InvoiceItemsTable.tsx": "the amount printed on the bill (₹)",
  "components/InvoiceTotals.tsx": "the bill's own money figures",
  "components/PayInvoiceForm.tsx": "the amount being paid (₹)",
  "components/RecipeForm.tsx": "indicative cost (₹)",
  "components/ingredient/MarketRate.tsx": "the market rate (₹ per unit)",
  "components/ingredient/supply.tsx": "a vendor's price (₹ per unit or per pack)",
};

function sourceFiles(dir: string, found: string[] = []): string[] {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) sourceFiles(path, found);
    else if (/\.tsx?$/.test(entry.name)) found.push(path);
  }
  return found;
}

describe("no quantity box is left stepping by any", () => {
  it("holds step=\"any\" only in the files that hold money or coordinates", () => {
    const root = join(__dirname, "..");
    const offenders: string[] = [];
    for (const dir of ["app", "components"]) {
      for (const file of sourceFiles(join(root, dir))) {
        const rel = file.slice(root.length + 1);
        // The sentence file quotes the attribute in its own prose rather than putting it on a box.
        if (rel === "components/ds/formMessages.ts") continue;
        const source = readFileSync(file, "utf8");
        // Only the attribute as a box carries it, never the string inside a comment.
        const onABox = source.split("\n").filter((l) => /step="any"/.test(l) && !/^\s*(\/\/|\*|\{\/\*)/.test(l));
        if (onABox.length > 0 && !(rel in NOT_A_QUANTITY)) offenders.push(rel);
      }
    }
    expect(offenders).toEqual([]);
  });
});
