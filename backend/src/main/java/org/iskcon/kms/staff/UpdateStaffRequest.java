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

/**
 * Editing an employment record (E6-S8): a promotion, a new phone number, a corrected joining date.
 *
 * <p>The same fields as the hire, minus the two that cannot change afterwards — which devotee's
 * account this is, and whether the person existed already. Changing {@code systemAccess} is the
 * promotion path: granting it to someone who had none creates their login, and taking it away
 * turns them back into a devotee.
 *
 * <p>{@code pan} left null leaves whatever is stored alone; a blank string clears it. Absent and
 * empty mean different things here on purpose — otherwise every edit of a phone number would
 * silently erase a PAN the form never showed in clear.
 */
public record UpdateStaffRequest(

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

		/** Null leaves the stored PAN untouched; "" clears it; a value replaces it. */
		@Pattern(
				regexp = "^$|^[A-Za-z]{5}[0-9]{4}[A-Za-z]$",
				message = "A PAN is five letters, four digits, then a letter — for example ABCDE1234F.")
		String pan,

		SystemAccess systemAccess,

		/**
		 * The monthly salary, or null for none recorded (B8). Unlike the PAN above, null here means
		 * exactly what it says rather than "leave it alone": pay is shown on the form when it is
		 * edited, so an admin who clears the box is telling us there is no agreed figure any more.
		 */
		@Positive(message = "A salary has to be more than zero. Leave it blank if no pay has been agreed yet.")
		@Digits(integer = 10, fraction = 2, message = "Enter a salary in rupees and paise, for example 18000.")
		BigDecimal monthlySalary,

		@Size(max = 2000) String notes) {
}
