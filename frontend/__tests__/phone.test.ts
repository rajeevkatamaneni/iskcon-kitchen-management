import { describe, expect, it } from "vitest";
import { isE164, normalizeIndianMobile, normalizePhone, savedPhoneForDisplay } from "@/lib/phone";

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

/**
 * The counter's rule for a donor's phone (T-186), on the same inputs as the server's
 * `CounterPhoneTest`. Read the two lists side by side: the server is the authority and this copy only
 * lets the screen say what was saved, so if they ever disagree the screen is telling the office a lie.
 */
const RECOGNISED = [
  ["ten digits with a space, as read out", "98765 43210"],
  ["ten digits bare", "9876543210"],
  ["a trunk 0", "09876543210"],
  ["91 with no plus", "919876543210"],
  ["+91 with a space and a hyphen", "+91 98765-43210"],
  ["already in +91 form", "+919876543210"],
  ["a non-breaking space and an en dash", "+91\u00A098765\u201343210"],
  ["a zero-width space and the marks Android wraps a copied contact in", "\u202A98765\u200B43210\u202C"],
  ["spaces at either end", "  98765 43210  "],
] as const;

const KEPT_AS_TYPED = [
  ["a Mumbai landline", "022 2345 6789", "022 2345 6789"],
  ["a foreign number", "+1 555 0100", "+1 555 0100"],
  ["a short number", "12345", "12345"],
  ["letters", "abcd", "abcd"],
  ["ten digits starting 1", "1234567890", "1234567890"],
  ["ten digits starting 5", "5876543210", "5876543210"],
  ["a typo with a letter", "98765 4321X", "98765 4321X"],
  ["a bracketed trunk 0", "+91 (0) 98765 43210", "+91 (0) 98765 43210"],
  ["an international 0091 prefix", "0091 98765 43210", "0091 98765 43210"],
  ["+91 followed by a 0 as well", "+91 0 98765 43210", "+91 0 98765 43210"],
  ["eleven digits", "98765 432101", "98765 432101"],
  ["a landline, trimmed", "  022 2345 6789 ", "022 2345 6789"],
] as const;

describe("normalizeIndianMobile", () => {
  it.each(RECOGNISED)("saves %s as +91 and its ten digits", (_how, typed) => {
    expect(normalizeIndianMobile(typed)).toBe("+919876543210");
  });

  it.each(KEPT_AS_TYPED)("keeps %s exactly as typed, after trimming", (_how, typed, saved) => {
    expect(normalizeIndianMobile(typed)).toBe(saved);
  });

  it("turns a Bengaluru landline with its trunk 0 into that same line's +91 number, which is not a guess", () => {
    // 080 is Bengaluru's code, so this is "0" and ten digits starting 8. The server does the same.
    expect(normalizeIndianMobile("080 2345 6789")).toBe("+918023456789");
  });

  it("leaves a blank box blank, which the screen sends as no phone", () => {
    expect(normalizeIndianMobile("")).toBe("");
    expect(normalizeIndianMobile("   ")).toBe("");
  });
});

describe("savedPhoneForDisplay", () => {
  it("writes a saved +91 number the way it is read aloud", () => {
    expect(savedPhoneForDisplay("+919876543210")).toBe("+91 98765 43210");
  });

  it("shows anything else exactly as it was saved", () => {
    expect(savedPhoneForDisplay("022 2345 6789")).toBe("022 2345 6789");
    expect(savedPhoneForDisplay("+15550100")).toBe("+15550100");
  });
});
