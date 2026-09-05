package org.iskcon.kms.equipment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A piece of equipment as shown in the list and detail header.
 *
 * <p>The last six fields are <strong>derived at read time and stored nowhere</strong> (E3-S10 D4).
 * {@code lastServicedOn} is the newest row in the service history, {@code nextServiceOn} is that
 * plus the interval — or the acquisition date plus the interval where nothing has ever been
 * serviced, which {@code nextServiceBasis} says in as many words — and {@code serviceStatus} places
 * that against the temple's own warning horizon.
 */
public record EquipmentView(
		UUID id,
		String name,
		String storageLocation,
		EquipmentCondition condition,
		LocalDate acquisitionDate,
		EquipmentSource source,
		String notes,
		Instant createdAt,

		// --- Registered facts about the thing itself (E3-S10 D8, D9) ---
		String serialNumber,
		BigDecimal purchaseCostInr,
		LocalDate warrantyExpiry,

		// --- The service schedule, as the temple set it (E3-S10 D3, D7) ---
		Integer serviceIntervalDays,
		ServiceInterval serviceIntervalUnit,
		// The count in the unit above — the six of "every six months". Null with no interval.
		Integer serviceIntervalCount,
		// Who services it and how to reach them, as typed. Two plain columns since V90: the managed
		// list D7 originally argued for was reversed on 2026-09-04 as more machinery than the fact
		// deserved.
		String serviceCompany,
		String serviceCompanyPhone,

		// --- Derived, never stored (E3-S10 D4) ---
		LocalDate lastServicedOn,
		LocalDate nextServiceOn,
		NextServiceBasis nextServiceBasis,
		ServiceStatus serviceStatus) {
}
