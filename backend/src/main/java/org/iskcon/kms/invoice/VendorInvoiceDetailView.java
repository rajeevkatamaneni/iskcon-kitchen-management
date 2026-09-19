package org.iskcon.kms.invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.attachment.AttachmentView;

/**
 * One invoice's page (R-INV-7): every field of {@link VendorInvoiceView}, in the same order and
 * under the same names, then its lines, the deliveries it bills, the bill's totals and the copy of
 * the bill. The client declares it as {@code VendorInvoiceDetailView extends VendorInvoiceView};
 * a Java record cannot extend another, so the base fields are repeated here and {@link #of} is the
 * one place that copies them, which keeps the two from drifting.
 *
 * <p>Every totals field is null on an invoice recorded before stage 6, and {@code lines} and
 * {@code deliveries} are then empty; {@code bill} is null on an old invoice that has none. Null, not
 * zero: nobody can say now what an old bill's GST was.
 */
public record VendorInvoiceDetailView(
		UUID id,
		UUID vendorId,
		String vendorName,
		UUID purchaseOrderId,
		String poNumber,
		boolean direct,
		String description,
		String invoiceNumber,
		LocalDate invoiceDate,
		BigDecimal amount,
		LocalDate dueDate,
		String scanRef,
		InvoiceStatus status,
		BigDecimal expectedValue,
		BigDecimal variance,
		boolean overdue,
		Instant voidedAt,
		String voidReason,
		BigDecimal creditedAmount,
		Instant createdAt,
		List<InvoiceLineView> lines,
		List<InvoiceDeliveryView> deliveries,
		BigDecimal subTotal,
		BigDecimal gstAmount,
		BigDecimal otherCharges,
		String otherChargesNote,
		BigDecimal discount,
		BigDecimal grandTotal,
		AttachmentView bill) {

	/** The totals block as stored, all null on an invoice recorded before stage 6. */
	record Totals(BigDecimal subTotal, BigDecimal gstAmount, BigDecimal otherCharges,
			String otherChargesNote, BigDecimal discount, BigDecimal grandTotal) {
	}

	static VendorInvoiceDetailView of(VendorInvoiceView v, List<InvoiceLineView> lines,
			List<InvoiceDeliveryView> deliveries, Totals totals, AttachmentView bill) {
		return new VendorInvoiceDetailView(v.id(), v.vendorId(), v.vendorName(), v.purchaseOrderId(),
				v.poNumber(), v.direct(), v.description(), v.invoiceNumber(), v.invoiceDate(), v.amount(),
				v.dueDate(), v.scanRef(), v.status(), v.expectedValue(), v.variance(), v.overdue(),
				v.voidedAt(), v.voidReason(), v.creditedAmount(), v.createdAt(), List.copyOf(lines),
				List.copyOf(deliveries), totals.subTotal(), totals.gstAmount(), totals.otherCharges(),
				totals.otherChargesNote(), totals.discount(), totals.grandTotal(), bill);
	}
}
