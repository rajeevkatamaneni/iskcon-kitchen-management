package org.iskcon.kms.donation;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.document.DocumentService;
import org.iskcon.kms.document.DocumentView;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A devotee's own gifts and their receipts (T-179).
 *
 * <p>{@code VIEW_OWN_DONATIONS}, which is not {@code VIEW_DONATIONS}. That one reads the whole
 * ledger — every donor's name, address and PAN — and the admin's Send receipt on
 * {@link DonationDetailController} stays on it, unchanged, as the safety net Rajeev asked it to
 * remain. This controller reads only what {@link MyDonationsService} decides belongs to the caller.
 *
 * <p><strong>Why the token is verified a second time here.</strong> The contacts that decide a
 * counter gift is yours have to be ones you have <em>proven</em>: the token's phone number, and its
 * email only when Firebase says it is verified. {@code AuthenticationFilter} verifies the token and
 * then keeps only the uid; the principal it builds, {@link AuthenticatedUser}, carries the email and
 * phone from our own {@code users} row, which an administrator typed and nobody has proven, and it
 * carries no {@code emailVerified} at all. The verified subject is used at request time only by the
 * first-sign-in claim. Carrying it on the principal would mean editing the filter and the principal,
 * both outside this task, so this reads the same bearer token again through the same
 * {@link TokenVerifier}. The cost is a second verification on these two endpoints only (with Firebase
 * that includes the revocation lookup); the follow-up to remove it is in T-179's proof.
 *
 * <p>If the second verification fails — a token revoked in the milliseconds since the filter
 * accepted it — the caller is matched by account alone. That fails towards showing less, never more.
 */
@RestController
@RequestMapping("/api/v1/my-donations")
public class MyDonationsController {

	private static final Logger log = LoggerFactory.getLogger(MyDonationsController.class);
	private static final String BEARER_PREFIX = "Bearer ";

	private final MyDonationsService service;
	private final DocumentService documentService;
	private final TokenVerifier tokenVerifier;

	public MyDonationsController(
			MyDonationsService service, DocumentService documentService, TokenVerifier tokenVerifier) {
		this.service = service;
		this.documentService = documentService;
		this.tokenVerifier = tokenVerifier;
	}

	/** Every successful gift that is the caller's own at the temple this request speaks for. */
	@GetMapping
	@PreAuthorize("hasAuthority('VIEW_OWN_DONATIONS')")
	public List<MyDonation> list(
			@AuthenticationPrincipal AuthenticatedUser caller, HttpServletRequest request) {
		return service.list(caller.getUserId(), verifiedContacts(caller, request));
	}

	/**
	 * Streams the receipt for one of the caller's own gifts.
	 *
	 * <p>404 {@code KMS-400030} for everything that is not "your gift, with a receipt ready" — see
	 * {@link MyDonationsService#requireOwnReceiptNumber}. The ownership check runs <em>before</em> the
	 * document is looked up, so nothing about another person's receipt is read on a stranger's request.
	 */
	@GetMapping("/{donationId}/receipt/download")
	@PreAuthorize("hasAuthority('VIEW_OWN_DONATIONS')")
	public ResponseEntity<InputStreamResource> download(
			@PathVariable UUID donationId,
			@AuthenticationPrincipal AuthenticatedUser caller,
			HttpServletRequest request) {
		String receiptNumber = service.requireOwnReceiptNumber(
				donationId, caller.getUserId(), verifiedContacts(caller, request));

		DocumentView document = documentService.receiptFor(donationId);
		if (document == null) {
			// A number issued with no document behind it yet. The same answer as a stranger gets, for
			// the same reason: there is nothing here this person can download.
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("donationId", donationId));
		}
		// Refuses a document not yet READY with the same code.
		InputStreamResource body = new InputStreamResource(documentService.openForDownload(document.id()));
		String filename = "receipt-" + receiptNumber + ".pdf";
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_PDF)
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
				.body(body);
	}

	private MyDonationsService.VerifiedContacts verifiedContacts(
			AuthenticatedUser caller, HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.startsWith(BEARER_PREFIX)) {
			return MyDonationsService.VerifiedContacts.NONE;
		}
		try {
			TokenVerifier.VerifiedSubject subject =
					tokenVerifier.verify(header.substring(BEARER_PREFIX.length()).trim());
			return MyDonationsService.VerifiedContacts.from(subject, caller.getFirebaseUid());
		} catch (TokenVerifier.InvalidTokenException e) {
			log.debug("Second token verification failed; matching own donations by account only");
			return MyDonationsService.VerifiedContacts.NONE;
		}
	}
}
