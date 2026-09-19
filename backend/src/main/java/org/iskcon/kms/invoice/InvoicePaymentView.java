package org.iskcon.kms.invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.attachment.AttachmentView;

/**
 * One recorded payment against a vendor invoice (E7-S8). {@code amount} is signed: positive for a
 * payment, negative for a row that undoes an earlier one.
 *
 * <p>The two ends of a reversal (T-010), and the reason there are two rather than a struck flag:
 * {@code invoice_payments} is append-only, so nothing is ever marked. On the compensating row,
 * {@code reverses} names the payment it undoes and {@code reverseReason} says why. On the original,
 * {@code reversedBy} names the row that undid it — worked out here by joining the ledger to itself,
 * so a screen never has to scan the list to find out whether a payment still stands. All three are
 * null on an ordinary payment.
 *
 * <p>{@code recordedByName} keeps its name although the screen now labels it "Paid by" (R-PAY-3). The
 * label is the screen's business; the field says what the server actually knows — the person who
 * recorded the payment — and renaming it would break every client for a change of wording.
 *
 * @param receivedByName who took the cash (R-PAY-2); null on every other method, on a correction,
 *     and on every payment recorded before stage 6
 * @param attachments the payment's proof (R-PAY-3): one PAYMENT_PROOF, or a CASH_SIGNED_NOTE and a
 *     CASH_RECEIVER_PHOTO for cash, in that order. Never null; empty on a reversal and on payments
 *     recorded before proof was asked for
 */
public record InvoicePaymentView(
		UUID id,
		LocalDate paidOn,
		BigDecimal amount,
		String method,
		String reference,
		String note,
		String recordedByName,
		UUID reverses,
		UUID reversedBy,
		String reverseReason,
		String receivedByName,
		List<AttachmentView> attachments,
		Instant createdAt) {

	/** The same payment with its files, which are read for the whole list in one query afterwards. */
	InvoicePaymentView withAttachments(List<AttachmentView> files) {
		return new InvoicePaymentView(id, paidOn, amount, method, reference, note, recordedByName, reverses,
				reversedBy, reverseReason, receivedByName, files == null ? List.of() : List.copyOf(files),
				createdAt);
	}
}
