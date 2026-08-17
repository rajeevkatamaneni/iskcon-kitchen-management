package org.iskcon.kms.donation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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
 *
 * <p>{@code wishlistItemId} is the one earmark a hand-recorded gift can carry: cash given <em>towards</em>
 * a wish-list item — the grinder, the sacks of rice — which until now could only be written in the
 * notes, where nothing could read it. It is money towards the item's cost, exactly as a part-payment
 * online is, so it counts in rupees and never in units: nobody hands over half a grinder. Goods are
 * deliberately not linkable, because a donated sack already lands in stock and counting it against an
 * item as well would fund the same thing twice.
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
		UUID wishlistItemId,
		@Valid List<IngredientDonationLine> ingredients,
		@Valid List<EquipmentDonationLine> equipment) {
}
