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
import org.springframework.web.bind.annotation.DeleteMapping;
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

	/**
	 * The shifts I have already served (T-429), newest first, and whether I was recorded as having
	 * turned up. The other half of {@code /my-shifts}, which shows only what is still to come — a
	 * volunteer with six past services used to open her own page and find it empty while the
	 * coordinator could see every one of them on the roster.
	 *
	 * <p>The same permission as {@code /my-shifts}, for the same reason the released list shares it:
	 * it is the same thing read differently, a person's own roster. It takes no "whose" parameter —
	 * the caller's id is the whole of its scoping — which matters more here than on the other two,
	 * because {@code attended} is a statement about a person that until now only a coordinator could
	 * read. This gives it to the person it is about and to nobody else.
	 *
	 * <p>Bounded; {@link SignupService#PAST_SHIFTS_LIMIT} carries the number and why it is a row cap
	 * and not a window. A caller with no history gets an empty list, not a refusal — a staff member
	 * holds {@code VIEW_OWN_SHIFTS} too and has never signed up for seva.
	 */
	@GetMapping("/api/v1/my-shifts/past")
	@PreAuthorize("hasAuthority('VIEW_OWN_SHIFTS')")
	public List<MyPastShiftView> myPastShifts(@AuthenticationPrincipal AuthenticatedUser actor) {
		return signupService.myPastShifts(actor.getUserId());
	}

	/**
	 * The shifts I came off in the last week without choosing to (T-149): a coordinator took me off,
	 * or the shift was cancelled with me on it. The other half of My Shifts, where those simply
	 * disappear — see {@link SignupService#myReleasedShifts} for the rules.
	 *
	 * <p>The same permission as {@code /my-shifts} because it is the same thing read differently: a
	 * person's own roster. It takes no "whose" parameter, for the reason {@code release} gives — the
	 * caller's id is the whole of its scoping.
	 */
	@GetMapping("/api/v1/my-shifts/released")
	@PreAuthorize("hasAuthority('VIEW_OWN_SHIFTS')")
	public List<MyReleasedShiftView> myReleasedShifts(@AuthenticationPrincipal AuthenticatedUser actor) {
		return signupService.myReleasedShifts(actor.getUserId());
	}

	/** Release my own spot (E6-S4); frees capacity and promotes the waitlist head (E6-S5). */
	@PostMapping("/api/v1/shifts/{id}/release")
	@PreAuthorize("hasAuthority('SIGN_UP_FOR_SHIFTS')")
	public ResponseEntity<Void> release(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		List<UUID> promoted = signupService.release(actor.getUserId(), id);
		promoted.forEach(userId -> signupService.notifyPromotion(userId, id));
		return ResponseEntity.noContent().build();
	}

	/** Join the waitlist of a full shift (E6-S5). */
	@PostMapping("/api/v1/shifts/{id}/waitlist")
	@PreAuthorize("hasAuthority('SIGN_UP_FOR_SHIFTS')")
	public ResponseEntity<Void> joinWaitlist(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		signupService.joinWaitlist(actor.getUserId(), id);
		return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).build();
	}

	@DeleteMapping("/api/v1/shifts/{id}/waitlist")
	@PreAuthorize("hasAuthority('SIGN_UP_FOR_SHIFTS')")
	public ResponseEntity<Void> leaveWaitlist(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		signupService.leaveWaitlist(actor.getUserId(), id);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/api/v1/my-waitlist")
	@PreAuthorize("hasAuthority('VIEW_OWN_SHIFTS')")
	public List<MyWaitlistView> myWaitlist(@AuthenticationPrincipal AuthenticatedUser actor) {
		return signupService.myWaitlist(actor.getUserId());
	}
}
