package org.iskcon.kms.equipment;

import java.time.Instant;
import java.util.UUID;

/** One entry in an item's condition history: from what, to what, why, and by whom. */
public record EquipmentStateChange(
		UUID id,
		EquipmentCondition fromCondition,
		EquipmentCondition toCondition,
		String reason,
		UUID actorUserId,
		String actorName,
		Instant createdAt) {
}
