import { dateWithYear } from "@/lib/format";

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
/** `step="1"`, or a number box with no step at all, whose default step is 1. */
export const wholeNumber = (name: string) => `${name} must be a whole number`;
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
    if (step === "1") return wholeNumber(name);
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
