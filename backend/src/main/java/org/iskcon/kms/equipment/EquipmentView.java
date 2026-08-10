package org.iskcon.kms.equipment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A piece of equipment as shown in the list and detail header. */
public record EquipmentView(
		UUID id,
		String name,
		EquipmentCategory category,
		String storageLocation,
		EquipmentCondition condition,
		LocalDate acquisitionDate,
		EquipmentSource source,
		String notes,
		Instant createdAt) {
}
