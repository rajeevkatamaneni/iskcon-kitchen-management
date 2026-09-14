package org.iskcon.kms.document;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.meal.ServedMealService;
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
 * Job cards (B5), behind {@code MANAGE_MEAL_PLANS}.
 *
 * <p>Deliberately the same permission as the meal plan itself, and deliberately not an
 * administrative one: the card is the kitchen's own worksheet, and putting it behind a manager's
 * permission would mean a cook has to ask somebody else for their own job sheet (brief §15 item 9).
 * Anybody who can see the plan can print the card for it.
 *
 * <p><strong>A meal is addressed by its id (D-27).</strong> Until D-27 a card was asked for by a date,
 * a kind's name and, for an event, the event's name, because the meal's own row was created on demand
 * by the first print and a screen had no id to send. That identification is what let two unnamed
 * events on one Saturday print as one card. Every meal has a row from the moment it is planned now,
 * and the planner holds its id.
 */
@RestController
@RequestMapping("/api/v1/job-cards")
public class JobCardController {

	private final ServedMealService servedMealService;
	private final DocumentService documentService;
	private final DocumentGenerationService generationService;
	private final JobCardService jobCardService;

	public JobCardController(
			ServedMealService servedMealService, DocumentService documentService,
			DocumentGenerationService generationService, JobCardService jobCardService) {
		this.servedMealService = servedMealService;
		this.documentService = documentService;
		this.generationService = generationService;
		this.jobCardService = jobCardService;
	}

	/**
	 * Queues a card for one meal, issuing its number if this is the first print.
	 *
	 * <p>{@code language} is the recipes appendix's language, not the sheet's — the worksheet is
	 * always English. {@code none} asks for the worksheet on its own, and no language at all means
	 * the temple's own.
	 */
	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public ResponseEntity<Map<String, Object>> request(
			@RequestParam UUID mealId,
			@RequestParam(name = "language", required = false) String language) {

		String cardNumber = servedMealService.issueCardNumber(mealId);
		UUID documentId = documentService.requestJobCardPdf(mealId, language);
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
				"documentId", documentId, "cardNumber", cardNumber, "status", "PENDING"));
	}

	/** What languages this meal's recipes can be printed in, and which the picker opens on (item 17). */
	@GetMapping("/languages")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public JobCardService.AppendixLanguages languages(@RequestParam UUID mealId) {
		return jobCardService.appendixLanguages(mealId);
	}

	/** Every card printed for this meal, latest version first. */
	@GetMapping("/documents")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public List<DocumentView> list(@RequestParam UUID mealId) {
		// Through require first, so a meal that is not this temple's is a refusal rather than an empty
		// list that reads as "never printed".
		servedMealService.require(mealId);
		return documentService.listForMeal(mealId);
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
			@RequestParam UUID mealId,
			@RequestParam(name = "language", required = false) String language) {

		// Printing issues the number too. A sheet that came out of the printer without one could not
		// be traced back later, which is the only reason the number exists.
		servedMealService.issueCardNumber(mealId);
		return ResponseEntity.ok()
				.contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
				.body(generationService.renderJobCardHtml(mealId, language));
	}
}
