package org.iskcon.kms.receiving;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.ingredient.Quantities;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.tenancy.TempleClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Deliveries screen (PROCUREMENT-REQUIREMENTS §7, R-DEL-1..5): what every vendor still owes,
 * what has come, and recording a van.
 *
 * <p><strong>One recording code path.</strong> R-DEL-3 says "Saving creates the goods receipts and
 * lines per order, as the existing {@code ReceivingService} does", and R-PO-4 says the choice of
 * where a delivery is recorded "must not create a second recording code path". So {@link #record}
 * writes nothing of its own: it checks the van as a whole, groups its lines by order, and hands each
 * order to {@link ReceivingService#receive} — the same method the order page's
 * {@code POST /purchase-orders/{id}/receipts} calls. Stock movements, batches, expiry, the order's
 * status and its trail therefore behave exactly as they always have, because they are the same
 * code.
 *
 * <p><strong>No price anywhere</strong> (R-DEL-1, R-DEL-5). Nothing here reads or returns one.
 *
 * <p><strong>Read by date, not by row.</strong> The Received tab is the temple's today and the 29
 * days before it, and older deliveries come 30 days at a time (conductor's ruling, 2026-09-19). The
 * bounds are the temple's midnights turned into instants, so the query compares {@code received_at}
 * against two timestamps and the day a receipt falls on is the temple's day, never the server's.
 */
@Service
public class DeliveriesService {

	/** How many of the temple's days one page of the Received tab covers (conductor, 2026-09-19). */
	static final int RECEIVED_WINDOW_DAYS = 30;

	private final JdbcTemplate jdbc;
	private final ReceivingService receiving;
	private final TempleClock clock;

	public DeliveriesService(JdbcTemplate jdbc, ReceivingService receiving, TempleClock clock) {
		this.jdbc = jdbc;
		this.receiving = receiving;
		this.clock = clock;
	}

	// ---- Reading ----------------------------------------------------------

	/** The whole screen: what is owed, and the last 30 days of what came. */
	@Transactional(readOnly = true)
	public DeliveriesView view() {
		ZoneId zone = clock.zone();
		LocalDate today = LocalDate.now(zone);
		LocalDate from = today.minusDays(RECEIVED_WINDOW_DAYS - 1L);
		Page page = page(zone, from, today);

		List<DeliveryLineView> open = lines(zone, OPEN_LINES, new Object[0]);

		return new DeliveriesView(today, open, page.received(), page.receivedLines(), from,
				hasBefore(zone, from));
	}

	/** "Show older deliveries": the 30 days ending the day before {@code before}. */
	@Transactional(readOnly = true)
	public OlderDeliveriesView older(LocalDate before) {
		ZoneId zone = clock.zone();
		LocalDate to = before.minusDays(1);
		LocalDate from = to.minusDays(RECEIVED_WINDOW_DAYS - 1L);
		Page page = page(zone, from, to);
		return new OlderDeliveriesView(page.received(), page.receivedLines(), from, hasBefore(zone, from));
	}

	private record Page(List<DeliveryReceiptView> received, List<DeliveryLineView> receivedLines) {
	}

	/** Receipts recorded on the temple's days {@code from} to {@code to}, both inclusive. */
	private Page page(ZoneId zone, LocalDate from, LocalDate to) {
		OffsetDateTime start = startOf(from, zone);
		OffsetDateTime end = startOf(to.plusDays(1), zone);
		List<DeliveryReceiptView> received = receipts(zone, start, end);
		List<DeliveryLineView> receivedLines = lines(zone, LINES_RECEIVED_BETWEEN, new Object[] {start, end});
		return new Page(received, receivedLines);
	}

	private boolean hasBefore(ZoneId zone, LocalDate from) {
		Boolean any = jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM goods_receipts WHERE received_at < ?)",
				Boolean.class, startOf(from, zone));
		return Boolean.TRUE.equals(any);
	}

	/**
	 * Which lines are open. The same test {@code PurchaseOrderService.isFullyAccountedFor} applies to
	 * a catalogue line — kept quantity against ordered, rejected not counted — on the two statuses a
	 * delivery can still arrive against. A CLOSED order is not here: closing it was the decision that
	 * the rest is not coming (T-142).
	 */
	private static final String OPEN_LINES = """
			SELECT l.id FROM purchase_order_lines l
			JOIN purchase_orders po ON po.id = l.po_id
			WHERE l.ingredient_id IS NOT NULL
			  AND po.status IN ('SENT', 'PARTIALLY_RECEIVED')
			  AND COALESCE((SELECT SUM(g.received_qty) FROM goods_receipt_lines g
							WHERE g.po_line_id = l.id), 0) < l.quantity
			""";

	/** Every line a receipt recorded in the window touched. */
	private static final String LINES_RECEIVED_BETWEEN = """
			SELECT gl.po_line_id FROM goods_receipt_lines gl
			JOIN goods_receipts r ON r.id = gl.receipt_id
			WHERE r.received_at >= ? AND r.received_at < ?
			""";

	/**
	 * The lines {@code which} selects, each with its whole history. Two statements whatever the
	 * number of lines — the lines, then every part of all of them — rather than one per line.
	 *
	 * <p>Ordered by vendor, then needed-by, then order and the order's own line order, so a vendor's
	 * lines arrive together for the screen's per-vendor grouping (R-DEL-2).
	 */
	private List<DeliveryLineView> lines(ZoneId zone, String which, Object[] args) {
		record Row(UUID id, UUID poId, String poNumber, UUID vendorId, String vendorName,
				UUID ingredientId, String itemName, String unit, BigDecimal ordered, BigDecimal returned,
				LocalDate neededBy, String packLabel, BigDecimal packQuantity, BigDecimal packCount) {
		}
		List<Row> rows = jdbc.query("""
				SELECT l.id, l.po_id, po.po_number, po.vendor_id, v.name AS vendor_name,
					   l.ingredient_id, i.name AS item_name, l.unit, l.quantity, po.needed_by,
					   l.pack_count, p.name AS pack_name, p.quantity AS pack_size_quantity,
					   p.unit AS pack_unit, p.base_quantity AS pack_base_quantity,
					   COALESCE((SELECT SUM(ret.quantity) FROM goods_returns ret
								 JOIN goods_receipt_lines rl ON rl.id = ret.receipt_line_id
								 WHERE rl.po_line_id = l.id), 0) AS returned_qty
				FROM purchase_order_lines l
				JOIN purchase_orders po ON po.id = l.po_id
				JOIN vendors v ON v.id = po.vendor_id
				JOIN ingredients i ON i.id = l.ingredient_id
				LEFT JOIN ingredient_pack_sizes p ON p.id = l.pack_size_id
				WHERE l.id IN (%s)
				ORDER BY v.name, po.needed_by NULLS LAST, po.po_number, l.line_order, i.name
				""".formatted(which), (rs, n) -> {
			String unit = rs.getString("unit");
			boolean inPack = rs.getString("pack_unit") != null;
			return new Row(
					rs.getObject("id", UUID.class),
					rs.getObject("po_id", UUID.class),
					rs.getString("po_number"),
					rs.getObject("vendor_id", UUID.class),
					rs.getString("vendor_name"),
					rs.getObject("ingredient_id", UUID.class),
					rs.getString("item_name"),
					unit,
					rs.getBigDecimal("quantity"),
					rs.getBigDecimal("returned_qty"),
					rs.getObject("needed_by", LocalDate.class),
					inPack ? packLabel(rs.getString("pack_name"), rs.getBigDecimal("pack_size_quantity"),
							Unit.valueOf(rs.getString("pack_unit"))) : null,
					// One pack in the LINE's unit, from its size in base units — the same reading
					// PurchaseOrderService gives the order page, so both screens turn 100 Kg into the
					// same number of bags.
					inPack ? plain(rs.getBigDecimal("pack_base_quantity").divide(
							BigDecimal.valueOf(Unit.valueOf(unit).baseFactor()), 6, RoundingMode.HALF_UP))
							: null,
					inPack ? plain(rs.getBigDecimal("pack_count")) : null);
		}, args);
		if (rows.isEmpty()) {
			return List.of();
		}

		Map<UUID, List<DeliveryPartView>> parts = new LinkedHashMap<>();
		jdbc.query("""
				SELECT gl.po_line_id, gl.receipt_id, gl.received_qty, gl.rejected_qty, gl.reject_reason,
					   r.received_at, u.full_name AS received_by_name
				FROM goods_receipt_lines gl
				JOIN goods_receipts r ON r.id = gl.receipt_id
				LEFT JOIN users u ON u.id = r.received_by
				WHERE gl.po_line_id IN (%s)
				ORDER BY r.received_at, gl.id
				""".formatted(which), rs -> {
			parts.computeIfAbsent(rs.getObject("po_line_id", UUID.class), k -> new ArrayList<>())
					.add(new DeliveryPartView(
							rs.getObject("receipt_id", UUID.class),
							dayOf(rs.getObject("received_at", OffsetDateTime.class), zone),
							rs.getBigDecimal("received_qty"),
							rs.getBigDecimal("rejected_qty"),
							reason(rs.getString("reject_reason")),
							rs.getString("received_by_name")));
		}, args);

		List<DeliveryLineView> out = new ArrayList<>(rows.size());
		for (Row row : rows) {
			List<DeliveryPartView> history = parts.getOrDefault(row.id(), List.of());
			BigDecimal kept = BigDecimal.ZERO;
			BigDecimal rejected = BigDecimal.ZERO;
			LocalDate completedOn = null;
			for (DeliveryPartView part : history) {
				kept = kept.add(part.receivedQty());
				rejected = rejected.add(part.rejectedQty());
				// The part whose kept quantity first reaches what was ordered completed the line.
				// Rejected goods never count toward it: a refused sack is still owed (R-DEL-3).
				if (completedOn == null && kept.compareTo(row.ordered()) >= 0) {
					completedOn = part.receivedOn();
				}
			}
			BigDecimal still = row.ordered().subtract(kept).max(BigDecimal.ZERO);
			out.add(new DeliveryLineView(row.id(), row.poId(), row.poNumber(), row.vendorId(),
					row.vendorName(), row.ingredientId(), row.itemName(), row.unit(), row.ordered(), kept,
					rejected, row.returned(), still, row.neededBy(), completedOn, row.packLabel(),
					row.packQuantity(), row.packCount(), List.copyOf(history)));
		}
		return out;
	}

	/** The receipts recorded in {@code [start, end)}, newest first, each with its lines. */
	private List<DeliveryReceiptView> receipts(ZoneId zone, OffsetDateTime start, OffsetDateTime end) {
		record Header(UUID id, UUID poId, String poNumber, UUID vendorId, String vendorName,
				LocalDate receivedOn, String receivedByName) {
		}
		List<Header> headers = jdbc.query("""
				SELECT r.id, r.po_id, po.po_number, po.vendor_id, v.name AS vendor_name, r.received_at,
					   u.full_name AS received_by_name
				FROM goods_receipts r
				JOIN purchase_orders po ON po.id = r.po_id
				JOIN vendors v ON v.id = po.vendor_id
				LEFT JOIN users u ON u.id = r.received_by
				WHERE r.received_at >= ? AND r.received_at < ?
				ORDER BY r.received_at DESC, r.id
				""", (rs, n) -> new Header(
				rs.getObject("id", UUID.class),
				rs.getObject("po_id", UUID.class),
				rs.getString("po_number"),
				rs.getObject("vendor_id", UUID.class),
				rs.getString("vendor_name"),
				dayOf(rs.getObject("received_at", OffsetDateTime.class), zone),
				rs.getString("received_by_name")), start, end);
		if (headers.isEmpty()) {
			return List.of();
		}

		// Every return against a line of these receipts, oldest first, keyed by the receipt line it
		// went back against (T-285). One statement for the whole window, like the lines below, and
		// read before them so each line can be built whole. `quantity` is in the receipt line's unit
		// because GoodsReturnService stores it in exactly that unit.
		Map<UUID, List<DeliveryReturnView>> returns = new LinkedHashMap<>();
		jdbc.query("""
				SELECT ret.receipt_line_id, ret.quantity, ret.reason, ret.returned_at
				FROM goods_returns ret
				JOIN goods_receipts r ON r.id = ret.receipt_id
				WHERE r.received_at >= ? AND r.received_at < ?
				ORDER BY ret.returned_at, ret.id
				""", rs -> {
			returns.computeIfAbsent(rs.getObject("receipt_line_id", UUID.class), k -> new ArrayList<>())
					.add(new DeliveryReturnView(
							rs.getBigDecimal("quantity"),
							ReturnReason.valueOf(rs.getString("reason")),
							dayOf(rs.getObject("returned_at", OffsetDateTime.class), zone)));
		}, start, end);

		// The returned quantity is a correlated sum and not a join, for ReceivingService's reason: a
		// line returned against twice would otherwise come back as two rows.
		Map<UUID, List<DeliveryReceiptLineView>> lines = new LinkedHashMap<>();
		jdbc.query("""
				SELECT gl.id, gl.receipt_id, gl.po_line_id, i.name AS item_name, gl.unit, gl.received_qty,
					   gl.rejected_qty, gl.reject_reason,
					   COALESCE((SELECT SUM(ret.quantity) FROM goods_returns ret
								 WHERE ret.receipt_line_id = gl.id), 0) AS returned_qty
				FROM goods_receipt_lines gl
				JOIN goods_receipts r ON r.id = gl.receipt_id
				JOIN ingredients i ON i.id = gl.ingredient_id
				WHERE r.received_at >= ? AND r.received_at < ?
				ORDER BY i.name, gl.id
				""", rs -> {
			lines.computeIfAbsent(rs.getObject("receipt_id", UUID.class), k -> new ArrayList<>())
					.add(new DeliveryReceiptLineView(
							rs.getObject("po_line_id", UUID.class),
							rs.getString("item_name"),
							rs.getString("unit"),
							rs.getBigDecimal("received_qty"),
							rs.getBigDecimal("rejected_qty"),
							reason(rs.getString("reject_reason")),
							rs.getBigDecimal("returned_qty"),
							List.copyOf(returns.getOrDefault(rs.getObject("id", UUID.class), List.of()))));
		}, start, end);

		return headers.stream()
				.map(h -> new DeliveryReceiptView(h.id(), h.poId(), h.poNumber(), h.vendorId(), h.vendorName(),
						h.receivedOn(), h.receivedByName(), List.copyOf(lines.getOrDefault(h.id(), List.of()))))
				.toList();
	}

	// ---- Recording ----------------------------------------------------------

	/**
	 * Records one vendor's van across any of their open orders (R-DEL-3), as one goods receipt per
	 * order through {@link ReceivingService#receive}, all in this one transaction.
	 *
	 * <p><strong>Checked as a whole before anything is written.</strong> Every line must be on a
	 * SENT or part-delivered order from this vendor, or the whole press is refused with
	 * {@code KMS-400164} — never skipped, because a line that quietly went nowhere is a storekeeper
	 * believing stock arrived that the ledger never saw. The usual cause is a stale screen: somebody
	 * else closed the order, or recorded its last part, since the page loaded.
	 *
	 * <p><strong>Idempotent on the request's key.</strong> Each order's receipt is keyed
	 * {@code <key>:<order id>}. The key column is unique per temple ({@code goods_receipts_idempotency},
	 * V27), so the order id is what keeps two orders from one press apart, and the request's key is
	 * what makes a retry find them again. An order whose receipt already exists under its derived key
	 * is not checked again — the first press may have been the one that completed it, and a retry
	 * must answer with that receipt, not refuse because the order is no longer open.
	 * {@link ReceivingService#receive} makes the same check itself and returns the stored receipt, so
	 * a retry writes nothing at any level.
	 */
	@Transactional
	public RecordedDelivery record(AuthenticatedUser actor, RecordDeliveryRequest request) {
		record Subject(UUID poId, UUID vendorId, String status) {
		}
		Map<UUID, Subject> subjects = new LinkedHashMap<>();
		for (RecordDeliveryLineInput line : request.lines()) {
			List<Subject> found = jdbc.query("""
					SELECT l.po_id, po.vendor_id, po.status
					FROM purchase_order_lines l
					JOIN purchase_orders po ON po.id = l.po_id
					WHERE l.id = ?
					""", (rs, n) -> new Subject(rs.getObject("po_id", UUID.class),
					rs.getObject("vendor_id", UUID.class), rs.getString("status")), line.poLineId());
			if (found.isEmpty()) {
				// Not a line at all, or another temple's, which RLS makes the same thing.
				throw notThisVendor(request, line.poLineId());
			}
			subjects.put(line.poLineId(), found.get(0));
		}

		// Group by order, keeping the order the screen listed them in.
		Map<UUID, List<RecordDeliveryLineInput>> byOrder = new LinkedHashMap<>();
		for (RecordDeliveryLineInput line : request.lines()) {
			byOrder.computeIfAbsent(subjects.get(line.poLineId()).poId(), k -> new ArrayList<>()).add(line);
		}

		Set<UUID> alreadyRecorded = new LinkedHashSet<>();
		for (UUID poId : byOrder.keySet()) {
			Boolean exists = jdbc.queryForObject(
					"SELECT EXISTS (SELECT 1 FROM goods_receipts WHERE po_id = ? AND idempotency_key = ?)",
					Boolean.class, poId, keyFor(request, poId));
			if (Boolean.TRUE.equals(exists)) {
				alreadyRecorded.add(poId);
			}
		}

		for (RecordDeliveryLineInput line : request.lines()) {
			Subject s = subjects.get(line.poLineId());
			if (alreadyRecorded.contains(s.poId())) {
				continue;
			}
			boolean open = s.status().equals("SENT") || s.status().equals("PARTIALLY_RECEIVED");
			if (!s.vendorId().equals(request.vendorId()) || !open) {
				throw notThisVendor(request, line.poLineId());
			}
		}

		List<UUID> receiptIds = new ArrayList<>();
		for (Map.Entry<UUID, List<RecordDeliveryLineInput>> order : byOrder.entrySet()) {
			List<ReceiptLineInput> lines = order.getValue().stream()
					.map(l -> new ReceiptLineInput(l.poLineId(), l.receivedQty(), l.rejectedQty(),
							l.rejectReason(), l.expiryDate(), null))
					.toList();
			GoodsReceiptView receipt = receiving.receive(actor, order.getKey(),
					new ReceiveDeliveryRequest(keyFor(request, order.getKey()), null, null, lines));
			receiptIds.add(receipt.id());
		}
		return new RecordedDelivery(List.copyOf(receiptIds));
	}

	private static String keyFor(RecordDeliveryRequest request, UUID poId) {
		return request.idempotencyKey() + ":" + poId;
	}

	private static ApplicationException notThisVendor(RecordDeliveryRequest request, UUID poLineId) {
		return new ApplicationException(ErrorCode.DELIVERY_LINE_NOT_THIS_VENDOR,
				Map.of("vendorId", request.vendorId(), "poLineId", poLineId));
	}

	// ---- Small helpers ------------------------------------------------------

	/**
	 * A pack as an order words it (R-SL-3): "Bag (25 Kg)", or the plain size, "500 gm", for a pack
	 * with no name. The same form {@code PurchaseOrderService.packLabel} gives the order page, built
	 * the same way — the size through {@link Quantities#exact}, so no unit word is written by hand
	 * ({@code UnitLabelAgreementTest}). Restated rather than called because that method is
	 * package-private to the purchase-order package; {@code DeliveriesIT} asserts the two agree on the
	 * same line, so they cannot drift apart unnoticed.
	 */
	private static String packLabel(String name, BigDecimal quantity, Unit unit) {
		String size = Quantities.exact(quantity, unit);
		return name == null ? size : name + " (" + size + ")";
	}

	/** 4.000 as 4 and 25.000000 as 25: a count and a size read as a person writes them. */
	private static BigDecimal plain(BigDecimal value) {
		BigDecimal stripped = value.stripTrailingZeros();
		return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
	}

	private static RejectReason reason(String stored) {
		return stored == null ? null : RejectReason.valueOf(stored);
	}

	private static LocalDate dayOf(OffsetDateTime at, ZoneId zone) {
		return at.atZoneSameInstant(zone).toLocalDate();
	}

	/**
	 * The instant the temple's {@code day} began, as an {@link OffsetDateTime} in UTC so the driver
	 * binds it as a {@code timestamptz} and no JVM default zone comes anywhere near the comparison.
	 */
	private static OffsetDateTime startOf(LocalDate day, ZoneId zone) {
		Instant instant = day.atStartOfDay(zone).toInstant();
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
