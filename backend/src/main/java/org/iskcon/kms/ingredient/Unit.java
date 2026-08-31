package org.iskcon.kms.ingredient;

import java.util.Arrays;
import java.util.List;

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
 * <p><strong>{@link #SERVINGS} is not a physical measure</strong> and is the reason
 * {@link #measuresFood()} exists. It counts people fed, so it converts into nothing, and an
 * ingredient, a stock movement or an issued quantity can never be expressed in it. Only a recipe's
 * yield may be. It survives rather than being tidied away because all 57 seeded recipes yield in it,
 * none of them carries a per-head portion, and the planner's only route from a head count to a
 * cooking target for such a recipe reads this constant by name.
 */
public enum Unit {

	KG("Kg", Family.MASS, 1_000),
	GM("gm", Family.MASS, 1),
	L("L", Family.VOLUME, 1_000),
	ML("ml", Family.VOLUME, 1),
	PIECES("pieces", Family.COUNT, 1),

	/**
	 * People fed, not food weighed. Admitted on a recipe's yield and nowhere else — see the class
	 * note. Its base factor is 1 so that nothing has to special-case the arithmetic; its family is
	 * its own so that nothing can convert it into anything.
	 */
	SERVINGS("servings", Family.SERVINGS, 1);

	/** Whether two units can be converted into each other (gm↔Kg) or not (pieces, servings). */
	public enum Family {
		MASS,
		VOLUME,
		COUNT,
		SERVINGS
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

	/**
	 * Whether this unit measures food rather than the people eating it.
	 *
	 * <p>True for everything except {@link #SERVINGS}. This is what every ingredient, stock and
	 * issue dropdown filters on, and what keeps a servings figure out of the store room's ledger.
	 */
	public boolean measuresFood() {
		return family != Family.SERVINGS;
	}

	/** The units food is measured in — the whole list except {@link #SERVINGS}. */
	public static List<Unit> measuringFood() {
		return Arrays.stream(values()).filter(Unit::measuresFood).toList();
	}
}
