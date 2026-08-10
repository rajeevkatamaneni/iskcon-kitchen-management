package org.iskcon.kms.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A draw from one batch: how much comes out of it. {@code movementId} is null on a preview and set to
 * the recorded consumption movement once committed.
 */
public record PlannedDraw(
		UUID batchId,
		BigDecimal quantity,
		String unit,
		LocalDate expiryDate,
		UUID movementId) {
}
