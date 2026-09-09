package org.iskcon.kms.donation;

import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Donations (E3-S5). Recording a gift someone handed over — cash, food, or equipment — is front-desk
 * and kitchen work behind {@code MANAGE_INVENTORY}. Reading what was given exposes donor details and
 * values and lives in the ledger ({@link DonationLedgerController}), behind {@code VIEW_DONATIONS}.
 *
 * <p>Striking one is a third act with a third permission, {@code VOID_DONATION} (D-4): it changes
 * what the temple reports under 80G, and it reverses stock. Recording is everyday work, reading is
 * confidential, and undoing is neither.
 */
@RestController
@RequestMapping("/api/v1/donations")
public class DonationController {

	private final DonationIntakeService donationIntakeService;
	private final MonetaryDonationService monetaryDonationService;
	private final DonationVoidService donationVoidService;

	public DonationController(
			DonationIntakeService donationIntakeService, MonetaryDonationService monetaryDonationService,
			DonationVoidService donationVoidService) {
		this.donationIntakeService = donationIntakeService;
		this.monetaryDonationService = monetaryDonationService;
		this.donationVoidService = donationVoidService;
	}

	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public ResponseEntity<Map<String, Object>> record(
			@Valid @RequestBody RecordDonationRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		UUID id = donationIntakeService.record(actor, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}

	/**
	 * Strikes a gift that was recorded wrongly (T-012) — entered twice, or against the wrong donor.
	 *
	 * <p>Behind {@code VOID_DONATION}, the Temple Admin's alone (D-4). Forced rather than chosen:
	 * {@code VIEW_DONATIONS} is already Temple Admin alone, so anything wider would let somebody
	 * strike a record they cannot read. Recording deliberately stays on {@code MANAGE_INVENTORY}
	 * (D-5), so a cook may create one of these and never undo one — inherited knowingly, because an
	 * in-kind gift is a sack of rice arriving at the gate and a cook is who receives it.
	 *
	 * <p>A POST rather than a DELETE, because nothing is deleted. The gift stays in the ledger,
	 * marked, and the in-kind half is reversed by a compensating stock movement in the same
	 * transaction — see {@link DonationVoidService}.
	 */
	@PostMapping("/{id}/void")
	@PreAuthorize("hasAuthority('VOID_DONATION')")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void voidDonation(
			@PathVariable UUID id,
			@Valid @RequestBody VoidDonationRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		donationVoidService.voidDonation(actor, id, request.reason());
	}

	/**
	 * A signed-in devotee giving money to their own temple (E7-S2).
	 *
	 * <p>Authentication rather than a permission: giving is not a duty anyone is assigned, it is
	 * something any member of the temple may do. The donor is read from the token, so the request
	 * carries an amount and nothing else.
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
				request.toDonor(actor), itemId, request.amountInr(), actor.getUserId());
		return ResponseEntity.status(HttpStatus.CREATED).body(checkout);
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
