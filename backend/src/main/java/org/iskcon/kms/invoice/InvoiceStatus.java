package org.iskcon.kms.invoice;

/**
 * Where a vendor invoice sits in the pay cycle (E5-S8). Capture starts it PENDING; the flip to PAID
 * belongs to payment execution (E7-S9), not here.
 */
public enum InvoiceStatus {

	/** Recorded, owed, not yet paid. */
	PENDING,

	/** Paid — set by E7-S9. */
	PAID,

	/**
	 * Struck as never owed (T-010), and terminal: nothing takes an invoice back out of here.
	 *
	 * <p>A third state rather than a flag, because every figure that sums invoices has to skip it —
	 * the payables queue, the overdue derivation, the pay-cycle filters — and a status is the thing
	 * all of those already read.
	 *
	 * <p>Note what is deliberately <em>not</em> here: a credited invoice. A credit says the bill was
	 * owed and is now owed less, so it stays in the cycle and can still be paid; it lives on
	 * {@code credited_amount} instead. Voiding and crediting being one word is exactly the confusion
	 * a temple arguing with a vendor a year later cannot afford.
	 */
	VOIDED,
}
