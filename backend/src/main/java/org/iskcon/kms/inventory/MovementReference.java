package org.iskcon.kms.inventory;

/**
 * What a stock movement points back to, when it points to anything. Stored as text in
 * {@code stock_movements.reference_type} beside {@code reference_id}, with a CHECK mirroring this
 * set. Null for a bare manual adjustment that references nothing external.
 *
 * <p>{@link #CORRECTION} is the one that stays inside this table: a compensating movement's
 * {@code reference_id} is the id of the original movement it reverses. The link is navigable both
 * ways without ever editing the immutable original — forward by reading the correction's reference,
 * backward by querying for corrections that name a given movement.
 */
public enum MovementReference {

	/** A purchase order the goods were received against (E5). */
	PURCHASE_ORDER,

	/** A meal plan the stock was consumed for (E3-S6 / E4). */
	MEAL_PLAN,

	/** An in-kind donation record the stock came from (E3-S5). */
	DONATION,

	/** The original movement this one compensates for (E3-S2). */
	CORRECTION,

	/**
	 * The approved request the goods were issued against (E10-S7).
	 *
	 * <p>This is also how a movement says which kitchen received it. The kitchen is not copied onto
	 * the movement: the request carries it, and pointing at the request rather than duplicating the
	 * answer means the two can never come to disagree.
	 */
	INGREDIENT_REQUEST
}
