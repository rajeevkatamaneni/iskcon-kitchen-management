package org.iskcon.kms.staff;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Move one person's working day to another date (B7 §6).
 *
 * <p>Both dates travel together and both halves are written in one transaction, because a swap done
 * in two requests is a swap that ends up half-done the first time the second one fails — the cook
 * marked off Thursday and never added to Saturday.
 */
public record SwapShiftRequest(
		/** The day they normally work and will not, now. */
		@NotNull LocalDate fromDate,
		/** The day they will work instead, taking the hours the first day was going to have. */
		@NotNull LocalDate toDate,
		@Size(max = 300) String note) {
}
