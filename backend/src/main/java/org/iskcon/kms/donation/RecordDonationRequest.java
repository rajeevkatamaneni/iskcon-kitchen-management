package org.iskcon.kms.donation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Record a gift someone handed to the temple (E3-S5, extended): cash, food, or equipment.
 *
 * <p>A gift is of one kind. Either a {@code cashAmountInr} — money put into the hundi or the office —
 * or goods: ingredients, equipment, or both. Cash and goods together are refused rather than merged,
 * because the two travel different roads once recorded (cash to the bank, goods to the shelf) and a
 * single row can only honestly be one of them. Two gifts on one visit are two records.
 *
 * <p>{@code estimatedValueInr} is the temple's own estimate of what the <em>goods</em> were worth;
 * cash needs no estimating, which is why it has a field of its own.
 */
public record RecordDonationRequest(
		boolean anonymous,
		@Size(max = 200) String donorName,
		@Size(max = 120) String donorPhone,
		@Size(max = 200) String donorEmail,
		@Positive BigDecimal cashAmountInr,
		@PositiveOrZero BigDecimal estimatedValueInr,
		@NotNull LocalDate donatedOn,
		@Size(max = 1000) String notes,
		@Valid List<IngredientDonationLine> ingredients,
		@Valid List<EquipmentDonationLine> equipment) {
}
