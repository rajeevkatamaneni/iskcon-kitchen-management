package org.iskcon.kms.equipment;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Edit an item's descriptive fields. Condition is deliberately absent — it changes only through a
 * recorded state change (with a reason), never as a silent field edit.
 *
 * <p>So is the service interval, and for a different reason: it is not a descriptive fact but a
 * commitment of the temple's money and somebody's diary, and it belongs to the administrator
 * (E3-S10 D10). It moves through {@link ServiceScheduleRequest} instead.
 */
public record UpdateEquipmentRequest(
		@NotBlank @Size(max = 200) String name,
		@NotNull EquipmentCategory category,
		@Size(max = 120) String storageLocation,
		LocalDate acquisitionDate,
		EquipmentSource source,
		@Size(max = 1000) String notes,
		@Size(max = 120) String serialNumber,
		@PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal purchaseCostInr,
		LocalDate warrantyExpiry) {
}
