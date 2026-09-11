package org.iskcon.kms.purchaseorder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.iskcon.kms.vendor.OrderLeadTime;
import org.iskcon.kms.vendor.OrderUrgency;

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

		/**
		 * Whether the nightly sweep cancelled this draft because its needed-by date had gone
		 * (T-137, D-24a).
		 *
		 * <p>Rajeev: <em>"mark it as Auto Cancelled. Reason: Past need by date."</em> The screens
		 * read this to say <em>Auto Cancelled</em> where they would otherwise say Cancelled, which
		 * matters because nobody in the temple did it and the person who finds the order will want
		 * to know that before they go looking for who did.
		 *
		 * <p>A column and not a sixth status, deliberately — see V125. Everything that reasons about
		 * cancellations goes on reading {@code status}.
		 */
		boolean autoCancelled,
		Instant sentAt,
		Instant cancelledAt,
		Instant createdAt,

		/**
		 * The lead time governing this order, in days, or null where nobody has said (T-137, D-25).
		 *
		 * <p><strong>Two different facts wear this one name, and which one it is depends on whether
		 * the order has gone out.</strong> While it is a draft this is read live from the vendor's
		 * supply rows — the longest across the ingredients on the order, because the order is only
		 * deliverable when its slowest item is — so editing the vendor's profile changes what the
		 * screen says about a draft, which is right: nothing has been asked of anybody yet. Once the
		 * order is sent it is the figure <em>stamped on the row</em> when it went out, and a later
		 * edit to that vendor cannot move it. Rajeev, 2026-09-10: <em>"Any SLA Adjustments made to a
		 * vendor's profile will take effect for the Orders after the change. No retroactive change
		 * here."</em>
		 *
		 * <p>Null is silence and never zero: no warning on the way out, no exclusion afterwards,
		 * nothing held against anybody.
		 */
		Integer leadTimeDays,

		/**
		 * The last day this order could be placed and still arrive — needed-by minus
		 * {@link #leadTimeDays()}. Null exactly when that or {@code neededBy} is null.
		 *
		 * <p>Computed by {@code LeadTimes.orderBy}, which is the only place that subtraction is
		 * written in this application. See {@link OrderLeadTime} for why that matters.
		 */
		LocalDate orderBy,

		/**
		 * Where today stands against {@link #orderBy()}, for an order still waiting to be sent.
		 *
		 * <p><strong>Null on an order that has already gone out</strong>, and that is not a missing
		 * answer. The zone is advice about when to press the button; once it has been pressed the
		 * question is settled, and what the order went out under is {@link #sentAfterLeadTime()}
		 * rather than a zone recomputed against today's date every time somebody opens the screen.
		 */
		OrderUrgency orderUrgency,

		/**
		 * Whether this order went out after the last day it could have been placed (T-137, D-25).
		 *
		 * <p>Decided once, in Java, at the moment of sending, and stored — never recomputed. The
		 * person who sent it was shown {@code KMS-400148} and pressed on anyway, which is allowed:
		 * <em>"That is a FAVOR we are asking."</em> What follows is that a delay on this delivery
		 * cannot fairly be counted towards the vendor's performance, so the scorecard leaves the
		 * order out of the on-time figure and counts it in its own column instead.
		 */
		boolean sentAfterLeadTime) {

	/**
	 * The same order with its lead-time facts filled in.
	 *
	 * <p>The row mapper cannot work these out on its own: a draft's governing lead time is a
	 * question about the vendor's supply rows rather than about this row, and the order-by date is
	 * arithmetic that lives in {@code LeadTimes}. So the mapper reads what is stored and the service
	 * fills the rest in one pass over a list, which is also what keeps it to one query for a whole
	 * screenful of orders.
	 */
	PurchaseOrderView with(OrderLeadTime leadTime) {
		return new PurchaseOrderView(id, poNumber, vendorId, vendorName, status, orderDate, neededBy,
				deliveryLocation, notes, cancelReason, vendorAbandoned, autoCancelled, sentAt,
				cancelledAt, createdAt, leadTime.days(), leadTime.orderBy(), leadTime.urgency(),
				sentAfterLeadTime);
	}
}
