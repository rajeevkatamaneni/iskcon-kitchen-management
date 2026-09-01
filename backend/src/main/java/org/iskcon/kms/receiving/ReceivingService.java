package org.iskcon.kms.receiving;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * {@code PARTIALLY_RECEIVED} — and what is still outstanding re-feeds the shopping list (E5-S2).
 *
 * <p>A submission is one unit: its client idempotency key makes a retry or double-click return the
 * receipt already recorded instead of booking stock a second time.
 *
 * <p><strong>This is where a price becomes true (INV1).</strong> Each line may carry what was
 * actually paid, pre-filled from the PO line's expected price and edited against the bill that came
 * with the lorry. Where a price is given on goods that were actually received, it is written back to
 * {@code vendor_supplies.last_price} for that vendor and ingredient, so the next shopping list and
 * every costing figure read a price somebody paid rather than one somebody typed into the vendor
 * screen and then forgot. A line with no price changes nothing: a delivery can arrive ahead of its
 * bill and a gift in kind has no purchase price at all, and neither is a reason to overwrite a
 * figure that was true.
 */
@Service
public class ReceivingService {

	private static final Logger log = LoggerFactory.getLogger(ReceivingService.class);

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
			recordPricePaid(po.order().vendorId(), poLine, line);
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
						reject_reason, unit, batch_id, expiry_date, received_date, stock_movement_id,
						unit_price)
					VALUES (gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
			// The table is append-only: no later pass can come back and fill this in, so the price is
			// part of the insert or it is absent for ever. setBigDecimal writes a null as SQL NULL,
			// which is the distinction the whole column rests on — no figure, not zero.
			ps.setBigDecimal(12, line.unitPrice());
			return ps;
		});
	}

	/**
	 * Writes what was actually paid back onto the vendor's supply row (INV1).
	 *
	 * <p>Only when a price was given <em>and</em> something was actually received. A line with no
	 * price leaves {@code last_price} alone rather than blanking it, because "the bill has not
	 * arrived yet" is not the same statement as "this costs nothing"; a line that was rejected in
	 * full leaves it alone too, since the temple did not buy those goods at that price or any other.
	 *
	 * <p>The row is created if this vendor has never been recorded as supplying this ingredient —
	 * they have now demonstrably supplied it. {@code preferred} is deliberately not touched: a
	 * delivery says what a thing cost, not who the temple would rather buy it from.
	 */
	private void recordPricePaid(UUID vendorId, PurchaseOrderLineView poLine, ReceiptLineInput line) {
		if (line.unitPrice() == null || line.receivedQty().signum() <= 0) {
			return;
		}
		Unit receiptUnit = Unit.valueOf(poLine.unit());
		Unit canonical = canonicalUnit(poLine.ingredientId());
		BigDecimal perCanonicalUnit = pricePerUnit(line.unitPrice(), receiptUnit, canonical);
		if (perCanonicalUnit == null) {
			// Different families — ₹ per litre says nothing about an ingredient held in kilograms.
			// Unreachable from here since BL-9 was closed: anything actually received goes through
			// the ledger first, and the ledger now refuses a unit the ingredient cannot be measured
			// in. Kept because the arithmetic above must stay honest wherever it is called from, and
			// because declining is still the right answer — a stale last_price can be recognised as
			// stale, and one wrong by a factor of a thousand cannot.
			log.warn("Not writing back a received price for ingredient {}: the receipt line is in {} "
					+ "but the ingredient is held in {}, and the two do not convert.",
					poLine.ingredientId(), receiptUnit, canonical);
			return;
		}
		jdbc.update("""
				INSERT INTO vendor_supplies (id, tenant_id, vendor_id, ingredient_id, last_price, preferred)
				VALUES (gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, false)
				ON CONFLICT (vendor_id, ingredient_id) DO UPDATE
				SET last_price = EXCLUDED.last_price, updated_at = now()
				""", vendorId, poLine.ingredientId(), perCanonicalUnit);
	}

	/**
	 * A price per one unit, restated as a price per another unit of the same family.
	 *
	 * <p>{@code vendor_supplies.last_price} is rupees per one of the ingredient's canonical unit —
	 * ₹45 per Kg for an ingredient held in Kg — which is how {@code MaterialsCostService} and the
	 * printed purchase order already read it. A receipt line's price is per the line's own unit. The
	 * two are the same unit on every order this application creates, but a manually posted PO line
	 * may name any of the five, so the conversion is done rather than assumed: at ₹0.05 per gram a
	 * kilo is ₹50, and writing the 0.05 straight into a per-Kg column would be wrong by a thousand.
	 *
	 * <p>Null where the units do not convert. Pieces are not grams and litres are not kilos, and no
	 * density this application knows of would make them so.
	 */
	private static BigDecimal pricePerUnit(BigDecimal price, Unit from, Unit to) {
		if (from == to) {
			return price;
		}
		if (from.family() != to.family()) {
			return null;
		}
		// Scale 2 because that is the column's scale: rounding here or letting PostgreSQL round on
		// insert gives the same stored figure, and doing it in the open makes it visible.
		return price.multiply(BigDecimal.valueOf(to.baseFactor()))
				.divide(BigDecimal.valueOf(from.baseFactor()), 2, RoundingMode.HALF_UP);
	}

	private Unit canonicalUnit(UUID ingredientId) {
		return Unit.valueOf(jdbc.queryForObject(
				"SELECT canonical_unit FROM ingredients WHERE id = ?", String.class, ingredientId));
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
					   l.rejected_qty, l.reject_reason, l.unit, l.batch_id, l.expiry_date, l.received_date,
					   l.unit_price
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
			rs.getObject("received_date", LocalDate.class),
			// getObject, not getBigDecimal: a price nobody gave must come back as null rather than
			// as the zero getBigDecimal would hand back for a SQL NULL.
			(BigDecimal) rs.getObject("unit_price"));

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
