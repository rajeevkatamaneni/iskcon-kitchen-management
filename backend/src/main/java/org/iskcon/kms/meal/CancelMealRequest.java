package org.iskcon.kms.meal;

import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Cancel a meal, and with it the meal's volunteer shift (D-27, answer 5) — and, for a repeating
 * event, optionally every later occurrence too (T-307).
 *
 * <p>Optional body. The reason, where given, is what the volunteers who signed up are told; without
 * one they are told the meal was cancelled, which is true and is all a volunteer needs to stop
 * coming.
 *
 * @param scope           {@code THIS} (or absent: exactly what cancel always did) or
 *                        {@code THIS_AND_LATER}, Rajeev's <em>"JUST this event OR all events from
 *                        this point onwards"</em>.
 * @param expectedMealIds the later occurrences the confirmation showed, from
 *                        {@code GET /meals/{id}/later-in-series}. Where given, a cancel whose later
 *                        set has changed since is refused and cancels nothing (KMS-400179).
 */
public record CancelMealRequest(
		@Size(max = 500, message = "That reason is too long.") String reason,
		Scope scope,
		@Size(max = 200, message = "That is more events than a repeat can make.") List<UUID> expectedMealIds) {

	/** How much of a repeating event to cancel. */
	public enum Scope {
		/** This meal only. */
		THIS,
		/** This meal and every later occurrence still to be cooked. */
		THIS_AND_LATER
	}

	/** The scope asked for, with absent meaning {@link Scope#THIS}. */
	public Scope scopeOrThis() {
		return scope == null ? Scope.THIS : scope;
	}
}
