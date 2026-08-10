package org.iskcon.kms.equipment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Edit an item's descriptive fields. Condition is deliberately absent — it changes only through a
 * recorded state change (with a reason), never as a silent field edit.
 */
public record UpdateEquipmentRequest(
		@NotBlank @Size(max = 200) String name,
		@NotNull EquipmentCategory category,
		@Size(max = 120) String storageLocation,
		LocalDate acquisitionDate,
		EquipmentSource source,
		@Size(max = 1000) String notes) {
}
