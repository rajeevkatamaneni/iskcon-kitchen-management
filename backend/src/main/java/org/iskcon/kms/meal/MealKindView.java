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
 * @param needsPurpose     the plan must say what the food is for — a reading, a school event (B6).
 *                         Free text, never a list: the reasons a temple cooks for an outside event
 *                         are open-ended, and a list of five would be wrong by the sixth.
 */
public record MealKindView(
		UUID id,
		String name,
		int sortOrder,
		LocalTime defaultReadyTime,
		boolean needsClient,
		boolean needsVenue,
		boolean needsPurpose) {
}
