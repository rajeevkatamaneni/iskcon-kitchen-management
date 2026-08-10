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
		@Size(max = 120) String ekadashiName,
		@Min(0) @Max(29) Integer tithi,
		@Size(max = 200) String festivalNote,
		@NotBlank @Size(max = 500) String reason) {
}
