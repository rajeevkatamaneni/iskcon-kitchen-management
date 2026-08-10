package org.iskcon.kms.donation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A recorded donation, for the donations list. {@code donorName} is null for an anonymous gift; the
 * item counts summarise what came in without re-listing every movement and asset.
 */
public record DonationView(
		UUID id,
		String type,
		String donorName,
		boolean anonymous,
		LocalDate donatedOn,
		BigDecimal estimatedValueInr,
		int ingredientCount,
		int equipmentCount,
		boolean acknowledged,
		String notes,
		Instant createdAt) {
}
