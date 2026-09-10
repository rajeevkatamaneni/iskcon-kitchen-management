package org.iskcon.kms.calendar;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An admin correction to one calendar date (E4-S3). {@code isEkadashi} is the correction that
 * matters most — whether the temple fasts this day; {@code tithi} and a festival note are optional.
 * The reason is mandatory: an override without a recorded why is exactly what the safety net exists
 * to prevent.
 */
public record SetCalendarOverrideRequest(
		boolean isEkadashi,
		@Size(max = 120, message = "That name is too long.") String ekadashiName,
		@Min(value = 0, message = "A tithi is a number from 0 to 29.")
		@Max(value = 29, message = "A tithi is a number from 0 to 29.")
		Integer tithi,
		@Size(max = 200, message = "That festival note is too long.") String festivalNote,
		@NotBlank(message = "Say why this date is being changed.")
		@Size(max = 500, message = "That reason is too long.")
		String reason) {
}
