package org.iskcon.kms.equipment;

/** How a piece of equipment came to the temple (E3-S4). Null when the provenance isn't known. */
public enum EquipmentSource {

	/** Bought by the temple. */
	PURCHASED,

	/** Given to the temple — links to an in-kind donation (E3-S5). */
	DONATED
}
