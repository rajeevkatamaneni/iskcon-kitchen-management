package org.iskcon.kms.staff;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Leave (B7): asking for it, and answering.
 *
 * <p>Two permissions, because they are two sides of the same record. {@code REQUEST_OWN_LEAVE} is
 * held by every employee with a login — including the admin who will answer their own, since a
 * temple with one administrator still records their absence. {@code APPROVE_LEAVE} is held by the
 * temple admin and by a Kitchen Manager where the temple has appointed one; it is deliberately not
 * folded into {@code MANAGE_STAFF}, which gates hiring, salary and PAN (build brief §5).
 *
 * <p>Every decision notifies the person outside the writing transaction, for the same reason the
 * schedule change does: a notice that cannot be queued must never roll back the decision.
 */
@RestController
@RequestMapping("/api/v1/leave")
public class LeaveController {

	private final LeaveService leave;

	public LeaveController(LeaveService leave) {
		this.leave = leave;
	}

	// ---- The staff member's own ------------------------------------------

	/** What I asked for and what came back. KMS-4403 if this temple does not employ me. */
	@GetMapping("/mine")
	@PreAuthorize("hasAuthority('REQUEST_OWN_LEAVE')")
	public List<LeaveView> mine(@AuthenticationPrincipal AuthenticatedUser actor) {
		return leave.myLeave(actor.getUserId());
	}

	@PostMapping("/mine")
	@PreAuthorize("hasAuthority('REQUEST_OWN_LEAVE')")
	public ResponseEntity<Map<String, Object>> request(
			@Valid @RequestBody RequestLeaveRequest input,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(Map.of("id", leave.request(actor, input)));
	}

	/** Takes back a request of mine that nobody has answered yet. */
	@DeleteMapping("/mine/{id}")
	@PreAuthorize("hasAuthority('REQUEST_OWN_LEAVE')")
	public ResponseEntity<Void> withdraw(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		leave.withdraw(actor, id);
		return ResponseEntity.noContent().build();
	}

	// ---- The approver's queue --------------------------------------------

	/** Everything waiting, then everything answered. One list, so a decision moves within it. */
	@GetMapping
	@PreAuthorize("hasAuthority('APPROVE_LEAVE')")
	public List<LeaveView> queue() {
		return leave.queue();
	}

	/**
	 * Records leave for a staff member, already approved — the janitor with no app, and the week
	 * grid's "mark them off". Its own endpoint rather than a flag on the request above: see
	 * {@link RecordLeaveRequest}.
	 */
	@PostMapping
	@PreAuthorize("hasAuthority('APPROVE_LEAVE')")
	public ResponseEntity<Map<String, Object>> record(
			@Valid @RequestBody RecordLeaveRequest input,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(Map.of("id", leave.recordOnBehalf(actor, input)));
	}

	@PostMapping("/{id}/approve")
	@PreAuthorize("hasAuthority('APPROVE_LEAVE')")
	public ResponseEntity<Void> approve(
			@PathVariable UUID id,
			@Valid @RequestBody(required = false) DecideLeaveRequest input,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		leave.notifyDecision(leave.approve(actor, id, note(input)));
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/decline")
	@PreAuthorize("hasAuthority('APPROVE_LEAVE')")
	public ResponseEntity<Void> decline(
			@PathVariable UUID id,
			@Valid @RequestBody(required = false) DecideLeaveRequest input,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		leave.notifyDecision(leave.decline(actor, id, note(input)));
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/revoke")
	@PreAuthorize("hasAuthority('APPROVE_LEAVE')")
	public ResponseEntity<Void> revoke(
			@PathVariable UUID id,
			@Valid @RequestBody(required = false) DecideLeaveRequest input,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		leave.notifyDecision(leave.revoke(actor, id, note(input)));
		return ResponseEntity.noContent().build();
	}

	/** A decision may carry no note at all, and an approval usually does not. */
	private static String note(DecideLeaveRequest input) {
		return input == null ? null : input.note();
	}
}
