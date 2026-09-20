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

		/**
		 * How many people currently work here (Epic 12): staff records naming this kitchen whose
		 * employment is ACTIVE. Former staff are not counted — they still name a kitchen, because the
		 * column is NOT NULL, but "4 staff" in the planner's kitchen picker means people there now.
		 */
		int staffCount,

		String contactPhone,
		String status,
		Instant createdAt) {
}
