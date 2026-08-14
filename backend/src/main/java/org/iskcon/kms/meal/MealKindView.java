package org.iskcon.kms.meal;

import java.time.LocalTime;
import java.util.UUID;

/**
 * A kind of meal the temple cooks (E4-S7).
 *
 * @param defaultReadyTime when meals of this kind are usually due, or null when the kind must always
 *                         be given a time — the difference between an everyday meal and an
 *                         occasional one.
 * @param needsClient      food someone outside the temple asked for and is paying for, so the plan
 *                         must name them.
 * @param needsVenue       food that leaves the temple, so the plan must say where it is going.
 */
public record MealKindView(
		UUID id,
		String name,
		int sortOrder,
		LocalTime defaultReadyTime,
		boolean needsClient,
		boolean needsVenue) {
}
