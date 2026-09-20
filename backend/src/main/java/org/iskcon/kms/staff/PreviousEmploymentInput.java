package org.iskcon.kms.staff;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.iskcon.kms.config.PhoneNumberDeserializer;

/**
 * One row of the previous-employment list as the edit screen sends it (T-428).
 *
 * <p>The whole list arrives with the rest of the record and replaces what was stored, so there is no
 * id here: a row that was there and is not in the list any more has been deleted, and a row that has
 * moved has moved. That is what the form actually does — it shows every job at once and the admin
 * edits them together — and it keeps a set of rows and the record they hang off in one transaction.
 *
 * <p><b>Only the employer is required.</b> Everything else is what the person could remember at their
 * interview. Demanding a manager's phone number would mean the temple records nothing about a job
 * rather than the part it knows, which is the worse of the two.
 */
public record PreviousEmploymentInput(

		@NotBlank(message = "Enter where they worked.")
		@Size(max = 200, message = "That employer name is too long.")
		String employer,

		@Size(max = 200, message = "That job title is too long.")
		String theirTitle,

		@Size(max = 200, message = "That name is too long.")
		String managerName,

		/**
		 * The same shape as every other phone number in this application, and normalised on the way in
		 * by the same deserializer, so a number typed with spaces is stored the way the rest are.
		 */
		@JsonDeserialize(using = PhoneNumberDeserializer.class)
		@Pattern(
				regexp = "^\\+[1-9][0-9]{7,14}$",
				message = "Include the country code, for example +919876543210.")
		String managerPhone,

		LocalDate fromDate,

		/** Left out for a job they were still in when they came here. */
		LocalDate toDate,

		@Size(max = 500, message = "That reason is too long.")
		String reasonForLeaving) {
}
