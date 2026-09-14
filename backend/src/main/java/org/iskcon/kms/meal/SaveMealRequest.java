package org.iskcon.kms.meal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.shift.MealShiftDraft;

/**
 * Plan a meal: its day, its kind, everything about it, its dishes, and the volunteer shift it asks
 * for — saved in one press (D-27).
 *
 * <p><strong>One request for the whole meal, and why.</strong> The composer used to send one request
 * per dish, each carrying the meal's facts again, and a meal was whatever rows happened to share a
 * date and two names. Rajeev's design gives the meal a row of its own, and his ruling on the shift
 * makes the single request necessary rather than tidy: <em>"The Sift when saved shoudl be left
 * uncommited until the meal is saved. Once the meal is saved, we take the ID of the meal and update
 * the Volenteer reruest with that ID and then commit everything."</em> A meal saved in three requests
 * cannot be committed with its shift in one transaction.
 *
 * <p><strong>Planning a meal that already exists reuses it.</strong> The same day, the same kind and
 * the same event name compared without regard to case is the same meal — including one that was
 * cancelled, which is planned again on its own row rather than beside a second copy nobody could tell
 * apart from it. The dishes sent here are added to it.
 *
 * <p>What is absent is the day type: weekend follows from the date and festival from the calendar,
 * so nobody is asked. {@code readyBy} may be omitted only for a kind with a default time.
 *
 * <p>The event fields (E4-S15) are honoured only by a kind flagged {@code isEvent}, and asked for in
 * a chain: an event has a name; an event going outside also has a contact and a handover; a delivered
 * one also has an address and the time the guests eat.
 *
 * @param mealKindId     which kind of meal, by id — the id the kinds list gives, never a name.
 * @param dishes         what is being cooked. At least one: a meal with nothing in it is not a plan.
 * @param volunteerShift the volunteer shift this meal asks for (D-27, answers 2 and 7), or null for
 *                       none. Saved with the meal in the same transaction; if any part of the save is
 *                       refused, nothing is saved — no meal, no dishes and no shift.
 * @param ekadashiAcknowledged set true to knowingly plan an Ekadashi-incompatible dish on an Ekadashi
 *                       (E4-S6). One answer for the meal, because the composer asks once for the save.
 */
public record SaveMealRequest(
		@NotNull(message = "Choose the day this is being cooked.") LocalDate planDate,
		@NotNull(message = "Choose which meal this is.") UUID mealKindId,
		LocalTime readyBy,

		/** What this event is called — "Children's Bhagavad-gita Reading" (E4-S15). Required by a
		 * kind flagged {@code isEvent} and ignored by every other kind. */
		@Size(max = 200, message = "That event name is too long.") String eventName,

		/** Whether this event's food leaves the temple. */
		boolean isOutside,
		/** PICKUP or DELIVERY, on an event going outside. Null on an in-house one. */
		Handover handover,
		/** Who to ring about food going outside the temple, and their number. Both or neither. */
		@Size(max = 200, message = "That name is too long.") String contactName,
		@Size(max = 200, message = "That phone number is too long.") String contactPhone,
		/** Where a delivered event's food is going. */
		@Size(max = 300, message = "That address is too long.") String deliveryAddress,
		/** Where exactly, once the driver is there — "Clubhouse", "Block C, second gate" (V93). */
		@Size(max = 200, message = "That location is too long.") String deliverySubLocation,
		/** Google's stable id for the address the planner picked (V93). */
		@Size(max = 300, message = "That place reference is too long.") String deliveryPlaceId,

		/** Where the picked address is. Sent with the place id and never on its own (T-044). */
		@DecimalMin(value = "-90", message = "Latitude must be between -90 and 90.")
		@DecimalMax(value = "90", message = "Latitude must be between -90 and 90.")
		BigDecimal deliveryLatitude,
		@DecimalMin(value = "-180", message = "Longitude must be between -180 and 180.")
		@DecimalMax(value = "180", message = "Longitude must be between -180 and 180.")
		BigDecimal deliveryLongitude,

		/** How long the temple allows for the drive, in minutes (V93). */
		@Min(value = 1, message = "Travel time is between 1 and 600 minutes.")
		@Max(value = 600, message = "Travel time is between 1 and 600 minutes.")
		Integer travelMinutes,
		/** Whether a person set that figure themselves. */
		boolean travelMinutesManual,
		/** The local time the guests sit down to eat, on a delivery. */
		LocalTime guestsEatAt,

		/** What the food is for, in the planner's own words (B6). Printed on the card. */
		@Size(max = 300, message = "That description is too long.") String purpose,
		/** Which festival this meal is for, where the kind asks (item 26). */
		@Size(max = 200, message = "That occasion name is too long.") String occasionName,

		@PositiveOrZero(message = "A head count cannot be less than nothing.") Integer adults,
		@PositiveOrZero(message = "A head count cannot be less than nothing.") Integer children,
		@PositiveOrZero(message = "A head count cannot be less than nothing.") Integer seniors,
		/** How many people it takes to execute this meal (item 24). */
		@Positive(message = "At least one person is needed on the crew.") Integer crewRequired,
		@Size(max = 2000, message = "That note is too long.") String kitchenNotes,
		/** Anything the people serving this meal need to know (V92). */
		@Size(max = 2000, message = "That note is too long.") String serverNotes,

		boolean ekadashiAcknowledged,

		@Valid @NotEmpty(message = "Choose at least one preparation.") List<DishDraft> dishes,

		@Valid MealShiftDraft volunteerShift) {

	/**
	 * One dish as the composer holds it.
	 *
	 * @param id null for a dish being added; the dish's own id for one already on the meal, which is
	 *           only meaningful on an update ({@link UpdateMealRequest}) and refused on a plan.
	 */
	public record DishDraft(
			UUID id,
			@NotNull(message = "Choose a recipe.") UUID recipeId,
			@NotNull(message = "Enter how much is being made.")
			@Positive(message = "Enter an amount greater than zero.")
			BigDecimal targetYield) {
	}
}
