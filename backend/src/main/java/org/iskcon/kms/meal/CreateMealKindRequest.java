package org.iskcon.kms.meal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;

/**
 * Add or change a kind of meal (E4-S7). A null {@code defaultReadyTime} is deliberate, not missing
 * input: it makes the kind ask for a time every time it is planned.
 */
public record CreateMealKindRequest(
		@NotBlank(message = "Enter the meal's name.")
		@Size(max = 80, message = "That name is too long.")
		String name,
		int sortOrder,
		LocalTime defaultReadyTime,

		/** Meals of this kind are events with a name of their own, which may be going outside the
		 * temple (E4-S15). A temple may set it on a kind of its own — *Catering event*, say — and the
		 * whole event block follows, with no code change here. */
		boolean isEvent,

		/** Meals of this kind must name the festival they are for (item 26) — a feast. */
		boolean needsOccasion) {
}
