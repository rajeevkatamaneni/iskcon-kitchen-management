package org.iskcon.kms.recipe;

/**
 * What a recipe's base yield is measured in.
 *
 * <p>Servings and litres came from RM 2019, where both occur: a sweet made in litres, a meal
 * counted in servings. Kilograms and pieces came from the shared recipe library, where 1,619
 * recipes yield in kilos and 839 in pieces — and not one in servings. Forcing a 12 Kg pickle or
 * 300 idlis into litres would be a lie the printed recipe card then repeats.
 *
 * <p>Scaling (E2-S3) is a ratio, so the unit only has to be consistent between base and target and
 * is never converted. That is why widening this enum touched nothing that computes.
 */
public enum YieldUnit {
	SERVINGS,
	LITRES,
	KG,
	PIECES;

	/** How it reads to a person: "kg", "pieces", "litres". */
	public String label() {
		return name().toLowerCase();
	}
}
