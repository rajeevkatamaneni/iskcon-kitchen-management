package org.iskcon.kms.occasion;

import java.util.UUID;

/** A festival occasion in the catalog (E4-S2). */
public record OccasionView(
		UUID id,
		String name,
		OccasionType type,
		String matchText,
		Integer fixedMonth,
		Integer fixedDay,
		Integer defaultServings,
		String notes,
		boolean seeded) {
}
