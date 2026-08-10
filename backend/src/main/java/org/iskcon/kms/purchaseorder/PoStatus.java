package org.iskcon.kms.purchaseorder;

/** The lifecycle of a purchase order (E5-S3). Receiving (E5-S6) drives the last three. */
public enum PoStatus {
	DRAFT,
	SENT,
	PARTIALLY_RECEIVED,
	RECEIVED,
	CANCELLED
}
