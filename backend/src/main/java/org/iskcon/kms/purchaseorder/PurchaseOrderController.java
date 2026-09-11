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

	/**
	 * The order list, narrowed three ways.
	 *
	 * <p>{@code vendorId} and {@code openOnly} were added for the invoice screen (T-082), which asks
	 * for one vendor's orders and only those still worth invoicing against. They are query parameters
	 * on this list rather than an endpoint of their own because that is the narrowest thing that
	 * answers the question — the row shape, the join and the ordering are all already right, and a
	 * second endpoint returning the same view would be a second place to keep in step.
	 */
	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public List<PurchaseOrderView> list(
			@RequestParam(required = false) PoStatus status,
			@RequestParam(required = false) UUID vendorId,
			@RequestParam(defaultValue = "false") boolean openOnly) {
		return service.list(status, vendorId, openOnly);
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public PurchaseOrderDetailView get(@PathVariable UUID id) {
		return service.get(id);
	}

	/**
	 * Raises one order against one vendor: the manual path at {@code /orders/new}, and the panel a
	 * vendor tile opens over the shopping list (T-134, D-24 §6).
	 *
	 * <p>Answers with the order's {@code poNumber} beside its id. The shopping list confirms a
	 * created order by name — "PO-2026-0041 raised for Heritage Fresh Dairy" — and the alternative
	 * was fetching the order back to read one string that this response already knew.
	 */
	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public ResponseEntity<Map<String, Object>> create(
			@Valid @RequestBody CreatePurchaseOrderRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		CreatedPurchaseOrder created = service.createManual(actor, request);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(Map.of("id", created.id(), "poNumber", created.poNumber()));
	}

	/** Generate one draft PO per vendor from the selected shopping-list lines. */
	@PostMapping("/generate")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public ResponseEntity<Map<String, Object>> generate(
			@RequestBody(required = false) GeneratePosRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		List<UUID> ids = service.generateFromShoppingList(actor,
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

	/**
	 * Marks an order as sent, which is where the vendor's lead time is enforced (T-137, D-25).
	 *
	 * <p>The body is optional and carries one thing: whether the person has already been shown
	 * {@code KMS-400148} — "this order is going out later than the vendor asked to be given" — and
	 * meant it anyway. Optional rather than required so that every existing caller keeps working and
	 * gets the reading that keeps the promise; see {@link SendPurchaseOrderRequest}.
	 */
	@PostMapping("/{id}/send")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public ResponseEntity<Void> send(
			@PathVariable UUID id,
			@RequestBody(required = false) SendPurchaseOrderRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		service.send(actor, id, request != null && request.sendAnyway());
		return ResponseEntity.noContent().build();
	}

	/**
	 * Closes a part-delivered order, naming how it ended for the vendor (T-142, D-26).
	 *
	 * <p>Behind {@code MANAGE_PURCHASE_ORDERS}, the same authority cancelling carries and for the
	 * same reason: this is the other way an order a person raised can end, taken by the same person
	 * at the same desk. No new permission — whoever may call an order off may say that a
	 * part-delivered one is finished.
	 *
	 * <p>The body carries the outcome and, for the two outcomes that say something about the
	 * supplier, the sentence D-26 requires. It carries no figure: the computed score is shown on
	 * the way in and there is nothing here that could move it.
	 */
	@PostMapping("/{id}/close")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public ResponseEntity<Void> close(
			@PathVariable UUID id,
			@Valid @RequestBody ClosePoRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		service.close(actor, id, request.outcome(), request.note());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/cancel")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public ResponseEntity<Void> cancel(
			@PathVariable UUID id,
			@Valid @RequestBody CancelPoRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		service.cancel(actor, id, request.reason(), request.vendorAbandoned());
		return ResponseEntity.noContent().build();
	}

	/**
	 * Records that described lines on this order turned up (T-066).
	 *
	 * <p>This is the action {@code KMS-400129} has been telling storekeepers to perform since T-024
	 * shipped, and which did not exist until now: "record it as delivered on the order". It writes a
	 * date on the lines and nothing else — no goods receipt, no batch, no stock movement — and then
	 * moves the order on if that was the last thing outstanding on it.
	 *
	 * <p>Behind {@code MANAGE_PURCHASE_ORDERS}, the same authority the receiving endpoint carries,
	 * because it is the same act by the same person at the same lorry. No new permission: a described
	 * line is a purchase-order line, and whoever may record what arrived against an order may record
	 * this too.
	 */
	@PostMapping("/{id}/arrivals")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public ResponseEntity<Void> recordArrivals(
			@PathVariable UUID id,
			@Valid @RequestBody RecordArrivalsRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		service.recordArrivals(actor, id, request.poLineIds());
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

