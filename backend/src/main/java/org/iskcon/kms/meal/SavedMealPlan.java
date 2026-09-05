package org.iskcon.kms.meal;

import java.util.UUID;
import org.iskcon.kms.error.ErrorCode;

/**
 * A meal plan that was saved, and the one thing worth mentioning about it if there is one.
 *
 * <p>The warning exists for a single case (E4-S16): a delivery address the map service could not
 * place. That is worth telling the admin about, because it is the one travel failure they can fix —
 * and it must not be a refusal, because the plan is a perfectly good plan and the drive is advice.
 * Raising it would throw away everything the planner typed to report a map service's opinion of a
 * street name.
 *
 * @param warning null in the ordinary case; {@code DELIVERY_ADDRESS_NOT_FOUND} where the address
 *                could not be placed. The plan is saved either way.
 */
public record SavedMealPlan(UUID id, ErrorCode warning) {

	public static SavedMealPlan of(UUID id) {
		return new SavedMealPlan(id, null);
	}
}
