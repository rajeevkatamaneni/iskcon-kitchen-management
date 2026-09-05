package org.iskcon.kms.meal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Plan a meal (E4-S7).
 *
 * <p>Note what is absent: the day type. Whether a day is a weekend, a festival or an ordinary
 * Tuesday follows from the date and the calendar, so nobody is asked — the server derives and
 * records it. What the planner supplies is what they actually know: what is being cooked, how much,
 * and when it must be ready.
 *
 * <p>{@code readyBy} may be omitted only for a kind that carries a default time; for the occasional
 * kinds it is required, which is the whole point of them having no default.
 *
 * <p>The event fields (E4-S15) are honoured only by a kind flagged {@code isEvent}, and are asked
 * for in a chain: an event has a name; an event going outside also has a contact and a handover; a
 * delivered one also has an address and the time the guests eat. Breakfast, Lunch and Dinner see
 * none of it, and an in-house event stops at its name — a Bhajan Prasadam in the temple hall has no
 * client, and a form should not ask a question with no answer.
 */
public record CreateMealPlanRequest(
		@NotNull LocalDate planDate,
		@NotBlank @Size(max = 80) String mealKind,
		@NotNull UUID recipeId,
		@NotNull @Positive BigDecimal targetYield,
		LocalTime readyBy,

		/** What this event is called — "Children's Bhagavad-gita Reading" (E4-S15). Required by a
		 * kind flagged {@code isEvent} and ignored by every other kind. */
		@Size(max = 200) String eventName,

		/** Whether this event's food leaves the temple. What reveals the handover and the contact,
		 * and what *Upcoming outside commitments* is keyed off. */
		boolean isOutside,

		/** PICKUP or DELIVERY, on an event going outside. Null on an in-house one. */
		Handover handover,

		/** Who to ring about food going outside the temple, and their number. Both or neither: a
		 * contact you cannot ring is not a contact. */
		@Size(max = 200) String contactName,
		@Size(max = 200) String contactPhone,

		/** Where a delivered event's food is going. Asked for on a delivery only — somebody
		 * collecting their own food does not need us to know where they are taking it. */
		@Size(max = 300) String deliveryAddress,

		/** The local time the guests sit down to eat, on a delivery. Not the ready-by: E4-S16 works
		 * backwards from this to say when to leave the temple. */
		LocalTime guestsEatAt,

		/** What the food is for, where the planner wants to say so (B6) — a reading, book
		 * distribution, a school event. Free text and not a picklist: the reasons are open-ended, and
		 * this is a label for the kitchen and the job card, not something the system reasons about.
		 * No kind demands it any more — an event's name says what it is (E4-S15 D5) — but it is still
		 * printed on the card, so it is still accepted. */
		@Size(max = 300) String purpose,

		/** Which festival this meal is for, where the kind asks (item 26). Honoured only by a kind
		 * carrying {@code needsOccasion} — for every other kind the occasion follows from the date and
		 * the calendar, and nobody is asked. Left null, a feast falls back to whatever the calendar
		 * says for that date, which is right nearly every time and wrong only for a temple anniversary
		 * or a local festival the calendar does not carry. */
		@Size(max = 200) String occasionName,

		/** The hall as the planner expects it. Optional: a meal may still be given a flat servings
		 * figure, and every meal planned before this existed has one and no breakdown. */
		@PositiveOrZero Integer adults,
		@PositiveOrZero Integer children,
		@PositiveOrZero Integer seniors,

		/** How many people it takes to execute this meal (item 24) — one counter, any mix of staff and
		 * volunteers, because the mix does not matter and splitting it would invent a constraint the
		 * temple does not have. Optional: a meal is planned weeks before anybody is rostered, and a
		 * planner who does not know yet leaves it empty rather than guessing. */
		@Positive Integer crewRequired,

		/** What the cooks should know about this meal, in the planner's own words. */
		@Size(max = 2000) String kitchenNotes,
		/** Set true to knowingly plan an Ekadashi-incompatible recipe on an Ekadashi (E4-S6). */
		boolean ekadashiAcknowledged) {
}
