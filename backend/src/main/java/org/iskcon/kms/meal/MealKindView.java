package org.iskcon.kms.meal;

import java.time.LocalTime;
import java.util.UUID;

/**
 * A kind of meal the temple cooks (E4-S7).
 *
 * @param defaultReadyTime when meals of this kind are usually due, or null when the kind must always
 *                         be given a time — the difference between an everyday meal and an
 *                         occasional one.
 * @param isEvent          meals of this kind are events (E4-S15): an occasion with its own name, its
 *                         own dishes and its own quantities. It reveals the event's name and *is this
 *                         going outside?*, and asks nothing further until the answer is yes. This one
 *                         flag replaced {@code needsClient}, {@code needsVenue} and
 *                         {@code needsPurpose}, which each described one corner of the same shape.
 * @param needsOccasion    the plan must name which festival it is for (item 26) — the flag that makes
 *                         a kind a feast. It defaults to whatever the calendar says for the date and
 *                         stays pickable, so a temple anniversary, or a local festival the calendar
 *                         does not carry, can still be planned as one.
 */
public record MealKindView(
		UUID id,
		String name,
		int sortOrder,
		LocalTime defaultReadyTime,
		boolean isEvent,
		boolean needsOccasion) {
}
