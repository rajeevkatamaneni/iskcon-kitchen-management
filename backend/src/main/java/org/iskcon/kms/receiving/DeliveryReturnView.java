package org.iskcon.kms.receiving;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One return of goods against a delivered line, as the Received tab's Returned column says it:
 * "Paneer 1 Kg, spoiled, 17 Sept" (the approved {@code dev-deliveries} mock; VERIFY-C defect 4).
 *
 * <p>Only what that sentence needs: how much went back, why, and on which of the temple's days. The
 * note, who returned it and the stock movement stay on {@link GoodsReturnView}, the order page's
 * fuller record of the same row, because the Received tab is a list of deliveries and not an audit
 * of each return.
 *
 * <p>{@code quantity} is in the receipt line's unit, which is the unit {@code GoodsReturnService}
 * stores every return in (it copies the line's). {@code returnedOn} is the temple's date of
 * {@code returned_at}, worked out the way {@link DeliveryReceiptView#receivedOn()} is, so a return
 * made at 00:30 in Bengaluru is dated that day and not the day before in UTC.
 */
public record DeliveryReturnView(
		BigDecimal quantity,
		ReturnReason reason,
		LocalDate returnedOn) {
}
