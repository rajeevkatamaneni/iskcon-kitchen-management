package org.iskcon.kms.receiving;

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
 * Receiving deliveries against a purchase order (E5-S6).
 *
 * <p><strong>Two permissions, on purpose</strong> (R-DEL-1, T-261). Recording a delivery is behind
 * {@code RECEIVE_DELIVERIES}, the same permission as the Deliveries screen, because the two record
 * through the one {@link ReceivingService#receive} and a person refused on one screen must not be
 * able to do the same thing from the other. Reading an order's receipts stays behind
 * {@code MANAGE_PURCHASE_ORDERS}: the order page reads its delivery history from it, and whoever can
 * see an order can see what arrived against it.
 *
 * <p>Kitchen Staff hold both. Open question Q-1 asked "Does Kitchen Staff get RECEIVE_DELIVERIES by
 * default, or only named staff?", and Rajeev answered it on 2026-09-19: by default. The grant lives
 * in {@code RolePermissions}, not in this controller, so any later change to who records deliveries
 * is made there and reaches both screens at once.
 */
@RestController
@RequestMapping("/api/v1/purchase-orders/{poId}/receipts")
public class ReceivingController {

	private final ReceivingService service;

	public ReceivingController(ReceivingService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public List<GoodsReceiptView> list(@PathVariable UUID poId) {
		return service.listForPurchaseOrder(poId);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('RECEIVE_DELIVERIES')")
	public ResponseEntity<GoodsReceiptView> receive(
			@PathVariable UUID poId,
			@Valid @RequestBody ReceiveDeliveryRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return ResponseEntity.status(HttpStatus.CREATED).body(service.receive(actor, poId, request));
	}
}
