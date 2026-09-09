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
 *   <li>{@link #RETURN_TO_VENDOR} — sent back to the supplier after it was already taken into stock
 *       (T-013). Negative, and the third door.
 *   <li>{@link #USED_BEYOND_RECORDED_STOCK} — cooked with more of something than the books held
 *       (T-087). Negative, and the only one of these the temple did not choose to do.
 * </ul>
 *
 * <p><strong>Adding a value here is never only a Java change.</strong> The CHECK constraint above is
 * the other half of this enum, and a value added on one side alone fails at runtime rather than at
 * compile time — the insert is refused by the database on the day somebody uses the new kind, not on
 * the day it was added. Every addition therefore carries a migration, and an integration test that
 * actually inserts the new value is what proves the two halves agree.
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
	ISSUE,

	/**
	 * Sent back to the supplier after it had already been taken into stock (T-013). Negative.
	 *
	 * <p>Distinct from a rejection, and that distinction is the whole reason this value exists.
	 * A rejection happens <em>at the gate</em>: {@code goods_receipt_lines.rejected_qty} records
	 * goods the storekeeper refused off the lorry, and those never enter the ledger at all, so
	 * there is nothing to take back out. This is what happens afterwards — weevils found in the
	 * sacks the next morning, or fifty kilos keyed when five arrived — once the stock is on the
	 * books and the only honest way to remove it is a movement that says so.
	 *
	 * <p>Not an {@link #ADJUSTMENT}, though the arithmetic would be identical. An adjustment is the
	 * temple correcting its own count; this is a statement about a supplier, and a store keeper
	 * reading the ledger back — or a scorecard reading it later — needs the two to be different
	 * rows. Collapsing them would make "goods we sent back to Govind Wholesale" a question that can
	 * only be answered by reading free text.
	 */
	RETURN_TO_VENDOR,

	/**
	 * The kitchen cooked with more of an ingredient than the store room's books held (T-087).
	 * Negative, and the fourth door — but the only one the temple did not choose to walk through.
	 *
	 * <p><strong>It exists because recording a meal is not allowed to refuse.</strong> The food is
	 * already cooked and the rice already left the store; refusing the record does not put it back,
	 * it moves the lie out of the stock ledger and into the meal record, where it is much harder to
	 * find. Driven on staging during the September review: a Dinner of 60 L of curd rice was
	 * refused, 20 L was refused, and 1 L was accepted — so the record says the temple served one
	 * litre of curd rice to 235 people, and the advice it gave, <em>"cook a smaller quantity"</em>,
	 * was addressed to a meal that had already happened.
	 *
	 * <p><strong>Why a named kind rather than simply letting the number go negative.</strong>
	 * Rajeev's reasoning, kept in his words because the cheaper option looks identical from the
	 * inside: <em>"Negative numbers get normalised and ignored; a named movement appears in a list
	 * somebody reads, and it says which ingredient's paperwork is behind."</em> A minus sign is a
	 * quantity, and every reader of this ledger is a sum. A row that says <em>used beyond recorded
	 * stock — 40 Kg, Sona Masuri, Lunch of 23 August</em> is a sentence, and it names the thing to
	 * go and chase.
	 *
	 * <p><strong>And what it is not.</strong> Not an {@link #ADJUSTMENT}: an adjustment is the
	 * temple correcting its own count, a deliberate act by somebody who went and looked at the
	 * shelf. This is the opposite — nobody looked, nobody decided, and the ledger is reporting an
	 * inconsistency it has just discovered. Collapsing the two would bury the discovery among the
	 * corrections, which is exactly the list it needs to stand out from.
	 *
	 * <p><strong>Stock is allowed to go impossible, and that is the finding rather than the bug.</strong>
	 * If the books say 20 Kg and the kitchen used 60, the missing 40 did not come from nowhere:
	 * somebody did not record a delivery. The ingredient sits below zero until that delivery is
	 * written down, and it should — an on-hand figure of minus forty kilos is a question, and the
	 * question is the point.
	 */
	USED_BEYOND_RECORDED_STOCK
}
