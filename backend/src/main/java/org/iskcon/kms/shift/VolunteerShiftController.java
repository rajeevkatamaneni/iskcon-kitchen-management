package org.iskcon.kms.shift;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The volunteer's side of shifts (E6-S3): browse open shifts, claim a spot, and see their own
 * upcoming signups. Release (E6-S4) and the waitlist (E6-S5) add to this controller.
 */
@RestController
public class VolunteerShiftController {

	private final SignupService signupService;

	public VolunteerShiftController(SignupService signupService) {
		this.signupService = signupService;
	}

	@GetMapping("/api/v1/available-shifts")
	@PreAuthorize("hasAuthority('SIGN_UP_FOR_SHIFTS')")
	public List<AvailableShiftView> available(
			@AuthenticationPrincipal AuthenticatedUser actor,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return signupService.availableShifts(actor.getUserId(), from, to);
	}

	@PostMapping("/api/v1/shifts/{id}/signup")
	@PreAuthorize("hasAuthority('SIGN_UP_FOR_SHIFTS')")
	public ResponseEntity<Map<String, Object>> signUp(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		SignupResult result = signupService.signUp(actor.getUserId(), id);
		signupService.notifyConfirmation(actor.getUserId(), id);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(Map.of("signupId", result.signupId(), "overlapWarning", result.overlapWarning()));
	}

	@GetMapping("/api/v1/my-shifts")
	@PreAuthorize("hasAuthority('VIEW_OWN_SHIFTS')")
	public List<MyShiftView> myShifts(@AuthenticationPrincipal AuthenticatedUser actor) {
		return signupService.myShifts(actor.getUserId());
	}
}
