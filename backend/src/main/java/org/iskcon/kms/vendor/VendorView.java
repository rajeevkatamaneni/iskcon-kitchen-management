package org.iskcon.kms.vendor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A vendor record (E5-S1).
 *
 * <p>{@code contractEndDate} is when the temple's agreement with them runs out, and
 * {@code contractEndingSoon} is true once that date is past or within the warning window. Both are
 * for the reader only: nothing in the application filters, sorts, orders or schedules on them, and
 * a vendor whose contract ended last March is still active and still selectable until a person
 * decides otherwise.
 */
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
		LocalDate contractEndDate,
		boolean contractEndingSoon,
		boolean active,
		boolean whatsappReachable,
		Instant createdAt) {
}
