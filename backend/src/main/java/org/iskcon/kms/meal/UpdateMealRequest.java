package org.iskcon.kms.meal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import org.iskcon.kms.shift.MealShiftDraft;

/**
 * Change a meal that has not been recorded yet: its facts, its dishes and its volunteer shift, in one
 * press — "Update this meal" (D-27).
 *
 * <p><strong>No date and no kind.</strong> Those, with the event's name, are which meal this is, and
 * the planner opens a meal by its id to edit it. Moving Lunch to Tuesday is planning Tuesday's lunch
 * and cancelling this one, which is what the planner already offers — and a meal shift's date always
 * comes from its meal (D-27, answer 4), so a meal that could move would carry a shift to a day nobody
 * signed up for. The event's name may change, since a reading renamed is still that reading, but not
 * onto the name of another event that day.
 *
 * <p><strong>The dishes are the whole list.</strong> A dish with an id is kept and changed; a dish
 * without one is added; a dish still planned that the list leaves out is cancelled — which is exactly
 * what the composer did one request at a time before, done now in the one transaction.
 *
 * <p><strong>The shift.</strong> {@code volunteerShift} null leaves any shift the meal has exactly as
 * it is; a draft creates the meal's shift or changes it, committed only with the meal (answer 7:
 * <em>"nothing saved until the meal is saved : Aggreed"</em>). Cancelling a shift is not done here;
 * cancelling the meal cancels it.
 *
 * <p><strong>The kitchens (Epic 12).</strong> {@code kitchens} replaced the meal-level
 * {@code crewRequired}: People needed is set per kitchen, and V151 dropped the meal's column.
 */
public record UpdateMealRequest(
		LocalTime readyBy,
		@Size(max = 200, message = "That event name is too long.") String eventName,
		boolean isOutside,
		Handover handover,
		@Size(max = 200, message = "That name is too long.") String contactName,
		@Size(max = 200, message = "That phone number is too long.") String contactPhone,
		@Size(max = 300, message = "That address is too long.") String deliveryAddress,
		@Size(max = 200, message = "That location is too long.") String deliverySubLocation,
		@Size(max = 300, message = "That place reference is too long.") String deliveryPlaceId,
		@DecimalMin(value = "-90", message = "Latitude must be between -90 and 90.")
		@DecimalMax(value = "90", message = "Latitude must be between -90 and 90.")
		BigDecimal deliveryLatitude,
		@DecimalMin(value = "-180", message = "Longitude must be between -180 and 180.")
		@DecimalMax(value = "180", message = "Longitude must be between -180 and 180.")
		BigDecimal deliveryLongitude,
		@Min(value = 1, message = "Travel time is between 1 and 600 minutes.")
		@Max(value = 600, message = "Travel time is between 1 and 600 minutes.")
		Integer travelMinutes,
		boolean travelMinutesManual,
		LocalTime guestsEatAt,
		@Size(max = 300, message = "That description is too long.") String purpose,
		@Size(max = 200, message = "That occasion name is too long.") String occasionName,
		@PositiveOrZero(message = "A head count cannot be less than nothing.") Integer adults,
		@PositiveOrZero(message = "A head count cannot be less than nothing.") Integer children,
		@PositiveOrZero(message = "A head count cannot be less than nothing.") Integer seniors,
		/**
		 * The kitchens cooking this meal, each with its People needed (Epic 12) — the whole list, like
		 * {@code dishes}. A kitchen on the meal and left out here is taken off it, which is allowed only
		 * when none of the dishes sent is under it (else KMS-400182); its planned dishes that are not
		 * sent are cancelled as any dish left out is. An empty list is KMS-400180.
		 *
		 * <p>Null — a caller that predates Epic 12 — leaves the meal's kitchens and their figures exactly
		 * as they are, and a dish sent without a kitchen stays where it was (or, added, goes to the
		 * meal's one kitchen). Replacing them with the saver's default kitchen, as a new plan does, would
		 * quietly move an existing meal to whoever edited it last.
		 */
		@Valid List<MealKitchenDraft> kitchens,
		@Size(max = 2000, message = "That note is too long.") String kitchenNotes,
		@Size(max = 2000, message = "That note is too long.") String serverNotes,
		boolean ekadashiAcknowledged,
		@Valid @NotEmpty(message = "Choose at least one preparation.") List<SaveMealRequest.DishDraft> dishes,
		@Valid MealShiftDraft volunteerShift) {
}
