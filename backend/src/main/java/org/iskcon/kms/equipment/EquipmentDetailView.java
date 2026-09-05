package org.iskcon.kms.equipment;

import java.util.List;

/**
 * A piece of equipment with its two histories, each newest first.
 *
 * <p>Two, and deliberately not one merged trail: a service is a different event from a change of
 * condition, and a grinder can be serviced every six months for five years without its condition
 * ever moving off GOOD (E3-S10). The condition trail answers "what state has this been in"; the
 * service trail answers "when did somebody last look at it".
 */
public record EquipmentDetailView(
		EquipmentView equipment,
		List<EquipmentStateChange> history,
		List<EquipmentServiceRecord> services) {
}
