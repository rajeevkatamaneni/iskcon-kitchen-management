package org.iskcon.kms.recipe;

import java.util.UUID;

/**
 * One row of the Recipes page's single search box, from either source (E2-S10).
 *
 * <p>The box searches the temple's own recipes and the shared library together, because a cook
 * looking for a dish does not know or care which of the two it is in. What differs is what the row
 * offers: a library recipe the temple has not taken carries a plus, and one it already holds does
 * not — that is the whole of the distinction the person sees.
 */
public record RecipeSearchResult(

		/** {@code MINE} or {@code LIBRARY}. Decides where the row links and whether it offers a plus. */
		String origin,

		/** The temple's recipe id, or the library recipe's. They address different routes. */
		UUID id,

		String name,
		String subtitle,
		String categoryName,

		/** The state a library recipe came from; null for the temple's own. */
		String state,

		/** Whether to print the state beside the name — false where the name already carries it. */
		boolean showState,

		String badge,

		/** Library rows only: true where this temple already holds it, so no plus is offered. */
		boolean alreadyAdded,

		/** The temple's own rows only: {@code ACTIVE} or {@code ARCHIVED}. */
		String status,

		/** The temple's own rows only: true where a prohibited ingredient was overridden. */
		boolean sattvicOverridden) {
}
