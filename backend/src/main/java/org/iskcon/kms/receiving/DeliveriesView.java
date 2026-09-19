package org.iskcon.kms.receiving;

import java.time.LocalDate;
import java.util.List;

/**
 * Everything the Deliveries screen reads, in one request (R-DEL-2).
 *
 * <p>{@code today} is the temple's today, so the "Today" and late pills never read the browser's
 * clock. {@code open} is every catalogue line of a sent or part-delivered order with something still
 * to come. {@code received} is the recorded deliveries of the last 30 days, newest first, and
 * {@code receivedLines} every line those receipts touched, with its full history — including parts
 * older than the window, because "Received 50 of 50 Kg ordered · complete 15 Sept" is about the line,
 * not about the month.
 *
 * <p><strong>Why 30 days and a button</strong>: the conductor's ruling, 2026-09-19. The document
 * says only "a dated history"; the mock lists all of its sample with no limit, which a real temple's
 * history would outgrow. So the tab shows the temple's today and the 29 days before it
 * ({@code receivedFrom} is the first of those), and "Show older deliveries" asks for the 30 days
 * before that ({@link OlderDeliveriesView}). {@code hasOlder} says whether there is anything to ask
 * for, so the button can be left out when there is not.
 *
 * <p>The wire shape is {@code DeliveriesView} in {@code frontend/lib/api.ts}, field for field.
 */
public record DeliveriesView(
		LocalDate today,
		List<DeliveryLineView> open,
		List<DeliveryReceiptView> received,
		List<DeliveryLineView> receivedLines,
		LocalDate receivedFrom,
		boolean hasOlder) {
}
