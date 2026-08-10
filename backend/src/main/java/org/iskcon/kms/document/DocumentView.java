package org.iskcon.kms.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A generated document's record, for polling status and (when READY) fetching the file. */
public record DocumentView(
		UUID id,
		String kind,
		UUID recipeId,
		String language,
		BigDecimal targetYield,
		String status,
		String error,
		Instant createdAt,
		Instant readyAt) {
}
