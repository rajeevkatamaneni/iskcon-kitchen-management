package org.iskcon.kms.ingredientrequest;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of the request's history, as a person reads it: <em>"Approved by Radha Devi — 'take from
 * the older sack' · 29 Aug"</em>.
 *
 * <p>The actor's name is the one stored on the event rather than one joined from {@code users}, so
 * the trail keeps saying who did this even after they have left the temple.
 */
public record IngredientRequestEventView(
		UUID id,
		String eventType,
		String detail,
		String actorName,
		Instant at) {
}
