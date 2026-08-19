package org.iskcon.kms.staff;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Hiring someone (E6-S8).
 *
 * <p>Two roads in, and exactly one of them is taken. {@code existingUserId} hires a devotee who
 * already registered here — their account is promoted rather than duplicated, which is what stops a
 * cook existing twice with two sets of shift history. Otherwise the person is new to the temple
 * entirely, and their details are given here.
 *
 * <p>{@code systemAccess} is null for staff who get no app account at all. A janitor does not need a
 * login, and requiring one would have every temple minting accounts nobody signs into.
 */
public record HireStaffRequest(

		/** An existing devotee to promote, or null to hire someone the temple has no record of. */
		UUID existingUserId,

		@NotBlank(message = "Enter the person's full name.")
		@Size(max = 200, message = "That name is too long.")
		String fullName,

		@Pattern(
				regexp = "^\\+[1-9][0-9]{7,14}$",
				message = "Include the country code, for example +919876543210.")
		String phone,

		@Email(message = "That doesn't look like an email address.")
		String email,

		@NotNull(message = "Choose a job title.")
		JobTitle jobTitle,

		/** Required when the title is OTHER: what this temple actually calls the job. */
		@Size(max = 100, message = "That job title is too long.")
		String jobTitleOther,

		@NotNull(message = "Choose how this person is employed.")
		EmploymentType employmentType,

		@NotNull(message = "Enter the date they joined.")
		LocalDate dateOfJoining,

		@Past(message = "A date of birth has to be in the past.")
		LocalDate dateOfBirth,

		@Size(max = 500) String address,

		@Size(max = 200) String emergencyContactName,
		@Size(max = 100) String emergencyContactRelationship,
		@Pattern(
				regexp = "^\\+[1-9][0-9]{7,14}$",
				message = "Include the country code, for example +919876543210.")
		String emergencyContactPhone,

		/** Ten characters, five letters then four digits then a letter. Encrypted before storage. */
		@Pattern(
				regexp = "^[A-Za-z]{5}[0-9]{4}[A-Za-z]$",
				message = "A PAN is five letters, four digits, then a letter — for example ABCDE1234F.")
		String pan,

		/** TEMPLE_ADMIN or KITCHEN_STAFF, or null for someone who gets no app account. */
		SystemAccess systemAccess,

		@Size(max = 2000) String notes) {
}
