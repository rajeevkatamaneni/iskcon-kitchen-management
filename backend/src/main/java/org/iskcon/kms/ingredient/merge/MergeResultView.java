package org.iskcon.kms.ingredient.merge;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a merge did. Mirrors {@code MergeResultView} in {@code frontend/lib/api.ts}.
 *
 * @param aliasesAdded the names the kept ingredient now also answers to, as they were written — the
 *     merged-away names, and any aliases those ingredients carried
 * @param repointed per table that the database catalogue shows pointing at {@code ingredients}, how
 *     many rows pointed at a merged-away ingredient and now belong to the kept one — re-pointed, or,
 *     where a table holds one row per ingredient (a vendor's supply, an inventory item, a shopping-list
 *     decision, a pack of the same size), folded into the kept ingredient's row. Every table is listed,
 *     with 0 where nothing pointed, so the answer shows it looked everywhere.
 */
public record MergeResultView(
		UUID keptIngredientId,
		List<UUID> mergedIngredientIds,
		List<String> aliasesAdded,
		Map<String, Long> repointed) {
}
