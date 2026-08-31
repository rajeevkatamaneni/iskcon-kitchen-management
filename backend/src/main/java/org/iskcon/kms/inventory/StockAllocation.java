package org.iskcon.kms.inventory;

import java.util.List;

/**
 * What {@link FefoAllocator} worked out: every ingredient's draws, and every ingredient the store
 * cannot cover.
 *
 * <p>Nothing here has been written. Both callers — cooking a meal (E3-S6) and issuing to a kitchen
 * (E10-S7) — decide in full before they write anything at all, because a half-finished drawdown that
 * took the dal but not the rice would leave two stock figures wrong and no way to tell which.
 */
public record StockAllocation(List<AllocatedLine> lines, List<StockShortfall> shortfalls) {

	/** Whether the store can cover every line. */
	public boolean sufficient() {
		return shortfalls.isEmpty();
	}
}
