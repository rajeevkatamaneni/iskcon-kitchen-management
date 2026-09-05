package org.iskcon.kms.equipment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One visit: the day the work was done, who did it, what was done, what it cost, and who wrote it
 * down (E3-S10 D2).
 *
 * <p>{@code servicedOn} and {@code createdAt} are different facts and both are kept. A service done
 * on Tuesday and recorded on Friday is a normal thing that happens in a temple, and the register
 * should not have to choose which of the two days it is willing to remember.
 */
public record EquipmentServiceRecord(
		UUID id,
		LocalDate servicedOn,
		UUID serviceProviderId,
		String serviceProviderName,
		String workDone,
		BigDecimal costInr,
		UUID actorUserId,
		String actorName,
		Instant createdAt) {
}
