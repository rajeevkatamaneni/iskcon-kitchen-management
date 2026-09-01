package org.iskcon.kms.vendor;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry in a vendor's active/inactive history: which way it went, why, and by whom.
 *
 * <p>The reason is required going out and optional coming back in, which is the asymmetry the
 * record is for: nobody has to justify keeping a supplier, and everybody has to justify dropping
 * one, because the next person along is reading this to decide whether the reason still holds.
 */
public record VendorStatusChange(
		UUID id,
		Boolean fromActive,
		boolean toActive,
		String reason,
		UUID actorUserId,
		String actorName,
		Instant createdAt) {
}
