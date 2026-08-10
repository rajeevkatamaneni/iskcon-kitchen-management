package org.iskcon.kms.recipe;

/**
 * What a recipe's base yield is measured in. Both occur in RM 2019: a sweet made in litres, a
 * meal counted in servings. Scaling (E2-S3) is a ratio, so the unit only has to be consistent
 * between base and target, never converted.
 */
public enum YieldUnit {
	SERVINGS,
	LITRES
}
