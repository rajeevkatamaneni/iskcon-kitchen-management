package org.iskcon.kms.occasion;

import java.time.LocalDate;
import java.util.UUID;

/**
 * An occasion resolved to a concrete date within a queried range (E4-S2) — what the planner shows
 * and what drives the festival day-type (E4-S4).
 */
public record ResolvedOccasion(
		UUID occasionId,
		String name,
		LocalDate date,
		Integer defaultServings,
		OccasionType type) {
}
