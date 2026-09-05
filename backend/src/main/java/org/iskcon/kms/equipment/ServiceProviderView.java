package org.iskcon.kms.equipment;

import java.time.Instant;
import java.util.UUID;

/**
 * A firm or a person who services temple equipment (E3-S10 D7).
 *
 * <p>Carries {@code equipmentCount} so the list can say what a provider is holding before anybody
 * tries to remove it — one annual maintenance contract covering six machines is the case this list
 * exists for, and "used by 6" is the fact that makes the delete button legible.
 */
public record ServiceProviderView(
		UUID id,
		String name,
		String phone,
		String email,
		String note,
		int equipmentCount,
		Instant createdAt) {
}
