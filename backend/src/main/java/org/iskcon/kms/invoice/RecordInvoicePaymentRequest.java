package org.iskcon.kms.invoice;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Records a payment made to a vendor outside the app (E7-S8). The amount must be more than ₹0; the
 * service refuses anything else with {@code KMS-400174}. This used to take a negative amount as a
 * hand-entered correction, with no proof; it no longer does (T-280). A payment recorded by mistake is
 * undone with the Reverse action, which writes the compensating negative entry itself.
 *
 * <p>The refusal is in {@link InvoicePaymentService} rather than a {@code @Positive} here because a
 * field annotation answers with the generic form error, and this one has its own code whose next step
 * points at Reverse.
 *
 * <p><strong>The proof (R-PAY-2, stage 6).</strong> The last four fields are the files and the name
 * that show the money really left the temple, and which of them a payment needs depends on how it was
 * paid:
 *
 * <ul>
 *   <li>UPI, bank transfer or cheque: {@code proofAttachmentId}, an upload of kind PAYMENT_PROOF —
 *       the receipt, the UPI screenshot, the bank's confirmation.</li>
 *   <li>Cash: {@code receivedByName}, {@code signedNoteAttachmentId} (CASH_SIGNED_NOTE) and
 *       {@code receiverPhotoAttachmentId} (CASH_RECEIVER_PHOTO). Cash leaves no bank record, so the
 *       person who took it, their signature and their face are the record.</li>
 * </ul>
 *
 * <p>None of the four is annotated {@code @NotNull}, because which ones are required is a question
 * about {@code method} and a field annotation cannot ask it. {@link InvoicePaymentService} asks it, and
 * answers a missing one with the same field error, against the same field name, that an annotation
 * would have produced. The ids are of files uploaded while the form was open (T-267's
 * upload-first-claim-on-save); the service claims them in the transaction that saves the payment.
 *
 * <p>There is deliberately no field for the kind of ID the cash receiver showed. Rajeev ruled it out
 * (R-PAY-2): staff are told in training what counts, and a drop-down would only be filled in with
 * whatever came first.
 */
public record RecordInvoicePaymentRequest(
		@NotNull(message = "Enter the date of the payment.") LocalDate paidOn,
		@NotNull(message = "Enter the amount paid.") BigDecimal amount,
		@NotNull(message = "Choose how the vendor was paid.") PaymentMethod method,
		@Size(max = 100, message = "That reference is too long.") String reference,
		@Size(max = 500, message = "That note is too long.") String note,
		UUID proofAttachmentId,
		@Size(max = 200, message = "That name is too long.") String receivedByName,
		UUID signedNoteAttachmentId,
		UUID receiverPhotoAttachmentId) {

	public enum PaymentMethod { BANK_TRANSFER, UPI, CHEQUE, CASH }
}
