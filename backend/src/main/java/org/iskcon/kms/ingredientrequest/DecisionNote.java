package org.iskcon.kms.ingredientrequest;

import jakarta.validation.constraints.Size;

/**
 * The optional sentence an approver leaves with their answer — "take it from the older sack", "we
 * have no jaggery until Thursday".
 *
 * <p>Optional on a denial as well as an approval. A note is worth far more on a refusal and the
 * screen says so, but making it mandatory would produce a field full of full stops rather than a
 * field full of reasons.
 */
public record DecisionNote(

		@Size(max = 2000, message = "That note is too long.")
		String note) {
}
