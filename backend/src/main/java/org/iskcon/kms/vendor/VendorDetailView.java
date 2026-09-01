package org.iskcon.kms.vendor;

import java.util.List;

/**
 * A vendor with the ingredients it supplies, and why it has been dropped and brought back (E5-S1).
 *
 * <p>The status history is most recent first, because the question it answers is "why is this one
 * inactive?" and the answer is the newest entry.
 */
public record VendorDetailView(
		VendorView vendor,
		List<VendorSupplyView> supplies,
		List<VendorStatusChange> statusHistory) {
}
