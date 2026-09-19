package org.iskcon.kms.meal;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * "Repeat Children's Bhagavad-gita Reading once every [1] week / weeks … until 31 Dec 2026"
 * (Rajeev, 2026-09-19).
 *
 * <p>Both are required and nothing else is checked here. The range of {@code everyWeeks} and how
 * far away {@code until} may be are the service's to refuse, with their own codes (KMS-400175,
 * KMS-400176), because the preview takes the same two values as query parameters and must refuse
 * them identically.
 *
 * @param everyWeeks how many weeks apart, 1 to 12.
 * @param until      the last date a copy may fall on, inclusive.
 */
public record RepeatEventRequest(
		@NotNull(message = "Choose how many weeks apart.") Integer everyWeeks,
		@NotNull(message = "Choose the date to repeat until.") LocalDate until) {
}
