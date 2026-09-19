package org.iskcon.kms.attachment;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.error.ErrorResponse;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Uploading the copy of a bill and proof of payment, and reading them back (R-INV-2, R-PAY-2,
 * R-PAY-3).
 *
 * <p>The paths sit under {@code /api/v1/vendor-invoices} because that is what the files belong to,
 * beside {@code VendorInvoiceController} and {@code InvoicePaymentController}, which own the rest of
 * it. None of these four collides with theirs: {@code bill-uploads} and {@code payment-uploads} are
 * POSTs of one segment where they have only {@code POST /} and {@code POST /{id}/void|credit|payments},
 * and the two GETs are deeper than their {@code GET /{id}} and {@code GET /{id}/payments}.
 *
 * <p>The permissions follow the thing the file is part of, not the act of uploading. A bill is part
 * of recording an invoice, which is {@code MANAGE_PURCHASE_ORDERS}; proof of payment is part of paying
 * one, which is {@code MANAGE_VENDOR_PAYMENTS} and is the Temple Admin alone. So the person who may
 * record a bill may upload its copy, and nobody gains a way to see or add payment proof by being able
 * to see the invoice.
 *
 * <p>Neither download is a link. Each file is streamed through here, with the permission checked and
 * RLS applied, exactly as a generated document is — there is no URL to the bucket to pass around.
 */
@RestController
public class AttachmentController {

	/** The kinds {@code payment-uploads} takes, in the words the caller sends. */
	private static final List<AttachmentKind> PAYMENT_KINDS = Arrays.stream(AttachmentKind.values())
			.filter(kind -> !kind.belongsOnAnInvoice())
			.toList();

	private final AttachmentService service;

	public AttachmentController(AttachmentService service) {
		this.service = service;
	}

	/**
	 * The copy of a bill, uploaded as soon as it is chosen and before the invoice is saved. The
	 * invoice's save claims it by id.
	 *
	 * <p>{@code file} is optional here only so that leaving it out is answered in our words. Declared
	 * required, Spring raises its own exception for a missing part, which nothing maps, and the
	 * person would be told something went wrong at our end.
	 */
	@PostMapping("/api/v1/vendor-invoices/bill-uploads")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public ResponseEntity<AttachmentView> uploadBill(
			@RequestParam(name = "file", required = false) MultipartFile file,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(service.upload(actor, AttachmentKind.INVOICE_BILL, file));
	}

	/**
	 * Proof of payment, or for cash the signed note and the photo of who took it.
	 *
	 * <p>{@code kind} is read as text and checked here rather than bound to the enum. Bound, the enum
	 * would accept INVOICE_BILL — a bill uploaded under the payment permission, which is not what this
	 * endpoint is for — and an unknown value would be answered with a list of choices that includes
	 * it.
	 */
	@PostMapping("/api/v1/vendor-invoices/payment-uploads")
	@PreAuthorize("hasAuthority('MANAGE_VENDOR_PAYMENTS')")
	public ResponseEntity<AttachmentView> uploadPaymentFile(
			@RequestParam(name = "kind") String kind,
			@RequestParam(name = "file", required = false) MultipartFile file,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		AttachmentKind paymentKind = PAYMENT_KINDS.stream()
				.filter(k -> k.name().equals(kind))
				.findFirst()
				.orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("kind", kind),
						List.of(new ErrorResponse.FieldError("kind", "Choose one of "
								+ String.join(", ", PAYMENT_KINDS.stream().map(Enum::name).toList()) + ".")),
						null));
		return ResponseEntity.status(HttpStatus.CREATED).body(service.upload(actor, paymentKind, file));
	}

	@GetMapping("/api/v1/vendor-invoices/{invoiceId}/bill")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public ResponseEntity<InputStreamResource> bill(@PathVariable UUID invoiceId) {
		return send(service.openBill(invoiceId), "bill");
	}

	@GetMapping("/api/v1/vendor-invoices/{invoiceId}/payments/{paymentId}/attachments/{attachmentId}")
	@PreAuthorize("hasAuthority('MANAGE_VENDOR_PAYMENTS')")
	public ResponseEntity<InputStreamResource> paymentFile(
			@PathVariable UUID invoiceId, @PathVariable UUID paymentId, @PathVariable UUID attachmentId) {
		return send(service.openPaymentFile(invoiceId, paymentId, attachmentId), "payment-proof");
	}

	/**
	 * Inline, so a browser shows the photo or the PDF rather than saving it, under the name the device
	 * gave it. The type is the one the bytes were found to be on upload, never what was declared, so
	 * a file is shown as what it is. Not cached anywhere on the way: it is a temple's bill.
	 */
	private ResponseEntity<InputStreamResource> send(AttachmentService.AttachmentFile file, String fallbackName) {
		String name = file.originalName() != null
				? file.originalName()
				: fallbackName + AttachmentFileType.extensionFor(file.contentType());
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(file.contentType()))
				.contentLength(file.sizeBytes())
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.inline().filename(name, StandardCharsets.UTF_8).build().toString())
				.cacheControl(CacheControl.noStore().cachePrivate())
				.body(new InputStreamResource(file.content()));
	}
}
