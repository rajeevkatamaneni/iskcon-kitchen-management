package org.iskcon.kms.kitchen;

import java.time.Instant;
import java.util.UUID;

/**
 * One kitchen, for the list and for the detail screen alike.
 *
 * <p>There is no separate summary shape. A kitchen owns no child rows, so the list costs the same
 * query as the detail does, and two records that would always carry the same fields are two places
 * to forget to add the next one.
 */
public record KitchenView(
		UUID id,
		String name,
		String description,
		String location,
		boolean isMain,
		boolean usesMealPlanner,

		/** Who runs it, and their name — the list shows the person, not an id. Null where nobody is named. */
		UUID inChargeUserId,
		String inChargeName,

		String contactPhone,
		String status,
		Instant createdAt) {
}
