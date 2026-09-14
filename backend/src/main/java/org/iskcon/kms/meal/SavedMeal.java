package org.iskcon.kms.meal;

import java.util.UUID;
import org.iskcon.kms.error.ErrorCode;

/**
 * A meal that was saved, and the one thing worth mentioning about it if there is one.
 *
 * <p>The warning exists for a single case (E4-S16): a delivery address the map service could not
 * place. That is worth telling the admin about, because it is the one travel failure they can fix —
 * and it must not be a refusal, because the meal is a perfectly good plan and the drive is advice.
 *
 * @param id      the meal's own id (D-27) — for a meal that was already planned for that day, kind
 *                and event name, the id it already had.
 * @param warning null in the ordinary case; {@code DELIVERY_ADDRESS_NOT_FOUND} where the address
 *                could not be placed. The meal is saved either way.
 */
public record SavedMeal(UUID id, ErrorCode warning) {
}
