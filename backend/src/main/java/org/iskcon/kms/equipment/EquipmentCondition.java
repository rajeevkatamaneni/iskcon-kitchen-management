package org.iskcon.kms.equipment;

/**
 * The state a piece of equipment is in (E3-S4). Changed only through a recorded state change, so the
 * reason and the who are never lost. {@link #SCRAPPED} is terminal — a scrapped item drops out of the
 * default views but keeps its history.
 */
public enum EquipmentCondition {

	/** Working and in service. */
	GOOD,

	/** Faulty and awaiting repair. */
	NEEDS_REPAIR,

	/** Away being repaired, or being repaired on site. */
	IN_REPAIR,

	/** Written off — beyond repair or disposed of. Hidden from default views. */
	SCRAPPED
}
