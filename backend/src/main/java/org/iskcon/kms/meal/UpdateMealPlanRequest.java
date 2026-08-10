package org.iskcon.kms.meal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Edit a planned (not yet cooked) meal. */
public record UpdateMealPlanRequest(
		@NotNull LocalDate planDate,
		@NotBlank @Size(max = 80) String slot,
		@NotNull UUID recipeId,
		@NotNull @Positive BigDecimal targetServings,
		DayType dayType,
		@Size(max = 200) String occasionName,
		@Size(max = 200) String clientName,
		@Size(max = 200) String clientContact,
		@Size(max = 300) String venue,
		Instant deliveryTime) {
}
