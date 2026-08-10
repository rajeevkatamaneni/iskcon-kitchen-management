package org.iskcon.kms.invoice;

/**
 * The result of capturing an invoice (E5-S8). {@code duplicateWarning} is a soft signal — another
 * invoice with the same number already exists for this vendor — surfaced but never blocking, because
 * vendors reuse numbering schemes imperfectly.
 */
public record RecordInvoiceResponse(VendorInvoiceView invoice, boolean duplicateWarning) {
}
