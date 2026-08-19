package org.iskcon.kms.staff;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One person's employment at this temple (E6-S8) — the row the staff register shows and the header
 * of their schedule.
 *
 * <p>{@code userId} is null for staff the temple gave no app account. The name, phone and email here
 * belong to the employment record, not to a users row, which is what lets such a person exist at
 * all.
 *
 * <p>A PAN never leaves the server in clear on this view: {@code panLast4} is enough to show a
 * masked value in a list, and reading the whole thing is a separate, audited request.
 */
public record StaffProfileView(
		UUID id,
		UUID userId,
		String fullName,
		String phone,
		String email,

		JobTitle jobTitle,
		/** The temple's own words, when jobTitle is OTHER. */
		String jobTitleOther,
		/** What to print: the temple's words if it gave any, otherwise the vocabulary's label. */
		String jobTitleLabel,

		EmploymentType employmentType,
		LocalDate dateOfJoining,
		LocalDate dateOfBirth,
		String address,

		String emergencyContactName,
		String emergencyContactRelationship,
		String emergencyContactPhone,

		String panLast4,

		/** TEMPLE_ADMIN, KITCHEN_STAFF, or null when they hold no login. */
		SystemAccess systemAccess,

		EmploymentStatus employmentStatus,
		LocalDate lastWorkingDay,
		String endReason,
		String notes,

		Instant createdAt) {

	/** Convenience for the register, which shows current and former staff in separate sections. */
	public boolean isFormer() {
		return employmentStatus.isFormer();
	}
}
