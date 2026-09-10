package org.iskcon.kms.vendor;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Add a vendor.
 *
 * <p>The phone is optional (T-025). It is the WhatsApp destination a purchase order is sent to, and
 * a shop somebody walks into and pays at the counter has no such destination and needs none —
 * inventing a number for it would be worse than leaving it out, because an invented number is
 * indistinguishable from a real one and would deliver the temple's order to a stranger. Sending a
 * PO to a vendor with no number refuses with {@code KMS-400130} instead.
 *
 * <p>{@code @NotBlank} is therefore gone and {@code @Pattern} deliberately stays: anything actually
 * supplied must still be a real E.164 number. Bean Validation treats null as valid for
 * {@code @Pattern}, so the pair reads exactly as the column now does — null, or E.164, and never
 * rubbish. A blank or whitespace-only string is not "no number": it fails the pattern, which is
 * what should happen to a field somebody typed a space into.
 */
public record CreateVendorRequest(
		@NotBlank(message = "Enter the vendor's name.")
		@Size(max = 200, message = "That name is too long.")
		String name,
		@Size(max = 200, message = "That name is too long.") String contactPerson,
		@Pattern(regexp = "^\\+[1-9][0-9]{7,14}$",
				message = "Include the country code, for example +919876543210.") String phone,
		@Email(message = "That doesn't look like an email address.")
		@Size(max = 200, message = "That email address is too long.")
		String email,
		@Size(max = 500, message = "That address is too long.") String address,
		@Size(max = 30, message = "That GSTIN is too long.") String gstin,
		@Size(max = 10, message = "That language code is too long.") String preferredLanguage,
		@Size(max = 1000, message = "That note is too long.") String notes,
		/**
		 * When the agreement with this vendor runs out, or null if there is no such date. Recorded
		 * and warned about, never acted on — it does not deactivate anybody.
		 */
		LocalDate contractEndDate) {
}
