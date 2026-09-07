package org.iskcon.kms.equipment;

/**
 * The state a piece of equipment is in (E3-S4). Changed only through a recorded state change, so the
 * reason and the who are never lost.
 *
 * <p>{@link #SCRAPPED} closes the ordinary path and nothing has loosened that.
 * {@code EquipmentService.changeCondition} refuses every condition change made after a scrapping,
 * unconditionally, with {@code EQUIPMENT_SCRAPPED} — there is no flag, no request field and no
 * caller that reaches past that guard, and it must stay that way.
 *
 * <p>There is exactly one way back and it is not this enum's business to make easy: a scrapped item
 * can be reinstated through {@code EquipmentService.reinstate}, an endpoint of its own behind
 * {@code REINSTATE_SCRAPPED_EQUIPMENT} — the Temple Admin's alone — which insists on a reason and
 * audits as {@code EQUIPMENT_REINSTATED} rather than as an ordinary condition change (D-15). That
 * is a deliberate, named, recorded act for an item somebody scrapped by mistake or cannot replace.
 * It is not a hint that scrapping is casual: the register still hides scrapped items until asked,
 * and the screen still asks before it scraps anything.
 *
 * <p>Which is why {@code changeCondition}'s refusal is a separate mechanism from the way back, and
 * why the two must not be merged. A future reader tempted to "simplify" by letting this path accept
 * a scrapped item would be removing the guard, not tidying it.
 */
public enum EquipmentCondition {

	/** Working and in service. */
	GOOD,

	/** Faulty and awaiting repair. */
	NEEDS_REPAIR,

	/** Away being repaired, or being repaired on site. */
	IN_REPAIR,

	/**
	 * Written off — beyond repair or disposed of. Hidden from default views, and closed to any
	 * further condition change; a Temple Admin may reinstate it, with a reason, through the
	 * endpoint that exists for exactly that.
	 */
	SCRAPPED
}
