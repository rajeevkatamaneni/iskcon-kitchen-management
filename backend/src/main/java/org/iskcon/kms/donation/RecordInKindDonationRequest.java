package org.iskcon.kms.donation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Record a gift of goods (E3-S5). At least one item — an ingredient or a piece of equipment — must be
 * present; a donor name is required unless the gift is anonymous. Contact details are optional and
 * used only to send a thank-you.
 */
public record RecordInKindDonationRequest(
		boolean anonymous,
		@Size(max = 200) String donorName,
		@Size(max = 120) String donorPhone,
		@Size(max = 200) String donorEmail,
		@PositiveOrZero BigDecimal estimatedValueInr,
		@NotNull LocalDate donatedOn,
		@Size(max = 1000) String notes,
		@Valid List<IngredientDonationLine> ingredients,
		@Valid List<EquipmentDonationLine> equipment) {
}
