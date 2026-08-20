package org.iskcon.kms.notice;

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
 * The platform notice board (E9-S1).
 *
 * <p>Two audiences and therefore two shapes of gate, both of them permissions rather than roles.
 *
 * <p><strong>Reading and dismissing are open to anyone signed in.</strong> There is no
 * VIEW_PLATFORM_NOTICES permission and there should not be: the notices that matter most are about
 * food, and the person standing at the pan is the one who needs to read them. Gating the board on an
 * administrative permission would deliver a contamination warning to everybody except the cook.
 * {@code isAuthenticated()} is the honest expression of "addressed to people, not to
 * permission-holders", and it is what the donation endpoints already use for the same reason.
 *
 * <p><strong>Writing is not.</strong> Raising a notice reaches every temple on the platform, so it
 * asks for {@code RAISE_PLATFORM_NOTICE} — held by temple admins as well as operators, because a
 * recall is known first by the temple that found it. Withdrawal admits either permission and then
 * has the service decide which of the two it was: the raising temple's own admin needs no more than
 * the permission they posted with, while taking down somebody else's is the operator's
 * {@code WITHDRAW_ANY_PLATFORM_NOTICE} and nobody else's.
 */
@RestController
@RequestMapping("/api/v1/notices")
public class NoticeController {

	private final NoticeService noticeService;

	public NoticeController(NoticeService noticeService) {
		this.noticeService = noticeService;
	}

	/** Every notice ever raised, withdrawn ones included. The permanent record. */
	@GetMapping
	@PreAuthorize("isAuthenticated()")
	public List<NoticeView> board(@AuthenticationPrincipal AuthenticatedUser actor) {
		return noticeService.board(actor);
	}

	/** What belongs at the top of this person's Today screen: unread, inside the 30-day window. */
	@GetMapping("/feed")
	@PreAuthorize("isAuthenticated()")
	public List<NoticeView> feed(@AuthenticationPrincipal AuthenticatedUser actor) {
		return noticeService.undismissedFor(actor);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('RAISE_PLATFORM_NOTICE')")
	public ResponseEntity<Map<String, Object>> raise(
			@Valid @RequestBody RaiseNoticeRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(Map.of("id", noticeService.raise(actor, request)));
	}

	/**
	 * Takes a notice down. Either permission gets through the gate; which of them the caller actually
	 * needed depends on whose notice it is, and only the service can see that.
	 */
	@PostMapping("/{id}/withdraw")
	@PreAuthorize("hasAnyAuthority('RAISE_PLATFORM_NOTICE', 'WITHDRAW_ANY_PLATFORM_NOTICE')")
	public ResponseEntity<Void> withdraw(
			@PathVariable UUID id,
			@Valid @RequestBody WithdrawNoticeRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		noticeService.withdraw(actor, id, request.reason());
		return ResponseEntity.noContent().build();
	}

	/** Clears it from this person's Today screen. Never from a colleague's. */
	@PostMapping("/{id}/dismiss")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<Void> dismiss(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		noticeService.dismiss(actor, id);
		return ResponseEntity.noContent().build();
	}
}
