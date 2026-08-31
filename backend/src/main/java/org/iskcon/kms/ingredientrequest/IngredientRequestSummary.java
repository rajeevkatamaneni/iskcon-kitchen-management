package org.iskcon.kms.ingredientrequest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A request as one row of the list: reference, kitchen, when it is wanted, who asked, where it
 * stands.
 *
 * <p>Its own shape rather than the full record, unlike kitchens. A request owns two sets of child
 * rows and a history, and fetching all of them for every row of a list somebody scrolls is three
 * queries per line for information that does not fit on one.
 */
public record IngredientRequestSummary(
		UUID id,
		String reference,
		UUID kitchenId,
		String kitchenName,
		LocalDate neededOn,
		String purpose,
		IngredientRequestStatus status,
		UUID requestedBy,
		String requestedByName,
		Instant submittedAt,
		String decidedByName,
		Instant decidedAt,
		Instant issuedAt,
		int lineCount,
		int dishCount) {
}
