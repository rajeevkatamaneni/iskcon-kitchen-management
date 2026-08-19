package org.iskcon.kms.communication;

import java.util.EnumSet;
import java.util.Set;

/**
 * What one devotee has chosen to hear (E8-S1) — the shape their own preferences screen renders and
 * the shape an unsubscribe link updates.
 *
 * @param optedOutOfAll  they want nothing optional, whatever the per-category set says. Recorded as
 *                       a fact of its own so a category added next year does not quietly
 *                       re-subscribe them.
 * @param optedOut       the individual kinds they have declined
 */
public record CommunicationPreferences(boolean optedOutOfAll, Set<CommunicationCategory> optedOut) {

	public static CommunicationPreferences none() {
		return new CommunicationPreferences(false, EnumSet.noneOf(CommunicationCategory.class));
	}

	/** Whether a message of this category may be sent to them at all. */
	public boolean accepts(CommunicationCategory category) {
		if (!category.isOptional()) {
			return true;
		}
		return !optedOutOfAll && !optedOut.contains(category);
	}
}
