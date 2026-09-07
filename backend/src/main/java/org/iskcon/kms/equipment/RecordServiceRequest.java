package org.iskcon.kms.equipment;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

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
 * <p>A date in the future is refused with KMS-400016 by the service rather than by an annotation here:
 * "future" means the temple's today, not the server's, and bean validation has no way to ask.
 *
 * <p>The company is a name, typed, and not a reference to anything (V90). The screen offers the
 * machine's own company already filled in, because that is who came in almost every case, and it
 * stays changeable: a one-off repair by somebody else is exactly the visit worth recording
 * accurately.
 */
public record RecordServiceRequest(
		@NotNull LocalDate servicedOn,
		@Size(max = 200) String serviceCompany,
		@Size(max = 1000) String workDone,
		@PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal costInr) {
}
