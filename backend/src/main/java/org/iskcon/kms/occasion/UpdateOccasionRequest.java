package org.iskcon.kms.occasion;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Edit an occasion's name, matching/date, default servings, or notes. The type is fixed at creation
 * — a computed occasion and a fixed-date one are different things; recreate rather than convert.
 */
public record UpdateOccasionRequest(
		@NotBlank @Size(max = 200) String name,
		@Size(max = 200) String matchText,
		@Min(1) @Max(12) Integer fixedMonth,
		@Min(1) @Max(31) Integer fixedDay,
		@PositiveOrZero Integer defaultServings,
		@Size(max = 1000) String notes) {
}
