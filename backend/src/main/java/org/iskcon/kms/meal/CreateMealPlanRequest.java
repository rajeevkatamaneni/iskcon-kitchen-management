package org.iskcon.kms.meal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Plan a meal. {@code dayType} may be omitted to accept the calendar's suggestion (festival by
 * occasion, weekend by weekday, else regular); catering must be chosen explicitly and carries client
 * details.
 */
public record CreateMealPlanRequest(
		@NotNull LocalDate planDate,
		@NotBlank @Size(max = 80) String slot,
		@NotNull UUID recipeId,
		@NotNull @Positive BigDecimal targetServings,
		DayType dayType,
		@Size(max = 200) String occasionName,
		@Size(max = 200) String clientName,
		@Size(max = 200) String clientContact,
		@Size(max = 300) String venue,
		Instant deliveryTime,
		/** Set true to knowingly plan an Ekadashi-incompatible recipe on an Ekadashi (E4-S6). */
		boolean ekadashiAcknowledged) {
}
