package org.iskcon.kms.meal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.shift.ShiftView;

/**
 * One meal — Breakfast, Lunch, Dinner or a named event on one day — read from its own row (D-27).
 *
 * <p><strong>What changed, and why the name stayed.</strong> Until D-27 there was no meal row: one
 * {@code meal_plans} row was one dish, and this record was the inference of "a meal" made once, by
 * grouping dish rows that shared a date, a kind's name and an event's name, with the card number and
 * the recording hanging off a second table keyed on the same three texts. Rajeev replaced that with
 * a meal that has its own id — <em>"Each meal gets its own unique ID and related infomration and a
 * Foreign Key relation to the Meal Plan's ID"</em> — so this record is now simply that row, its
 * dishes, and the names it points at. It keeps its name because the crew readout, the job card, the
 * recording form and Today all already mean this by it.
 *
 * <p>Every whole-meal fact lives here and nowhere else: the head count, the ready-by, the notes, the
 * event's contact and delivery, the card, the recording and the correction. A dish carries only what
 * belongs to one dish ({@link MealDishView}).
 *
 * @param mealId        the meal's own id. Everything that refers to a meal — the job card, the
 *                      recording, the correction, a volunteer shift — refers to it by this.
 * @param mealKindId    which kind of meal, by id. {@code mealKind} is that kind's name as the temple
 *                      spells it today, read through the foreign key, so a kind renamed in Settings
 *                      reads renamed here with nothing cascaded.
 * @param plates        what the meal scales to. Never the sum of its dishes: a lunch of three dishes
 *                      at 250 servings each is 250 plates, not 750.
 * @param crewRequired  how many people it takes to execute this meal (item 24), any mix of staff and
 *                      volunteers. Null where nobody has said.
 * @param status        the meal's state as its dishes say it: COOKED once any dish went into a pot,
 *                      PLANNED while any is still to be cooked, CANCELLED when none will be. A meal
 *                      has no status column of its own, because every one of these is already a fact
 *                      about its dishes and a second copy could disagree.
 * @param dishes        every dish of this meal, cancelled ones included, in the order they were
 *                      added — a cancelled dish is part of the record of what was decided.
 * @param volunteerShift the meal's live volunteer shift (D-27, answers 2 and 5), or null where it has
 *                      none. Filled by the meal endpoints, which is where the planner shows "View
 *                      volunteer shift" and the cancel warning's counts; null from the internal
 *                      readers that do not ask for it (the crew readout, Today, the job card), which
 *                      count volunteers through the shift service themselves.
 */
public record ServedMeal(
		UUID mealId,
		UUID mealKindId,
		LocalDate planDate,
		String mealKind,
		LocalTime readyBy,

		Integer adults,
		Integer children,
		Integer seniors,
		int plates,
		Integer crewRequired,

		DayType dayType,
		String occasionName,

		/** What this event is called (E4-S15), where the meal is one. Null for Breakfast, Lunch,
		 * Dinner and everything else that is not an event. Part of the meal's identity with its day
		 * and kind, compared case-insensitively (V136's {@code meals_one_per_meal}). */
		String eventName,
		boolean isOutside,
		Handover handover,
		String contactName,
		String contactPhone,
		String deliveryAddress,
		String deliverySubLocation,
		String deliveryPlaceId,

		/** Where the food is going, as this row holds it (T-044). Null says the address was typed
		 * rather than picked; never zero. Anything that sends these back must send them as they came. */
		BigDecimal deliveryLatitude,
		BigDecimal deliveryLongitude,
		LocalTime guestsEatAt,
		Integer travelMinutes,
		String travelMinutesSource,
		String purpose,
		String kitchenNotes,

		/** The mirror of {@code kitchenNotes} for the people handing the food out (V92). */
		String serverNotes,

		MealStatus status,

		String cardNumber,
		Instant cardIssuedAt,

		/**
		 * True once the returned job card has been typed in.
		 *
		 * <p>What is recorded can later be <em>corrected</em> (T-007), which is a different act from
		 * re-recording it and is behind a different permission. The recording itself is never
		 * rewritten: the four fields below say a correction happened, and each dish carries the
		 * figure it was first given.
		 */
		boolean recorded,
		Instant recordedAt,
		String recordedByName,
		String recordingNote,

		/** Whether a correction has been recorded against this meal (T-007), and by whom. Goes true
		 * exactly once — a second correction is {@code KMS-400137}. */
		boolean corrected,
		Instant correctedAt,
		String correctedByName,
		String correctionNote,

		List<MealDishView> dishes,

		ShiftView volunteerShift) {

	/** A meal with at least one dish still to be cooked — what the nudge counts and Today says. */
	public boolean awaitingRecord() {
		return !recorded && dishes.stream().anyMatch(d -> d.status() == MealStatus.PLANNED);
	}

	/** The same meal with its live volunteer shift attached, or with none. */
	public ServedMeal withVolunteerShift(ShiftView shift) {
		return new ServedMeal(mealId, mealKindId, planDate, mealKind, readyBy, adults, children, seniors,
				plates, crewRequired, dayType, occasionName, eventName, isOutside, handover, contactName,
				contactPhone, deliveryAddress, deliverySubLocation, deliveryPlaceId, deliveryLatitude,
				deliveryLongitude, guestsEatAt, travelMinutes, travelMinutesSource, purpose, kitchenNotes,
				serverNotes, status, cardNumber, cardIssuedAt, recorded, recordedAt, recordedByName,
				recordingNote, corrected, correctedAt, correctedByName, correctionNote, dishes, shift);
	}
}
