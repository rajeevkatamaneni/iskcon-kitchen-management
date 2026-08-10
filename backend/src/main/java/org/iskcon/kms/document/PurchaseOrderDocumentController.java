package org.iskcon.kms.document;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Purchase-order documents (E5-S4), behind {@code MANAGE_PURCHASE_ORDERS}. Request a versioned PDF
 * (queued off the request thread), list versions latest-first, download through an authorized
 * backend stream, or get the browser print view rendered inline.
 */
@RestController
public class PurchaseOrderDocumentController {

	private final DocumentService documentService;
	private final DocumentGenerationService generationService;

	public PurchaseOrderDocumentController(
			DocumentService documentService, DocumentGenerationService generationService) {
		this.documentService = documentService;
		this.generationService = generationService;
	}

	/** Queues a new version of the PO sheet on demand. */
	@PostMapping("/api/v1/purchase-orders/{poId}/pdf")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public ResponseEntity<Map<String, Object>> requestPdf(
			@PathVariable UUID poId,
			@RequestParam(name = "language", required = false) String language) {
		UUID id = documentService.requestPurchaseOrderPdf(poId, language);
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("documentId", id, "status", "PENDING"));
	}

	/** Every generated sheet for the PO, latest version first. */
	@GetMapping("/api/v1/purchase-orders/{poId}/documents")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public List<DocumentView> list(@PathVariable UUID poId) {
		return documentService.listForPurchaseOrder(poId);
	}

	@GetMapping("/api/v1/purchase-orders/{poId}/documents/{documentId}")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public DocumentView get(@PathVariable UUID poId, @PathVariable UUID documentId) {
		return documentService.get(documentId);
	}

	/** Streams a generated sheet (authorized proxy — no public URL). 404 until READY. */
	@GetMapping("/api/v1/purchase-orders/{poId}/documents/{documentId}/download")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public ResponseEntity<InputStreamResource> download(
			@PathVariable UUID poId, @PathVariable UUID documentId) {
		InputStreamResource body = new InputStreamResource(documentService.openForDownload(documentId));
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_PDF)
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"po-" + documentId + ".pdf\"")
				.body(body);
	}

	/** The browser print view — the same sheet rendered as HTML, no worker needed. */
	@GetMapping(value = "/api/v1/purchase-orders/{poId}/print", produces = MediaType.TEXT_HTML_VALUE)
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public ResponseEntity<String> print(
			@PathVariable UUID poId,
			@RequestParam(name = "language", required = false) String language) {
		return ResponseEntity.ok()
				.contentType(MediaType.TEXT_HTML)
				.body(generationService.renderPurchaseOrderHtml(poId, language));
	}
}
