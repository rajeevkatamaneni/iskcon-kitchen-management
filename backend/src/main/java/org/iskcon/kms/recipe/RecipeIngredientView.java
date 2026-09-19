package org.iskcon.kms.recipe;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One rendered ingredient line: the ingredient's identity, how much of it, and how it is prepared.
 *
 * <p>{@code preparationNote} is null when the line has none. It is printed after the name as
 * "Green chilli · slit" everywhere the line prints (R-DUP-1); {@link #displayName} is that form.
 */
public record RecipeIngredientView(
		UUID ingredientId,
		String ingredientName,
		BigDecimal quantity,
		String unit,
		String preparationNote) {

	/** The name as a cook reads it: "Green chilli · slit", or just the name with no note. */
	public String displayName() {
		return RecipeIngredientView.withPreparation(ingredientName, preparationNote);
	}

	/**
	 * A name and its preparation note in the one printed form, "Green chilli · slit" (R-DUP-1).
	 *
	 * <p>One place, because the line is printed in five — the recipe page, the scaled view, the job
	 * card, the recipe PDF and the translation — and five hand-written separators is how one of them
	 * ends up with a comma, which is the very form the library used and that made "Green chilli,
	 * slit" look like an ingredient.
	 */
	public static String withPreparation(String name, String preparationNote) {
		if (preparationNote == null || preparationNote.isBlank()) {
			return name;
		}
		return name + " · " + preparationNote.strip();
	}
}
