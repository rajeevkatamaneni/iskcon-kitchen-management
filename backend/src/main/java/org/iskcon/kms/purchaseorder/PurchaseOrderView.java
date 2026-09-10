package org.iskcon.kms.purchaseorder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A purchase order header (E5-S3), as shown in the list. */
public record PurchaseOrderView(
		UUID id,
		String poNumber,
		UUID vendorId,
		String vendorName,
		PoStatus status,
		LocalDate orderDate,
		LocalDate neededBy,
		String deliveryLocation,
		String notes,
		String cancelReason,
		/**
		 * Whether this cancellation was recorded against the vendor — "Vendor Never Delivered this
		 * Order" (T-124), Rajeev's own wording of 2026-09-09.
		 *
		 * <p>It sits beside {@code cancelReason} on purpose, because the two are one fact read
		 * together: the reason is what the temple wrote, and this is whether the temple is holding
		 * the vendor responsible for it. T-124 wrote the tick into the row, the activity trail and
		 * the audit record, but not into this view — so the one screen where somebody asks "why was
		 * this cancelled?" could not answer the question the tick exists to answer. Reading it back
		 * here is the whole of T-126.
		 *
		 * <p>Always false unless {@code status} is CANCELLED, and the database says so rather than
		 * trusting every future writer to remember
		 * ({@code purchase_orders_abandoned_is_a_cancellation}, V118).
		 */
		boolean vendorAbandoned,
		Instant sentAt,
		Instant cancelledAt,
		Instant createdAt) {
}
