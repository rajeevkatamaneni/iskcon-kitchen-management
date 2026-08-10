package org.iskcon.kms.meal;

import java.util.List;

/**
 * Whether planning a recipe on a date raises an Ekadashi warning (E4-S6). The planner calls this
 * before saving: if {@code isEkadashi} and not {@code compatible}, it shows a confirmation naming the
 * offending ingredients, and only then saves with acknowledgment.
 */
public record EkadashiCheck(
		boolean isEkadashi,
		boolean compatible,
		List<String> offendingIngredients) {
}
