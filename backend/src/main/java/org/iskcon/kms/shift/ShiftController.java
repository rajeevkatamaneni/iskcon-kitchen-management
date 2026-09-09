package org.iskcon.kms.shift;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Posting and managing volunteer shifts (E6-S2), behind {@code MANAGE_VOLUNTEER_SHIFTS} — and,
 * since B7, marking who turned up and taking a named volunteer off a roster.
 *
 * <p>One endpoint here is not on that permission: correcting a mark already made is
 * {@code CORRECT_RECORDED_ATTENDANCE}, the Temple Admin's and the Kitchen Manager's (T-106).
 */
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

	/**
	 * Marks who actually turned up (B7). One call for the whole roster; a second is KMS-400139, and
	 * the way to change an answer afterwards is {@link #correctAttendance} below, one person at a
	 * time (T-079).
	 *
	 * <p>Here rather than on {@code VolunteerShiftController} because it is the coordinator's act,
	 * not the volunteer's: a volunteer must not be able to say who came.
	 */
	@PostMapping("/{id}/attendance")
	@PreAuthorize("hasAuthority('MANAGE_VOLUNTEER_SHIFTS')")
	public ResponseEntity<Void> recordAttendance(
			@PathVariable UUID id, @Valid @RequestBody RecordAttendanceRequest request) {
		signupService.recordAttendance(id, request.marks());
		return ResponseEntity.noContent().build();
	}

	/**
	 * Changes one volunteer's attendance mark (T-079), and files it on the audit trail.
	 *
	 * <p>PUT and not POST, and per volunteer rather than per roster, because those two together are
	 * the whole difference from the call above it. That one is a statement about a shift, made once;
	 * this one sets one person's answer to the value given, and asking twice for the same answer
	 * leaves the same state — which is exactly what a coordinator's second press on a slow
	 * connection should do.
	 *
	 * <p>Behind {@code CORRECT_RECORDED_ATTENDANCE} and not the {@code MANAGE_VOLUNTEER_SHIFTS} the
	 * marking above it carries (T-106). A volunteer still cannot reach either door — that much is
	 * unchanged — but this one is now narrower than the marking beside it, and the narrowing is the
	 * point: the Temple Admin and the Kitchen Manager may change a mark, and Kitchen Staff may not.
	 * The person running the shift is the one who actually knows who turned up, so they must be able
	 * to put a wrong mark right; a cook is a colleague of the people on that roster and should not be
	 * able to change a record about one of them.
	 *
	 * <p>Started narrow deliberately. Widening a permission later is one line in {@code
	 * RolePermissions}; narrowing one after temples have built a habit around it is a conversation
	 * with every one of them.
	 */
	@PutMapping("/{id}/attendance/{userId}")
	@PreAuthorize("hasAuthority('CORRECT_RECORDED_ATTENDANCE')")
	public ResponseEntity<Void> correctAttendance(
			@PathVariable UUID id, @PathVariable UUID userId,
			@Valid @RequestBody CorrectAttendanceRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		signupService.correctAttendance(actor, id, userId, request.attended());
		return ResponseEntity.noContent().build();
	}

	/**
	 * The one answer a correction carries.
	 *
	 * <p>A boxed {@link Boolean} with {@code @NotNull}, for the reason {@code RecordAttendanceRequest}
	 * gives at length and which applies twice over here: a primitive deserialises an absent JSON key
	 * to {@code false} without complaint, so a client that sent {@code {}} would record a no-show
	 * against somebody who came — and on this path it would be overwriting an answer somebody had
	 * already considered.
	 */
	public record CorrectAttendanceRequest(@NotNull Boolean attended) {
	}

	/**
	 * Takes a named volunteer off the roster (B7), freeing the spot and promoting the waitlist head
	 * into it exactly as the volunteer's own release does.
	 *
	 * <p><strong>A separate endpoint from the volunteer's release, not a parameter on it.</strong>
	 * {@code POST /api/v1/shifts/{id}/release} lives on {@code VolunteerShiftController}, is gated on
	 * {@code SIGN_UP_FOR_SHIFTS}, and acts on {@code actor.getUserId()} and nothing else — that
	 * scoping is the whole of its security. Adding a "whose spot" parameter there would have made
	 * every volunteer able to strike anybody off any roster, gated by a permission that exists to let
	 * them manage their own. So the coordinator's release is this one, it names the person in the
	 * path, and it is gated on managing the roster.
	 */
	@DeleteMapping("/{id}/signups/{userId}")
	@PreAuthorize("hasAuthority('MANAGE_VOLUNTEER_SHIFTS')")
	public ResponseEntity<Void> releaseVolunteer(@PathVariable UUID id, @PathVariable UUID userId) {
		// The promoted volunteer is told, as they are on any other release — the spot opening is news
		// to them whoever freed it.
		signupService.releaseVolunteer(id, userId)
				.forEach(promotedUserId -> signupService.notifyPromotion(promotedUserId, id));
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
