package org.iskcon.kms.equipment;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Record one service that has happened (E3-S10 D2).
 *
 * <p>The date is the only thing insisted on. Who came, what they did and what it cost are all things
 * a temple may genuinely not have to hand a week later, and a form that refuses the row until every
 * box is filled produces no row at all — which is the outcome this feature exists to prevent.
 *
 * <p>There is deliberately no way to edit or delete a service afterwards, and no "last serviced"
 * field anywhere that a person can type into. Both would let a service that happened stop having
 * happened.
 *
 * <p>A date in the future is refused with KMS-4016 by the service rather than by an annotation here:
 * "future" means the temple's today, not the server's, and bean validation has no way to ask.
 */
public record RecordServiceRequest(
		@NotNull LocalDate servicedOn,
		UUID serviceProviderId,
		@Size(max = 1000) String workDone,
		@PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal costInr) {
}
