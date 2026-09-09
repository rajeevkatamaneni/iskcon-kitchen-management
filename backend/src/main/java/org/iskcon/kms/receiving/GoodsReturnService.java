package org.iskcon.kms.receiving;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sending goods back to the vendor after they were already taken into stock (T-013).
 *
 * <p><strong>What this is for, and why rejection was not enough.</strong> A rejection is a field of
 * the receiving submission itself — {@code goods_receipt_lines.rejected_qty} — so it can only record
 * what the storekeeper refused while the lorry was still at the gate, and those goods never enter
 * the ledger at all. Everything discovered afterwards had no path: weevils found in the sacks the
 * next morning, or fifty kilos keyed when five arrived. The stock was on the books and nothing in
 * the application could take it off them.
 *
 * <h2>The three rules this class exists to keep</h2>
 *
 * <p><strong>A return reduces stock through a movement, like everything else.</strong> A negative
 * {@link MovementType#RETURN_TO_VENDOR} row against the batch the receipt established. On-hand is
 * the sum of the ledger and always has been (SYSTEM_DESIGN §5), so the returned quantity comes off
 * every reader of it at once — the inventory screen, the shopping list, the meal planner's
 * sufficiency check — without any of them being told about returns.
 *
 * <p><strong>The goods receipt itself is never mutated.</strong> It is append-only because it is a
 * fact about a moment: this is what the storekeeper signed for on the day. A return is a later fact
 * about the same goods, and it lives in its own table pointing back at the receipt line. Somebody
 * asking "what did we accept" and somebody asking "what did we keep" get different, honest answers.
 *
 * <p><strong>You cannot return more than was received.</strong> Cumulative, across every return
 * already recorded against that receipt line — five kilos back today and five more tomorrow is eight
 * too many on a three-kilo line, and the check has to see both. Anything over gives
 * {@code KMS-400140}.
 *
 * <h2>What is deliberately <em>not</em> checked</h2>
 *
 * <p><strong>Not the quantity on hand.</strong> Returning fifty kilos that were keyed when five
 * arrived is exactly the case this exists for, and by the time somebody notices, some of that
 * phantom stock has usually been cooked. The batch can go through zero and it should: the ledger
 * said fifty, five were real, and the arithmetic only comes right once the forty-five leaves the
 * same way it came in. Refusing to book a return because the shelf is already empty would leave the
 * overstatement standing for ever, which is the defect rather than the guard.
 *
 * <p><strong>Not the purchase order's status.</strong> A return does not reopen the order and does
 * not put the quantity back on the shopping list. Whether to buy the rice again is a decision, not
 * an arithmetic consequence — the temple may go to a different vendor, or not need it any more —
 * and it is made on the ordering screen where such decisions are already made.
 */
@Service
public class GoodsReturnService {

	private final JdbcTemplate jdbc;
	private final StockMovementService stockMovements;

	public GoodsReturnService(JdbcTemplate jdbc, StockMovementService stockMovements) {
		this.jdbc = jdbc;
		this.stockMovements = stockMovements;
	}

	/** Every return recorded against one receipt, oldest first. */
	@Transactional(readOnly = true)
	public List<GoodsReturnView> listForReceipt(UUID receiptId) {
		return jdbc.query(SELECT_RETURNS + " WHERE g.receipt_id = ? ORDER BY g.returned_at, g.id",
				RETURN_MAPPER, receiptId);
	}

	/**
	 * Records one return against one line of {@code receiptId}, draws the stock back out of the
	 * ledger, and returns what was written.
	 *
	 * <p>Idempotent on the request's key: a repeat returns the return already stored rather than
	 * withdrawing the stock a second time.
	 */
	@Transactional
	public GoodsReturnView returnGoods(AuthenticatedUser actor, UUID receiptId,
			ReturnGoodsRequest request) {
		Optional<GoodsReturnView> already = findByKey(request.receiptLineId(), request.idempotencyKey());
		if (already.isPresent()) {
			return already.get();
		}

		ReceiptLine line = loadLine(receiptId, request.receiptLineId());
		BigDecimal returnedSoFar = returnedSoFar(line.id());
		BigDecimal remaining = line.receivedQty().subtract(returnedSoFar);
		if (request.quantity().compareTo(remaining) > 0) {
			// Says both halves of the sum, because "you can't return more than was received" is not
			// actionable on a line that has been returned against before: 30 was received, 25 has
			// already gone back, and the number the person needs is the 5 that is left.
			throw new ApplicationException(ErrorCode.RETURN_EXCEEDS_RECEIVED, Map.of(
					"receiptLineId", line.id(),
					"receivedQty", line.receivedQty(),
					"alreadyReturned", returnedSoFar,
					"remaining", remaining.max(BigDecimal.ZERO),
					"requested", request.quantity()));
		}

		// Against the batch the receipt established, not the ingredient at large. These particular
		// goods are the ones going back, and FEFO reads the ledger per batch — a return booked
		// against no batch would come off the ingredient's total while leaving the batch it came
		// from looking full, which is the store room's own record made wrong to make a total right.
		UUID movementId = stockMovements.record(actor, new RecordMovement(
				line.ingredientId(), null, line.batchId(), request.quantity().negate(),
				Unit.valueOf(line.unit()), MovementType.RETURN_TO_VENDOR,
				null, null, null,
				MovementReference.PURCHASE_ORDER, line.poId(),
				noteFor(request, line)));

		// No catch on the unique key, deliberately. The retry this application actually sees is a
		// sequential one — a resubmitted request, a second press after a slow response — and the
		// findByKey above has already answered it with the return that was recorded. Two truly
		// simultaneous submissions are a different case: the loser's whole transaction rolls back,
		// movement included, so it withdraws nothing and the caller is told plainly that it failed.
		// Swallowing the collision and going back to read the winner's row cannot work anyway, since
		// PostgreSQL abandons a transaction the moment a constraint refuses a statement in it.
		UUID id = UUID.randomUUID();
		insert(actor, id, receiptId, request, line, movementId);
		return findById(id).orElseThrow();
	}

	// ---------------------------------------------------------------------

	/**
	 * How much has already gone back against this receipt line.
	 *
	 * <p>Read from {@code goods_returns} and never from the ledger. Summing the
	 * {@code RETURN_TO_VENDOR} movements for the batch would look equivalent and is not: a batch is
	 * established per receipt line, but a movement carries no line, and a compensating adjustment
	 * against a return would leave the ledger netting to a figure that no longer says how much this
	 * vendor was sent back. This table is the record of the act; the ledger is the record of the
	 * stock.
	 */
	private BigDecimal returnedSoFar(UUID receiptLineId) {
		BigDecimal sum = jdbc.queryForObject(
				"SELECT COALESCE(SUM(quantity), 0) FROM goods_returns WHERE receipt_line_id = ?",
				BigDecimal.class, receiptLineId);
		return sum == null ? BigDecimal.ZERO : sum;
	}

	/**
	 * The receipt line being returned against, refused unless it belongs to the receipt in the path.
	 *
	 * <p>The pairing is checked rather than assumed. Both ids come from the client, and a line id
	 * from some other delivery would otherwise cap this return against a quantity that has nothing
	 * to do with it — the goods of one lorry returned under the record of another.
	 */
	private ReceiptLine loadLine(UUID receiptId, UUID receiptLineId) {
		List<ReceiptLine> lines = jdbc.query("""
				SELECT l.id, l.ingredient_id, l.received_qty, l.unit, l.batch_id, r.po_id,
					   po.po_number
				FROM goods_receipt_lines l
				JOIN goods_receipts r ON r.id = l.receipt_id
				JOIN purchase_orders po ON po.id = r.po_id
				WHERE l.id = ? AND l.receipt_id = ?
				""", (rs, n) -> new ReceiptLine(
				rs.getObject("id", UUID.class),
				rs.getObject("ingredient_id", UUID.class),
				rs.getBigDecimal("received_qty"),
				rs.getString("unit"),
				rs.getObject("batch_id", UUID.class),
				rs.getObject("po_id", UUID.class),
				rs.getString("po_number")), receiptLineId, receiptId);
		if (lines.isEmpty()) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND,
					Map.of("receiptId", receiptId, "receiptLineId", receiptLineId));
		}
		return lines.get(0);
	}

	/**
	 * What the ledger row says about itself, for somebody reading the movement history rather than
	 * this screen. The vendor is on the purchase order the movement already references, so the note
	 * names the order and the reason and leaves the rest to the link.
	 */
	private static String noteFor(ReturnGoodsRequest request, ReceiptLine line) {
		String base = "Returned to vendor against " + line.poNumber() + ": "
				+ request.reason().name().replace('_', ' ').toLowerCase();
		String note = request.note() == null ? null : request.note().trim();
		return note == null || note.isEmpty() ? base : base + " — " + note;
	}

	private void insert(AuthenticatedUser actor, UUID id, UUID receiptId, ReturnGoodsRequest request,
			ReceiptLine line, UUID movementId) {
		jdbc.update(connection -> {
			var ps = connection.prepareStatement("""
					INSERT INTO goods_returns (
						id, tenant_id, receipt_id, receipt_line_id, idempotency_key, quantity, unit,
						reason, note, stock_movement_id, returned_by)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						?, ?, ?, ?, ?, ?, ?, ?, ?)
					""");
			ps.setObject(1, id);
			ps.setObject(2, receiptId);
			ps.setObject(3, line.id());
			ps.setString(4, request.idempotencyKey());
			ps.setBigDecimal(5, request.quantity());
			ps.setString(6, line.unit());
			ps.setString(7, request.reason().name());
			ps.setString(8, trimToNull(request.note()));
			ps.setObject(9, movementId);
			ps.setObject(10, actor.getUserId());
			return ps;
		});
	}

	private Optional<GoodsReturnView> findById(UUID id) {
		return jdbc.query(SELECT_RETURNS + " WHERE g.id = ?", RETURN_MAPPER, id).stream().findFirst();
	}

	private Optional<GoodsReturnView> findByKey(UUID receiptLineId, String key) {
		return jdbc.query(SELECT_RETURNS + " WHERE g.receipt_line_id = ? AND g.idempotency_key = ?",
				RETURN_MAPPER, receiptLineId, key).stream().findFirst();
	}

	private static final String SELECT_RETURNS = """
			SELECT g.id, g.receipt_id, g.receipt_line_id, l.ingredient_id, i.name AS ingredient_name,
				   g.quantity, g.unit, g.reason, g.note, u.full_name AS returned_by_name,
				   g.returned_at, g.stock_movement_id
			FROM goods_returns g
			JOIN goods_receipt_lines l ON l.id = g.receipt_line_id
			JOIN ingredients i ON i.id = l.ingredient_id
			LEFT JOIN users u ON u.id = g.returned_by
			""";

	private static final RowMapper<GoodsReturnView> RETURN_MAPPER = (rs, n) -> new GoodsReturnView(
			rs.getObject("id", UUID.class),
			rs.getObject("receipt_id", UUID.class),
			rs.getObject("receipt_line_id", UUID.class),
			rs.getObject("ingredient_id", UUID.class),
			rs.getString("ingredient_name"),
			rs.getBigDecimal("quantity"),
			rs.getString("unit"),
			rs.getString("reason"),
			rs.getString("note"),
			rs.getString("returned_by_name"),
			instant(rs.getObject("returned_at", OffsetDateTime.class)),
			rs.getObject("stock_movement_id", UUID.class));

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

	/** The receipt line a return is being made against, and the order it arrived on. */
	private record ReceiptLine(UUID id, UUID ingredientId, BigDecimal receivedQty, String unit,
			UUID batchId, UUID poId, String poNumber) {
	}
}
