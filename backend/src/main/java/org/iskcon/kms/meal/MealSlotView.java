package org.iskcon.kms.meal;

import java.util.UUID;

/** A meal slot in the tenant's configurable list (E4-S4). */
public record MealSlotView(UUID id, String name, int sortOrder) {
}
