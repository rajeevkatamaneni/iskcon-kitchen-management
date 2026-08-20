package org.iskcon.kms.staff;

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
import org.springframework.web.bind.annotation.RestController;

/**
 * Salary, payments, advances and docking (B8).
 *
 * <p>Every route here is {@code MANAGE_STAFF} without exception, and that single permission is the
 * whole of the access-control story for pay. The temple administrator holds it; the kitchen manager
 * holds {@code MANAGE_STAFF_SCHEDULE} and {@code APPROVE_LEAVE} and does not. So the roster, the
 * leave queue and the week grid can be handed to whoever runs the kitchen without handing over what
 * anyone earns — no new permission and no field-level rule was needed to get there.
 *
 * <p>Its own controller rather than more methods on {@link StaffScheduleController}: that class is
 * about who works here and when, and a payment is a different subject with a different reader.
 * They share the {@code /api/v1/staff} prefix because they are the same person's record.
 */
@RestController
@RequestMapping("/api/v1/staff")
public class StaffPayController {

	private final StaffPayService service;

	public StaffPayController(StaffPayService service) {
		this.service = service;
	}

	/** Salary, the advance balance, the last salary payment, and the history behind them. */
	@GetMapping("/members/{id}/pay")
	@PreAuthorize("hasAuthority('MANAGE_STAFF')")
	public StaffPayView pay(@PathVariable UUID id) {
		return service.pay(id);
	}

	@PostMapping("/members/{id}/payments")
	@PreAuthorize("hasAuthority('MANAGE_STAFF')")
	public ResponseEntity<Map<String, Object>> recordPayment(
			@PathVariable UUID id,
			@Valid @RequestBody RecordStaffPaymentRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(Map.of("id", service.recordPayment(actor, id, request)));
	}

	@PostMapping("/members/{id}/advances")
	@PreAuthorize("hasAuthority('MANAGE_STAFF')")
	public ResponseEntity<Map<String, Object>> recordAdvance(
			@PathVariable UUID id,
			@Valid @RequestBody RecordStaffAdvanceRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(Map.of("id", service.recordAdvance(actor, id, request)));
	}

	/**
	 * Strikes a payment entered wrongly. A POST rather than a DELETE, because nothing is removed:
	 * the row stays, marked, and the URL says what actually happens to it.
	 */
	@PostMapping("/members/{id}/payments/{paymentId}/void")
	@PreAuthorize("hasAuthority('MANAGE_STAFF')")
	public ResponseEntity<Void> voidPayment(
			@PathVariable UUID id, @PathVariable UUID paymentId,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		service.voidPayment(actor, id, paymentId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/members/{id}/advances/{advanceId}/void")
	@PreAuthorize("hasAuthority('MANAGE_STAFF')")
	public ResponseEntity<Void> voidAdvance(
			@PathVariable UUID id, @PathVariable UUID advanceId,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		service.voidAdvance(actor, id, advanceId);
		return ResponseEntity.noContent().build();
	}
}
