package org.iskcon.kms.translation;

import java.util.List;
import java.util.UUID;

/** A recipe rendered into another language (E2-S6). Quantities/units stay as they are. */
public record TranslatedRecipeView(
		UUID recipeId,
		String language,
		String provider,
		String name,
		String categoryName,
		List<TranslatedLine> ingredients,
		List<String> method) {
}
