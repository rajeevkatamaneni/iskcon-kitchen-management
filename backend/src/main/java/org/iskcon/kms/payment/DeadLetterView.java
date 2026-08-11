package org.iskcon.kms.payment;

import java.time.Instant;
import java.util.UUID;

/** A dead-lettered payment event as the ops page shows it (E7-S9), with a replay-after-fix path. */
public record DeadLetterView(
		UUID id,
		String providerEventId,
		String eventType,
		String error,
		int attempts,
		Instant receivedAt) {
}
