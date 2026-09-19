package org.iskcon.kms.receiving;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One recorded delivery, for the Received tab's dated history (R-DEL-2). One goods receipt, which
 * is one order: a van that brought goods for two of a vendor's orders is two of these, recorded by
 * the one press of "Record a delivery" (R-DEL-3).
 *
 * <p>{@code receivedOn} is the temple's date of the receipt, the same date its parts carry in
 * {@link DeliveryPartView#receivedOn()}, so the two tabs can never date one delivery two ways.
 */
public record DeliveryReceiptView(
		UUID receiptId,
		UUID poId,
		String poNumber,
		UUID vendorId,
		String vendorName,
		LocalDate receivedOn,
		String receivedByName,
		List<DeliveryReceiptLineView> lines) {
}
