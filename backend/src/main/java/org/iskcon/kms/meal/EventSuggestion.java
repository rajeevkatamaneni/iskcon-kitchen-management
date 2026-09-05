package org.iskcon.kms.meal;

/**
 * An event name this temple has used before, and what it was last time (E4-S15 D9).
 *
 * <p><strong>Why this exists at all.</strong> The temple's own artifacts contain no event register
 * of any kind — no dates, no head counts, no contact, no venue, anywhere. Events are not a practice
 * being digitised here; they are a practice being introduced. If entering a Saturday reading costs
 * three minutes it will stop being entered, and the data will be worse than if events had never been
 * split out of the main meals at all. So this is not a convenience. It is what makes D1 survive
 * contact with a kitchen.
 *
 * <p>Which is why it carries more than the name. Choosing a suggestion brings the previous event's
 * whole shape forward — whether it went outside, how it was handed over, who to ring, and where it
 * went — so the second School Bhagavad-gita Reading is one keystroke and not a form.
 *
 * <p>Every field is what the <em>most recent</em> plan of that name said. Not the first, and not a
 * merge of all of them: a contact who changed is a contact who changed, and the newest answer is the
 * one somebody would ring today.
 *
 * @param eventName       the name as the planner last spelled it. Suggestions are distinct by name
 *                        ignoring case, so a temple that typed it two ways sees the newer spelling
 *                        once rather than both.
 * @param isOutside       this food left the temple.
 * @param handover        {@code PICKUP} or {@code DELIVERY}. Null on an in-house event, and null on
 *                        the outside plans V88 carried across, which predate the question — nobody
 *                        was ever asked, and a suggestion does not invent an answer.
 * @param contactName     who was rung about it, where it went outside.
 * @param contactPhone    the number that was rung.
 * @param deliveryAddress where it was delivered, on a delivery.
 */
public record EventSuggestion(
		String eventName,
		boolean isOutside,
		Handover handover,
		String contactName,
		String contactPhone,
		String deliveryAddress) {
}
