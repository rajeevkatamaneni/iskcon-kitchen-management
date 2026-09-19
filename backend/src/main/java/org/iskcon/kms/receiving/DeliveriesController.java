package org.iskcon.kms.receiving;

import jakarta.validation.Valid;
import java.time.LocalDate;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Deliveries screen (R-DEL-1..5), behind {@code RECEIVE_DELIVERIES}: Temple Admin, Kitchen
 * Manager and Kitchen Staff. Rajeev answered open question Q-1 on 2026-09-19: Kitchen Staff get
 * RECEIVE_DELIVERIES by default, not only named staff; the grant is in {@code RolePermissions}.
 * The tenant is the signed-in user's, set on the connection by the security filter; nothing here
 * takes one from the request.
 */
@RestController
@RequestMapping("/api/v1/deliveries")
public class DeliveriesController {

	private final DeliveriesService service;

	public DeliveriesController(DeliveriesService service) {
		this.service = service;
	}

	/** What every vendor still owes, and the last 30 days of what came. */
	@GetMapping
	@PreAuthorize("hasAuthority('RECEIVE_DELIVERIES')")
	public DeliveriesView view() {
		return service.view();
	}

	/** "Show older deliveries": the 30 days ending the day before {@code before}. */
	@GetMapping("/received")
	@PreAuthorize("hasAuthority('RECEIVE_DELIVERIES')")
	public OlderDeliveriesView older(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate before) {
		return service.older(before);
	}

	/** One vendor's van, across any of their open orders: one goods receipt per order. */
	@PostMapping
	@PreAuthorize("hasAuthority('RECEIVE_DELIVERIES')")
	public ResponseEntity<RecordedDelivery> record(
			@Valid @RequestBody RecordDeliveryRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return ResponseEntity.status(HttpStatus.CREATED).body(service.record(actor, request));
	}
}
