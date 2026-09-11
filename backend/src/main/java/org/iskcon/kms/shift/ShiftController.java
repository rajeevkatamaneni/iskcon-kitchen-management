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
	public record CorrectAttendanceRequest(
			@NotNull(message = "Say whether they turned up.") Boolean attended) {
	}

	/**
	 * Takes a named volunteer off the roster (B7), freeing the spot and promoting the waitlist head
	 * into it exactly as the volunteer's own release does — and, since T-080, saying why.
	 *
	 * <p><strong>A separate endpoint from the volunteer's release, not a parameter on it.</strong>
	 * {@code POST /api/v1/shifts/{id}/release} lives on {@code VolunteerShiftController}, is gated on
	 * {@code SIGN_UP_FOR_SHIFTS}, and acts on {@code actor.getUserId()} and nothing else — that
	 * scoping is the whole of its security. Adding a "whose spot" parameter there would have made
	 * every volunteer able to strike anybody off any roster, gated by a permission that exists to let
	 * them manage their own. So the coordinator's release is this one, it names the person in the
	 * path, and it is gated on managing the roster. T-080 changes the verb and the body below and
	 * changes neither of those two things.
	 *
	 * <p><strong>Why this is now a POST with a body, and the DELETE it replaces is gone.</strong>
	 * T-080 gives a removal two required fields — the reason the volunteer is told, and the note the
	 * temple keeps — and a {@code DELETE} has nowhere to put them. Three options were on the table:
	 *
	 * <ol>
	 * <li><em>Give the DELETE a request body.</em> Legal under RFC 9110 and widely supported, but the
	 *     spec gives it no defined semantics, some proxies and client libraries drop it silently, and
	 *     a body that vanishes in transit here is a removal recorded with no reason and no note —
	 *     which is the exact defect this task exists to close, reappearing only in production and
	 *     only behind somebody's corporate proxy.</li>
	 * <li><em>Put the reason in query parameters.</em> Rejected outright: the internal note is the
	 *     coordinator's private sentence about a devotee, and query strings are logged by every
	 *     proxy, load balancer and access log between here and the browser.</li>
	 * <li><em>Make it a POST naming the act.</em> Chosen. It is also the more honest description of
	 *     what happens: the row is not deleted and never was — it keeps its identity, gains
	 *     {@code released_at}, a reason and a note, and stays on the roster under "Removed" where a
	 *     coordinator can see what was decided. A thing that records why it happened is an event, and
	 *     events are posted.</li>
	 * </ol>
	 *
	 * <p>The {@code DELETE} is withdrawn rather than left delegating. It has exactly one caller — this
	 * repo's own frontend — the product is pre-beta with no third party bound to it, and leaving it
	 * open would leave a door through which a volunteer can still be removed with no reason and no
	 * note, which is a hole the size of the whole feature. A stale client gets this project's settled
	 * answer to the right address with the wrong verb — {@code KMS-400030}, "what you asked for is
	 * not there" ({@code GlobalExceptionHandler#handleWrongMethod}) — which fails loudly, at the one
	 * moment somebody can still do something about it.
	 *
	 * <p>Both fields are required by Bean Validation, so a missing one is {@code KMS-400001} with
	 * {@code fieldErrors} naming it. No new error code and no new permission: the coordinator who
	 * could already remove somebody is the coordinator who must now say why.
	 */
	@PostMapping("/{id}/signups/{userId}/release")
	@PreAuthorize("hasAuthority('MANAGE_VOLUNTEER_SHIFTS')")
	public ResponseEntity<Void> releaseVolunteer(
			@PathVariable UUID id, @PathVariable UUID userId,
			@Valid @RequestBody RemoveVolunteerRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		SignupService.Removal removal = signupService.releaseVolunteer(actor, id, userId, request);

		// The person who lost the shift is told first, and told the structured reason only (T-080).
		// Before this they were told nothing at all, while the volunteer promoted into their place
		// got "a spot opened, you're in" — which is the defect, stated as an ordering.
		signupService.notifyRemoval(userId, id, removal.reason());

		// And the promoted volunteer, as on any other release — the spot opening is news to them
		// whoever freed it. Deliberately the same call and the same template as before: T-080 was
		// explicit that the waitlist promotion message does not change.
		removal.promoted().forEach(promotedUserId -> signupService.notifyPromotion(promotedUserId, id));
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
