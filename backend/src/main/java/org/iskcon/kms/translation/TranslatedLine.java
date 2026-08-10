package org.iskcon.kms.translation;

import java.math.BigDecimal;

/** A translated ingredient line for the API view: translated name, untranslated amount. */
public record TranslatedLine(String name, BigDecimal quantity, String unit) {
}
