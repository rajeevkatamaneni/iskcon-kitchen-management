package org.iskcon.kms.equipment;

import java.util.List;

/** A piece of equipment with its full condition history, newest first. */
public record EquipmentDetailView(
		EquipmentView equipment,
		List<EquipmentStateChange> history) {
}
