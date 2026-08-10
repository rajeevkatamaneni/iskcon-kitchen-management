package org.iskcon.kms.receiving;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.inventory.MovementReference;
import org.iskcon.kms.inventory.MovementType;
import org.iskcon.kms.inventory.RecordMovement;
import org.iskcon.kms.inventory.StockMovementService;
import org.iskcon.kms.purchaseorder.PurchaseOrderDetailView;
import org.iskcon.kms.purchaseorder.PurchaseOrderLineView;
import org.iskcon.kms.purchaseorder.PurchaseOrderService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Receiving deliveries against a purchase order (E5-S6): what actually arrived, not what was
 * ordered. Received quantities write immutable {@code PO_RECEIPT} movements (with batch, expiry,
 * received-date); rejected quantities are recorded with a reason and never touch stock. The PO's
 * status is then auto-derived — {@code RECEIVED} once every line is covered, otherwise
 * {@code PARTIALLY_RECEIVED} — and what is still outstanding re-feeds the order list (E5-S2).
 *
 * <p>A submission is one unit: its client idempotency key makes a retry or double-click return the
 * receipt already recorded instead of booking stock a second time.
 */
@Service
public class ReceivingService {

	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	private final JdbcTemplate jdbc;
	private final PurchaseOrderService purchaseOrders;
	private final StockMovementService stockMovements;

	public ReceivingService(JdbcTemplate jdbc, PurchaseOrderService purchaseOrders,
			StockMovementService stockMovements) {
		this.jdbc = jdbc;
		this.purchaseOrders = purchaseOrders;
		this.stockMovements = stockMovements;
	}

	/** Every recorded delivery against a PO, oldest first. */
	@Transactional(readOnly = true)
	public List<GoodsReceiptView> listForPurchaseOrder(UUID poId) {
		List<UUID> ids = jdbc.queryForList(
				"SELECT id FROM goods_receipts WHERE po_id = ? ORDER BY received_at", UUID.class, poId);
		return ids.stream().map(id -> loadReceipt(id).orElseThrow()).toList();
	}

	/**
	 * Records one delivery against {@code poId}. Idempotent on the request's key: a repeat returns the
	 * receipt already stored, unchanged, so stock is never double-booked.
	 */
	@Transactional
	public GoodsReceiptView receive(AuthenticatedUser actor, UUID poId, ReceiveDeliveryRequest request) {
		Optional<GoodsReceiptView> already = findByKey(poId, request.idempotencyKey());
		if (already.isPresent()) {
			return already.get();
		}

		PurchaseOrderDetailView po = purchaseOrders.get(poId);
		String status = po.order().status().name();
		if (!status.equals("SENT") && !status.equals("PARTIALLY_RECEIVED")) {
			throw new ApplicationException(ErrorCode.PO_INVALID_TRANSITION, Map.of("purchaseOrderId", poId));
		}
		Map<UUID, PurchaseOrderLineView> poLines = new LinkedHashMap<>();
		for (PurchaseOrderLineView line : po.lines()) {
			poLines.put(line.id(), line);
		}
		validate(poId, request, poLines);

		UUID receiptId;
		try {
			receiptId = insertHeader(actor, poId, request);
		} catch (DuplicateKeyException race) {
			// A concurrent identical submission won the unique key; return its result, not a second one.
			return findByKey(poId, request.idempotencyKey())
					.orElseThrow(() -> new ApplicationException(ErrorCode.UNEXPECTED_FAILURE, Map.of()));
		}

		LocalDate today = LocalDate.now(TEMPLE_ZONE);
		for (ReceiptLineInput line : request.lines()) {
			PurchaseOrderLineView poLine = poLines.get(line.poLineId());
			UUID batchId = null;
			UUID movementId = null;
			if (line.receivedQty().signum() > 0) {
				batchId = UUID.randomUUID();
				LocalDate receivedDate = line.receivedDate() != null ? line.receivedDate() : today;
				movementId = stockMovements.record(actor, new RecordMovement(
						poLine.ingredientId(), null, batchId, line.receivedQty(),
						Unit.valueOf(poLine.unit()), MovementType.PO_RECEIPT,
						line.expiryDate(), receivedDate, null,
						MovementReference.PURCHASE_ORDER, poId,
						"Received against " + po.order().poNumber()));
			}
			insertLine(receiptId, poLine, line, batchId, movementId, today);
		}

		purchaseOrders.applyReceivedStatus(actor, poId, isFullyReceived(poId, poLines));
		return loadReceipt(receiptId).orElseThrow();
	}

	// ---------------------------------------------------------------------

	private void validate(UUID poId, ReceiveDeliveryRequest request,
			Map<UUID, PurchaseOrderLineView> poLines) {
		for (ReceiptLineInput line : request.lines()) {
			if (!poLines.containsKey(line.poLineId())) {
				throw new ApplicationException(ErrorCode.RECEIPT_LINE_NOT_ON_PO,
						Map.of("purchaseOrderId", poId, "poLineId", line.poLineId()));
			}
			boolean received = line.receivedQty().signum() > 0;
			boolean rejected = line.rejectedQty().signum() > 0;
			if (!received && !rejected) {
				throw new ApplicationException(ErrorCode.RECEIPT_LINE_EMPTY,
						Map.of("poLineId", line.poLineId()));
			}
			if (rejected == (line.rejectReason() == null)) {
				// A rejection needs a reason; a reason without a rejected quantity is meaningless.
				throw new ApplicationException(ErrorCode.RECEIPT_LINE_EMPTY,
						Map.of("poLineId", line.poLineId()));
			}
		}
	}

	private UUID insertHeader(AuthenticatedUser actor, UUID poId, ReceiveDeliveryRequest request) {
		UUID id = UUID.randomUUID();
		jdbc.update(connection -> {
			var ps = connection.prepareStatement("""
					INSERT INTO goods_receipts (
						id, tenant_id, po_id, idempotency_key, delivery_note_ref, note, received_by)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?)
					""");
			ps.setObject(1, id);
			ps.setObject(2, poId);
			ps.setString(3, request.idempotencyKey());
			ps.setString(4, trimToNull(request.deliveryNoteRef()));
			ps.setString(5, trimToNull(request.note()));
			ps.setObject(6, actor.getUserId());
			return ps;
		});
		return id;
	}

	private void insertLine(UUID receiptId, PurchaseOrderLineView poLine, ReceiptLineInput line,
			UUID batchId, UUID movementId, LocalDate today) {
		jdbc.update(connection -> {
			var ps = connection.prepareStatement("""
					INSERT INTO goods_receipt_lines (
						id, tenant_id, receipt_id, po_line_id, ingredient_id, received_qty, rejected_qty,
						reject_reason, unit, batch_id, expiry_date, received_date, stock_movement_id)
					VALUES (gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""");
			ps.setObject(1, receiptId);
			ps.setObject(2, poLine.id());
			ps.setObject(3, poLine.ingredientId());
			ps.setBigDecimal(4, line.receivedQty());
			ps.setBigDecimal(5, line.rejectedQty());
			ps.setString(6, line.rejectReason() == null ? null : line.rejectReason().name());
			ps.setString(7, poLine.unit());
			ps.setObject(8, batchId);
			ps.setObject(9, line.expiryDate());
			ps.setObject(10, line.receivedQty().signum() > 0
					? (line.receivedDate() != null ? line.receivedDate() : today) : null);
			ps.setObject(11, movementId);
			return ps;
		});
	}

	/** True once every PO line's total received quantity covers what was ordered. */
	private boolean isFullyReceived(UUID poId, Map<UUID, PurchaseOrderLineView> poLines) {
		Map<UUID, BigDecimal> receivedByLine = new LinkedHashMap<>();
		jdbc.query("""
				SELECT po_line_id, COALESCE(SUM(received_qty), 0) AS recv
				FROM goods_receipt_lines
				WHERE po_line_id IN (SELECT id FROM purchase_order_lines WHERE po_id = ?)
				GROUP BY po_line_id
				""", rs -> {
			receivedByLine.put(rs.getObject("po_line_id", UUID.class), rs.getBigDecimal("recv"));
		}, poId);
		for (PurchaseOrderLineView line : poLines.values()) {
			BigDecimal recv = receivedByLine.getOrDefault(line.id(), BigDecimal.ZERO);
			if (recv.compareTo(line.quantity()) < 0) {
				return false;
			}
		}
		return true;
	}

	private Optional<GoodsReceiptView> findByKey(UUID poId, String key) {
		List<UUID> ids = jdbc.queryForList(
				"SELECT id FROM goods_receipts WHERE po_id = ? AND idempotency_key = ?",
				UUID.class, poId, key);
		return ids.isEmpty() ? Optional.empty() : loadReceipt(ids.get(0));
	}

	private Optional<GoodsReceiptView> loadReceipt(UUID receiptId) {
		List<GoodsReceiptView> headers = jdbc.query("""
				SELECT r.id, r.po_id, r.delivery_note_ref, r.note, u.full_name AS received_by_name,
					   r.received_at
				FROM goods_receipts r
				LEFT JOIN users u ON u.id = r.received_by
				WHERE r.id = ?
				""", (rs, n) -> new GoodsReceiptView(
				rs.getObject("id", UUID.class),
				rs.getObject("po_id", UUID.class),
				rs.getString("delivery_note_ref"),
				rs.getString("note"),
				rs.getString("received_by_name"),
				instant(rs.getObject("received_at", OffsetDateTime.class)),
				List.of()), receiptId);
		if (headers.isEmpty()) {
			return Optional.empty();
		}
		List<GoodsReceiptLineView> lines = jdbc.query("""
				SELECT l.id, l.po_line_id, l.ingredient_id, i.name AS ingredient_name, l.received_qty,
					   l.rejected_qty, l.reject_reason, l.unit, l.batch_id, l.expiry_date, l.received_date
				FROM goods_receipt_lines l
				JOIN ingredients i ON i.id = l.ingredient_id
				WHERE l.receipt_id = ?
				ORDER BY i.name
				""", LINE_MAPPER, receiptId);
		GoodsReceiptView h = headers.get(0);
		return Optional.of(new GoodsReceiptView(h.id(), h.purchaseOrderId(), h.deliveryNoteRef(),
				h.note(), h.receivedByName(), h.receivedAt(), lines));
	}

	private static final RowMapper<GoodsReceiptLineView> LINE_MAPPER = (rs, n) -> new GoodsReceiptLineView(
			rs.getObject("id", UUID.class),
			rs.getObject("po_line_id", UUID.class),
			rs.getObject("ingredient_id", UUID.class),
			rs.getString("ingredient_name"),
			rs.getBigDecimal("received_qty"),
			rs.getBigDecimal("rejected_qty"),
			rs.getString("reject_reason"),
			rs.getString("unit"),
			rs.getObject("batch_id", UUID.class),
			rs.getObject("expiry_date", LocalDate.class),
			rs.getObject("received_date", LocalDate.class));

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	private static java.time.Instant instant(OffsetDateTime odt) {
		return odt == null ? null : odt.toInstant();
	}
}
