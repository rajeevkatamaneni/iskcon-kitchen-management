package org.iskcon.kms.ingredient;

import java.math.BigDecimal;

/**
 * Every unit anything in this system is measured in — one vocabulary, per E11-S2.
 *
 * <p>Stored as the enum name (a CHECK mirrors the admitted subset on each column). The {@code label}
 * is how the unit is shown to a person — "Kg", not "KILOGRAMS" — and the family lets the display
 * layer move between grams and kilograms, millilitres and litres, without the stored value changing.
 *
 * <p>There are two ways to ask for that word and the difference matters: {@link #label()} names the
 * unit where nothing is being counted, and {@link #label(BigDecimal)} gives the word that agrees
 * with a figure about to be printed in front of it — "1 piece", but "3 pieces". Reach for the second
 * whenever a number is in your hand; the first said "1 pieces" on every screen and every printed
 * sheet in the product until T-108 and T-144.
 *
 * <p>This used to be two enums. {@code YieldUnit} named the same litre {@code LITRES} while this one
 * called it {@code L}, so a recipe and the store room it draws from disagreed about the word, and a
 * recipe could not be measured in grams or millilitres at all. They are one list now.
 *
 * <p><strong>Everything here measures food.</strong> There is no unit for people. A recipe yields
 * kilos, litres or pieces; a request asks for the same; a dish is made in the same. "Servings" was
 * briefly a member of this list and is gone (V80): it counts the people fed rather than the food
 * made, so a line reading "Kheer · 40 servings" said nothing anybody could weigh, pour or hand over.
 *
 * <p>The idea survives where it belongs — the meal planner asks how many adults, children and
 * seniors are expected and shows a rough plate count — but as a head count on a screen, never as a
 * unit anybody selects and never as a measure anybody stores.
 */
public enum Unit {

	KG("Kg", Family.MASS, 1_000),
	GM("gm", Family.MASS, 1),
	L("L", Family.VOLUME, 1_000),
	ML("ml", Family.VOLUME, 1),

	// The one unit in the vocabulary with a singular to get wrong. See label(BigDecimal).
	PIECES("pieces", "piece", Family.COUNT, 1);

	/** Whether two units can be converted into each other (gm↔Kg) or not (pieces). */
	public enum Family {
		MASS,
		VOLUME,
		COUNT
	}

	private final String label;
	private final String singular;
	private final Family family;
	private final int baseFactor;

	Unit(String label, Family family, int baseFactor) {
		this(label, label, family, baseFactor);
	}

	Unit(String label, String singular, Family family, int baseFactor) {
		this.label = label;
		this.singular = singular;
		this.family = family;
		this.baseFactor = baseFactor;
	}

	/**
	 * How the unit is written where no number is being said beside it — a column that names an
	 * ingredient's unit, a dropdown option, the "/ Kg" after a price. Plural, always, and correct:
	 * "Unit: pieces" is a heading, not a count.
	 *
	 * <p>Where a figure <em>is</em> printed in front of it, use {@link #label(BigDecimal)} instead.
	 */
	public String label() {
		return label;
	}

	/**
	 * The label agreeing with the number printed in front of it — "1 piece", but "3 pieces" and
	 * "1 Kg".
	 *
	 * <p>This is the repair for <strong>"1 pieces"</strong> (T-144), which Rajeev saw on a purchase
	 * order and which the printed job card, recipe card and work order went on saying for a wave
	 * after the screens stopped. The cause is worth stating because it is not obvious from the
	 * symptom: {@link #label()} holds one word per unit, and a word that has never been shown the
	 * number it is going to sit beside cannot agree with it. {@link Quantities} had the figure in
	 * its hand and threw it away before choosing the word.
	 *
	 * <p>The count is a <strong>required</strong> argument and deliberately not an optional one. An
	 * optional count is a count the next caller forgets, and the forgotten case renders the exact
	 * defect this exists to remove.
	 *
	 * <p><strong>Only a count has a singular.</strong> {@code Kg}, {@code gm}, {@code L} and
	 * {@code ml} are abbreviations of a mass or a volume, and an abbreviation takes no "s" in Indian
	 * English any more than in British: one kilo of rice is written "1 Kg", never "1 Kgs". So every
	 * unit but {@link #PIECES} declares one word and is written the same at every number — which is
	 * the common case, and therefore what the three-argument constructor above gives you for free.
	 * The next counted unit somebody adds — crates, sacks, bundles — is one extra word on its own
	 * line rather than a second place to remember a rule.
	 *
	 * <p>This mirrors {@code unitLabelFor} in {@code frontend/lib/format.ts} exactly, including the
	 * two edges below, and the vector tables in {@code QuantitiesTest} and
	 * {@code __tests__/quantities.test.ts} hold both files to the same strings.
	 *
	 * <p><strong>Compared on the absolute value</strong>, so a reversal of one stool reads
	 * "-1 piece" rather than "-1 pieces".
	 *
	 * <p><strong>Compared with {@code compareTo} and not {@code equals}</strong>, which is the
	 * whole reason this is three lines rather than one. A quantity arrives from JDBC scaled to its
	 * column, so a genuine one is {@code 1.000}, and {@code BigDecimal.equals} compares the scale as
	 * well as the value and would answer false on the scale alone. {@link Quantities#displayUnit}
	 * four files along carries the same note about {@code signum()} for the same reason; this
	 * project has been caught by that once already.
	 *
	 * @param count the figure that will be printed immediately in front of this label — the figure
	 *     as it will be <em>shown</em>, after any rounding and any promotion between gm and Kg, not
	 *     the raw stored value. 1.2 pieces is printed "1 piece" on a job card because the cook's
	 *     form rounds it to a whole thing first; it is printed "1.2 pieces" on a ledger row, which
	 *     does not round, and both are right.
	 */
	public String label(BigDecimal count) {
		if (count == null) {
			return label;
		}
		return count.abs().compareTo(BigDecimal.ONE) == 0 ? singular : label;
	}

	public Family family() {
		return family;
	}

	/** How many base-family units one of this unit is (1 Kg = 1000 base grams; pieces = 1). */
	public int baseFactor() {
		return baseFactor;
	}

}
