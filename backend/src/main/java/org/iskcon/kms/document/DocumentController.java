package org.iskcon.kms.document;

import java.math.BigDecimal;
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
 * Recipe documents (E2-S5), behind {@code MANAGE_RECIPES}. Request a PDF (queued off the request
 * thread), poll its status, and download it through an authorized backend stream.
 */
@RestController
public class DocumentController {

	private final DocumentService documentService;

	public DocumentController(DocumentService documentService) {
		this.documentService = documentService;
	}

	/** Queues a recipe PDF at the given scale (omit targetYield for the base yield). */
	@PostMapping("/api/v1/recipes/{recipeId}/pdf")
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public ResponseEntity<Map<String, Object>> requestPdf(
			@PathVariable UUID recipeId,
			@RequestParam(name = "targetYield", required = false) BigDecimal targetYield,
			@RequestParam(name = "language", required = false) String language) {

		UUID id = documentService.requestRecipePdf(recipeId, targetYield, language);
		return ResponseEntity.status(HttpStatus.ACCEPTED)
				.body(Map.of("documentId", id, "status", "PENDING"));
	}

	@GetMapping("/api/v1/documents/{id}")
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public DocumentView get(@PathVariable UUID id) {
		return documentService.get(id);
	}

	/** Streams the generated file (authorized proxy — no public URL). 404 until READY. */
	@GetMapping("/api/v1/documents/{id}/download")
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public ResponseEntity<InputStreamResource> download(@PathVariable UUID id) {
		InputStreamResource body = new InputStreamResource(documentService.openForDownload(id));
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_PDF)
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"recipe-" + id + ".pdf\"")
				.body(body);
	}
}
