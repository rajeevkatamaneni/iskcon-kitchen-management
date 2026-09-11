package org.iskcon.kms.purchaseorder;

/** The lifecycle of a purchase order (E5-S3). Receiving (E5-S6) drives the middle three. */
public enum PoStatus {
	DRAFT,
	SENT,
	PARTIALLY_RECEIVED,
	RECEIVED,

	/**
	 * A part-delivered order a person has ended, undelivered remainder and all (T-142, D-26).
	 *
	 * <p>Rajeev's rice: 500 kg ordered, 300 sent straight away so the kitchen could cook, 200 still
	 * owed. <em>"The 200 KG should still be tied to the PO that raised and sent the 500KG rice
	 * order and it should sit in a partially delivered state and the clock keeps ticking."</em> So
	 * the remainder stays with the vendor while the order is PARTIALLY_RECEIVED, and this status is
	 * the moment somebody decides it is never coming — which releases the remainder back to the
	 * shopping list, the third door beside D-24a's creation and cancellation.
	 *
	 * <p><strong>Terminal, and different from CANCELLED in the one way that matters:</strong> goods
	 * arrived against this order, they are owed for, and the vendor is scored on what turned up.
	 * A cancellation says the order never happened; this says it happened and fell short.
	 *
	 * <p>Nothing transitions out of it. Receiving, arrivals, cancelling, editing and sending all
	 * refuse it, each by already naming the statuses it is not.
	 */
	CLOSED,
	CANCELLED
}
