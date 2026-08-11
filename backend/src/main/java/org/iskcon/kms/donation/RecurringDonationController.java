package org.iskcon.kms.donation;

import jakarta.validation.Valid;
import java.util.List;
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
 * A signed-in donor's recurring donations (E7-S3). Recurring requires an account — a mandate needs a
 * persistent identity — so these are behind authentication rather than the public donation surface.
 */
@RestController
@RequestMapping("/api/v1/donations/recurring")
public class RecurringDonationController {

	private final RecurringDonationService service;

	public RecurringDonationController(RecurringDonationService service) {
		this.service = service;
	}

	@PostMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<RecurringPlanView> create(
			@Valid @RequestBody CreateRecurringRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return ResponseEntity.status(HttpStatus.CREATED).body(service.createPlan(actor, request));
	}

	@GetMapping
	@PreAuthorize("isAuthenticated()")
	public List<RecurringPlanView> myPlans(@AuthenticationPrincipal AuthenticatedUser actor) {
		return service.myPlans(actor.getUserId());
	}

	@GetMapping("/{id}/history")
	@PreAuthorize("isAuthenticated()")
	public List<RecurringDonationService.DonationView> history(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		return service.planHistory(id, actor.getUserId());
	}

	@PostMapping("/{id}/cancel")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<Void> cancel(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		service.cancelPlan(actor, id);
		return ResponseEntity.noContent().build();
	}
}
