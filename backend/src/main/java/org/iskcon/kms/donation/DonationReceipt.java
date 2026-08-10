package org.iskcon.kms.donation;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The outcome of recording a donation, carried from the transactional write to the (post-commit)
 * thank-you step: everything needed to acknowledge the donor, without a second lookup.
 */
public record DonationReceipt(
		UUID donationId,
		boolean anonymous,
		String donorName,
		String donorPhone,
		String donorEmail,
		LocalDate donatedOn,
		String templeName) {
}
