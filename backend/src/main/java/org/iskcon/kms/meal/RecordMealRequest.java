package org.iskcon.kms.meal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Record what actually went out at one meal (B5), when the signed job card comes back to the office.
 *
 * <p>One request for the whole meal, not one per dish. The person typing this has a sheet in front
 * of them with every dish on it, and the figure that matters — what actually went out, against what
 * was planned — is only worth collecting if collecting it takes one form and one press.
 *
 * @param dishes every dish the meal currently has. Naming them all is required rather than optional:
 *               a dish left out of the request is a dish nobody said anything about, and quietly
 *               deciding on the office's behalf whether it was cooked is the one thing this form
 *               must not do.
 */
public record RecordMealRequest(
		@NotNull LocalDate planDate,
		@NotBlank @Size(max = 80) String mealKind,

		/** Anything the office wants on the record — "ran short, sent out at 220". */
		@Size(max = 2000) String note,

		@Valid @NotEmpty List<DishRecord> dishes) {

	/**
	 * One dish as the card came back.
	 *
	 * @param actualServings what actually went out. Ignored — and stored as zero — when the dish was
	 *                       not made.
	 * @param notMade        the dish never went into a pot, so it draws nothing from stock.
	 */
	public record DishRecord(
			@NotNull UUID mealPlanId,
			BigDecimal actualServings,
			boolean notMade) {
	}
}
