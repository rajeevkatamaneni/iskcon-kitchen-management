package org.iskcon.kms.occasion;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Add an occasion. A COMPUTED occasion needs {@code matchText}; a MANUAL one needs
 * {@code fixedMonth}/{@code fixedDay} — the service enforces the right combination for the type.
 */
public record CreateOccasionRequest(
		@NotBlank(message = "Enter the occasion's name.")
		@Size(max = 200, message = "That name is too long.")
		String name,
		@NotNull(message = "Choose whether the date is worked out or fixed.") OccasionType type,
		@Size(max = 200, message = "That wording is too long.") String matchText,
		@Min(value = 1, message = "A month is a number from 1 to 12.")
		@Max(value = 12, message = "A month is a number from 1 to 12.")
		Integer fixedMonth,
		@Min(value = 1, message = "A day is a number from 1 to 31.")
		@Max(value = 31, message = "A day is a number from 1 to 31.")
		Integer fixedDay,
		@PositiveOrZero(message = "A servings figure cannot be less than nothing.")
		Integer defaultServings,
		@Size(max = 1000, message = "That note is too long.") String notes) {
}
