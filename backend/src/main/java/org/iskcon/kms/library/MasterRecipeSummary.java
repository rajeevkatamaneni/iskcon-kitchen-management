package org.iskcon.kms.library;

import java.util.UUID;

/** A library recipe as it appears in a list — enough to show a row and decide what it offers. */
public record MasterRecipeSummary(
		UUID id,
		String displayName,
		String subtitle,
		String categoryName,
		String state,
		String badge,

		/**
		 * Whether the row should print the state beside the name.
		 *
		 * <p>False where the name already carries it: a row reading "Sabudana Khichdi (Maharashtra) ·
		 * Maharashtra" says it twice. Answered from the stored rung rather than by looking for a
		 * bracket in the name, because a recipe is allowed to have a bracket of its own.
		 */
		boolean showState,

		/** True where this temple already holds a copy, or a recipe of the same name. No plus icon. */
		boolean alreadyAdded) {
}
