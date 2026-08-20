package org.iskcon.kms.staff;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.iskcon.kms.ban.AadhaarIdentity;

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

		/**
		 * A monthly figure in the temple's currency, or null (B8). Null is ordinary and means what it
		 * says: a temple takes somebody on before the pay is agreed, and a part-timer paid daily in
		 * cash may have nothing recorded at all. It is never rewritten as zero.
		 */
		@Positive(message = "A salary has to be more than zero. Leave it blank if no pay has been agreed yet.")
		@Digits(integer = 10, fraction = 2, message = "Enter a salary in rupees and paise, for example 18000.")
		BigDecimal monthlySalary,

		/**
		 * The check the admin has already seen the findings of and chosen to hire past (B9).
		 *
		 * <p>Null on a first attempt, which is every ordinary hire. When a check finds something the
		 * hire does <em>not</em> complete: the findings come back instead, naming the temple that
		 * raised each record and quoting what they wrote, and the admin chooses. Sending the same
		 * hire again with the id of the check they were shown is that choice, made explicitly.
		 *
		 * <p>Note what this is not. It is not an override of a block — there is no block, and a match
		 * never creates one. It is the admin's answer, recorded because <em>hired anyway</em> is a
		 * legitimate answer and often the right one, and because a decision nobody wrote down is a
		 * decision nobody can stand behind later.
		 */
		UUID acknowledgedBanCheckId,

		/**
		 * The UIDAI-signed Aadhaar triple, where a temple has captured one (B9). Nothing produces one
		 * in this build — the signed-QR reader is not built — so it is always null and the Aadhaar arm
		 * of the check is inert. It is on the request rather than absent from it so that the seam is a
		 * real one: see {@code AadhaarIdentity} for what would fill it.
		 */
		AadhaarIdentity aadhaar,

		@Size(max = 2000) String notes) {
}
