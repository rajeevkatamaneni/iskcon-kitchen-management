package org.iskcon.kms.equipment;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
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
 * (E3-S10 D10). It moves through {@link ServiceScheduleRequest} instead, and so — being half of the
 * same decision — do the service company and its phone number.
 */
public record UpdateEquipmentRequest(
		@NotBlank(message = "Enter the equipment's name.")
		@Size(max = 200, message = "That name is too long.")
		String name,
		@Size(max = 120, message = "That location is too long.") String storageLocation,
		LocalDate acquisitionDate,
		EquipmentSource source,
		@Size(max = 1000, message = "That note is too long.") String notes,
		@Size(max = 120, message = "That serial number is too long.") String serialNumber,
		@PositiveOrZero(message = "A purchase cost cannot be less than nothing.")
		@Digits(integer = 10, fraction = 2, message = "Enter a cost in rupees and paise, for example 4500.")
		BigDecimal purchaseCostInr,
		LocalDate warrantyExpiry) {
}
