package org.iskcon.kms.purchaseorder;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Close a part-delivered purchase order, naming how it ended for the vendor (T-142, D-26).
 *
 * <p><strong>The outcome is required and has no default.</strong> Every other shape was worse: a
 * default of {@code AS_COMPUTED} would let a client that forgets the field quietly decline to say
 * anything, and a default of either named outcome would put words in somebody's mouth about a
 * supplier. A closing is a decision, and the request either carries the decision or is refused.
 *
 * <p><strong>The note is required for the two named outcomes and optional for the third</strong>,
 * which is D-26 word for word: <em>anything other than "as computed" requires a sentence</em>. It
 * is expressed as Bean Validation rather than as an error code of its own, because that is the
 * established pattern for "this field is wrong" in this application — {@code KMS-400001} answers
 * with a {@code fieldErrors} entry naming the field — and the database carries the same rule underneath
 * ({@code purchase_orders_named_outcome_has_a_sentence}, V126) so a caller that bypasses this
 * layer is refused by the row itself.
 */
public record ClosePoRequest(
		@NotNull(message = "Say how this order ended for the vendor.")
		CloseOutcome outcome,
		@Size(max = 500, message = "That is too long. A sentence or two is what a reader needs.")
		String note) {

	/**
	 * The sentence D-26 requires whenever the closing says something about the supplier.
	 *
	 * <p>Named as a question about the request rather than about the field, following the one other
	 * cross-field rule in this application ({@code UpdatePreferenceRequest.isExactlyOneThing}).
	 * Blank counts as absent: a space bar is not an explanation, and accepting one would leave a
	 * permanent claim on a vendor's record with an empty line where its reason should be.
	 */
	@AssertTrue(message = "Say why, in a sentence. This is going on the vendor's record.")
	public boolean isNamedOutcomeExplained() {
		if (outcome == null || outcome == CloseOutcome.AS_COMPUTED) {
			return true;
		}
		return note != null && !note.isBlank();
	}
}
