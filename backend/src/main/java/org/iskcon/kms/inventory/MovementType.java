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
 * </ul>
 */
public enum MovementType {
	PO_RECEIPT,
	DONATION_IN_KIND,
	CONSUMPTION,
	ADJUSTMENT
}
