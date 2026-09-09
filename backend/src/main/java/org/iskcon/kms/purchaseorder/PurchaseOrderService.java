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
import org.iskcon.kms.error.ErrorResponse;
import org.iskcon.kms.ingredient.IngredientUnits;
import org.iskcon.kms.tenancy.TempleClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purchase orders and their lifecycle (E5-S3): DRAFT → SENT → PARTIALLY_RECEIVED → RECEIVED /
 * CANCELLED. Approved shopping-list lines are grouped into one draft PO per vendor; manual creation is
 * also allowed. Every PO carries a per-tenant sequential number and an append-only activity trail;
 * illegal transitions (editing after SENT, receiving a DRAFT) are refused at this layer.
 */
@Service
public class PurchaseOrderService {


	private final TempleClock clock;
	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final org.iskcon.kms.document.DocumentService documentService;
	private final IngredientUnits ingredientUnits;

	public PurchaseOrderService(JdbcTemplate jdbc, AuditService auditService,
			org.iskcon.kms.document.DocumentService documentService,
			IngredientUnits ingredientUnits, TempleClock clock) {
		this.clock = clock;
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.documentService = documentService;
		this.ingredientUnits = ingredientUnits;
	}

	// ---- Read -----------------------------------------------------------

	@Transactional(readOnly = true)
	public List<PurchaseOrderView> list(PoStatus status) {
		return list(status, null, false);
	}

	/**
	 * The order list, optionally narrowed to one vendor and to the orders still open for invoicing.
	 *
	 * <p><strong>What "open" means here, and why it is these three statuses.</strong> The caller is
	 * the invoice screen (T-082), which offers this list as the order an incoming bill is against, so
	 * the question each status has to answer is "could a vendor's bill legitimately quote this?".
	 *
	 * <ul>
	 * <li><b>DRAFT is excluded.</b> A draft has not been sent — {@link #send} is the only path out of
	 * DRAFT and it is what puts the order in front of the vendor. A vendor cannot have billed for an
	 * order it has never seen, and offering drafts would invite an invoice against a document that is
	 * still being edited.</li>
	 * <li><b>CANCELLED is excluded.</b> The order was withdrawn; a bill against it is a dispute, not a
	 * payable, and it must not be capturable in one dropdown pick.</li>
	 * <li><b>SENT, PARTIALLY_RECEIVED and RECEIVED are all offered.</b> RECEIVED is the ordinary case
	 * — the bill usually arrives after the goods. PARTIALLY_RECEIVED is offered deliberately: vendors
	 * bill for what they have delivered so far, and hiding a part-delivered order would push exactly
	 * that invoice onto the direct path, where it loses its order and its variance.</li>
	 * </ul>
	 *
	 * <p><strong>What this does not filter, and that is a decision rather than an omission.</strong>
	 * "Already fully invoiced" is not a state this schema can express. {@code vendor_invoices.po_id}
	 * carries no unique constraint — several invoices against one order is the supported case, and it
	 * is the same part-billing case as above — and there is no invoiced-to-date figure anywhere to
	 * compare an order's value against. Dropping an order the moment it has one invoice would silently
	 * block the second legitimate bill, so this offers the order and leaves the judgement with the
	 * person reading the invoice. If a fully-invoiced notion is ever wanted it needs a definition
	 * first, not a filter bolted on here.
	 */
	@Transactional(readOnly = true)
	public List<PurchaseOrderView> list(PoStatus status, UUID vendorId, boolean openOnly) {
		StringBuilder sql = new StringBuilder(HEADER_SELECT + " WHERE 1 = 1");
		List<Object> args = new ArrayList<>();
		if (status != null) {
			sql.append(" AND po.status = ?");
			args.add(status.name());
		}
		if (vendorId != null) {
			sql.append(" AND po.vendor_id = ?");
			args.add(vendorId);
		}
		if (openOnly) {
			sql.append(" AND po.status IN ('SENT', 'PARTIALLY_RECEIVED', 'RECEIVED')");
		}
		sql.append(" ORDER BY po.created_at DESC");
		return jdbc.query(sql.toString(), HEADER_MAPPER, args.toArray());
	}

	@Transactional(readOnly = true)
	public PurchaseOrderDetailView get(UUID id) {
		PurchaseOrderView header = findHeader(id).orElseThrow(() -> notFound(id));
		// LEFT JOIN, and this is the single most dangerous line in T-024 (D-1). It was an inner join,
		// which was correct only while ingredient_id was NOT NULL. The moment a line may be described
		// instead, an inner join drops that row — silently, with no error, no log and no count: the
		// order would simply be missing a line on the detail screen, on the printed vendor sheet and
		// in the receiving table, and the only way anybody would find out is a vendor delivering
		// something nobody could see they had ordered.
		//
		// ORDER BY on COALESCE for the same reason: ordering on i.name alone would sort every
		// described line together under NULL rather than into the alphabetical run its own words
		// belong in.
		List<PurchaseOrderLineView> lines = jdbc.query("""
				SELECT l.id, l.ingredient_id, i.name AS ingredient_name, l.description, l.quantity,
					   l.unit, l.expected_price
				FROM purchase_order_lines l
				LEFT JOIN ingredients i ON i.id = l.ingredient_id
				WHERE l.po_id = ?
				ORDER BY l.line_order, COALESCE(i.name, l.description)
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
		// A new order is dated the temple's today, so that is the floor a hand-typed needed-by is
		// measured against. Checked here and not in createPo, because generation is not a person
		// typing: see requireNeededByOnOrAfter.
		requireNeededByOnOrAfter(request.neededBy(), LocalDate.now(clock.zone()), null);
		UUID id = createPo(actor, request.vendorId(), request.neededBy(),
				request.deliveryLocation(), request.notes(), toLines(request.lines()));
		return id;
	}

	/** One draft PO per distinct vendor from the selected, included shopping-list lines (E5-S3). */
	@Transactional
	public List<UUID> generateFromShoppingList(AuthenticatedUser actor, List<UUID> ingredientIds) {
		StringBuilder sql = new StringBuilder("""
				SELECT o.ingredient_id, o.suggested_qty, o.unit, o.suggested_vendor_id, o.needed_by,
					   vs.last_price
				FROM shopping_list_lines o
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
			// Never a described line: the shopping list is computed from demand for catalogue
			// ingredients, so everything it generates has an ingredient_id by construction.
			List<LineDraft> lines = vlines.stream()
					.map(r -> new LineDraft(r.ingredientId(), null, r.quantity(), r.unit(), r.lastPrice()))
					.toList();
			created.add(createPo(actor, e.getKey(), neededBy, null,
					"Generated from the shopping list", lines));
		}
		return created;
	}

	private UUID createPo(AuthenticatedUser actor, UUID vendorId, LocalDate neededBy,
			String deliveryLocation, String notes, List<LineDraft> lines) {
		requireVendor(vendorId);
		String poNumber = nextPoNumber();
		UUID id = UUID.randomUUID();
		// The temple's own day, not CURRENT_DATE, which the driver evaluates in whatever time zone
		// the JVM happens to run in. An order raised at 02:00 in Bengaluru was dated the previous
		// day by a server running in UTC, and the needed-by date below is measured against this one.
		LocalDate orderDate = LocalDate.now(clock.zone());
		jdbc.update(connection -> {
			var ps = connection.prepareStatement("""
					INSERT INTO purchase_orders (
						id, tenant_id, po_number, vendor_id, status, order_date, needed_by,
						delivery_location, notes, created_by)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, 'DRAFT',
						?, ?, ?, ?, ?)
					""");
			ps.setObject(1, id);
			ps.setString(2, poNumber);
			ps.setObject(3, vendorId);
			ps.setObject(4, orderDate);
			ps.setObject(5, neededBy);
			ps.setString(6, trimToNull(deliveryLocation));
			ps.setString(7, trimToNull(notes));
			ps.setObject(8, actor.getUserId());
			return ps;
		});
		insertLines(id, lines);
		recordEvent(id, "CREATED", poNumber + " created as draft with " + lines.size() + " line(s)", actor);
		return id;
	}

	// ---- Lifecycle ------------------------------------------------------

	/**
	 * Edits a draft: its lines, its delivery location, its notes, and the date the temple needs it by.
	 *
	 * <p><strong>This is the only way a needed-by date is ever changed, and it stops at SENT.</strong>
	 * That guard is not a tidiness rule about editing. The date has been read out to a vendor, so
	 * moving it afterwards changes what they were asked for without telling them; and it is the line
	 * the vendor scorecard measures on-time against (E5-S9), so leaving it editable would let anybody
	 * rewrite a supplier's record after the deliveries had already happened. The refusal lives here,
	 * on the server, because a form that merely hides the field is not a guard.
	 */
	@Transactional
	public void update(AuthenticatedUser actor, UUID id, UpdatePurchaseOrderRequest request) {
		PurchaseOrderView po = findHeader(id).orElseThrow(() -> notFound(id));
		if (po.status() != PoStatus.DRAFT) {
			throw new ApplicationException(ErrorCode.PO_NOT_EDITABLE, Map.of("purchaseOrderId", id));
		}
		requireNeededByOnOrAfter(request.neededBy(), po.orderDate(), id);
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
		recordEvent(poId, eventType, detail, actor, null);
	}

	/** Records a trail event linked to a notification, so a delivery webhook can find it (E5-S7). */
	@Transactional
	public void recordEvent(UUID poId, String eventType, String detail, AuthenticatedUser actor,
			UUID notificationId) {
		jdbc.update(connection -> {
			var ps = connection.prepareStatement("""
					INSERT INTO po_events (
						tenant_id, po_id, event_type, detail, actor_user_id, actor_name, notification_id)
					VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?)
					""");
			ps.setObject(1, poId);
			ps.setString(2, eventType);
			ps.setString(3, detail);
			ps.setObject(4, actor == null ? null : actor.getUserId());
			ps.setString(5, actor == null ? null : actor.getFullName());
			ps.setObject(6, notificationId);
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
		return "PO-" + LocalDate.now(clock.zone()).getYear() + "-" + String.format("%04d", seq);
	}

	/**
	 * Writes the line set. Every line is checked before anything is written, so a refusal is decided
	 * about the whole order rather than discovered half-way through it — and the earlier, kinder
	 * version of the refusal the ledger would make later anyway, at the point where somebody can
	 * still fix the line.
	 *
	 * <p>Two checks, and they are different in kind. Every line must name <em>exactly one</em>
	 * subject — an ingredient or a description (T-024) — which is the database's own
	 * {@code po_lines_has_exactly_one_subject} said in words somebody can act on rather than as a
	 * constraint violation. And every line that names an <em>ingredient</em> must be measured in
	 * something that ingredient can be measured in (BL-9).
	 *
	 * <p><strong>The unit check is skipped for a described line, not relaxed for everybody.</strong>
	 * {@code IngredientUnits.find()} refuses an id it cannot resolve with RESOURCE_NOT_FOUND, and a
	 * null id is one of those. Making {@code find()} lenient so that null passed through would have
	 * been the small edit, and it would have weakened the check for every real ingredient too — a
	 * mistyped id would then have sailed past BL-9 rather than being refused. So the caller decides
	 * whether there is an ingredient to check, and the check itself stays strict. A described line
	 * still carries a unit (four stools is 4 PIECES); there is simply no catalogue row to compare it
	 * against, and no arithmetic anywhere that will convert it.
	 */
	private void insertLines(UUID poId, List<LineDraft> lines) {
		for (int i = 0; i < lines.size(); i++) {
			LineDraft l = lines.get(i);
			requireExactlyOneSubject(l, i);
			if (l.ingredientId() != null) {
				ingredientUnits.requireSameFamily(l.ingredientId(), IngredientUnits.parse(l.unit()));
			}
		}

		int[] order = {0};
		for (LineDraft l : lines) {
			jdbc.update("""
					INSERT INTO purchase_order_lines (
						id, tenant_id, po_id, ingredient_id, description, quantity, unit, expected_price,
						line_order)
					VALUES (gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						?, ?, ?, ?, ?, ?, ?)
					""", poId, l.ingredientId(), l.description(), l.quantity(), l.unit(),
					l.expectedPrice(), order[0]++);
		}
	}

	/**
	 * A line names an ingredient or describes something the catalogue has never heard of, and never
	 * both or neither (T-024).
	 *
	 * <p>Both halves of the refusal matter. Neither is the old shape of the request arriving from a
	 * client that has not been updated, and it must not be allowed to write a line with no subject
	 * at all. Both is the more interesting one: "Rice — and also, plastic stools" is not a line, it
	 * is two, and letting the description ride along as a note beside an ingredient would make
	 * {@code ingredientId == null} stop being a reliable discriminator for the six consumers that
	 * now branch on it.
	 *
	 * <p>The field error names the line by position, because an order can run to twenty lines and a
	 * described one has no ingredient name to identify it by. The position is the one the request
	 * sent, which is the order the form shows.
	 */
	private void requireExactlyOneSubject(LineDraft l, int index) {
		boolean hasIngredient = l.ingredientId() != null;
		boolean hasDescription = l.description() != null;
		if (hasIngredient != hasDescription) {
			return;
		}
		throw new ApplicationException(
				ErrorCode.PURCHASE_LINE_NEEDS_A_SUBJECT,
				Map.of("lineIndex", index, "hasIngredient", hasIngredient,
						"hasDescription", hasDescription),
				List.of(new ErrorResponse.FieldError(
						"Line " + (index + 1),
						hasIngredient
								? "This line names an ingredient and also describes something. Keep one."
								: "This line names nothing. Pick an ingredient, or describe what you're buying.")),
				null);
	}

	/**
	 * A needed-by date somebody typed must not sit behind the order it belongs to.
	 *
	 * <p>Asking a vendor for something yesterday is not a request, and E5-S9 would score the order
	 * late from the moment it was raised. Null passes: the column is nullable, an order with nothing
	 * to meet is a real thing, and the scorecard counts those aside rather than judging them.
	 *
	 * <p><strong>Only what a person types is checked.</strong> Generation from the shopping list
	 * derives the date from demand — the earliest meal that needs the ingredient, less the lead
	 * buffer — and that arithmetic can land legitimately in the past when a meal is planned for
	 * tomorrow. Refusing it there would break the shopping list rather than protect anything, so the
	 * rule is about a date that was asked for, not about one that was worked out.
	 */
	private void requireNeededByOnOrAfter(LocalDate neededBy, LocalDate floor, UUID poId) {
		if (neededBy == null || floor == null || !neededBy.isBefore(floor)) {
			return;
		}
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("neededBy", neededBy);
		detail.put("orderDate", floor);
		if (poId != null) {
			detail.put("purchaseOrderId", poId);
		}
		throw new ApplicationException(ErrorCode.NEEDED_BY_BEFORE_ORDER_DATE, detail);
	}

	private void requireVendor(UUID vendorId) {
		Integer n = jdbc.queryForObject("SELECT count(*) FROM vendors WHERE id = ?", Integer.class, vendorId);
		if (n == null || n == 0) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("vendorId", vendorId));
		}
	}

	/**
	 * A described line's text is trimmed to null here, so " " arrives at the exclusivity check as
	 * "nothing was described" rather than as a description made of a space. The database's own CHECK
	 * refuses a blank too, but a constraint violation is not a sentence anybody can act on.
	 */
	private List<LineDraft> toLines(List<PoLineInput> inputs) {
		return inputs.stream()
				.map(i -> new LineDraft(i.ingredientId(), trimToNull(i.description()), i.quantity(),
						i.unit().trim(), i.expectedPrice()))
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

	/** Exactly one of {@code ingredientId} and {@code description} is set — see insertLines. */
	private record LineDraft(UUID ingredientId, String description, BigDecimal quantity, String unit,
			BigDecimal expectedPrice) {
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
			rs.getString("description"),
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
