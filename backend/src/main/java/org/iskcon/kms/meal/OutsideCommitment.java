package org.iskcon.kms.meal;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Something the temple has undertaken to send out of the building (E4-S15 D4).
 *
 * <p>This list replaces the *Upcoming catering* table that was designed and never built, and it
 * covers more than that one would have: it is keyed off <em>is this going outside</em> rather than
 * <em>is this catering</em>, so the school delivery and the community programme are on it beside the
 * wedding. Those are exactly as easy to forget on the morning.
 *
 * <p>One row per event, not per dish. An event of three preparations is one commitment — three lines
 * for one delivery would read as three deliveries.
 *
 * <p>What is on it is what somebody can act on without opening anything: when it is, what it is
 * called, who to ring, and where it is going.
 */
public record OutsideCommitment(
		LocalDate planDate,
		String eventName,
		String mealKind,
		Handover handover,
		String contactName,
		String contactPhone,
		String deliveryAddress,
		LocalTime readyBy,
		LocalTime guestsEatAt,
		int preparations) {
}
