package org.iskcon.kms.ingredient;

/**
 * The units an ingredient can be measured in, fixed per RM 2019.
 *
 * <p>Stored as the enum name in {@code ingredients.canonical_unit} (a CHECK mirrors this set). The
 * {@code label} is how the unit is shown to a person — "Kg", not "KILOGRAMS" — and the metric
 * family lets the display layer promote grams to kilograms and millilitres to litres (E2-S3)
 * without the stored value changing.
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
