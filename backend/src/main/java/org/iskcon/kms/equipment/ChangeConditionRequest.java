package org.iskcon.kms.equipment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Move a piece of equipment to a new condition, with a reason. The reason is required — the whole
 * point of the state-change flow is that "sent for repair" or "scrapped" never happens without a why.
 */
public record ChangeConditionRequest(
		@NotNull(message = "Choose the condition it is in now.") EquipmentCondition condition,
		@NotBlank(message = "Say why the condition changed.")
		@Size(max = 500, message = "That reason is too long.")
		String reason) {
}
