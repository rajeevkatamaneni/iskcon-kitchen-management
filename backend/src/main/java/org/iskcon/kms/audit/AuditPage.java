package org.iskcon.kms.audit;

import java.util.List;

/**
 * A page of audit events plus the cursor for the next page, or null when this is the last.
 *
 * <p>Keyset pagination rather than offset: at tens of thousands of events an {@code OFFSET} scans
 * and discards every skipped row, so deep pages get linearly slower. A cursor on
 * {@code (created_at, id)} makes every page the same cost — the acceptance criterion this story
 * has to meet at 10k+ rows.
 */
public record AuditPage(
		List<AuditEventView> events,
		String nextCursor) {
}
