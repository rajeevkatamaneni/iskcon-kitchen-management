package org.iskcon.kms.vendor;

import java.time.Instant;
import java.util.UUID;

/** A vendor record (E5-S1). */
public record VendorView(
		UUID id,
		String name,
		String contactPerson,
		String phone,
		String email,
		String address,
		String gstin,
		String preferredLanguage,
		String notes,
		boolean active,
		boolean whatsappReachable,
		Instant createdAt) {
}
