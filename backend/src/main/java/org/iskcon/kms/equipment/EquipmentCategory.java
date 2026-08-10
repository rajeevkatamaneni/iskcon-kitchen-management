package org.iskcon.kms.equipment;

/**
 * What kind of asset a piece of equipment is (E3-S4). Stored as text with a CHECK mirroring this
 * set — a fixed, small vocabulary, unlike the free-text categories of the ingredient catalogue.
 */
public enum EquipmentCategory {

	/** Powered or mechanical: wet grinder, mixer, steam boiler, refrigerator. */
	MACHINE,

	/** Hand equipment: ladles, cauldrons, cutting boards, weighing scales. */
	TOOL,

	/** Fittings and fixtures: trestle tables, shelving, serving counters. */
	FURNITURE
}
