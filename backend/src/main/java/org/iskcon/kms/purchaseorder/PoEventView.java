package org.iskcon.kms.purchaseorder;

import java.time.Instant;

/** One entry in a PO's activity trail (E5-S3). */
public record PoEventView(String eventType, String detail, String actorName, Instant createdAt) {
}
