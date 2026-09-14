package org.iskcon.kms.shift;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.List;

/**
 * The volunteer shift a meal asks for, as the meal planner holds it until the meal is saved (D-27,
 * answers 2 and 7).
 *
 * <p><strong>No date and no meal, on purpose.</strong> A shift for a meal is raised only from that
 * meal in the planner, and nothing about it is saved until "Save this meal" / "Update this meal"
 * commits the meal and the shift together. At that moment the meal's id is the caller's to pass, and
 * the shift's date is read from the meal row itself ({@link ShiftService#saveForMeal}). A field for
 * either here would be a second copy of a fact the meal already owns — and the one time the two
 * disagreed, the shift would count toward one meal while its card said another day. Rajeev's words:
 * <em>"a shift is unambiguisloy linked to ONE and ONLY one meal."</em>
 *
 * <p>{@code capacity} is "Volunteers requested" on screen (answer 1); the column keeps its name. The
 * validation messages are {@link CreateShiftRequest}'s, word for word, because the planner's layer and
 * Post a shift are the same form to the person filling them in, and a field refused in two different
 * sentences would read as two different rules.
 */
public record MealShiftDraft(
		@NotBlank(message = "Give the shift a title.")
		@Size(max = 200, message = "That title is too long.")
		String title,
		@Size(max = 2000, message = "That description is too long.") String description,
		@NotNull(message = "Choose what time the shift starts.") LocalTime startTime,
		@NotNull(message = "Choose what time the shift ends.") LocalTime endTime,
		@Size(max = 300, message = "That location is too long.") String location,
		@Positive(message = "A shift needs room for at least one volunteer.") int capacity,
		List<@Positive(message = "A reminder goes out at least one minute before the shift.") Integer>
				reminderOffsetsMinutes) {

	/**
	 * A shift has to have some length (T-146). The same rule, and the same sentence, as
	 * {@link CreateShiftRequest#isEndTimeDifferentFromStartTime}; see there for why equal times are the
	 * one pairing refused and an end before the start is a shift through midnight.
	 */
	@AssertTrue(message = "A shift cannot start and end at the same time. For one that runs through the night, give the time it ends the next morning.")
	public boolean isEndTimeDifferentFromStartTime() {
		// Either being absent is @NotNull's failure to report, not this one's.
		return startTime == null || endTime == null || !startTime.equals(endTime);
	}
}
