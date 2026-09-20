package org.iskcon.kms.staff;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

/**
 * "These are right" on the Temple Admin's "Check these kitchen assignments" list (Epic 12): the staff
 * records whose kitchen the admin has looked at and is leaving as it is.
 *
 * <p>Named one by one rather than "confirm everything", so the admin confirms exactly the rows they
 * were shown. A record flagged after the list was drawn — somebody the migration touched that a
 * second admin has not seen yet — stays on the list rather than being waved through unseen.
 *
 * @param staffIds the staff records to mark checked; each must be somebody currently employed here
 */
public record ConfirmKitchenChecksRequest(
		@NotEmpty(message = "Choose at least one person to confirm.")
		List<UUID> staffIds) {
}
