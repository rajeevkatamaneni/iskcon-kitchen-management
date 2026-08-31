package org.iskcon.kms.inventory;

/**
 * Why a stock movement happened. Stored as text in {@code stock_movements.movement_type}, with a
 * CHECK constraint mirroring this set (a fixed, small vocabulary that changes rarely, unlike audit
 * actions).
 *
 * <p>Every operational stock change resolves to one of these:
 *
 * <ul>
 *   <li>{@link #PO_RECEIPT} — goods received against a purchase order (E5). A positive movement
 *       that establishes a batch.
 *   <li>{@link #DONATION_IN_KIND} — food given rather than bought (E3-S5). Also batch-establishing.
 *   <li>{@link #CONSUMPTION} — drawn down to cook a meal (E3-S6). Negative.
 *   <li>{@link #ADJUSTMENT} — a manual correction (E3-S7) or a compensating correction of an
 *       earlier movement. Signed either way, and the only type that carries a reason.
 *   <li>{@link #ISSUE} — handed to one of the temple's other kitchens against an approved request
 *       (E10-S7). Negative, and the second door stock leaves the store by.
 * </ul>
 */
public enum MovementType {
	PO_RECEIPT,
	DONATION_IN_KIND,
	CONSUMPTION,
	ADJUSTMENT,

	/**
	 * Issued to a child kitchen (E10-S7). Negative.
	 *
	 * <p>Note what this is <em>not</em>: a transfer into a second balance. The temple keeps one
	 * store, and a kitchen that only asks for ingredients is not running this application, so
	 * nothing would ever draw that second balance down and within a month it would be a number
	 * saying the Deity kitchen still holds rice it ate in September. Issuing is the food leaving
	 * the temple's books, and what happens to it afterwards is the kitchen's own business.
	 */
	ISSUE
}
