package org.iskcon.kms.donation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.iskcon.kms.equipment.EquipmentCategory;

/** One piece of equipment given in kind: it becomes a DONATED asset in the equipment register. */
public record EquipmentDonationLine(
		@NotBlank @Size(max = 200) String name,
		@NotNull EquipmentCategory category,
		@Size(max = 1000) String notes) {
}
