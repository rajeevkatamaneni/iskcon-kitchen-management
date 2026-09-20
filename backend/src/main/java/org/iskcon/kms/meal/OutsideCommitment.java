package org.iskcon.kms.meal;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Something the temple has undertaken to send out of the building (E4-S15 D4).
 *
 * <p>It is keyed off <em>is this going outside</em> rather than <em>is this catering</em>, so the
 * school delivery and the community programme are on it beside the wedding. Those are exactly as
 * easy to forget on the morning.
 *
 * <p>One row per event, not per dish. An event of three preparations is one commitment — three lines
 * for one delivery would read as three deliveries.
 *
 * <p><b>Read by Today, not by the planner, since T-363 (2026-09-19).</b> The planner used to carry a
 * section of these at the foot of every view, and Rajeev took it out: an outside event is an ordinary
 * meal and belongs in the day list with the rest, sorted by ready-by. What did not survive that move
 * is the one thing a day cannot show — a delivery on Saturday is invisible while you are looking at
 * Monday — so these rows are now the cross-date heads-up on the morning screen, assembled by
 * {@code TodayService}.
 *
 * <p>{@code mealId} is the meal's own id (D-27), so a row on this list opens that meal rather than
 * whichever meal on that date happens to share its kind and name. The old planner section sent the
 * id and then linked nowhere, which is what Rajeev saw: the event named, with no way through to it.
 */
public record OutsideCommitment(
		java.util.UUID mealId,
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
