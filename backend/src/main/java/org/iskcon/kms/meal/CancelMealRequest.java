package org.iskcon.kms.meal;

import jakarta.validation.constraints.Size;

/**
 * Cancel a meal, and with it the meal's volunteer shift (D-27, answer 5).
 *
 * <p>Optional body. The reason, where given, is what the volunteers who signed up are told; without
 * one they are told the meal was cancelled, which is true and is all a volunteer needs to stop
 * coming.
 */
public record CancelMealRequest(
		@Size(max = 500, message = "That reason is too long.") String reason) {
}
