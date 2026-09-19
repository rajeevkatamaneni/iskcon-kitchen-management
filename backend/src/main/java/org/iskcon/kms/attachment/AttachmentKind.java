package org.iskcon.kms.attachment;

/**
 * What an uploaded file is for (R-INV-2, R-PAY-2). The same four values as V144's
 * {@code attachments_kind_valid}, and the table's {@code attachments_parent_matches_kind} is what
 * {@link #belongsOnAnInvoice()} mirrors: a bill only ever goes on an invoice, and the other three only
 * ever on a payment.
 */
public enum AttachmentKind {

	/** The copy of the vendor's bill, on an invoice. */
	INVOICE_BILL,

	/** A receipt, UPI screenshot or bank confirmation, on a UPI, bank transfer or cheque payment. */
	PAYMENT_PROOF,

	/** The note signed by whoever took the cash, on a cash payment. */
	CASH_SIGNED_NOTE,

	/** Their ID card or a photo of them, on a cash payment. */
	CASH_RECEIVER_PHOTO;

	public boolean belongsOnAnInvoice() {
		return this == INVOICE_BILL;
	}
}
