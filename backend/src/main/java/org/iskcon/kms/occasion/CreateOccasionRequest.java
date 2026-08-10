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
		@NotBlank @Size(max = 200) String name,
		@NotNull OccasionType type,
		@Size(max = 200) String matchText,
		@Min(1) @Max(12) Integer fixedMonth,
		@Min(1) @Max(31) Integer fixedDay,
		@PositiveOrZero Integer defaultServings,
		@Size(max = 1000) String notes) {
}
