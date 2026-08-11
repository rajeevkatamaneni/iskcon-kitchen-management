package org.iskcon.kms.purchaseorder;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Purchase orders (E5-S3), all behind {@code MANAGE_PURCHASE_ORDERS}. */
@RestController
@RequestMapping("/api/v1/purchase-orders")
public class PurchaseOrderController {

	private final PurchaseOrderService service;
	private final PurchaseOrderDeliveryService deliveryService;

	public PurchaseOrderController(
			PurchaseOrderService service, PurchaseOrderDeliveryService deliveryService) {
		this.service = service;
		this.deliveryService = deliveryService;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public List<PurchaseOrderView> list(@RequestParam(required = false) PoStatus status) {
		return service.list(status);
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public PurchaseOrderDetailView get(@PathVariable UUID id) {
		return service.get(id);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public ResponseEntity<Map<String, Object>> create(
			@Valid @RequestBody CreatePurchaseOrderRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		UUID id = service.createManual(actor, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}

	/** Generate one draft PO per vendor from the selected order-list lines. */
	@PostMapping("/generate")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public ResponseEntity<Map<String, Object>> generate(
			@RequestBody(required = false) GeneratePosRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		List<UUID> ids = service.generateFromOrderList(actor,
				request == null ? null : request.ingredientIds());
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("purchaseOrderIds", ids));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public ResponseEntity<Void> update(
			@PathVariable UUID id,
			@Valid @RequestBody UpdatePurchaseOrderRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		service.update(actor, id, request);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/send")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public ResponseEntity<Void> send(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		service.send(actor, id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/cancel")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public ResponseEntity<Void> cancel(
			@PathVariable UUID id,
			@Valid @RequestBody CancelPoRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		service.cancel(actor, id, request.reason());
		return ResponseEntity.noContent().build();
	}

	/** Sends (or resends) the PO to its vendor on WhatsApp (E5-S7). */
	@PostMapping("/{id}/whatsapp")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public ResponseEntity<Map<String, Object>> sendWhatsApp(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		UUID notificationId = deliveryService.sendViaWhatsApp(actor, id);
		return ResponseEntity.accepted().body(Map.of("notificationId", notificationId));
	}
}

