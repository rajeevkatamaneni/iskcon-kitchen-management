package org.iskcon.kms.donation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.document.DocumentService;
import org.iskcon.kms.document.DocumentView;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One donation, and the two things that land on it (T-110): the receipt a donor can be given or sent
 * again, and what else that donor has given.
 *
 * <p><strong>Everything here is {@code VIEW_DONATIONS}, and that is checked rather than assumed.</strong>
 * Recording a gift is {@code MANAGE_INVENTORY} because a cook takes delivery of a sack of rice at the
 * gate; striking one is {@code VOID_DONATION} because it moves an 80G figure; and reading one is
 * {@code VIEW_DONATIONS} because it exposes a donor's name, address and PAN. Issuing a receipt is a
 * read of exactly that PII put onto paper — it is the same act as
 * {@code DonationController.revealPan}, which is already {@code VIEW_DONATIONS} — so it belongs on
 * the same permission and nothing new had to be minted for it.
 *
 * <p>The donor history is deliberately <em>not</em> here. It already exists at
 * {@code /api/v1/donations/ledger/donor/{donationId}} behind the same permission, has done since
 * E7-S7, and had simply never been called by anything. A second route to it would have been the
 * parallel path this task exists to avoid.
 *
 * <p>Mapped under {@code /api/v1/donations/{donationId}}, which shares a prefix with the giving
 * page's {@code /page} and {@code /wishlist}. Those are literal segments and win the match against a
 * template, so there is no ambiguity for Spring to resolve — but a future path added here that could
 * be mistaken for a donation id would create one.
 */
@RestController
@RequestMapping("/api/v1/donations/{donationId}")
public class DonationDetailController {

	private final DonationLedgerService ledgerService;
	private final DonationReceiptService receiptService;
	private final DocumentService documentService;

	public DonationDetailController(
			DonationLedgerService ledgerService, DonationReceiptService receiptService,
			DocumentService documentService) {
		this.ledgerService = ledgerService;
		this.receiptService = receiptService;
		this.documentService = documentService;
	}

	/** The gift itself — everything the screen needs before anybody decides to receipt it. */
	@GetMapping
	@PreAuthorize("hasAuthority('VIEW_DONATIONS')")
	public DonationDetail get(@PathVariable UUID donationId) {
		return ledgerService.donation(donationId);
	}

	/**
	 * Issues the receipt, or hands back the one that is already there.
	 *
	 * <p>Pressed twice, this returns the same document id and the same receipt number both times —
	 * see {@link DocumentService#requestDonationReceiptPdf}, where the reasoning and V117's unique
	 * index behind it live. 200 rather than 202, because on every call but the first there is
	 * nothing new being accepted for processing.
	 */
	@PostMapping("/receipt")
	@PreAuthorize("hasAuthority('VIEW_DONATIONS')")
	public Map<String, Object> issueReceipt(
			@PathVariable UUID donationId, @AuthenticationPrincipal AuthenticatedUser actor) {
		UUID documentId = documentService.requestDonationReceiptPdf(donationId, actor);
		DocumentView document = documentService.get(documentId);
		// LinkedHashMap rather than Map.of: the receipt number is null until the worker has caught up
		// on a freshly issued row in a context with no scheduler, and Map.of will not carry a null.
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("documentId", documentId);
		body.put("status", document.status());
		body.put("receiptNumber", ledgerService.donation(donationId).receiptNumber());
		return body;
	}

	/**
	 * The receipt issued for this gift, or 204 where none has been.
	 *
	 * <p>No content rather than a 404: "has this gift been receipted yet" is the screen's first
	 * question and on most gifts the honest answer is no. Answering an expected "not yet" with an
	 * error would make every caller treat the ordinary case as a failure.
	 */
	@GetMapping("/receipt")
	@PreAuthorize("hasAuthority('VIEW_DONATIONS')")
	public ResponseEntity<DocumentView> receipt(@PathVariable UUID donationId) {
		// Confirms the gift is visible in this tenant before answering anything about its documents.
		ledgerService.donation(donationId);
		DocumentView document = documentService.receiptFor(donationId);
		return document == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(document);
	}

	/** Streams the receipt (authorised proxy — no public URL). 404 until READY. */
	@GetMapping("/receipt/download")
	@PreAuthorize("hasAuthority('VIEW_DONATIONS')")
	public ResponseEntity<InputStreamResource> download(@PathVariable UUID donationId) {
		DocumentView document = documentService.receiptFor(donationId);
		if (document == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
		}
		DonationDetail donation = ledgerService.donation(donationId);
		InputStreamResource body =
				new InputStreamResource(documentService.openForDownload(document.id()));
		// Named for the receipt rather than for the document's UUID, because this file lands in
		// somebody's downloads folder beside a year of other paperwork and a hex id names nothing.
		String filename = "receipt-" + donation.receiptNumber() + ".pdf";
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_PDF)
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
				.body(body);
	}

	/**
	 * Sends the donor word that their receipt has been issued — as often as they ask.
	 *
	 * <p>Issues the receipt first where none exists, so "send it to them" does not fail on a gift
	 * nobody has receipted yet. That still produces exactly one document, now and on every later
	 * press.
	 *
	 * <p>{@code sent} is false, not an error, where there is nobody to send to: an anonymous gift, or
	 * one recorded at the gate with no phone number and no email address. That is a fact about the
	 * record rather than a mistake, and the screen says so.
	 */
	@PostMapping("/receipt/send")
	@PreAuthorize("hasAuthority('VIEW_DONATIONS')")
	public Map<String, Object> sendReceipt(
			@PathVariable UUID donationId, @AuthenticationPrincipal AuthenticatedUser actor) {
		documentService.requestDonationReceiptPdf(donationId, actor);
		return Map.of("sent", receiptService.send(donationId));
	}
}
