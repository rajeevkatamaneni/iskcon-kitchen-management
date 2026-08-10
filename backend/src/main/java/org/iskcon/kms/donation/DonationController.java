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

	public DonationController(
			DonationIntakeService donationIntakeService, DonationRecorder donationRecorder) {
		this.donationIntakeService = donationIntakeService;
		this.donationRecorder = donationRecorder;
	}

	@PostMapping("/in-kind")
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public ResponseEntity<Map<String, Object>> recordInKind(
			@Valid @RequestBody RecordInKindDonationRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		UUID id = donationIntakeService.recordInKind(actor, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}

	@GetMapping
	@PreAuthorize("hasAuthority('VIEW_DONATIONS')")
	public List<DonationView> list() {
		return donationRecorder.list();
	}
}
