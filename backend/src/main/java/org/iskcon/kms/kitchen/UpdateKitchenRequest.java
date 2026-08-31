package org.iskcon.kms.kitchen;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Editing a kitchen. A full replacement of the editable fields, as every other edit in this
 * codebase is: a partial patch would make "the caller left it out" and "the caller cleared it"
 * indistinguishable, and clearing who runs a kitchen is a thing a temple does.
 *
 * <p>Turning {@code isMain} on here moves the flag off whichever kitchen holds it, in the same
 * transaction. Turning it off is not offered: a temple that wants a different main kitchen names
 * that kitchen, and leaving the temple with none is not a state the register should be able to
 * reach through an edit.
 */
public record UpdateKitchenRequest(

		@NotBlank(message = "Enter the kitchen's name.")
		@Size(max = 200, message = "That name is too long.")
		String name,

		@Size(max = 2000, message = "That description is too long.")
		String description,

		@Size(max = 500, message = "That location is too long.")
		String location,

		boolean isMain,

		boolean usesMealPlanner,

		UUID inChargeUserId,

		@Size(max = 30, message = "That phone number is too long.")
		String contactPhone) {
}
