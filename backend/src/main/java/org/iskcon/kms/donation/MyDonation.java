package org.iskcon.kms.donation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One of a devotee's own gifts, as the My donations page shows it (T-179).
 *
 * <p><strong>Deliberately narrow.</strong> The admin's {@link DonationDetail} carries the donor's
 * phone, email, address and whether a PAN is held, because the office needs them to answer a
 * question about a gift. This record carries none of them. The person reading it is the donor, who
 * already knows their own contact details, and every field left out is a field that cannot leak if
 * the matching behind this page is ever wrong. The receipt itself still prints the PAN for an 80G
 * gift; that is the document they asked for, and it is reached only through the same ownership check.
 *
 * <p>Shaped to {@code MyDonation} in {@code frontend/lib/api.ts}: {@code kind} is {@code MONEY} or
 * {@code GOODS}, {@code amount} is null for goods (the temple's estimate of what goods were worth is
 * its own bookkeeping, not something the donor gave), and {@code receiptNumber} is null until the
 * temple has issued one.
 */
public record MyDonation(
		UUID id,
		String kind,
		LocalDate receivedOn,
		BigDecimal amount,
		String description,
		String receiptNumber) {
}
