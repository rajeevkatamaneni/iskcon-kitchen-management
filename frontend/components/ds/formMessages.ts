import { dateWithYear, isCountedUnit, unitLabel } from "@/lib/format";

/**
 * Every sentence a form says when the browser's own checks refuse a box (T-160).
 *
 * <p>One file, so that a change of wording is one edit and so that every sentence the application
 * can put under a box is readable in one place. Rajeev's ruling, 2026-09-11: *"Ideally, we should
 * say 'Quantity is required' OR 'Note is required'."* So every sentence names the field, in the
 * label's own words, and follows DESIGN_SYSTEM §9 — sentence case, no full stop under a field, no
 * semicolons, twelve words or fewer once a label of normal length is put in.
 *
 * <p>What is here is exactly the checks the application's forms use, counted when the task was
 * planned: `required`, `min`, `max`, `step` ("0.01" and "1"; "any" never refuses), `maxLength`, and
 * the `number`, `date`, `time` and `email` types. `pattern`, `minLength` and `type="url"` are used
 * nowhere, so they have no sentence of their own — they fall to {@link notValid}, which exists only
 * so that a check added later refuses out loud rather than blocking the button in silence.
 *
 * <p>Nothing here decides *whether* a box is wrong. That is the browser's `ValidityState`, with the
 * two refusals `Form` adds to it (T-203: a required box of only spaces is blank, and a number at or
 * below `data-more-than` is under its floor). This file only decides what to say about it.
 */

/** The name used when a control has no aria-label, no label and no name. Nothing today reaches it. */
export const UNNAMED_FIELD = "This field";

/** The facts about a control that a sentence can depend on, read off the element. */
export interface ControlFacts {
  /** `input.type`, or "select-one" / "textarea" for those elements. */
  type: string;
  validity: Pick<
    ValidityState,
    | "valueMissing"
    | "typeMismatch"
    | "badInput"
    | "rangeUnderflow"
    | "rangeOverflow"
    | "stepMismatch"
    | "tooLong"
  >;
  /** The attributes as written, or "" where absent. */
  min: string;
  max: string;
  step: string;
  /** -1 where absent, which is what the DOM reports. */
  maxLength: number;
  /**
   * The box's `data-more-than`, or "" / absent where it has none (T-203). HTML has no exclusive
   * lower bound — `min="0"` lets exactly 0 through — so a box whose floor is "above zero" carries this
   * attribute, and `Form` reports a value at or below it as a `rangeUnderflow` of its own. Optional so
   * that a facts object written before it existed still describes a box correctly.
   */
  moreThan?: string;
  /**
   * What the box counts, where the screen was able to say (T-431). Absent on every box that was
   * not given the two attributes in {@link countedBox}, and on every box that counts nothing.
   */
  counted?: CountedBox;
}

/**
 * The two facts a whole-number refusal needs before it can say **why** (T-431).
 *
 * <p>"Tell me when Agarbatti drops below must be a whole number" is the field's own label with a
 * rule glued onto it. It never says that a piece of incense cannot be split, which is the only
 * thing the person needs to know. The server has said it properly since the rule shipped —
 * `IngredientUnits.java`, *"Apron is counted in whole pieces. Enter 88 or 89."* — so the browser
 * takes the server's words rather than inventing a second wording for one rule.
 */
export interface CountedBox {
  /** The thing being counted, in the words the screen already shows it in: "Agarbatti". */
  subject: string;
  /** The unit code it is counted in — "PIECES" — or {@link PACKS} where the box counts packs. */
  unit: string;
}

/**
 * The `unit` of a box that counts **packs** rather than the things inside them.
 *
 * <p>Two boxes are whole for a reason that has nothing to do with the unit: a purchase-order line
 * bought by the pack counts bags, and a third of a bag is not something a vendor sells. The
 * ingredient may be measured in kilograms, so "Rice is counted in whole Kg" would be flatly false
 * there. Marked rather than inferred, because only the call site knows which of its two shapes a
 * line is in — the same reason those call sites already write `step="1"` outright instead of
 * asking `stepForUnit`.
 */
export const PACKS = "PACK";

/** Where a box carries {@link CountedBox}. Read by `Form`'s facts builder, written by {@link countedBox}. */
export const COUNTED_SUBJECT_ATTRIBUTE = "data-counted-subject";
export const COUNTED_UNIT_ATTRIBUTE = "data-counted-unit";

/**
 * The attributes a counted box carries so that `Form` can say why it refused a fraction.
 *
 * <p>Spread onto the `<input>`: `{...countedBox(item.ingredientName, item.unit)}`. Data attributes
 * rather than props for the reason `data-more-than` is one (T-203) — every other fact `Form` reads
 * off a box is an attribute, and the 18 boxes inside a `<Form>` are hand-rolled inputs with no
 * props to give. Either fact missing means the attribute is left off altogether and the refusal
 * falls back to the sentence it said before, which is the right answer for a box on a form where
 * no ingredient has been chosen yet and there is nothing true to name.
 */
export function countedBox(
  subject: string | null | undefined,
  unit: string | null | undefined
): { "data-counted-subject"?: string; "data-counted-unit"?: string } {
  const named = subject?.trim();
  const code = unit?.trim();
  if (!named || !code) return {};
  return { "data-counted-subject": named, "data-counted-unit": code };
}

/** A box left empty. */
export const required = (name: string) => `${name} is required`;
/** A number below `min`. Rajeev's own example: "Quantity must be at least 1". */
export const atLeast = (name: string, min: string) => `${name} must be at least ${min}`;
/**
 * A number at or below a `data-more-than` floor (T-203): "Amount must be more than 0". The credit on
 * a bill is the case that needed it — a credit of 0 is not "at least 0" wrong, it is not a credit.
 */
export const moreThan = (name: string, min: string) => `${name} must be more than ${min}`;
/** A number above `max`. Rajeev's own example: "Quantity can be at most 500". */
export const atMost = (name: string, max: string) => `${name} can be at most ${max}`;
/**
 * A date before `min` or after `max`.
 *
 * <p>The limit is printed the way every other date in the application is printed — day first, the
 * month as a name, through `dateWithYear` in lib/format.ts — and never as "2026-09-13", which is what
 * the attribute holds and what nobody in a temple kitchen writes.
 */
export const onOrAfter = (name: string, min: string) => `${name} must be on or after ${dateWithYear(min)}`;
export const onOrBefore = (name: string, max: string) => `${name} must be on or before ${dateWithYear(max)}`;
/** A time before `min` or after `max`. "HH:mm" is already how the application writes a time. */
export const orLater = (name: string, min: string) => `${name} must be ${min} or later`;
export const orEarlier = (name: string, max: string) => `${name} must be ${max} or earlier`;
/**
 * `step="1"`, or a number box with no step at all, whose default step is 1.
 *
 * <p>**It says why where the box said what it counts** (T-431). "Agarbatti is counted in whole
 * pieces" is the server's own sentence for the same refusal, minus the "Enter 7 or 8" the server
 * adds after it: DESIGN_SYSTEM §9 allows one clause and twelve words under a field, and the two
 * whole numbers either side of what was typed are a second clause on 26 boxes, several of them in
 * a table cell 154px wide on a phone. The reason is the half that was missing; the fix follows
 * from it.
 *
 * <p>**A box counting packs says packs.** A part of a bag is not something a vendor sells, and the
 * ingredient in the bag may be measured in kilograms, so the unit's reason would be untrue there.
 * "whole packs" is the product's own phrase for it, from the pack sizes hint on an ingredient.
 *
 * <p>**Without both facts it says exactly what it said before.** A form where no ingredient has
 * been chosen, or a recipe nobody has named yet, has no true subject to put in front of "is counted
 * in", and an invented one ("This is counted in whole pieces") would be worse than the label.
 */
export const wholeNumber = (name: string, counted?: CountedBox | null): string => {
  const subject = counted?.subject.trim();
  const unit = counted?.unit.trim();
  if (subject && unit === PACKS) return `${subject} is ordered in whole packs`;
  // Guarded by the same function the `step` came from, so a box marked with a unit that is not a
  // count cannot produce "Rice is counted in whole Kg" from a step somebody wrote by hand.
  if (subject && isCountedUnit(unit)) return `${subject} is counted in whole ${unitLabel(unit)}`;
  return `${name} must be a whole number`;
};

/**
 * The same refusal, for a box that is **not** inside a `<Form>` — or null when there is nothing
 * to say (T-424).
 *
 * <p>**Why this exists.** Five screens hold a quantity box outside the shared `<Form>`: recording a
 * delivery, recording what was issued against a request, the reorder level edited in place on the
 * Inventory row, the Shopping list's two boxes, and the meal recording form. They gather their own
 * refusals and draw their own red sentence under the box, so nothing in `Form` reaches them and
 * `stepMismatch` is never read on their behalf. Without this they would each have grown their own
 * wording, and "Received now must be a whole number" on one screen beside "Enter a whole number" on
 * the next is the inconsistency the one-vocabulary rule exists to stop. The sentence therefore comes
 * from {@link wholeNumber} here exactly as it does for every box inside a `<Form>`, and there is
 * still one place to change the words.
 *
 * <p>**It is given the box's own `step`, not the box's unit, on purpose.** The caller works out
 * `step` once — usually `stepForUnit(someUnit)`, sometimes `"1"` outright because the box counts
 * packs — and hands the same value to the `step` attribute and to this. So the attribute and the
 * sentence cannot disagree about whether the box is counted, which they could if each worked it out
 * from the unit separately. A `step` of anything but `"1"` is nothing to do with this rule and
 * returns null.
 *
 * <p>**`counted` is a separate argument and not a replacement for `step`** (T-431). The step still
 * decides *whether* the box refuses; the unit inside `counted` only decides what the sentence calls
 * the thing. Folding the two together would put the rule back where it could disagree with the
 * attribute — a pack box carries `step="1"` and a `counted.unit` of {@link PACKS}, and the whole
 * point is that those are two different facts. It is optional so that a caller with nothing true to
 * name gets the sentence this file said before, and so the eight boxes here word the refusal from
 * the same {@link wholeNumber} as the eighteen inside a `<Form>`.
 *
 * <p>**A blank or half-typed box is not this rule's business** and comes back null, matching the
 * order `messageFor` checks things in: "must be a whole number" is no help to somebody whose box
 * holds nothing, or "1e". Those screens already say their own thing about an empty box.
 *
 * <p>**A value already on file is judged, not rewritten.** A line that arrives holding 88.5 aprons
 * renders 88.5 and is refused on the save, which is the point: the figure is wrong and saying so is
 * the whole feature. Nothing here changes what the box shows.
 */
export function wholeNumberProblem(
  name: string,
  step: string,
  value: string | number | null | undefined,
  counted?: CountedBox | null
): string | null {
  if (step.trim() !== "1") return null;
  if (value === null || value === undefined) return null;
  if (typeof value === "string" && value.trim() === "") return null;
  const n = Number(value);
  if (!Number.isFinite(n)) return null;
  return Number.isInteger(n) ? null : wholeNumber(name, counted);
}
/** `step="0.01"` and its kin. "2 decimal places" rather than "steps of 0.01", which is how people say it. */
export const decimalPlaces = (name: string, places: number) =>
  `${name} can have at most ${places} decimal ${places === 1 ? "place" : "places"}`;
/** Any other step. No form uses one today. */
export const inStepsOf = (name: string, step: string) => `${name} must go up in steps of ${step}`;
/** A number box holding something half typed, like "1e" or "-". */
export const notANumber = (name: string) => `${name} must be a number`;
/** A date box with a day, month or year still missing. */
export const incompleteDate = (name: string) => `${name} must be a complete date`;
/** A time box with the hours or minutes still missing. */
export const incompleteTime = (name: string) => `${name} must be a complete time`;
/** Longer than `maxLength`. The browser stops typing at the limit, so only a filled-in value gets here. */
export const tooManyCharacters = (name: string, maxLength: number) =>
  `${name} can be at most ${maxLength} characters`;
/** An email box holding something that is not an email address. The example does the explaining. */
export const EMAIL_ADDRESS = "Enter an email address like name@example.com";
/** Any refusal this file has no sentence for. Reached by nothing the forms use today. */
export const notValid = (name: string) => `${name} is not valid`;

/**
 * How many decimal places a step of this kind allows, or null when the step is not a plain power of
 * ten below one ("0.01" is 2, "0.5" is null).
 */
function placesOf(step: string): number | null {
  const m = /^0\.(0*)1$/.exec(step.trim());
  return m ? m[1].length + 1 : null;
}

/**
 * The one sentence for a refused control.
 *
 * <p>Checked in the order a person meets the problems: an empty box before anything about its value,
 * a half-typed value before any limit, because "must be at least 1" is no help to somebody whose
 * box does not yet hold a number at all.
 */
export function messageFor(name: string, facts: ControlFacts): string {
  const { type, validity } = facts;

  if (validity.valueMissing) return required(name);

  if (validity.badInput) {
    if (type === "date") return incompleteDate(name);
    if (type === "time") return incompleteTime(name);
    return notANumber(name);
  }

  if (validity.typeMismatch && type === "email") return EMAIL_ADDRESS;

  if (validity.rangeUnderflow) {
    // A box with an exclusive floor says so whichever bound refused it. The credit box carries
    // `min="0"` as well, and "-50 must be at least 0" followed by "0 must be more than 0" on the next
    // press would be two rules for one box; "more than 0" is the true one and covers both.
    if (facts.moreThan) return moreThan(name, facts.moreThan);
    if (type === "date") return onOrAfter(name, facts.min);
    if (type === "time") return orLater(name, facts.min);
    return atLeast(name, grouped(facts.min));
  }

  if (validity.rangeOverflow) {
    if (type === "date") return onOrBefore(name, facts.max);
    if (type === "time") return orEarlier(name, facts.max);
    return atMost(name, grouped(facts.max));
  }

  if (validity.stepMismatch) {
    // A number box with no step attribute steps by 1 (HTML's default step for number), so it
    // refuses "1.5" exactly as step="1" does and gets the same sentence.
    const step = facts.step.trim() || "1";
    if (step === "1") return wholeNumber(name, facts.counted);
    const places = placesOf(step);
    return places === null ? inStepsOf(name, step) : decimalPlaces(name, places);
  }

  if (validity.tooLong) return tooManyCharacters(name, facts.maxLength);

  return notValid(name);
}

/** A number bound written the way the rest of the app writes numbers: 50000 reads "50,000". */
function grouped(bound: string): string {
  const n = Number(bound);
  return bound.trim() !== "" && Number.isFinite(n) ? n.toLocaleString("en-IN") : bound;
}
