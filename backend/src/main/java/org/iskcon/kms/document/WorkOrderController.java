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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Work orders (E10-S11), behind {@code ISSUE_INGREDIENTS}.
 *
 * <p><strong>The permission is the storekeeper's, and that is the whole argument.</strong> This
 * sheet is a picking list: it names the lots on the temple's shelves, how much of each to take, and
 * in what order, and it is signed by the two people either side of the counter. The person it is for
 * is the one who opens the store room. {@link JobCardController} reasons the other way for its own
 * sheet and reaches the opposite answer — a job card is the kitchen's own worksheet, so putting it
 * behind a manager's permission would mean a cook asking somebody else for their own job sheet. The
 * difference is not who may read a request; it is that a work order is an instrument for moving
 * stock, and {@code ISSUE_INGREDIENTS} is exactly the permission for moving stock.
 *
 * <p>It follows that a cook who raised the request cannot print the sheet for it. That is intended:
 * they can read the request on its own screen, and the paper that walks into the store room belongs
 * to the person who works there.
 *
 * <p><strong>Both paths, one control.</strong> A synchronous print view returning HTML — which works
 * with the worker down, and is what the UI's Download button falls back to — and a queued, versioned
 * PDF. Both render the same template from the same model, so what is printed and what is filed
 * cannot come apart.
 */
@RestController
@RequestMapping("/api/v1/work-orders")
public class WorkOrderController {

	private final DocumentService documentService;
	private final DocumentGenerationService generationService;
	private final WorkOrderService workOrderService;

	public WorkOrderController(
			DocumentService documentService, DocumentGenerationService generationService,
			WorkOrderService workOrderService) {
		this.documentService = documentService;
		this.generationService = generationService;
		this.workOrderService = workOrderService;
	}

	/**
	 * Queues a work order for one approved request.
	 *
	 * <p>No language at all means the temple's own, so a queued PDF and a browser print of the same
	 * request come out as the same sheet. A request that has not been approved is refused here rather
	 * than left to fail in the worker.
	 */
	@PostMapping
	@PreAuthorize("hasAuthority('ISSUE_INGREDIENTS')")
	public ResponseEntity<Map<String, Object>> request(
			@RequestParam("requestId") UUID requestId,
			@RequestParam(name = "language", required = false) String language) {

		UUID documentId = documentService.requestWorkOrderPdf(requestId, language);
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
				"documentId", documentId, "status", "PENDING"));
	}

	/**
	 * What languages a work order can be printed in, and which the picker opens on.
	 *
	 * <p>All 23 — English and the 22 scheduled — offered from the application's own list rather than
	 * from what happens to be translated already, so a slow call or an empty cache cannot shrink the
	 * picker. That correction is the job card's, recorded in {@link JobCardService}'s class doc, and
	 * it is the same correction here.
	 */
	@GetMapping("/languages")
	@PreAuthorize("hasAuthority('ISSUE_INGREDIENTS')")
	public WorkOrderService.PrintLanguages languages() {
		return workOrderService.languages();
	}

	/** Every work order printed for this request, latest version first. */
	@GetMapping("/documents")
	@PreAuthorize("hasAuthority('ISSUE_INGREDIENTS')")
	public List<DocumentView> list(@RequestParam("requestId") UUID requestId) {
		return documentService.listForIngredientRequest(requestId);
	}

	@GetMapping("/documents/{documentId}")
	@PreAuthorize("hasAuthority('ISSUE_INGREDIENTS')")
	public DocumentView get(@PathVariable UUID documentId) {
		return documentService.get(documentId);
	}

	/** Streams a generated sheet (authorised proxy — no public URL). 404 until READY. */
	@GetMapping("/documents/{documentId}/download")
	@PreAuthorize("hasAuthority('ISSUE_INGREDIENTS')")
	public ResponseEntity<InputStreamResource> download(@PathVariable UUID documentId) {
		InputStreamResource body = new InputStreamResource(documentService.openForDownload(documentId));
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_PDF)
				.header(HttpHeaders.CONTENT_DISPOSITION,
						"attachment; filename=\"work-order-" + documentId + ".pdf\"")
				.body(body);
	}

	/** The browser print view — the same sheet rendered as HTML, no worker needed. */
	@GetMapping(value = "/print", produces = "text/html;charset=UTF-8")
	@PreAuthorize("hasAuthority('ISSUE_INGREDIENTS')")
	public ResponseEntity<String> print(
			@RequestParam("requestId") UUID requestId,
			@RequestParam(name = "language", required = false) String language) {

		return ResponseEntity.ok()
				.contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
				.body(generationService.renderWorkOrderHtml(requestId, language));
	}
}
