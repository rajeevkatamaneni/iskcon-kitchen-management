package org.iskcon.kms.invoice;

import jakarta.validation.Valid;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Vendor invoice capture (E5-S8), itemised since stage 6 (T-271), behind {@code MANAGE_PURCHASE_ORDERS}. */
@RestController
@RequestMapping("/api/v1/vendor-invoices")
public class VendorInvoiceController {

	private final VendorInvoiceService service;

	public VendorInvoiceController(VendorInvoiceService service) {
		this.service = service;
	}

	/**
	 * The Invoices list. {@code owed=true} is its Unpaid filter: bills money is still owed on, the same
	 * set as {@code /api/v1/payables}, but readable by anyone who can open the list. Payables stays
	 * behind MANAGE_VENDOR_PAYMENTS because it carries each bill's outstanding balance and aging; this
	 * returns only the invoice rows the list already shows to the same people, so it needs nothing
	 * stricter than the list does. {@code overdue=true} is past due and still owed (T-281).
	 */
	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public List<VendorInvoiceView> list(
			@RequestParam(required = false) InvoiceStatus status,
			@RequestParam(required = false, defaultValue = "false") boolean overdue,
			@RequestParam(required = false, defaultValue = "false") boolean owed) {
		return service.list(status, overdue, owed);
	}

	/** One invoice's page (R-INV-7): the row plus its items, deliveries, totals and bill. */
	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public VendorInvoiceDetailView get(@PathVariable UUID id) {
		return service.get(id);
	}

	/**
	 * A vendor's deliveries that no standing invoice bills yet (R-INV-3), newest first — what the
	 * invoice form offers once a vendor is chosen. A literal path, so Spring prefers it to
	 * {@code /{id}} rather than trying to read "billable-deliveries" as an invoice id.
	 */
	@GetMapping("/billable-deliveries")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public List<BillableDeliveryView> billableDeliveries(@RequestParam UUID vendorId) {
		return service.billableDeliveries(vendorId);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public ResponseEntity<RecordInvoiceResponse> record(
			@Valid @RequestBody RecordInvoiceRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return ResponseEntity.status(HttpStatus.CREATED).body(service.record(actor, request));
	}

	/**
	 * Strikes a bill that should never have been recorded (T-010). A POST rather than a DELETE,
	 * because nothing is removed: the row stays, marked, and the URL says what actually happens to it
	 * — the principle {@code StaffPayController}'s void already states, followed here.
	 *
	 * <p>Behind the stricter {@code MANAGE_VENDOR_PAYMENTS} rather than the
	 * {@code MANAGE_PURCHASE_ORDERS} that captures an invoice. Anyone who buys can record what a
	 * vendor billed; withdrawing a bill from the pay cycle is a money decision, and this temple gives
	 * that permission to its administrator alone.
	 */
	@PostMapping("/{id}/void")
	@PreAuthorize("hasAuthority('MANAGE_VENDOR_PAYMENTS')")
	public ResponseEntity<Void> voidInvoice(
			@PathVariable UUID id,
			@Valid @RequestBody VoidInvoiceRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		service.voidInvoice(actor, id, request);
		return ResponseEntity.noContent().build();
	}

	/** Records a credit note against a bill that stands: it was owed, and it is now owed less. */
	@PostMapping("/{id}/credit")
	@PreAuthorize("hasAuthority('MANAGE_VENDOR_PAYMENTS')")
	public ResponseEntity<Void> creditInvoice(
			@PathVariable UUID id,
			@Valid @RequestBody CreditInvoiceRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		service.creditInvoice(actor, id, request);
		return ResponseEntity.noContent().build();
	}
}
