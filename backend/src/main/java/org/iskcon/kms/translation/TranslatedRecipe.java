package org.iskcon.kms.translation;

import java.util.List;

/**
 * The translatable content of a recipe (E2-S6): everything that is words, none of what is numbers.
 * Ingredient names are in the recipe's line order; quantities and units are never translated and so
 * are not here. Cached as JSONB per (recipe, version, language).
 */
public record TranslatedRecipe(
		String name,
		String categoryName,
		List<String> ingredientNames,
		List<String> method,
		String provider) {
}
