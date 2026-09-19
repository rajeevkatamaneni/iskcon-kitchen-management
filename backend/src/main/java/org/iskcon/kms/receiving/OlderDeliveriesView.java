package org.iskcon.kms.receiving;

import java.time.LocalDate;
import java.util.List;

/**
 * The next 30 days back of the Received tab (conductor's ruling, 2026-09-19): the receipts recorded
 * in the 30 days ending the day before the {@code before} the screen passed, newest first, with the
 * same {@code receivedLines} rule as {@link DeliveriesView}. {@code receivedFrom} is the first day
 * this page covers, which the screen passes back as the next {@code before}.
 *
 * <p>The wire shape is {@code OlderDeliveriesView} in {@code frontend/lib/api.ts}, field for field.
 */
public record OlderDeliveriesView(
		List<DeliveryReceiptView> received,
		List<DeliveryLineView> receivedLines,
		LocalDate receivedFrom,
		boolean hasOlder) {
}
