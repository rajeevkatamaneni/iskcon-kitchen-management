import { templeTimeZone } from "./api";

/**
 * Small shared formatters.
 *
 * <p>Times arrive from the API as "HH:mm:ss". Seconds are noise on a screen — a meal is due at 12:00,
 * not 12:00:00 — so nothing renders them (INT-8).
 */

/** "12:00:00" or "12:00" → "12:00". Anything unparseable comes back as it arrived. */
export function hhmm(time: string | null | undefined): string {
  if (!time) return "—";
  const m = /^(\d{1,2}):(\d{2})/.exec(time);
  return m ? `${m[1].padStart(2, "0")}:${m[2]}` : time;
}

/**
 * Does this shift run through midnight — that is, does it end on the day after its own date?
 *
 * <p>**`shiftDate` is the date a shift STARTS.** Everything about an overnight shift follows from
 * that one sentence, and this is where the screens say it. A shift whose end time is at or before
 * its start time — 20:00 to 02:00, the Janmashtami midnight offering — ends the next morning.
 *
 * <p>The same rule is written in exactly two other places and nowhere else, because the most
 * expensive recurring defect in this project is one sum implemented three times and disagreeing:
 * `shift_ends_at()` in SQL (migration V127) and `ShiftWindow` in Java. A fourth copy should not be
 * written; call this one.
 *
 * <p>Compares "HH:mm" prefixes as strings, which is a chronological comparison because both are
 * fixed-width and zero-padded — the same trick the roster already uses to decide whether a shift
 * has started. The API sends "HH:mm:ss" and a form sends "HH:mm", so both are cut to five
 * characters first and the two agree.
 */
export function crossesMidnight(
  startTime: string | null | undefined,
  endTime: string | null | undefined
): boolean {
  if (!startTime || !endTime) return false;
  return endTime.slice(0, 5) <= startTime.slice(0, 5);
}

/**
 * A shift's hours as a person reads them — "08:00–12:00", or "20:00–02:00 (next day)" where it runs
 * through the night.
 *
 * <p>The parenthesis is the point of it. "20:00–02:00" read cold is a shift that ends sixteen hours
 * before it begins, and a volunteer deciding whether they can make it, or a coordinator scanning a
 * roster, should not have to work out which of the two readings the temple meant. Before T-146 an
 * overnight shift could not be posted at all, so no screen had ever had to say this.
 *
 * <p>The same sentence the server puts in a reminder and a signup confirmation
 * (`ShiftWindow.describe`), so the message on somebody's phone and the screen it came from say the
 * shift's hours the same way.
 */
export function shiftWindow(
  startTime: string | null | undefined,
  endTime: string | null | undefined
): string {
  const window = `${hhmm(startTime)}–${hhmm(endTime)}`;
  return crossesMidnight(startTime, endTime) ? `${window} (next day)` : window;
}

/** "2026-08-14" → "Friday, 14 August 2026", in the reader's own locale. */
/**
 * "Saturday, 15 August" — a day the way it is said aloud in a kitchen. No year: the planner and the
 * Today screen are always about the days around now, and the year on every heading is noise.
 */
export function longDay(iso: string): string {
  const d = new Date(`${iso}T00:00:00`);
  const weekday = d.toLocaleDateString("en-GB", { weekday: "long" });
  const rest = d.toLocaleDateString("en-GB", { day: "numeric", month: "long" });
  return `${weekday}, ${rest}`;
}

/**
 * Every date this application writes is written the Indian way — "1 Sep 2026", never "Sep 1, 2026"
 * — whoever is reading it. The locale is pinned rather than left to the browser because the temple
 * is the subject: a screen that reformats itself for a reader in California is describing the same
 * Thursday in a way the people running the kitchen do not use, and two screens then disagree on one
 * machine (this file already pinned `longDay` and left its neighbours to drift).
 */
export function longDate(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString("en-GB", {
    weekday: "long",
    day: "numeric",
    month: "long",
    year: "numeric",
  });
}

/** "2026-08-14" → "14 Aug", for tight spaces. */
export function shortDate(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString("en-GB", {
    day: "numeric",
    month: "short",
  });
}

/**
 * The clock this temple keeps — read from the session, not assumed.
 *
 * <p>It was `const TEMPLE_TIME_ZONE = "Asia/Kolkata"` until 2026-09-05: right for every temple
 * onboarded so far and wrong for the first one that is not, in seventeen files at once. Rajeev:
 * *"ALL Date and Time values for that Temple MUST be in that Time zone irrespective of where the
 * Temples dedicated tenant is being accessed from."*
 *
 * <p>A function rather than a constant on purpose. The zone is not known until the session resolves,
 * and a temple switch can change it — a constant read at module load would be captured before either
 * had happened. Every formatter below calls it rather than closing over it.
 */
export function templeZone(): string {
  return templeTimeZone();
}

/**
 * The temple's today, as "YYYY-MM-DD".
 *
 * <p>Deliberately not the browser's. Every date the server works in — a meal plan, a shift, the
 * Today screen — is the temple's own day, so a screen reading the device clock disagrees with the
 * server for anyone testing or travelling outside it: the planner would mark one day as today while
 * Today called it another. The kitchen's day is the operational day.
 */
export function todayIso(): string {
  // en-CA renders as "YYYY-MM-DD", which is the format the API speaks.
  return new Intl.DateTimeFormat("en-CA", { timeZone: templeZone() }).format(new Date());
}

/**
 * An amount in the temple's own currency (B8) — "₹18,000", or whatever its currency's symbol is.
 *
 * <p>The currency comes from the temple rather than from a hard-coded rupee sign, so a screen
 * written now needs no edit if a temple outside India is ever taken on.
 *
 * <p>The **grouping** is not the reader's, and that is the same decision the dates in this file
 * made. Left to the browser, eleven lakh fifty thousand renders as "₹1,150,000" on an en-US
 * machine while the wish list, which pinned `en-IN`, printed "₹11,50,000" one screen away: two
 * ways of saying the same rupees, on one machine, a click apart. Money is grouped in lakhs and
 * crores because that is how the people spending it say the number out loud, and a screen that
 * reformats itself for somebody in California is describing the temple's money in a way the temple
 * does not use.
 *
 * <p>Null is "—" and never "₹0". Money we have no figure for and money that is genuinely nothing are
 * different facts, and the screens that show a salary depend on the difference.
 */
export function money(amount: number | null | undefined, currency: string): string {
  if (amount === null || amount === undefined) return "—";
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency,
    // Paise are shown only when there are any: a salary of 18,000 reads better than 18,000.00,
    // and a settlement of 18,432.50 must not be rounded away.
    minimumFractionDigits: Number.isInteger(amount) ? 0 : 2,
    maximumFractionDigits: 2,
  }).format(amount);
}

/**
 * How a unit is written where a person reads it. The one copy — there used to be eight, and the one
 * in RecipePeek had drifted to lowercase "kg" while this one said "Kg".
 */
export const UNIT_LABEL: Record<string, string> = {
  KG: "Kg",
  GM: "gm",
  L: "L",
  ML: "ml",
  PIECES: "pieces",
};

/**
 * How a unit is written, or nothing at all where there is no unit to name.
 *
 * <p>The label on its own, for the places a quantity is not being said: a column that names an
 * ingredient's unit, and the adornment beside a box somebody types into. That second case is the
 * reason this exists next to {@link quantity} rather than being replaced by it — a readout may be
 * promoted from 0.6 Kg to 600 gm, but the box beside it still submits kilograms, and labelling it
 * "gm" would invite a thousandfold error.
 */
export function unitLabel(unit: string | null | undefined): string {
  if (!unit) return "";
  return UNIT_LABEL[unit] ?? unit.toLowerCase();
}

/**
 * The units that read differently when there is exactly one of the thing (T-108).
 *
 * <p>Only a **count** has a singular to get wrong. `Kg`, `gm`, `L` and `ml` are abbreviations of a
 * mass or a volume, and an abbreviation does not take an "s" in Indian English any more than in
 * British: one kilo of rice is written "1 Kg", never "1 Kgs". `PIECES` is the one unit in the
 * vocabulary that names the things themselves rather than how much of something there is, and one
 * of those is a piece.
 *
 * <p>A map rather than a two-line `if`, because the next counted unit somebody adds — crates,
 * sacks, bundles — should be one line beside this one rather than a second place to remember a
 * rule. Anything absent from it is written the same at every number, which is the common case and
 * therefore the default.
 */
const UNIT_SINGULAR: Record<string, string> = {
  PIECES: "piece",
};

/**
 * The unit label agreeing with the number printed in front of it — "1 piece", but "3 pieces" and
 * "1 Kg".
 *
 * <p>This is the repair for **"1 pieces"**, which Rajeev saw on a purchase order and which every
 * screen in the application printed, because {@link UNIT_LABEL} holds one label per unit and a
 * label cannot agree with a number it has never been shown. The count is therefore a **required**
 * argument and not an optional one: an optional count is a count the next caller forgets, and the
 * forgotten case renders exactly the defect this exists to remove.
 *
 * <p>Use it wherever a figure and a unit are printed as one phrase but are rendered apart — a
 * number in one element and the unit in another. Where the whole phrase comes from
 * {@link quantity} or {@link cooksQuantity} there is nothing to do: they call this themselves.
 * Where the unit is named with **no** number beside it — a column heading, a dropdown option, the
 * adornment on a box somebody types into — {@link unitLabel} is still the right one, and "pieces"
 * is still what it should say.
 *
 * <p>Compared on the absolute value, so a reversal of one stool reads "−1 piece".
 */
export function unitLabelFor(value: number, unit: string | null | undefined): string {
  if (!unit) return "";
  const singular = UNIT_SINGULAR[unit.toUpperCase()];
  return Math.abs(value) === 1 && singular ? singular : unitLabel(unit);
}

/**
 * The one vocabulary (E11-S2), and the part of it that can be true in each place.
 *
 * <p>An ingredient, a stock level, a donation and a purchase-order line are all quantities of food,
 * so they take the five physical units. Everything in this application measures food.
 *
 * <p>`SERVINGS` was briefly a sixth, admitted on a recipe's yield and on a dish. It is gone (V80):
 * it counts the people fed rather than the food made, so "Kheer · 40 servings" told an approver
 * nothing about how much kheer and a storekeeper reading it off a work order even less. The idea
 * survives where it belongs — the planner asks for adults, children and seniors and shows a rough
 * plate count — as a head count on a screen, never a unit anybody picks.
 */
export const FOOD_UNITS: readonly string[] = ["KG", "GM", "L", "ML", "PIECES"];

/**
 * What a recipe's yield may be measured in — the same five, because a yield is an amount of food.
 *
 * <p>Kept as its own name rather than folded into {@link FOOD_UNITS} at every call site: the two
 * meant different things until 2026-08-31 and the places that ask for a yield are still asking a
 * different question from the places that ask for a quantity.
 */
export const YIELD_UNITS: readonly string[] = FOOD_UNITS;

/**
 * What a recipe form offers under "Measured in" (T-218): kilos, litres or pieces, and not grams or
 * millilitres.
 *
 * <p>A batch is made by the kilo or the litre. A recipe measured in grams invites the exact mistake
 * T-217 found in the planner from the other side — a number that is right in one unit and a
 * thousand times wrong in its neighbour — and nobody writes a temple recipe as "270000 ml of rasam".
 * The smaller unit stays on offer for the portion, where "350 ml" is exactly how a person says it.
 *
 * <p>Narrower than {@link YIELD_UNITS} on purpose, and not a replacement for it: the database still
 * admits all five, an older recipe measured in grams must still open and save untouched, and the
 * ingredient request's dish lines are a different question. The form adds a recipe's own stored
 * unit back to this list when it is not already on it.
 */
export const RECIPE_MEASURES: readonly string[] = ["KG", "L", "PIECES"];

/**
 * The portion units that fit a recipe measured in `yieldUnit` — the same family and nothing else
 * (T-218): Kg or gm for a recipe in kilos, L or ml for one in litres, pieces for one in pieces.
 *
 * <p>Built on {@link FAMILY}, so there is still one table of what goes with what, and it agrees
 * with {@link convertQuantity}: every unit this returns converts into `yieldUnit`, and nothing else
 * does. `RecipeService` refuses the same mismatch on the server, keyed on `Unit.family()`.
 */
export function portionUnitsFor(yieldUnit: string | null | undefined): readonly string[] {
  const code = (yieldUnit ?? "").toUpperCase();
  const family = FAMILY[code];
  if (family) return [family.large, family.small];
  return code ? [code] : [];
}

/** How many base-family units one of each unit is. Mirrors Unit.baseFactor() and to_base_qty(). */
const BASE_FACTOR: Record<string, number> = { KG: 1000, GM: 1, L: 1000, ML: 1, PIECES: 1 };

/** The bigger and smaller unit of each family. A count has neither. */
const FAMILY: Record<string, { large: string; small: string }> = {
  KG: { large: "KG", small: "GM" },
  GM: { large: "KG", small: "GM" },
  L: { large: "L", small: "ML" },
  ML: { large: "L", small: "ML" },
};

/**
 * A quantity restated in another unit of the same family — 350 ml is 0.35 L, 2 Kg is 2000 gm — or
 * null where the two units measure different things.
 *
 * <p>Built on {@link BASE_FACTOR} and {@link FAMILY} above and nothing else, so that there is one
 * table of what a kilo is. Written for the meal planner (T-217): a recipe measured in litres with a
 * portion of 350 ml was being planned as people × 350 *litres*, a thousand times too much, because
 * the portion's unit was never read.
 *
 * <p>The same unit, or no unit given for either side, comes back unchanged — which is how pieces,
 * a family of one, convert to themselves. A volume into a mass, or pieces into either, is null
 * rather than a guess: without a density there is no honest answer, and the caller says so.
 */
export function convertQuantity(value: number, from: string | null | undefined, to: string | null | undefined): number | null {
  const a = (from ?? "").toUpperCase();
  const b = (to ?? "").toUpperCase();
  if (a === b) return value;
  const fa = FAMILY[a];
  const fb = FAMILY[b];
  if (!fa || !fb || fa.large !== fb.large) return null;
  return (value * BASE_FACTOR[a]) / BASE_FACTOR[b];
}

/**
 * A quantity as a box should show it for typing into: the figure and the unit it is in, promoted to
 * the family's large unit from 1,000 up — 3000 gm is `{ value: 3, unit: "KG" }`, 450 gm stays
 * `{ value: 450, unit: "GM" }` (T-243, rebuilt and re-verified in T-264).
 *
 * <p>Rajeev, 2026-09-19, of the shopping list's order box reading "2792 gm": unreadable (R-SL-1). The
 * box used to hold the stored unit on purpose, and its comment said why — a readout that said "gm"
 * over a figure in kilograms would invite a thousandfold error. That hazard is real and this keeps
 * clear of it the only safe way: the unit printed beside the box is the one returned here, always,
 * and {@link fromEntry} reads what was typed in that same unit. The box and its label cannot
 * disagree because they are one answer.
 *
 * <p>The same threshold as {@link quantity}, and zero stays in its own unit for the same reason.
 * The figure is exact — no rounding — because it is a value somebody may submit unchanged; the
 * toFixed only clears float dust (2.5 × 1000 is exact; 0.3 × 1000 is not quite). Pieces, and any
 * unit this file does not know, come back as they went in.
 */
export function entryQuantity(value: number, unit: string): { value: number; unit: string } {
  const code = (unit ?? "").toUpperCase();
  const family = FAMILY[code];
  if (!family || !Number.isFinite(value)) return { value, unit: code };
  const inBase = value * BASE_FACTOR[code];
  const shown = inBase === 0 ? code : Math.abs(inBase) >= 1000 ? family.large : family.small;
  return { value: Number((inBase / BASE_FACTOR[shown]).toFixed(6)), unit: shown };
}

/**
 * What somebody typed into a box labelled `shownUnit`, back in the unit it is stored in — 3 typed
 * beside "Kg" on a line kept in grams is 3000 (T-243, T-264). The inverse of {@link entryQuantity}.
 *
 * <p>Two units that do not convert (which a box built from entryQuantity cannot produce) leave the
 * figure as typed rather than inventing a factor.
 */
export function fromEntry(typed: number, shownUnit: string, storedUnit: string): number {
  const converted = convertQuantity(typed, shownUnit, storedUnit);
  return converted == null ? typed : Number(converted.toFixed(6));
}

/**
 * A price per one unit restated per another unit of the same family — ₹0.0712 per gm is ₹71.20 per
 * Kg — or null where there is no price or the units do not convert (T-264).
 *
 * <p>The inverse direction of {@link convertQuantity}, and deliberately built on it rather than on a
 * second table: a kilo is a thousand grams, so a price per kilo is a thousand times a price per
 * gram. Kept to four decimal places, which is what a purchase-order line's price column holds
 * (V146) — any finer and the figure sent is not the figure stored.
 *
 * <p>Why it exists: the conductor's ruling for the shopping list (2026-09-19) is that the rate
 * follows the unit the quantity is shown in — "3 Kg" goes with "₹71.20 / Kg", never with a price per
 * gram — and a list price is stored per the ingredient's own unit.
 */
export function pricePer(price: number | null | undefined, perUnit: string, wantedUnit: string): number | null {
  if (price == null || !Number.isFinite(price)) return null;
  const converted = convertQuantity(price, wantedUnit, perUnit);
  return converted == null ? null : Number(converted.toFixed(4));
}

/**
 * A price said the way a person says it: per Kg, per L or per piece — "₹300 / Kg" from ₹0.30 a
 * gram, "₹250 / L" from ₹0.25 a millilitre, "₹4 / piece" — and, for a line bought in packs, per
 * pack first: "₹1,500 / bag · ₹60 / Kg" (T-288, VERIFY-B D-8).
 *
 * <p><b>The one formatter for a rate on any screen.</b> Every screen that prints a price per unit
 * should come through here, so a price reads the same on the create form, the order, the merge
 * screen, the invoice and the ingredient page. Before it, a 500 gm tea pack read "₹250 / 500 gm ·
 * ₹0.50 / gm" beside pepper at "₹800 / Kg" on one sheet: the rate followed the unit the quantity
 * happened to be printed in, and below 1,000 gm that is gm. The conductor's rule (2026-09-19): the
 * rate follows the READABLE unit. A weight is priced per Kg and a volume per L whatever the amount
 * beside it, because a price per gram is a few paise that two places round into a different price
 * and nobody in a market quotes one. The server's PO sheet follows the same rule
 * (`DocumentGenerationService.rateUnit`), so the paper and the screen agree.
 *
 * @param price the price per ONE `perUnit` — as a purchase-order line stores it (four places, V146),
 *   or a vendor's list price per the ingredient's unit. Null or not finite gives null: no price is
 *   said as nothing, never as "₹0".
 * @param perUnit the unit `price` is per: "GM", "KG", "ML", "L" or "PIECES".
 * @param pack the pack the line is bought in, or nothing: `label` is the order form the server
 *   writes, "Bag (25 Kg)", or a plain size, "500 gm"; `quantity` is one pack's size in `perUnit`.
 *   The price per pack is `price × quantity`, and the word it is "per" is the pack's name in lower
 *   case ("bag"), or its size where it has no name ("₹250 / 500 gm") — the vendor page's rule.
 *
 * <p>Each figure is rounded to paise before it is written, because ₹0.0712 × 1000 is not exactly
 * 71.2 in floating point and {@link money} would print the dust as "₹71.20" beside a whole "₹60.00".
 * A count is said in the singular — "/ piece", not "/ pieces" — because a rate is a price for one.
 */
export function readableRate(
  price: number | null | undefined,
  perUnit: string,
  pack?: { label: string; quantity: number } | null
): string | null {
  if (price == null || !Number.isFinite(price)) return null;
  if (pack) return readablePackRate(price * pack.quantity, pack.label, price, perUnit);
  const code = (perUnit ?? "").toUpperCase();
  const said = rateUnit(code);
  const perSaid = convertQuantity(price, said, code) ?? price;
  return `${money(toPaise(perSaid), "INR")} / ${unitLabelFor(1, said)}`;
}

/**
 * {@link readableRate}'s pack form, for a view that holds the price per pack as its own figure —
 * the vendor's quoted "₹1,450 a bag", an invoice's amount over its count — rather than as the price
 * per unit times the pack (T-293). Working the pack price back out of a four-place price per gram
 * can miss by paise: ₹1,000 for a 3 Kg bag is ₹0.3333 a gram, and ₹0.3333 × 3,000 is ₹999.90. So
 * the figure somebody quoted is printed as they quoted it, and only the rate after the dot is
 * derived. Same words as {@link readableRate}: "₹1,500 / bag · ₹60 / Kg".
 *
 * <p>Either half may be missing, and the line says what it has: a pack price with no unit price is
 * "₹1,500 / bag" (the vendor page's supply quoted only per bag), a unit price with no pack price is
 * "₹60 / Kg", and neither is null — never "₹0".
 *
 * @param packLabel the pack as any view holds it: the order form "Bag (25 Kg)", the vendor page's
 *   chip "Bag = 25 Kg", a bare word "bag", or a plain size "500 gm". See {@link ratePackWord}.
 */
export function readablePackRate(
  packPrice: number | null | undefined,
  packLabel: string,
  unitPrice: number | null | undefined,
  perUnit: string
): string | null {
  const rate = readableRate(unitPrice, perUnit);
  if (packPrice == null || !Number.isFinite(packPrice)) return rate;
  const perPack = `${money(toPaise(packPrice), "INR")} / ${ratePackWord(packLabel)}`;
  return rate === null ? perPack : `${perPack} · ${rate}`;
}

/**
 * The unit a rate is said per: Kg for a price kept per gram, L for one kept per millilitre, and the
 * unit itself otherwise (T-288's rule, T-293). Exported for the price boxes that are typed per Kg
 * and the price-trend tip, so a box and the words beside it name the same unit.
 */
export function rateUnit(unit: string): string {
  const code = (unit ?? "").toUpperCase();
  return FAMILY[code]?.large ?? code;
}

/**
 * The word a price is "per" for a pack: "bag" for "Bag (25 Kg)" (the order form, T-259) or "Bag = 25
 * Kg" (the pack chip, T-252), and the size itself for a pack with no name ("500 gm"). Before T-293
 * each view read its own label format with its own copy of this; one copy reads both, so a pack is
 * called the same thing on every screen.
 */
export function ratePackWord(label: string): string {
  const named = /^(.+) \((.+)\)$/.exec(label);
  if (named) return named[1].toLowerCase();
  const at = label.indexOf(" = ");
  return at > 0 ? label.slice(0, at).toLowerCase() : label;
}

/**
 * Whether the stock amount written beside a pack count only says the pack's label again: one pack
 * with no name, whose label is its size. "1 × 500 gm" over "500 gm" says the amount twice, so the
 * second one goes (VERIFY2-C, T-299 on the Deliveries screen; T-302 on the order, the invoice and
 * the invoice form; T-303 made it this one rule for all of them, and for the Billed qty box).
 *
 * <p>It counts packs, not text. A label written "0.5 Kg" over a "500 gm" total is a repeat too,
 * although the two strings differ. "2 × 500 gm" keeps its "1 Kg", because the total is new
 * information (the conductor's ruling). A named pack, "1 × Bag (25 Kg)", keeps its "25 Kg": the
 * name is the word before " (", as `packWord` on the Deliveries screen and the order document take
 * it, and the size in the bracket is not the whole of what the line says.
 *
 * <p>`packs` is the count as the caller writes it. A view that prints a count to 3 places passes it
 * rounded to 3 places, so the line under "1 × 500 gm" never goes for a count shown as "1.2" or stays
 * for one shown as "1". A box passes what was typed: 1.0004 packs of 500 gm typed on the Deliveries
 * screen still shows "(500.2 gm)", because the box says 1.0004 and not 1. The comparison is exact
 * for that reason; rounding is the display's business, and each display already does it.
 */
export function repeatsPack(label: string, packs: number): boolean {
  return label.indexOf(" (") <= 0 && packs === 1;
}

function toPaise(amount: number): number {
  return Math.round(amount * 100) / 100;
}

/**
 * A quantity rounded the way a person rounds it — to a step that grows with the size of the number.
 *
 * <p>Nobody weighs 134.4 gm of cardamom; they weigh 135. Nobody measures 10.08 Kg of rice; they
 * measure 10. But 4.7 gm of camphor is not 5 — at that size half a gram is the honest step. So the
 * step is not fixed, it climbs: tenths below one, halves to ten, ones to a hundred, fives to a
 * thousand, tens above.
 *
 * <p>Bounded by the step and never compounding, because this runs once, last, on a value that has
 * already been through every calculation it is going to. Round then compute and the errors stack;
 * compute then round and they cannot.
 */
function roundAsAPersonWould(value: number): number {
  const size = Math.abs(value);
  const step = size < 1 ? 0.1 : size < 10 ? 0.5 : size < 100 ? 1 : size < 1000 ? 5 : 10;
  // toFixed mops up the float dust a division and multiplication leave behind: without it
  // 0.3 comes back as 0.30000000000000004.
  return Number((Math.round(value / step) * step).toFixed(3));
}

// The number and its unit, as one phrase. It goes through unitLabelFor rather than reading
// UNIT_LABEL directly for two reasons: the label has to agree with the number in front of it
// (T-108 — "1 piece", not "1 pieces"), and this used to hold its own fallback for a unit the map
// has never heard of, which printed the shouted enum name where unitLabel() would have printed a
// word. One renderer, one fallback.
function say(value: number, unit: string, maxDecimals: number): string {
  return `${value.toLocaleString("en-IN", { maximumFractionDigits: maxDecimals })} ${unitLabelFor(value, unit)}`;
}

/**
 * A quantity, in whichever unit of its family a person would actually say it in.
 *
 * <p>Stock is held in the ingredient's own unit, and for something kept in millilitres that means
 * the store room reads "173542 ml of ghee". Nobody says that. Equally, a scaled recipe asking for
 * 0.6 Kg of rice is asking for 600 grams, and writing it the first way makes a cook do arithmetic
 * over a hot stove. So the number is converted to its family's base and then shown in the large
 * unit from 1000 up and the small unit below — in both directions, which is the half that was
 * missing. The stored value never changes.
 *
 * <p>This is the **ledger** form: the figure is exact. Use it wherever somebody reconciles or is
 * audited against the number — a stock balance, a movement row, a batch, a goods receipt, an
 * invoice line. Rounding those would stop the rows visibly adding up to the balance above them,
 * which is the one thing the inventory screen exists to show (E3-S1).
 *
 * <p>For anything somebody weighs or buys against, use {@link cooksQuantity}.
 */
export function quantity(value: number | null | undefined, unit: string): string {
  return render(value, unit, false);
}

/**
 * The same quantity, rounded the way a cook would round it — see {@link roundAsAPersonWould}.
 *
 * <p>The **cook's** form. Use it wherever the number is something a person acts on with their
 * hands: a recipe line, a scaled recipe, a planner target, a job card, a work order, a shopping list,
 * a shortfall. 10.08 Kg and 10 Kg are the same sack of rice, and the second is the one to print.
 */
export function cooksQuantity(value: number | null | undefined, unit: string): string {
  return render(value, unit, true);
}

/**
 * A recipe's rough cost, said with what it buys: "₹8,000 per batch (270 L)" (T-231).
 *
 * <p>The field was labelled "Indicative cost" and shown as a bare "₹8,000", which left the reader
 * to guess whether that was per plate, per kilo or per pot. It is the cost of one batch, the amount
 * the recipe's "This recipe makes" says it makes (Rajeev, 2026-09-18), so the batch size is printed
 * beside it, in the same rounded form the recipe page uses for that quantity, and the money in the
 * lakh grouping every other rupee figure uses.
 *
 * <p>No batch size to name — a quantity of zero or none — and it says "per batch" and stops, rather
 * than printing an empty bracket. No cost at all is "—", as {@link money} has it.
 */
export function batchCost(
  cost: number | null | undefined,
  batchQty: number | null | undefined,
  batchUnit: string | null | undefined,
  currency = "INR",
): string {
  if (cost == null) return "—";
  const size = batchQty != null && Number.isFinite(batchQty) && batchQty > 0
    ? ` (${cooksQuantity(batchQty, batchUnit ?? "")})`
    : "";
  return `${money(cost, currency)} per batch${size}`;
}

function render(value: number | null | undefined, unit: string, forCooking: boolean): string {
  // A quantity nobody has is not a zero — an em dash says "no answer" where 0 would say "none left".
  if (value == null || !Number.isFinite(value)) {
    return "—";
  }

  const code = (unit ?? "").toUpperCase();
  const family = FAMILY[code];

  // Pieces are whole things counted in themselves — three idlis is three idlis, with no larger
  // sibling to be promoted into.
  if (!family) {
    return say(forCooking ? Math.round(value) : value, code, 3);
  }

  const inBase = value * (BASE_FACTOR[code] ?? 1);
  // Zero is said in the unit the thing is actually kept in, not in the family's small one. The
  // step-down rule exists to stop a fraction being printed — 0.6 Kg is 600 gm — and zero has no
  // fraction to avoid, so all the rule did was change the subject: the curd item, kept in litres
  // with a reorder level of 15 L, read "0 ml" on hand, which invites the reader to work out how
  // many millilitres are missing before they notice the answer is fifteen litres. Found on
  // staging, 2026-09-09. The em dash above is a different case and is untouched — a null is "we
  // have no figure", a zero is "we have none of it", and the two must go on reading differently.
  const empty = inBase === 0;
  let display = empty ? code : Math.abs(inBase) >= 1000 ? family.large : family.small;
  let shown = inBase / BASE_FACTOR[display];

  if (forCooking) {
    shown = roundAsAPersonWould(shown);
    // Rounding can carry a figure up over the line it was just measured against: 999.6 gm rounds
    // to 1000 gm, which is a kilo and should say so.
    if (display === family.small && Math.abs(shown) >= 1000) {
      display = family.large;
      shown = shown / 1000;
    }
  }

  return say(shown, display, forCooking ? 2 : 3);
}

/**
 * What to call a date that has already gone.
 *
 * <p>The server flags anything at or inside the expiry horizon as "expiring soon", which is right
 * for a sack that goes off on Friday and wrong for one that went off last week — and last week is
 * the case that actually matters, because it is already in the store being cooked from. The stored
 * flag says "act on this"; only the date can say which of the two it is.
 */
export function expiryWord(expiry: string | null | undefined, today = todayIso()): "expired" | "soon" {
  return expiry != null && expiry < today ? "expired" : "soon";
}


/**
 * The warning on a vendor whose contract is running out — "Contract ended 12 Mar 2026",
 * "Contract ends in 5 days".
 *
 * <p>Written the same way as an expiring batch: the server decides *whether* this is worth saying,
 * using the temple's own contract horizon (Settings → Warnings, thirty days unless it has been
 * changed), and this decides only the words. That way the number lives in exactly one place, and a
 * screen cannot quietly disagree with the flag it was handed.
 *
 * <p>It warns and nothing more. A vendor past their contract end date is still active, still in
 * every picker, and still the preferred source for whatever they supply — a date somebody set months
 * ago should never be what silently changes tomorrow's shopping.
 */
export function contractWarning(contractEndDate: string, today = todayIso()): string {
  const days = wholeDaysBetween(today, contractEndDate);
  if (days < 0) return `Contract ended ${dateWithYear(contractEndDate)}`;
  if (days === 0) return "Contract ends today";
  return `Contract ends in ${days} ${days === 1 ? "day" : "days"}`;
}

/**
 * What to say about a needed-by date that leaves this vendor little or no notice — or none at all.
 *
 * <p>It warns and never refuses, and the difference is the point. A temple that genuinely needs a
 * sack of rice tomorrow should be able to ask for it tomorrow, and a rule that made that impossible
 * would only teach people to write a date they do not mean. Only a date behind the order itself is
 * refused, and that is refused on the server, because it is not a request anybody can act on. What
 * *is* enforced — on Mark sent, with an override — is D-25's cutoff, and that lives on the server
 * too (KMS-400148).
 *
 * <p><strong>`leadTimeDays` is the vendor's own number, and this function used to invent one.</strong>
 * It compared every date against a hard-coded two days — *"Sooner than the 2 days a vendor usually
 * gets"* — which was honest before T-090 stored a real figure per vendor and ingredient, and became
 * a second answer to the question T-137 exists to make single. Two numbers on one screen is the
 * drift, not the fix: the form would say "2 days" beside a server that had refused the send against
 * five. So the caller passes the governing lead time the server sent it, and this says what that
 * vendor actually asked for.
 *
 * <p>**Null `leadTimeDays` is silence about notice, not two days.** Rajeev's rule for a vendor who
 * has never given a lead time is that there is no cutoff at all — no nudge, no warning, nothing held
 * against anybody — so with nothing recorded the only thing left to say is that the date itself has
 * gone. Inventing a promise on a supplier's behalf is what the Golden Rule forbids.
 *
 * <p>Zero is a real answer and means cash and carry, so it warns about nothing: goods come back in
 * the same van as the person.
 *
 * <p>`leadTimeDays` is **required and nullable, never optional with a default**, which is the
 * convention `lib/api.ts` argues for at length and pays for here: a default would let a caller that
 * has a vendor's figure forget to pass it and get silence that looks exactly like a vendor who has
 * never been asked. Every call site has to say which of the two it means.
 *
 * <p>Null when there is nothing to say, so a caller renders the ordinary hint instead.
 */
export function leadTimeWarning(
  neededBy: string,
  leadTimeDays: number | null,
  today = todayIso()
): string | null {
  const days = wholeDaysBetween(today, neededBy);
  if (days < 0) return "That day has already gone";
  if (leadTimeDays == null || days >= leadTimeDays) return null;
  return leadTimeDays === 1
    ? "Sooner than the 1 day’s notice this vendor asked for"
    : `Sooner than the ${leadTimeDays} days’ notice this vendor asked for`;
}

/**
 * "2026-03-12" → "12 Mar 2026". Short enough for a badge, and carrying the year, which
 * {@link shortDate} leaves off — a contract that ended is often one that ended a year ago.
 */
export function dateWithYear(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString("en-GB", {
    day: "numeric",
    month: "short",
    year: "numeric",
  });
}

/**
 * An instant off the server — "20 Aug 2026, 14:30" — in the temple's own clock.
 *
 * <p>The one moment formatter. Four screens each carried a private copy of this and a fifth was
 * borrowing the platform board's, which spelled the month out in full and put the word "at" in the
 * middle: "1 September 2026 at 11:09" sitting a column away from "1 Sept 2026". A date is written
 * one way in this application, so a moment is that same date with a clock on the end of it.
 *
 * <p>The clock is the temple's and not the reader's, for the reason {@link todayIso} gives: an
 * entry made at 09:20 in Bengaluru reads as the previous evening to anybody looking from further
 * west, which puts it on the wrong day and out of order against everything else the temple dates.
 * Seconds are left off, as everywhere else (INT-8) — a note was written at 18:08, not at 18:08:41.
 */
export function moment(iso: string): string {
  return new Date(iso).toLocaleString("en-GB", {
    timeZone: templeZone(),
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/**
 * The same instant reduced to the day it happened on in the temple — "20 Aug 2026", written like
 * {@link dateWithYear}, because it is one.
 *
 * <p>Deliberately not `iso.slice(0, 10)`. An `Instant` is UTC, so the first ten characters are the
 * UTC day: everything between midnight and 05:30 IST lands on the date before. This codebase has
 * warned about that fault in three separate comments and fixed it twice, which is reason enough for
 * it to exist in one place where it can only be got right once.
 */
export function templeDay(iso: string): string {
  return new Date(iso).toLocaleDateString("en-GB", {
    timeZone: templeZone(),
    day: "numeric",
    month: "short",
    year: "numeric",
  });
}

/**
 * A span of days the way a person says it — "12 to 14 August", "30 September to 2 October",
 * "12 August", "12 August (half day)" (T-194).
 *
 * <p>The one copy. Before this the leave rows on My profile and on the approver's Leave screen printed
 * "2026-08-12 to 2026-08-14", while the question T-184 put in front of the same person, one click
 * later, asked about "12 to 14 August" from a helper private to the profile page. Two ways of writing
 * one fortnight on one screen is the drift this file exists to stop, so the question and the rows now
 * both come from here. Day first, month spelled out, as everywhere else in the application. The month
 * is said once when both days share it, because that is how it is said aloud.
 *
 * <p>**The year rule.** The year is written only when either day falls outside the temple's current
 * year. When both days share a year it is written once, at the end — "12 to 14 August 2025". When they
 * do not, each day carries its own — "30 December 2026 to 2 January 2027" — because "30 December to 2
 * January 2027" reads as though both were in 2027. T-184's helper never wrote a year, and that was
 * right for what it described: leave that can still be withdrawn has not begun, so it is always about
 * the weeks ahead. A leave list is not. It holds last year's leave too, and "12 to 14 August" under a
 * row from 2025 names a week that has not happened yet. A range inside this year keeps the short form,
 * which is still what nearly every row on either screen is.
 *
 * <p>"The current year" is the **temple's**, from {@link todayIso}, never the device's: on the evening
 * of 31 December in California it is already the new year in the kitchen, and the rows should read the
 * way the people there would read them.
 *
 * <p>**The days are calendar dates, never instants.** They are split out of the "YYYY-MM-DD" by hand
 * and named in UTC, so no reader's zone takes part at any point. `new Date("2026-08-01")` is UTC
 * midnight, which is still 31 July for anybody west of Greenwich, and a leave list is exactly the place
 * a manager reads from somewhere else.
 *
 * <p>`halfDay` is required rather than defaulted, for the reason {@link leadTimeWarning} gives: a
 * default is what the next caller forgets, and a forgotten half day reads as a whole one. A half day is
 * one day, so only `fromDate` is named, as T-184 wrote it. Anything that is not a date comes back as it
 * arrived, rather than as "Invalid Date".
 */
export function dayRange(fromIso: string, toIso: string, halfDay: boolean, today = todayIso()): string {
  const from = calendarDay(fromIso);
  const to = calendarDay(toIso);
  if (!from || !to) return fromIso === toIso || halfDay ? fromIso : `${fromIso} to ${toIso}`;

  const thisYear = today.slice(0, 4);
  const withYear = from.year !== thisYear || to.year !== thisYear;
  const whole = (d: CalendarDay) => (withYear ? `${d.day} ${d.month} ${d.year}` : `${d.day} ${d.month}`);

  if (halfDay) return `${whole(from)} (half day)`;
  if (fromIso === toIso) return whole(from);
  if (from.year !== to.year) return `${whole(from)} to ${whole(to)}`;
  if (from.month === to.month) return `${from.day} to ${whole(to)}`;
  return `${from.day} ${from.month} to ${whole(to)}`;
}

type CalendarDay = { day: number; month: string; year: string };

/**
 * "2026-08-01" → its day, its month's name and its year, with no time zone anywhere in the working.
 * The name comes from `Intl` in UTC, over a moment built in UTC, so it is the same month wherever it
 * runs; en-GB for the reason {@link longDate} gives.
 */
function calendarDay(iso: string): CalendarDay | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso ?? "");
  if (!m) return null;
  const at = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
  return {
    day: Number(m[3]),
    month: new Intl.DateTimeFormat("en-GB", { month: "long", timeZone: "UTC" }).format(at),
    year: m[1],
  };
}

/** Calendar days from one "YYYY-MM-DD" to another, counted in UTC so no time zone can shift one. */
export function wholeDaysBetween(fromIso: string, toIso: string): number {
  const day = 24 * 60 * 60 * 1000;
  return Math.round((Date.parse(`${toIso}T00:00:00Z`) - Date.parse(`${fromIso}T00:00:00Z`)) / day);
}
