package org.iskcon.kms.inventory;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** One ingredient's requirement for a consumption, and the batches drawn to meet it. */
public record PlannedLine(
		UUID ingredientId,
		String ingredientName,
		BigDecimal required,
		String unit,
		List<PlannedDraw> draws) {
}
