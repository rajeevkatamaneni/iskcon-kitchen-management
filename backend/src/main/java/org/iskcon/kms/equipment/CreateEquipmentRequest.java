package org.iskcon.kms.equipment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Register a piece of equipment. Condition is optional and defaults to GOOD — most things are added
 * in working order; something added already needing repair can say so.
 */
public record CreateEquipmentRequest(
		@NotBlank @Size(max = 200) String name,
		@NotNull EquipmentCategory category,
		@Size(max = 120) String storageLocation,
		EquipmentCondition condition,
		LocalDate acquisitionDate,
		EquipmentSource source,
		@Size(max = 1000) String notes) {
}
