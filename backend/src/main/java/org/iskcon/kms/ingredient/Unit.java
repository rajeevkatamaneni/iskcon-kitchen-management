package org.iskcon.kms.ingredient;


/**
 * Every unit anything in this system is measured in — one vocabulary, per E11-S2.
 *
 * <p>Stored as the enum name (a CHECK mirrors the admitted subset on each column). The {@code label}
 * is how the unit is shown to a person — "Kg", not "KILOGRAMS" — and the family lets the display
 * layer move between grams and kilograms, millilitres and litres, without the stored value changing.
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
	PIECES("pieces", Family.COUNT, 1);

	/** Whether two units can be converted into each other (gm↔Kg) or not (pieces). */
	public enum Family {
		MASS,
		VOLUME,
		COUNT
	}

	private final String label;
	private final Family family;
	private final int baseFactor;

	Unit(String label, Family family, int baseFactor) {
		this.label = label;
		this.family = family;
		this.baseFactor = baseFactor;
	}

	public String label() {
		return label;
	}

	public Family family() {
		return family;
	}

	/** How many base-family units one of this unit is (1 Kg = 1000 base grams; pieces = 1). */
	public int baseFactor() {
		return baseFactor;
	}

}
