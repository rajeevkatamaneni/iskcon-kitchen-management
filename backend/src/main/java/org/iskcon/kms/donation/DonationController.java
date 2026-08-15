package org.iskcon.kms.donation;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Donations (E3-S5). Recording an in-kind gift is receiving goods — kitchen work behind
 * {@code MANAGE_INVENTORY}. Reading the donations list exposes donor details and values, so it sits
 * behind {@code VIEW_DONATIONS}, a leadership permission.
 */
@RestController
@RequestMapping("/api/v1/donations")
public class DonationController {

	private final DonationIntakeService donationIntakeService;
	private final DonationRecorder donationRecorder;
	private final MonetaryDonationService monetaryDonationService;

	public DonationController(
			DonationIntakeService donationIntakeService, DonationRecorder donationRecorder,
			MonetaryDonationService monetaryDonationService) {
		this.donationIntakeService = donationIntakeService;
		this.donationRecorder = donationRecorder;
		this.monetaryDonationService = monetaryDonationService;
	}

	@PostMapping("/in-kind")
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public ResponseEntity<Map<String, Object>> recordInKind(
			@Valid @RequestBody RecordInKindDonationRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		UUID id = donationIntakeService.recordInKind(actor, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}

	/**
	 * A signed-in devotee giving money to their own temple (E7-S2).
	 *
	 * <p>Authentication rather than a permission, as recurring giving already is: giving is not a
	 * duty anyone is assigned, it is something any member of the temple may do. The donor is read
	 * from the token, so the request carries an amount and nothing else.
	 */
	@PostMapping("/one-time")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<DonationCheckout> giveOnce(
			@Valid @RequestBody AccountDonationRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		DonationCheckout checkout = monetaryDonationService.startCheckout(
				request.toDonor(actor), request.amountInr(), null, actor.getUserId());
		return ResponseEntity.status(HttpStatus.CREATED).body(checkout);
	}

	/** The same gift, put towards a piece of equipment the kitchen wants (E7-S6). */
	@PostMapping("/wishlist/{itemId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<DonationCheckout> giveTowards(
			@PathVariable UUID itemId,
			@Valid @RequestBody AccountDonationRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		DonationCheckout checkout = monetaryDonationService.startWishlistCheckout(
				request.toDonor(actor), itemId, 0, request.amountInr(), actor.getUserId());
		return ResponseEntity.status(HttpStatus.CREATED).body(checkout);
	}

	@GetMapping
	@PreAuthorize("hasAuthority('VIEW_DONATIONS')")
	public List<DonationView> list() {
		return donationRecorder.list();
	}

	/** Decrypts a donor's PAN for a Temple Admin (E7-S4); every read is audited. */
	@GetMapping("/{id}/pan")
	@PreAuthorize("hasAuthority('VIEW_DONATIONS')")
	public Map<String, Object> revealPan(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		String pan = monetaryDonationService.revealPan(id, actor);
		return java.util.Collections.singletonMap("pan", pan);
	}
}
