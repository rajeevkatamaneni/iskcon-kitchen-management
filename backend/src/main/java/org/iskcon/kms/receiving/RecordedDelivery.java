package org.iskcon.kms.receiving;

import java.util.List;
import java.util.UUID;

/**
 * What "Record a delivery" answers with (R-DEL-3): the goods receipts it created, one per order the
 * van brought goods for, in the order the lines named those orders. A retried press answers with
 * the same ids, because it created nothing.
 */
public record RecordedDelivery(List<UUID> receiptIds) {
}
