package org.iskcon.kms.ingredientrequest;

/**
 * Where one request for ingredients stands (E10-S5 to S7).
 *
 * <p>Five values, and the machine is a straight line with one fork and one way back:
 *
 * <pre>
 *   DRAFT ──submit──▶ SUBMITTED ──approve──▶ APPROVED ──issue──▶ ISSUED
 *     ▲                   │
 *     └────withdraw───────┤
 *                         └──deny─────────▶ DENIED
 * </pre>
 *
 * <p>Five is more than the four a leave record needs, and each of the extra ones is earning its
 * place. {@link #DRAFT} exists because a cook writing down what a feast needs does not do it in one
 * sitting, and a half-written request that anybody could act on would be answered before it was
 * finished. {@link #ISSUED} exists because approving a request and handing the food over are two
 * different events on two different days, done by two different people — the same distinction the
 * system already draws between sending a purchase order and receiving one — and collapsing them
 * would mean the books said the rice had left the store while it was still on the shelf.
 *
 * <p><strong>Two of them are terminal, and deliberately so.</strong> {@link #DENIED} is a dead end
 * because a refusal that can be edited into a different request and shown again is not a refusal;
 * the requester raises a fresh one, and the denial stays on the record with its note for whoever
 * asks later. {@link #ISSUED} is a dead end because the goods have gone and the stock movements are
 * append-only — there is nothing left to change that would not be a lie about a shelf.
 *
 * <p>There is no {@code PARTIALLY_ISSUED} and no {@code CANCELLED}. A store that can only fill six
 * of eight lines fills six, and the kitchen raises a second request for the rest; a request nobody
 * wants any more is withdrawn to a draft and deleted. Both were considered and both would add a
 * state that nothing reads.
 */
public enum IngredientRequestStatus {

	/**
	 * Being written. Readable by every member of staff — two people separately drafting a request
	 * for the same feast is the failure a private draft would cause — and editable only by its
	 * author. The only status a request can be deleted from.
	 */
	DRAFT,

	/**
	 * Sent for review and waiting for an answer. Still editable by its author or an approver, and
	 * still able to go back to {@link #DRAFT} by being withdrawn, because an approver should not be
	 * reading a request its author is halfway through rewriting.
	 */
	SUBMITTED,

	/**
	 * Answered yes. Nothing has moved yet: this is a decision, not a physical event, and the store
	 * room is untouched until somebody records what actually went over the counter.
	 */
	APPROVED,

	/** Answered no, with the approver's note. Never re-answered — a fresh request is a fresh record. */
	DENIED,

	/**
	 * The store recorded what it handed over and the stock fell by that much. Terminal: the food has
	 * left the shelf, and the movements that say so cannot be edited.
	 */
	ISSUED
}
