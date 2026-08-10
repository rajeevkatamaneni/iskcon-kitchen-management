package org.iskcon.kms.purchaseorder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purchase orders and their lifecycle (E5-S3): DRAFT → SENT → PARTIALLY_RECEIVED → RECEIVED /
 * CANCELLED. Approved order-list lines are grouped into one draft PO per vendor; manual creation is
 * also allowed. Every PO carries a per-tenant sequential number and an append-only activity trail;
 * illegal transitions (editing after SENT, receiving a DRAFT) are refused at this layer.
 */
@Service
public class PurchaseOrderService {

	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final org.iskcon.kms.document.DocumentService documentService;

	public PurchaseOrderService(JdbcTemplate jdbc, AuditService auditService,
			org.iskcon.kms.document.DocumentService documentService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.documentService = documentService;
	}

	// ---- Read -----------------------------------------------------------

	@Transactional(readOnly = true)
	public List<PurchaseOrderView> list(PoStatus status) {
		StringBuilder sql = new StringBuilder(HEADER_SELECT + " WHERE 1 = 1");
		List<Object> args = new ArrayList<>();
		if (status != null) {
			sql.append(" AND po.status = ?");
			args.add(status.name());
		}
		sql.append(" ORDER BY po.created_at DESC");
		return jdbc.query(sql.toString(), HEADER_MAPPER, args.toArray());
	}

	@Transactional(readOnly = true)
	public PurchaseOrderDetailView get(UUID id) {
		PurchaseOrderView header = findHeader(id).orElseThrow(() -> notFound(id));
		List<PurchaseOrderLineView> lines = jdbc.query("""
				SELECT l.id, l.ingredient_id, i.name AS ingredient_name, l.quantity, l.unit, l.expected_price
				FROM purchase_order_lines l
				JOIN ingredients i ON i.id = l.ingredient_id
				WHERE l.po_id = ?
				ORDER BY l.line_order, i.name
				""", LINE_MAPPER, id);
		List<PoEventView> events = jdbc.query("""
				SELECT event_type, detail, actor_name, created_at
				FROM po_events WHERE po_id = ? ORDER BY created_at
				""", EVENT_MAPPER, id);
		return new PurchaseOrderDetailView(header, lines, events);
	}

	// ---- Create ---------------------------------------------------------

	@Transactional
	public UUID createManual(AuthenticatedUser actor, CreatePurchaseOrderRequest request) {
		UUID id = createPo(actor, request.vendorId(), request.neededBy(),
				request.deliveryLocation(), request.notes(), toLines(request.lines()));
		return id;
	}

	/** One draft PO per distinct vendor from the selected, included order-list lines (E5-S3). */
	@Transactional
	public List<UUID> generateFromOrderList(AuthenticatedUser actor, List<UUID> ingredientIds) {
		StringBuilder sql = new StringBuilder("""
				SELECT o.ingredient_id, o.suggested_qty, o.unit, o.suggested_vendor_id, o.needed_by,
					   vs.last_price
				FROM order_list_lines o
				LEFT JOIN vendor_supplies vs
					ON vs.vendor_id = o.suggested_vendor_id AND vs.ingredient_id = o.ingredient_id
				WHERE o.included = true AND o.suggested_vendor_id IS NOT NULL
				""");
		List<Object> args = new ArrayList<>();
		if (ingredientIds != null && !ingredientIds.isEmpty()) {
			sql.append(" AND o.ingredient_id IN (")
					.append(String.join(", ", java.util.Collections.nCopies(ingredientIds.size(), "?")))
					.append(")");
			args.addAll(ingredientIds);
		}
		List<OrderLineRow> rows = jdbc.query(sql.toString(), (rs, n) -> new OrderLineRow(
				rs.getObject("ingredient_id", UUID.class),
				rs.getBigDecimal("suggested_qty"),
				rs.getString("unit"),
				rs.getObject("suggested_vendor_id", UUID.class),
				rs.getObject("needed_by", LocalDate.class),
				(BigDecimal) rs.getObject("last_price")), args.toArray());

		Map<UUID, List<OrderLineRow>> byVendor = new LinkedHashMap<>();
		for (OrderLineRow r : rows) {
			byVendor.computeIfAbsent(r.vendorId(), k -> new ArrayList<>()).add(r);
		}

		List<UUID> created = new ArrayList<>();
		for (Map.Entry<UUID, List<OrderLineRow>> e : byVendor.entrySet()) {
			List<OrderLineRow> vlines = e.getValue();
			LocalDate neededBy = vlines.stream().map(OrderLineRow::neededBy)
					.filter(java.util.Objects::nonNull).min(LocalDate::compareTo).orElse(null);
			List<LineDraft> lines = vlines.stream()
					.map(r -> new LineDraft(r.ingredientId(), r.quantity(), r.unit(), r.lastPrice()))
					.toList();
			created.add(createPo(actor, e.getKey(), neededBy, null,
					"Generated from the order list", lines));
		}
		return created;
	}

	private UUID createPo(AuthenticatedUser actor, UUID vendorId, LocalDate neededBy,
			String deliveryLocation, String notes, List<LineDraft> lines) {
		requireVendor(vendorId);
		String poNumber = nextPoNumber();
		UUID id = UUID.randomUUID();
		jdbc.update(connection -> {
			var ps = connection.prepareStatement("""
					INSERT INTO purchase_orders (
						id, tenant_id, po_number, vendor_id, status, order_date, needed_by,
						delivery_location, notes, created_by)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, 'DRAFT',
						CURRENT_DATE, ?, ?, ?, ?)
					""");
			ps.setObject(1, id);
			ps.setString(2, poNumber);
			ps.setObject(3, vendorId);
			ps.setObject(4, neededBy);
			ps.setString(5, trimToNull(deliveryLocation));
			ps.setString(6, trimToNull(notes));
			ps.setObject(7, actor.getUserId());
			return ps;
		});
		insertLines(id, lines);
		recordEvent(id, "CREATED", poNumber + " created as draft with " + lines.size() + " line(s)", actor);
		return id;
	}

	// ---- Lifecycle ------------------------------------------------------

	@Transactional
	public void update(AuthenticatedUser actor, UUID id, UpdatePurchaseOrderRequest request) {
		PurchaseOrderView po = findHeader(id).orElseThrow(() -> notFound(id));
		if (po.status() != PoStatus.DRAFT) {
			throw new ApplicationException(ErrorCode.PO_NOT_EDITABLE, Map.of("purchaseOrderId", id));
		}
		jdbc.update("""
				UPDATE purchase_orders SET needed_by = ?, delivery_location = ?, notes = ?, updated_at = now()
				WHERE id = ?
				""", request.neededBy(), trimToNull(request.deliveryLocation()),
				trimToNull(request.notes()), id);
		jdbc.update("DELETE FROM purchase_order_lines WHERE po_id = ?", id);
		insertLines(id, toLines(request.lines()));
		recordEvent(id, "EDITED", "Draft edited", actor);
	}

	@Transactional
	public void send(AuthenticatedUser actor, UUID id) {
		PurchaseOrderView po = findHeader(id).orElseThrow(() -> notFound(id));
		if (po.status() != PoStatus.DRAFT) {
			throw new ApplicationException(ErrorCode.PO_INVALID_TRANSITION, Map.of("purchaseOrderId", id));
		}
		jdbc.update("UPDATE purchase_orders SET status = 'SENT', sent_at = now(), updated_at = now() WHERE id = ?", id);
		recordEvent(id, "SENT", po.poNumber() + " sent to vendor", actor);
		auditService.record(actor, AuditAction.PO_SENT, AuditEntityType.PURCHASE_ORDER, id,
				Map.of("status", "DRAFT"), Map.of("status", "SENT", "poNumber", po.poNumber()), null);

		// A sent PO gets its vendor sheet automatically (E5-S4); best-effort, so a worker-less
		// context (or node) never blocks the send.
		documentService.autoGeneratePurchaseOrderPdf(id);
	}

	@Transactional
	public void cancel(AuthenticatedUser actor, UUID id, String reason) {
		PurchaseOrderView po = findHeader(id).orElseThrow(() -> notFound(id));
		if (po.status() == PoStatus.RECEIVED || po.status() == PoStatus.CANCELLED) {
			throw new ApplicationException(ErrorCode.PO_INVALID_TRANSITION, Map.of("purchaseOrderId", id));
		}
		jdbc.update("""
				UPDATE purchase_orders SET status = 'CANCELLED', cancel_reason = ?, cancelled_at = now(),
					updated_at = now() WHERE id = ?
				""", reason.trim(), id);
		recordEvent(id, "CANCELLED", reason.trim(), actor);
		auditService.record(actor, AuditAction.PO_CANCELLED, AuditEntityType.PURCHASE_ORDER, id,
				Map.of("status", po.status().name()), Map.of("status", "CANCELLED"), reason.trim());
	}

	/**
	 * Moves a PO to PARTIALLY_RECEIVED or RECEIVED as receiving progresses (E5-S6). Only a SENT or
	 * already-partially-received PO can receive — receiving a DRAFT or a done PO is refused here.
	 */
	@Transactional
	public void applyReceivedStatus(AuthenticatedUser actor, UUID id, boolean fullyReceived) {
		PurchaseOrderView po = findHeader(id).orElseThrow(() -> notFound(id));
		if (po.status() != PoStatus.SENT && po.status() != PoStatus.PARTIALLY_RECEIVED) {
			throw new ApplicationException(ErrorCode.PO_INVALID_TRANSITION, Map.of("purchaseOrderId", id));
		}
		PoStatus target = fullyReceived ? PoStatus.RECEIVED : PoStatus.PARTIALLY_RECEIVED;
		jdbc.update("UPDATE purchase_orders SET status = ?, updated_at = now() WHERE id = ?", target.name(), id);
		recordEvent(id, target.name(), "Delivery received", actor);
		auditService.record(actor,
				fullyReceived ? AuditAction.PO_RECEIVED : AuditAction.PO_PARTIALLY_RECEIVED,
				AuditEntityType.PURCHASE_ORDER, id,
				Map.of("status", po.status().name()), Map.of("status", target.name()), null);
	}

	/** Records an event on a PO's trail (used by receiving and delivery too). */
	@Transactional
	public void recordEvent(UUID poId, String eventType, String detail, AuthenticatedUser actor) {
		jdbc.update(connection -> {
			var ps = connection.prepareStatement("""
					INSERT INTO po_events (tenant_id, po_id, event_type, detail, actor_user_id, actor_name)
					VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?)
					""");
			ps.setObject(1, poId);
			ps.setString(2, eventType);
			ps.setString(3, detail);
			ps.setObject(4, actor == null ? null : actor.getUserId());
			ps.setString(5, actor == null ? null : actor.getFullName());
			return ps;
		});
	}

	// ---------------------------------------------------------------------

	private String nextPoNumber() {
		Integer seq = jdbc.queryForObject("""
				INSERT INTO po_sequence (tenant_id, last_number)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, 1)
				ON CONFLICT (tenant_id) DO UPDATE SET last_number = po_sequence.last_number + 1
				RETURNING last_number
				""", Integer.class);
		return "PO-" + LocalDate.now(TEMPLE_ZONE).getYear() + "-" + String.format("%04d", seq);
	}

	private void insertLines(UUID poId, List<LineDraft> lines) {
		int[] order = {0};
		for (LineDraft l : lines) {
			jdbc.update("""
					INSERT INTO purchase_order_lines (
						id, tenant_id, po_id, ingredient_id, quantity, unit, expected_price, line_order)
					VALUES (gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						?, ?, ?, ?, ?, ?)
					""", poId, l.ingredientId(), l.quantity(), l.unit(), l.expectedPrice(), order[0]++);
		}
	}

	private void requireVendor(UUID vendorId) {
		Integer n = jdbc.queryForObject("SELECT count(*) FROM vendors WHERE id = ?", Integer.class, vendorId);
		if (n == null || n == 0) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("vendorId", vendorId));
		}
	}

	private List<LineDraft> toLines(List<PoLineInput> inputs) {
		return inputs.stream()
				.map(i -> new LineDraft(i.ingredientId(), i.quantity(), i.unit().trim(), i.expectedPrice()))
				.toList();
	}

	private Optional<PurchaseOrderView> findHeader(UUID id) {
		return jdbc.query(HEADER_SELECT + " WHERE po.id = ?", HEADER_MAPPER, id).stream().findFirst();
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	private ApplicationException notFound(UUID id) {
		return new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("purchaseOrderId", id));
	}

	private record LineDraft(UUID ingredientId, BigDecimal quantity, String unit, BigDecimal expectedPrice) {
	}

	private record OrderLineRow(
			UUID ingredientId, BigDecimal quantity, String unit, UUID vendorId, LocalDate neededBy,
			BigDecimal lastPrice) {
	}

	private static final String HEADER_SELECT = """
			SELECT po.id, po.po_number, po.vendor_id, v.name AS vendor_name, po.status, po.order_date,
				   po.needed_by, po.delivery_location, po.notes, po.cancel_reason, po.sent_at,
				   po.cancelled_at, po.created_at
			FROM purchase_orders po
			JOIN vendors v ON v.id = po.vendor_id
			""";

	private static final RowMapper<PurchaseOrderView> HEADER_MAPPER = (rs, n) -> new PurchaseOrderView(
			rs.getObject("id", UUID.class),
			rs.getString("po_number"),
			rs.getObject("vendor_id", UUID.class),
			rs.getString("vendor_name"),
			PoStatus.valueOf(rs.getString("status")),
			rs.getObject("order_date", LocalDate.class),
			rs.getObject("needed_by", LocalDate.class),
			rs.getString("delivery_location"),
			rs.getString("notes"),
			rs.getString("cancel_reason"),
			instant(rs.getObject("sent_at", OffsetDateTime.class)),
			instant(rs.getObject("cancelled_at", OffsetDateTime.class)),
			instant(rs.getObject("created_at", OffsetDateTime.class)));

	private static final RowMapper<PurchaseOrderLineView> LINE_MAPPER = (rs, n) -> new PurchaseOrderLineView(
			rs.getObject("id", UUID.class),
			rs.getObject("ingredient_id", UUID.class),
			rs.getString("ingredient_name"),
			rs.getBigDecimal("quantity"),
			rs.getString("unit"),
			(BigDecimal) rs.getObject("expected_price"));

	private static final RowMapper<PoEventView> EVENT_MAPPER = (rs, n) -> new PoEventView(
			rs.getString("event_type"),
			rs.getString("detail"),
			rs.getString("actor_name"),
			instant(rs.getObject("created_at", OffsetDateTime.class)));

	private static java.time.Instant instant(OffsetDateTime odt) {
		return odt == null ? null : odt.toInstant();
	}
}
