package org.iskcon.kms.meal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;

/**
 * Add or change a kind of meal (E4-S7). A null {@code defaultReadyTime} is deliberate, not missing
 * input: it makes the kind ask for a time every time it is planned.
 */
public record CreateMealKindRequest(
		@NotBlank @Size(max = 80) String name,
		int sortOrder,
		LocalTime defaultReadyTime,
		boolean needsClient,
		boolean needsVenue,
		/** Meals of this kind must say what the food is for (B6). Free text, never a list. */
		boolean needsPurpose,

		/** Meals of this kind must name the festival they are for (item 26) — a feast. */
		boolean needsOccasion) {
}
