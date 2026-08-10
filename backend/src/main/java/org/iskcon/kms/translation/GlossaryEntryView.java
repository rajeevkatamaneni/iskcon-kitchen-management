package org.iskcon.kms.translation;

import java.util.UUID;

/** A glossary override: the preferred translation of a culinary term in one language. */
public record GlossaryEntryView(UUID id, String language, String sourceTerm, String targetTerm) {
}
