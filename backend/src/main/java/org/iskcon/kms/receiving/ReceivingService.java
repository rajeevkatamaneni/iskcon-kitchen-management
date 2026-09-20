package org.iskcon.kms.receiving;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.error.ErrorResponse;
import org.iskcon.kms.ingredient.IngredientUnits;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.inventory.MovementReference;
import org.iskcon.kms.inventory.MovementType;
import org.iskcon.kms.inventory.RecordMovement;
import org.iskcon.kms.inventory.StockMovementService;
import org.iskcon.kms.purchaseorder.PurchaseOrderDetailView;
import org.iskcon.kms.purchaseorder.PurchaseOrderLineView;
import org.iskcon.kms.purchaseorder.PurchaseOrderService;
import org.iskcon.kms.tenancy.TempleClock;
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
 * "Covered" is {@code PurchaseOrderService.isFullyAccountedFor}, which asks a different question of
 * each kind of line: a catalogue line is covered by receipts adding up to what was ordered, and a
 * described line — which can never have a receipt — by somebody recording that it arrived (T-066).
 *
 * <p>A submission is one unit: its client idempotency key makes a retry or double-click return the
 * receipt already recorded instead of booking stock a second time.
 *
 * <p><strong>What is recorded here is never edited afterwards.</strong> Goods found to be bad after
 * they were accepted, or a quantity keyed wrongly, go back through {@link GoodsReturnService}
 * (T-013): a negative movement in the ledger and a row in {@code goods_returns}, leaving this
 * receipt exactly as the storekeeper signed it. The only thing that changes on a line here is
 * {@code returnedQty}, which is not stored on it — it is summed from those returns at read time.
 *
 * <p><strong>No price is taken here any more</strong> (R-DEL-5, R-VEN-4; PROCUREMENT-REQUIREMENTS
 * 2026-09-19, T-261). Until then each line could carry what was paid, stored in
 * {@code goods_receipt_lines.unit_price} and written straight onto
 * {@code vendor_supplies.last_price} (INV1). A price now comes from the bill the temple actually
 * pays, item by item, on the invoice, which also writes the vendor's price history; the delivery
 * records what arrived and nothing else. Two consequences, both deliberate:
 * <ul>
 *   <li>{@code unit_price} is no longer written. The column stays, and the rows written before this
 *       keep their figures: the table is append-only, and those prices are what somebody typed on
 *       the day. Every new row carries NULL, which V82 already defined as "no figure", never zero.
 *       Nothing sums or averages the column (T-261's proof lists every reader), so a column that
 *       stops being filled changes no total.</li>
 *   <li>The write-back to {@code last_price} is gone. It bypassed {@code vendor_price_history}
 *       altogether and knew nothing of a supply sold in packs, whose per-unit price is derived from
 *       the price per pack and never typed twice (R-VEN-1) — so it was the one writer left that
 *       could set a list price the history never saw.</li>
 * </ul>
 *
 * <p><strong>Two callers, one path.</strong> The order page's receipt endpoint and the Deliveries
 * screen ({@link DeliveriesService}, R-DEL-3) both record through {@link #receive}; the Deliveries
 * screen calls it once per order a van brought goods for, inside its own transaction.
 */
@Service
public class ReceivingService {

	private final TempleClock clock;

	private final JdbcTemplate jdbc;
	private final PurchaseOrderService purchaseOrders;
	private final StockMovementService stockMovements;

	public ReceivingService(JdbcTemplate jdbc, PurchaseOrderService purchaseOrders,
			StockMovementService stockMovements, TempleClock clock) {
		this.clock = clock;
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

		LocalDate today = LocalDate.now(clock.zone());
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

		noteDescribedLinesStillNeedAccountingFor(poId, po.lines(), actor);
		purchaseOrders.applyReceivedStatus(actor, poId, purchaseOrders.isFullyAccountedFor(poId));
		return loadReceipt(receiptId).orElseThrow();
	}

	/**
	 * Says on the order's own trail which described lines this delivery did not, and could not, take
	 * into stock — and that they are still waiting to be accounted for (T-024, amended by T-066).
	 *
	 * <p>The refusal in {@code validate} covers somebody who tries. This covers everybody who does
	 * not: a storekeeper receiving a lorry against an order that carries four plastic stools fills
	 * in the ingredient lines, presses the button, and the order advances — and without this the
	 * only record of what happened to the stools would be their continued absence from a ledger they
	 * were never going to be in. A skip that leaves no mark is indistinguishable from a bug.
	 *
	 * <p><strong>Only the lines still outstanding, since T-066.</strong> A described line somebody
	 * has already recorded as arrived is accounted for, and naming it again on every later receipt
	 * would make the trail read as though it were still hanging. The sentence now also says what to
	 * do about the ones that are outstanding, because there is finally something to do — which is the
	 * defect T-066 was raised for, one step earlier in the same story.
	 *
	 * <p>Recorded once per receipt rather than once per order, deliberately. Each delivery is its
	 * own statement about what did and did not arrive, and an order can take several.
	 *
	 * <p>{@code description} is safe to join here — the exclusivity CHECK guarantees it is non-null
	 * on exactly the lines this filter selects. Joining {@code ingredientName()} would not be:
	 * {@code Collectors.joining} appends a null as the four characters "null".
	 */
	private void noteDescribedLinesStillNeedAccountingFor(UUID poId,
			List<PurchaseOrderLineView> lines, AuthenticatedUser actor) {
		String described = lines.stream()
				.filter(l -> l.ingredientId() == null && !l.hasArrived())
				.map(PurchaseOrderLineView::description)
				.collect(Collectors.joining(", "));
		if (described.isEmpty()) {
			return;
		}
		purchaseOrders.recordEvent(poId, "DESCRIBED_LINES_NOT_STOCKED",
				"Not taken into stock, because the store room doesn't track them — record on the "
						+ "order whether they arrived: " + described,
				actor);
	}

	// ---------------------------------------------------------------------

	private void validate(UUID poId, ReceiveDeliveryRequest request,
			Map<UUID, PurchaseOrderLineView> poLines) {
		IngredientUnits.Whole whole = IngredientUnits.wholeNumbers();
		for (ReceiptLineInput line : request.lines()) {
			if (!poLines.containsKey(line.poLineId())) {
				throw new ApplicationException(ErrorCode.RECEIPT_LINE_NOT_ON_PO,
						Map.of("purchaseOrderId", poId, "poLineId", line.poLineId()));
			}
			// A described line is orderable and payable but never receivable (T-024). This is the one
			// guard, and it is here rather than repeated at the five ingredient sites downstream on
			// purpose: validate() runs over the whole submission before insertHeader, so nothing has
			// been written when it refuses, and every one of those five sites is reached only through
			// this loop. Repeating the null check at each of them would be five pieces of unreachable
			// code pretending to be defence.
			//
			// Refused rather than silently dropped. The storekeeper typed a quantity against this
			// line; telling them it went nowhere is the whole point, and KMS-400129 says what to do
			// instead — record it as delivered on the order. Since T-066 that instruction is true:
			// POST /api/v1/purchase-orders/{id}/arrivals is the action it names, and the order
			// screen offers it beside this table. Until T-066 it was a live false instruction
			// pointing at an action that existed nowhere in the application.
			PurchaseOrderLineView subject = poLines.get(line.poLineId());
			if (subject.ingredientId() == null) {
				throw new ApplicationException(ErrorCode.CANNOT_RECEIVE_A_DESCRIBED_LINE,
						Map.of("purchaseOrderId", poId, "poLineId", line.poLineId()),
						List.of(new ErrorResponse.FieldError(subject.description(),
								"This isn't something the store room tracks, so it can't be received "
										+ "into stock.")),
						null);
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

			// A counted thing cannot be a fraction (T-423). Both figures, because both are things
			// the storekeeper counted at the gate: 7.2 cylinders did not arrive and 0.5 of a broom
			// was not sent back off the lorry. The unit is the order line's own and is not on the
			// request at all — the screen turns "4 bags" into 100 Kg before it sends — which is why
			// this is here and not an annotation on ReceiptLineInput.
			//
			// This is the one gate for both doors into receiving. "Record a delivery" builds
			// ReceiptLineInput from its own body in DeliveriesService and comes through here, the
			// same argument the described-line check above makes for itself.
			//
			// Collected, not thrown at the first bad line: a lorry is many lines keyed in one pass,
			// which is the whole reason this screen is multi-line, and the same reason the shortfall
			// on an issue names every ingredient that is short.
			Unit lineUnit = Unit.valueOf(subject.unit());
			whole.check(subject.subject(), line.receivedQty(), lineUnit);
			whole.check(subject.subject(), line.rejectedQty(), lineUnit);
		}
		whole.refuseAnyPart();
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
			// unit_price is left out, so every new row stores NULL: no price is taken at delivery
			// (R-DEL-5). See the class comment.
			return ps;
		});
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
		// Still an INNER JOIN, and correctly so (T-024). goods_receipt_lines.ingredient_id is NOT
		// NULL (V27:48) and stays that way, and validate() refuses a receipt against a described PO
		// line, so no row here can have a null ingredient_id to be dropped by this join. That is a
		// fact about the receipt table, not an assumption about the PO table it came from — which is
		// exactly the distinction that made PurchaseOrderService.get's inner join dangerous.
		// The returned quantity is a correlated sum rather than a join, and a LEFT JOIN would be
		// wrong here rather than merely different: a line returned against twice would come back as
		// two rows and the receipt would appear to hold a line it does not. COALESCE, because a
		// line nothing has gone back on must read as 0.000 and not as an absence — see
		// GoodsReceiptLineView.returnedQty.
		List<GoodsReceiptLineView> lines = jdbc.query("""
				SELECT l.id, l.po_line_id, l.ingredient_id, i.name AS ingredient_name, l.received_qty,
					   l.rejected_qty, l.reject_reason, l.unit, l.batch_id, l.expiry_date, l.received_date,
					   l.unit_price,
					   COALESCE((SELECT SUM(g.quantity) FROM goods_returns g
								 WHERE g.receipt_line_id = l.id), 0) AS returned_qty
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
			(BigDecimal) rs.getObject("unit_price"),
			rs.getBigDecimal("returned_qty"));

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
