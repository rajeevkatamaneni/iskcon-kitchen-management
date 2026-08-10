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
}
