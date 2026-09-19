package org.iskcon.kms.translation;

import java.math.BigDecimal;

/**
 * A translated ingredient line for the API view: translated name, untranslated amount.
 *
 * <p>{@code preparationNote} is the line's note ("slit", "halved"), or null when it has none
 * (R-DUP-1). See {@code RecipeTranslationService#view} for which language it is in.
 */
public record TranslatedLine(String name, String preparationNote, BigDecimal quantity, String unit) {
}
