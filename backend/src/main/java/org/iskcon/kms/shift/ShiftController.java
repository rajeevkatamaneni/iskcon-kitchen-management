package org.iskcon.kms.shift;

import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Posting and managing volunteer shifts (E6-S2), behind {@code MANAGE_VOLUNTEER_SHIFTS}. */
@RestController
@RequestMapping("/api/v1/shifts")
public class ShiftController {

	private final ShiftService service;
	private final SignupService signupService;
	private final ShiftReminderScheduler reminderScheduler;
	private final BroadcastService broadcastService;

	public ShiftController(ShiftService service, SignupService signupService,
			ShiftReminderScheduler reminderScheduler, BroadcastService broadcastService) {
		this.service = service;
		this.signupService = signupService;
		this.reminderScheduler = reminderScheduler;
		this.broadcastService = broadcastService;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_VOLUNTEER_SHIFTS')")
	public List<ShiftView> list(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false, defaultValue = "false") boolean includeCancelled) {
		return service.list(from, to, includeCancelled);
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_VOLUNTEER_SHIFTS')")
	public ShiftView get(@PathVariable UUID id) {
		return service.get(id);
	}

	@GetMapping("/{id}/roster")
	@PreAuthorize("hasAuthority('MANAGE_VOLUNTEER_SHIFTS')")
	public RosterView roster(@PathVariable UUID id) {
		return service.roster(id);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_VOLUNTEER_SHIFTS')")
	public ResponseEntity<Map<String, Object>> create(
			@Valid @RequestBody CreateShiftRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", service.create(actor, request)));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_VOLUNTEER_SHIFTS')")
	public ResponseEntity<Void> update(@PathVariable UUID id, @Valid @RequestBody UpdateShiftRequest request) {
		service.update(id, request);
		// A capacity increase may open spots the waitlist should fill (E6-S5).
		signupService.promoteWaitlist(id).forEach(userId -> signupService.notifyPromotion(userId, id));
		// A time or offset change moves every pending reminder to its new fire time (E6-S6).
		reminderScheduler.rescheduleForShift(id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/cancel")
	@PreAuthorize("hasAuthority('MANAGE_VOLUNTEER_SHIFTS')")
	public ResponseEntity<Void> cancel(@PathVariable UUID id, @Valid @RequestBody CancelShiftRequest request) {
		service.cancel(id, request.reason());
		service.notifyCancellation(id);
		reminderScheduler.cancelForShift(id); // no reminders for a cancelled shift (E6-S6)
		return ResponseEntity.noContent().build();
	}

	/** Blast an immediate update to everyone signed up (optionally the waitlist), E6-S7. */
	@PostMapping("/{id}/broadcast")
	@PreAuthorize("hasAuthority('MANAGE_VOLUNTEER_SHIFTS')")
	public ResponseEntity<Map<String, Object>> broadcast(
			@PathVariable UUID id,
			@Valid @RequestBody BroadcastRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		BroadcastService.Plan plan = broadcastService.plan(actor, id, request.message(), request.includeWaitlist());
		int queued = broadcastService.deliver(id, plan);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(Map.of("broadcastId", plan.broadcastId(), "recipients", plan.recipients().size(),
						"queued", queued));
	}
}
