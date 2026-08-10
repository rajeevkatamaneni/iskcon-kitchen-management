package org.iskcon.kms.meal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Add a meal slot to the tenant's configurable list (E4-S4). */
public record CreateMealSlotRequest(
		@NotBlank @Size(max = 80) String name,
		int sortOrder) {
}
