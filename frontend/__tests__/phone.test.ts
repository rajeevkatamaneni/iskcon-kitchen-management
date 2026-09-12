import { describe, expect, it } from "vitest";
import { isE164, normalizePhone } from "@/lib/phone";

/**
 * The phone helper every E.164 box sends through (T-157).
 *
 * <p>KMS-400003 writes its example as "+91 98765 43210", and every box it applied to used to refuse
 * that example. The same ways of typing the number are in the server's `PhoneNormalisationIT`,
 * because the two sides must remove the same characters: a box that enables its button for a number
 * the server then refuses is worse than one that never enabled it.
 *
 * <p>Escaped rather than pasted, so that an editor cannot turn an invisible character back into a
 * plain space and leave the test passing for the wrong reason.
 */
const THE_SAME_NUMBER_TYPED = [
  ["KMS-400003's own example", "+91 98765 43210"],
  ["hyphens", "+91-98765-43210"],
  ["an en dash, which a phone keyboard makes of a hyphen", "+91 98765\u201343210"],
  ["non-breaking spaces, from autofill or a document", "\u00A0+91\u00A098765\u00A043210"],
  ["zero-width spaces and a byte-order mark, from a paste", "+91\u200B98765\u200B43210\uFEFF"],
  ["the direction marks Android wraps a copied contact in", "\u202A+91 98765 43210\u202C"],
  ["a tab and a newline, from a spreadsheet cell", "\t+91 98765 43210\n"],
] as const;

describe("normalizePhone", () => {
  it.each(THE_SAME_NUMBER_TYPED)("removes %s", (_how, typed) => {
    expect(normalizePhone(typed)).toBe("+919876543210");
    expect(isE164(typed)).toBe(true);
  });

  it("leaves a number that was already bare exactly as it was", () => {
    expect(normalizePhone("+919876543210")).toBe("+919876543210");
  });

  it("removes separators and nothing else, so a typo is refused rather than repaired", () => {
    // The provisioning screen's old version kept only the plus and the digits, which turned this
    // into "+91987654321" — a well-formed number belonging to somebody else.
    expect(normalizePhone("+91 98765 4321X")).toBe("+91987654321X");
    expect(isE164("+91 98765 4321X")).toBe(false);
  });

  it("does not treat brackets as separators, because a bracket usually holds a 0 that must go", () => {
    // "+91 (0) 98765 43210" with the brackets removed would be "+910987…", which passes the rule and
    // rings nobody. Refusing it costs one retype.
    expect(normalizePhone("+91 (0) 98765 43210")).toBe("+91(0)9876543210");
    expect(isE164("+91 (0) 98765 43210")).toBe(false);
  });

  it("does not invent a country code", () => {
    expect(isE164("98765 43210")).toBe(false);
  });

  it("turns a box of spaces into nothing, which every screen already treats as no number", () => {
    expect(normalizePhone("   ")).toBe("");
    expect(isE164("   ")).toBe(false);
  });
});
