package org.iskcon.kms.donation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One ingredient given in kind: it becomes a new batch received into stock as a DONATION_IN_KIND
 * movement. Expiry is optional — a donor may bring dated packaged goods, or loose produce with none.
 */
public record IngredientDonationLine(
		@NotNull UUID ingredientId,
		@NotNull @Positive BigDecimal quantity,
		@NotNull String unit,
		LocalDate expiryDate) {
}
