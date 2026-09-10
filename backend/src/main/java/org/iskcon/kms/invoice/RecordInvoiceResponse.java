package org.iskcon.kms.invoice;

/**
 * The result of capturing an invoice (E5-S8). {@code duplicateWarning} is a soft signal — another
 * invoice with the same number already exists for this vendor <em>and still stands</em> — surfaced
 * but never blocking, because vendors reuse numbering schemes imperfectly.
 *
 * <p>Struck bills do not count towards it (2026-09-10). Voiding and re-entering under the same
 * number is how a mis-keyed bill is corrected, so counting the struck row warned the clerk about the
 * very care they had just taken.
 */
public record RecordInvoiceResponse(VendorInvoiceView invoice, boolean duplicateWarning) {
}
