package org.iskcon.kms.document;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.meal.ServedMealService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.format.annotation.DateTimeFormat;
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
 * Job cards (B5), behind {@code MANAGE_MEAL_PLANS}.
 *
 * <p>Deliberately the same permission as the meal plan itself, and deliberately not an
 * administrative one: the card is the kitchen's own worksheet, and putting it behind a manager's
 * permission would mean a cook has to ask somebody else for their own job sheet (brief §15 item 9).
 * Anybody who can see the plan can print the card for it.
 *
 * <p>A meal is addressed by its date and its kind rather than by an id, because that is what the
 * caller has — the meal's own row is created on demand by the first print, and asking a screen to
 * know an id that does not exist yet would be backwards.
 */
@RestController
@RequestMapping("/api/v1/job-cards")
public class JobCardController {

	private final ServedMealService servedMealService;
	private final DocumentService documentService;
	private final DocumentGenerationService generationService;

	public JobCardController(
			ServedMealService servedMealService, DocumentService documentService,
			DocumentGenerationService generationService) {
		this.servedMealService = servedMealService;
		this.documentService = documentService;
		this.generationService = generationService;
	}

	/** Queues a card for one meal, issuing its number if this is the first print. */
	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public ResponseEntity<Map<String, Object>> request(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam String mealKind,
			@RequestParam(name = "language", required = false) String language) {

		String cardNumber = servedMealService.issueCardNumber(date, mealKind);
		UUID documentId = documentService.requestJobCardPdf(
				servedMealService.serviceFor(date, mealKind), language);
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
				"documentId", documentId, "cardNumber", cardNumber, "status", "PENDING"));
	}

	/** Every card printed for this meal, latest version first. */
	@GetMapping("/documents")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public List<DocumentView> list(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam String mealKind) {

		return documentService.listForMealService(servedMealService.serviceFor(date, mealKind));
	}

	@GetMapping("/documents/{documentId}")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public DocumentView get(@PathVariable UUID documentId) {
		return documentService.get(documentId);
	}

	/** Streams a generated card (authorised proxy — no public URL). 404 until READY. */
	@GetMapping("/documents/{documentId}/download")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public ResponseEntity<InputStreamResource> download(@PathVariable UUID documentId) {
		InputStreamResource body = new InputStreamResource(documentService.openForDownload(documentId));
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_PDF)
				.header(HttpHeaders.CONTENT_DISPOSITION,
						"attachment; filename=\"job-card-" + documentId + ".pdf\"")
				.body(body);
	}

	/** The browser print view — the same card rendered as HTML, no worker needed. */
	@GetMapping(value = "/print", produces = "text/html;charset=UTF-8")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public ResponseEntity<String> print(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam String mealKind,
			@RequestParam(name = "language", required = false) String language) {

		// Printing issues the number too. A sheet that came out of the printer without one could not
		// be traced back later, which is the only reason the number exists.
		servedMealService.issueCardNumber(date, mealKind);
		UUID mealServiceId = servedMealService.serviceFor(date, mealKind);
		return ResponseEntity.ok()
				.contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
				.body(generationService.renderJobCardHtml(mealServiceId, language));
	}
}
