package org.iskcon.kms.equipment;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Register a piece of equipment. Condition is optional and defaults to GOOD — most things are added
 * in working order; something added already needing repair can say so.
 *
 * <p>The last three arrived with E3-S10 and are facts about the <em>purchase</em>, which is why they
 * are here on the register's own form rather than behind {@code MANAGE_EQUIPMENT_SERVICING}: whoever
 * unpacks the grinder is the person holding the invoice and looking at the plate on the back. The
 * service interval and the company that services it are the administrator's and live on their own
 * endpoint ({@link ServiceScheduleRequest}), which since V90 carries the company as plain text
 * rather than a reference to a list.
 *
 * <p>All three optional. Furniture has no serial number and no warranty, and the temple will not
 * know what a donated table cost.
 */
public record CreateEquipmentRequest(
		@NotBlank @Size(max = 200) String name,
		@Size(max = 120) String storageLocation,
		EquipmentCondition condition,
		LocalDate acquisitionDate,
		EquipmentSource source,
		@Size(max = 1000) String notes,
		@Size(max = 120) String serialNumber,
		@PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal purchaseCostInr,
		LocalDate warrantyExpiry) {
}
