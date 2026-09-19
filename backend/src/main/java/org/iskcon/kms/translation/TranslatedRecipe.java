package org.iskcon.kms.translation;

import java.util.List;

/**
 * The translatable content of a recipe (E2-S6): everything that is words, none of what is numbers.
 * Ingredient names are in the recipe's line order; quantities and units are never translated and so
 * are not here. Cached as JSONB per (recipe, version, language).
 *
 * <p>{@code preparationNotes} runs beside {@code ingredientNames}, one per line in the same order,
 * with null where the line has no note ("slit", "halved" — R-DUP-1). It is null as a whole in a
 * translation cached before notes existed; {@link #preparationNote} reads it either way.
 */
public record TranslatedRecipe(
		String name,
		String categoryName,
		List<String> ingredientNames,
		List<String> method,
		String provider,
		List<String> preparationNotes) {

	/**
	 * The translated note for line {@code index}, or {@code fallback} (the note as the temple wrote
	 * it) when this translation has none for that line — an older cached row, or a line past its end.
	 * A note that was translated is never replaced by the English one.
	 */
	public String preparationNote(int index, String fallback) {
		if (preparationNotes != null && index < preparationNotes.size() && preparationNotes.get(index) != null) {
			return preparationNotes.get(index);
		}
		return fallback;
	}
}
