package org.iskcon.kms.equipment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Move a piece of equipment to a new condition, with a reason. The reason is required — the whole
 * point of the state-change flow is that "sent for repair" or "scrapped" never happens without a why.
 */
public record ChangeConditionRequest(
		@NotNull EquipmentCondition condition,
		@NotBlank @Size(max = 500) String reason) {
}
