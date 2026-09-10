package org.iskcon.kms.meal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
		@NotNull(message = "Choose the day this is being cooked.") LocalDate planDate,
		@NotBlank(message = "Choose which meal this is.")
		@Size(max = 80, message = "That name is too long.")
		String mealKind,
		@NotNull(message = "Choose a recipe.") UUID recipeId,
		@NotNull(message = "Enter how much is being made.")
		@Positive(message = "Enter an amount greater than zero.")
		BigDecimal targetYield,
		LocalTime readyBy,

		/** What this event is called — "Children's Bhagavad-gita Reading" (E4-S15). Required by a
		 * kind flagged {@code isEvent} and ignored by every other kind. */
		@Size(max = 200, message = "That event name is too long.") String eventName,

		/** Whether this event's food leaves the temple. What reveals the handover and the contact,
		 * and what *Upcoming outside commitments* is keyed off. */
		boolean isOutside,

		/** PICKUP or DELIVERY, on an event going outside. Null on an in-house one. */
		Handover handover,

		/** Who to ring about food going outside the temple, and their number. Both or neither: a
		 * contact you cannot ring is not a contact. */
		@Size(max = 200, message = "That name is too long.") String contactName,
		@Size(max = 200, message = "That phone number is too long.") String contactPhone,

		/** Where a delivered event's food is going. Asked for on a delivery only — somebody
		 * collecting their own food does not need us to know where they are taking it. */
		@Size(max = 300, message = "That address is too long.") String deliveryAddress,

		/** Where exactly, once the driver is there — "Clubhouse", "Block C, second gate" (V93).
		 * Never geocoded and never routed on: being at the right gate is what matters, and the last
		 * fifty metres is a phone call. Kept apart from the address for that reason. */
		@Size(max = 200, message = "That location is too long.") String deliverySubLocation,

		/** Google's stable id for the address the planner picked (V93). Absent when the address was
		 * typed rather than chosen, which is every plan made before the picker existed. */
		@Size(max = 300, message = "That place reference is too long.") String deliveryPlaceId,

		/** Where the picked address actually is. Sent with the place id and never on its own: these
		 * came from our own Places proxy moments earlier, and having them here is what stops the save
		 * throwing away a good pin and asking a geocoder to find the address all over again. */
		@DecimalMin(value = "-90", message = "Latitude must be between -90 and 90.")
		@DecimalMax(value = "90", message = "Latitude must be between -90 and 90.")
		BigDecimal deliveryLatitude,
		@DecimalMin(value = "-180", message = "Longitude must be between -180 and 180.")
		@DecimalMax(value = "180", message = "Longitude must be between -180 and 180.")
		BigDecimal deliveryLongitude,

		/** How long the temple allows for the drive, in minutes (V93) — Google's estimate, or a
		 * figure from somebody who knows the road better than a traffic model does. */
		@Min(value = 1, message = "Travel time is between 1 and 600 minutes.")
		@Max(value = 600, message = "Travel time is between 1 and 600 minutes.")
		Integer travelMinutes,

		/** Whether a person set that figure themselves. It decides whether printing the job card
		 * refreshes the estimate or leaves their correction standing. */
		boolean travelMinutesManual,

		/** The local time the guests sit down to eat, on a delivery. Not the ready-by: E4-S16 works
		 * backwards from this to say when to leave the temple. */
		LocalTime guestsEatAt,

		/** What the food is for, where the planner wants to say so (B6) — a reading, book
		 * distribution, a school event. Free text and not a picklist: the reasons are open-ended, and
		 * this is a label for the kitchen and the job card, not something the system reasons about.
		 * No kind demands it any more — an event's name says what it is (E4-S15 D5) — but it is still
		 * printed on the card, so it is still accepted. */
		@Size(max = 300, message = "That description is too long.") String purpose,

		/** Which festival this meal is for, where the kind asks (item 26). Honoured only by a kind
		 * carrying {@code needsOccasion} — for every other kind the occasion follows from the date and
		 * the calendar, and nobody is asked. Left null, a feast falls back to whatever the calendar
		 * says for that date, which is right nearly every time and wrong only for a temple anniversary
		 * or a local festival the calendar does not carry. */
		@Size(max = 200, message = "That occasion name is too long.") String occasionName,

		/** The hall as the planner expects it. Optional: a meal may still be given a flat servings
		 * figure, and every meal planned before this existed has one and no breakdown. */
		@PositiveOrZero(message = "A head count cannot be less than nothing.") Integer adults,
		@PositiveOrZero(message = "A head count cannot be less than nothing.") Integer children,
		@PositiveOrZero(message = "A head count cannot be less than nothing.") Integer seniors,

		/** How many people it takes to execute this meal (item 24) — one counter, any mix of staff and
		 * volunteers, because the mix does not matter and splitting it would invent a constraint the
		 * temple does not have. Optional: a meal is planned weeks before anybody is rostered, and a
		 * planner who does not know yet leaves it empty rather than guessing. */
		@Positive(message = "At least one person is needed on the crew.") Integer crewRequired,

		/** What the cooks should know about this meal, in the planner's own words. */
		@Size(max = 2000, message = "That note is too long.") String kitchenNotes,

		/** Anything the people serving this meal need to know (V92) — the mirror of the kitchen's
		 * notes, and what the serving sheet of the job card is for. */
		@Size(max = 2000, message = "That note is too long.") String serverNotes,
		/** Set true to knowingly plan an Ekadashi-incompatible recipe on an Ekadashi (E4-S6). */
		boolean ekadashiAcknowledged) {
}
