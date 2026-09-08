package org.iskcon.kms.invoice;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recording vendor invoice payments and the payables view (E7-S8), behind
 * {@code MANAGE_VENDOR_PAYMENTS} — a finance/leadership permission. The system records payments made
 * elsewhere; it never executes them.
 */
@RestController
public class InvoicePaymentController {

	private final InvoicePaymentService service;

	public InvoicePaymentController(InvoicePaymentService service) {
		this.service = service;
	}

	@PostMapping("/api/v1/vendor-invoices/{id}/payments")
	@PreAuthorize("hasAuthority('MANAGE_VENDOR_PAYMENTS')")
	public ResponseEntity<Map<String, Object>> record(
			@PathVariable UUID id,
			@Valid @RequestBody RecordInvoicePaymentRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		UUID paymentId = service.recordPayment(actor, id, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", paymentId));
	}

	/**
	 * Undoes a payment recorded in error (T-010). A POST rather than a DELETE, for the reason
	 * {@code StaffPayController}'s void gives: nothing is removed.
	 *
	 * <p>But <em>reverse</em> rather than <em>void</em>, and the difference in the word is the
	 * difference in the table. A void promises a mark on the row, which is what happens to a staff
	 * payment; {@code invoice_payments} is append-only, so what happens here is a compensating entry
	 * of the opposite sign. A URL saying {@code /void} would promise something the database refuses.
	 */
	@PostMapping("/api/v1/vendor-invoices/{invoiceId}/payments/{paymentId}/reverse")
	@PreAuthorize("hasAuthority('MANAGE_VENDOR_PAYMENTS')")
	public ResponseEntity<Void> reverse(
			@PathVariable UUID invoiceId,
			@PathVariable UUID paymentId,
			@Valid @RequestBody ReverseInvoicePaymentRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		service.reversePayment(actor, invoiceId, paymentId, request);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/api/v1/vendor-invoices/{id}/payments")
	@PreAuthorize("hasAuthority('MANAGE_VENDOR_PAYMENTS')")
	public List<InvoicePaymentView> payments(@PathVariable UUID id) {
		return service.payments(id);
	}

	@GetMapping("/api/v1/payables")
	@PreAuthorize("hasAuthority('MANAGE_VENDOR_PAYMENTS')")
	public List<PayableView> payables() {
		return service.payables();
	}
}
