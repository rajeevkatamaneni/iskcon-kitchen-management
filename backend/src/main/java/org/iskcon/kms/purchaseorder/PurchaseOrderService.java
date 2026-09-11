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
import org.iskcon.kms.notification.TenantWhatsAppSettingsService;
import org.iskcon.kms.shoppinglist.ShoppingListLineView;
import org.iskcon.kms.shoppinglist.ShoppingListService;
import org.iskcon.kms.tenancy.TempleClock;
import org.iskcon.kms.vendor.LeadTimes;
import org.iskcon.kms.vendor.OrderLeadTime;
import org.iskcon.kms.vendor.VendorPerformanceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purchase orders and their lifecycle (E5-S3): DRAFT → SENT → PARTIALLY_RECEIVED → RECEIVED /
 * CLOSED / CANCELLED. Approved shopping-list lines are grouped into one draft PO per vendor; manual creation is
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
	private final ShoppingListService shoppingListService;
	/**
	 * Only ever asked one question: has this temple's WhatsApp ever actually sent anything (T-136)?
	 *
	 * <p>Injected rather than reading {@code tenant_settings} with SQL of our own, because the
	 * column and the meaning of its NULL belong to the notification side. Two packages holding two
	 * copies of "and NULL means never" is how the second copy comes to be wrong.
	 */
	private final TenantWhatsAppSettingsService whatsappSettings;

	/**
	 * The one place the order-by date is worked out (T-090), and the one place that knows which lead
	 * time governs an order with several on it (T-137).
	 *
	 * <p>Injected rather than reimplemented here, and that is the whole of why T-137 was built as a
	 * single task. The same subtraction — needed-by minus the vendor's lead time — has to answer on
	 * the planner badge, on Mark sent, on the Today dashboard and at the top of the purchase-order
	 * list. Written four times it would quietly disagree four ways: one counting plain days and
	 * another skipping Sundays, one reading an unrecorded lead time as zero and another as unknown.
	 * Nobody would ever see an exception. They would see the planner telling a cook to order by the
	 * 13th, this service letting the order through on the 14th in silence, and the dashboard calling
	 * the draft healthy.
	 */
	private final LeadTimes leadTimes;

	/**
	 * Asked one question: what did this order score on delivery (T-142, D-26)?
	 *
	 * <p>Injected rather than reimplemented for the same reason {@link #leadTimes} is. The person
	 * closing a part-delivered order is shown the figure the scorecard will report for it, and a
	 * second implementation of that sum would eventually show them a different number from the one
	 * on the report — with both defended by somebody, and the transparency the ruling asked for
	 * turned into a second thing to argue about.
	 */
	private final VendorPerformanceService vendorPerformance;

	public PurchaseOrderService(JdbcTemplate jdbc, AuditService auditService,
			org.iskcon.kms.document.DocumentService documentService,
			IngredientUnits ingredientUnits, ShoppingListService shoppingListService,
			TenantWhatsAppSettingsService whatsappSettings,
			LeadTimes leadTimes,
			VendorPerformanceService vendorPerformance,
			TempleClock clock) {
		this.clock = clock;
		this.leadTimes = leadTimes;
		this.vendorPerformance = vendorPerformance;
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.documentService = documentService;
		this.ingredientUnits = ingredientUnits;
		this.shoppingListService = shoppingListService;
		this.whatsappSettings = whatsappSettings;
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
	 * <li><b>SENT, PARTIALLY_RECEIVED, CLOSED and RECEIVED are all offered.</b> RECEIVED is the
	 * ordinary case — the bill usually arrives after the goods. PARTIALLY_RECEIVED is offered
	 * deliberately: vendors bill for what they have delivered so far, and hiding a part-delivered
	 * order would push exactly that invoice onto the direct path, where it loses its order and its
	 * variance. CLOSED is that same order after somebody ended it (T-142, D-26), and it is the case
	 * this list would most easily have got wrong: 300 kg of rice arrived and is owed for, so the
	 * bill for it is a payable like any other. Closing an order settles what the vendor still owes
	 * us, not what we owe them.</li>
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
			sql.append(" AND po.status IN ('SENT', 'PARTIALLY_RECEIVED', 'CLOSED', 'RECEIVED')");
		}
		sql.append(" ORDER BY po.created_at DESC");
		return withLeadTimes(jdbc.query(sql.toString(), HEADER_MAPPER, args.toArray()));
	}

	/**
	 * Fills in every order's lead-time facts: what its vendor asked for, the last day it could be
	 * placed, and — while it is still a draft — where today stands against that day (T-137, D-25).
	 *
	 * <p><strong>A draft is measured live and a sent order is read off its own row, and that split
	 * is the no-retroactive rule.</strong> Nothing has been asked of a vendor while an order is a
	 * draft, so if their profile changes the draft moves with it. Once the order has gone out, the
	 * lead time it went out under is stamped on it by {@link #send}, and editing the vendor's
	 * profile next month must not re-judge it: <em>"Any SLA Adjustments made to a vendor's profile
	 * will take effect for the Orders after the change. No retroactive change here."</em> Reading
	 * {@code vendor_supplies} for a sent order would turn past deliveries late, or excuse ones that
	 * genuinely were.
	 *
	 * <p>One extra query for a whole screenful, and none at all for a list with no unsent orders on
	 * it.
	 */
	private List<PurchaseOrderView> withLeadTimes(List<PurchaseOrderView> orders) {
		List<UUID> unsent = orders.stream()
				.filter(po -> po.sentAt() == null)
				.map(PurchaseOrderView::id)
				.toList();
		Map<UUID, Integer> live = leadTimes.governingByPurchaseOrder(unsent);
		LocalDate today = LocalDate.now(clock.zone());
		return orders.stream()
				.map(po -> po.with(po.sentAt() == null
						? OrderLeadTime.beforeSending(live.get(po.id()), po.neededBy(), today)
						: OrderLeadTime.asSent(po.leadTimeDays(), po.neededBy())))
				.toList();
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
					   l.unit, l.expected_price, l.arrived_on
				FROM purchase_order_lines l
				LEFT JOIN ingredients i ON i.id = l.ingredient_id
				WHERE l.po_id = ?
				ORDER BY l.line_order, COALESCE(i.name, l.description)
				""", LINE_MAPPER, id);
		List<PoEventView> events = jdbc.query("""
				SELECT event_type, detail, actor_name, created_at
				FROM po_events WHERE po_id = ? ORDER BY created_at
				""", EVENT_MAPPER, id);
		// A tenant-wide fact, carried on the order because this is the payload the order screen
		// already reads and it may not ask the settings endpoint for it — see the note on
		// PurchaseOrderDetailView.whatsappEverSent. One extra single-row lookup by primary key.
		return new PurchaseOrderDetailView(
				header, lines, events, whatsappSettings.hasEverSentSuccessfully(),
				// Shown, never editable (D-26). One more read on a screen that already runs three,
				// and it is the fact the closing decision is meant to be made against.
				vendorPerformance.scoreOfOrder(id));
	}

	// ---- Create ---------------------------------------------------------

	/**
	 * Raises an order that did not exist a moment ago — typed by hand at {@code /orders/new}, or
	 * built in the panel a vendor tile on the shopping list opens (T-134).
	 *
	 * <p><strong>It answers with the order's number as well as its id.</strong> Rajeev's journey
	 * (D-24 §6) ends with "a green confirmation naming the PO number" on the shopping list — the
	 * thing a person can read out, {@code PO-2026-0041}, not a uuid — and the number is already in
	 * hand here, having just been allocated. Returning it costs nothing; making the screen fetch
	 * the order back to read one string is a second round trip for a fact this method already knows.
	 */
	@Transactional
	public CreatedPurchaseOrder createManual(AuthenticatedUser actor, CreatePurchaseOrderRequest request) {
		// A new order is dated the temple's today, so that is the floor a hand-typed needed-by is
		// measured against. Checked here and not in createPo, because generation is not a person
		// typing: see requireNeededByOnOrAfter.
		requireNeededByOnOrAfter(request.neededBy(), LocalDate.now(clock.zone()), null);
		return createPo(actor, request.vendorId(), request.neededBy(),
				request.deliveryLocation(), request.notes(), toLines(request.lines()));
	}

	/**
	 * One draft PO per distinct vendor from the selected, included shopping-list lines (E5-S3).
	 *
	 * <p><strong>The lines are asked for, not selected.</strong> Until T-132 this read
	 * {@code shopping_list_lines} directly — {@code WHERE included = true AND suggested_vendor_id IS
	 * NOT NULL} — because that table held the whole list. It now holds only what a person decided
	 * about a line: an edited quantity, an untick, something typed in by hand. The same query against
	 * the same table would therefore raise orders containing the hand-added lines and nothing else —
	 * no rice, no curd, no oil — and would do it silently, with a 201 and a plausible-looking order.
	 *
	 * <p>A database view over the decisions could not have saved that query, and it is worth saying
	 * why rather than leaving somebody to try it: the shortfall stream is
	 * {@code SufficiencyService.allocateAcrossWindow()}, a Java walk that allocates the store to every
	 * claim in the buying window in draw-down order, meal by meal. Reimplementing it in SQL would put
	 * the temple's most important arithmetic in two places.
	 *
	 * <p>So the derivation stays in one place and this asks it for the list. One consequence follows
	 * immediately and is D-24a working as ruled: the orders created here are drafts, and a draft
	 * covers its ingredients, so those lines are off the shopping list on the very next read without
	 * anything being deleted.
	 *
	 * <p>The needed-by date on each order is still the <strong>earliest</strong> across that vendor's
	 * lines — the order is only useful if it arrives in time for the first meal that wants any of it.
	 * D-25's separate ruling, that the <em>longest</em> lead time governs a multi-line order, is about
	 * a different quantity: the last day the order can be placed. The two do not conflict and neither
	 * replaces the other. Stamping that lead time onto the order is T-134's, not this method's.
	 */
	@Transactional
	public List<UUID> generateFromShoppingList(AuthenticatedUser actor, List<UUID> ingredientIds) {
		Map<UUID, Map<UUID, BigDecimal>> lastPrices = lastPricesByVendor();

		Map<UUID, List<OrderLineRow>> byVendor = new LinkedHashMap<>();
		for (ShoppingListLineView line : shoppingListService.list()) {
			if (!line.included() || line.suggestedVendorId() == null) {
				continue;
			}
			if (ingredientIds != null && !ingredientIds.isEmpty()
					&& !ingredientIds.contains(line.ingredientId())) {
				continue;
			}
			BigDecimal lastPrice = lastPrices
					.getOrDefault(line.suggestedVendorId(), Map.of())
					.get(line.ingredientId());
			byVendor.computeIfAbsent(line.suggestedVendorId(), k -> new ArrayList<>())
					.add(new OrderLineRow(line.ingredientId(), line.suggestedQty(), line.unit(),
							line.suggestedVendorId(), line.neededBy(), lastPrice));
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
					"Generated from the shopping list", lines).id());
		}
		return created;
	}

	/**
	 * What each vendor last charged for each thing it supplies, so a generated line carries a price
	 * somebody can sanity-check. One read for the catalogue rather than a join onto a list that is no
	 * longer a table.
	 */
	private Map<UUID, Map<UUID, BigDecimal>> lastPricesByVendor() {
		Map<UUID, Map<UUID, BigDecimal>> map = new LinkedHashMap<>();
		jdbc.query("""
				SELECT vendor_id, ingredient_id, last_price
				FROM vendor_supplies WHERE last_price IS NOT NULL
				""", rs -> {
			map.computeIfAbsent(rs.getObject("vendor_id", UUID.class), k -> new LinkedHashMap<>())
					.put(rs.getObject("ingredient_id", UUID.class), rs.getBigDecimal("last_price"));
		});
		return map;
	}

	/**
	 * Writes the order header and its lines, and hands back both halves of its identity.
	 *
	 * <p><strong>A line that arrives without an expected price is given the vendor's last-known
	 * one.</strong> That figure is what the sheet prints beside the quantity and what the delivery
	 * and the vendor's bill are both checked against, and until T-134 only generation from the
	 * shopping list carried it — generation looked the prices up and manual creation sent nulls.
	 * The shopping list now raises its orders through this path (one order per vendor tile, created
	 * by the panel's Save), so leaving the fill in the generator would have quietly dropped the
	 * price column off every order the temple raises in the ordinary way.
	 *
	 * <p>It fills only what was left blank, and only for a line naming a catalogue ingredient: a
	 * price the caller sent is the caller's, and a described line — four plastic stools — has no
	 * catalogue row to have a last price on. Where nothing is known the column stays null and the
	 * sheet prints a dash, which is truthful; an invented number would not be.
	 */
	private CreatedPurchaseOrder createPo(AuthenticatedUser actor, UUID vendorId, LocalDate neededBy,
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
		insertLines(id, withLastKnownPrices(vendorId, lines));
		recordEvent(id, "CREATED", poNumber + " created as draft with " + lines.size() + " line(s)", actor);
		return new CreatedPurchaseOrder(id, poNumber);
	}

	/** One vendor's last-known prices, applied to the lines that did not bring one. */
	private List<LineDraft> withLastKnownPrices(UUID vendorId, List<LineDraft> lines) {
		if (lines.stream().noneMatch(l -> l.expectedPrice() == null && l.ingredientId() != null)) {
			return lines;
		}
		Map<UUID, BigDecimal> lastPrices = new LinkedHashMap<>();
		jdbc.query("""
				SELECT ingredient_id, last_price FROM vendor_supplies
				WHERE vendor_id = ? AND last_price IS NOT NULL
				""", rs -> {
			lastPrices.put(rs.getObject("ingredient_id", UUID.class), rs.getBigDecimal("last_price"));
		}, vendorId);
		return lines.stream()
				.map(l -> l.expectedPrice() != null || l.ingredientId() == null
						? l
						: new LineDraft(l.ingredientId(), l.description(), l.quantity(), l.unit(),
								lastPrices.get(l.ingredientId())))
				.toList();
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

	/** Sends an order, refusing one that is already past its vendor's lead time. See the overload. */
	@Transactional
	public void send(AuthenticatedUser actor, UUID id) {
		send(actor, id, false);
	}

	/**
	 * Marks an order as sent — and this is where the vendor's lead time is enforced and stamped
	 * (T-137, D-25).
	 *
	 * <h2>Why the gate is here and nowhere else</h2>
	 *
	 * <p>A lead time is the vendor's own number, agreed at onboarding: <em>"we are good with 1 day
	 * lead time BUT we want to be safe than sorry so we need 3 days notice to guarantee that
	 * everything will be delivered on time 100%."</em> Rajeev, asked where the check belongs,
	 * answered that the zone is a fact about <strong>submitting</strong>. Showing it earlier — on
	 * the panel that creates the order, on the planner badge — is helpful, but those are advice.
	 * This is the moment the promise is either kept or asked to be broken, so this is the gate.
	 *
	 * <h2>The three zones, in his own worked example</h2>
	 *
	 * <p>Heritage Fresh Dairy promised two days; curd is wanted for 15 September.
	 *
	 * <ul>
	 *   <li><strong>Ordered 11 September</strong> — <em>"with in spec and no alarm bells here"</em>.
	 *       Nothing happens.</li>
	 *   <li><strong>Ordered 13 September</strong>, the last day that works — a gentle nudge, which he
	 *       called optional: <em>"All of this nudging and coaching is optional."</em> Not a refusal,
	 *       and nothing is stamped: the order is inside the promise.</li>
	 *   <li><strong>Ordered 14 September</strong> — refused with {@code KMS-400148} until somebody
	 *       says they mean it, and then stamped.</li>
	 * </ul>
	 *
	 * <p><strong>The cutoff is a date and not a time.</strong> His <em>"Sep 13 at 6 AM"</em> is
	 * colour; the rule is needed-by minus the lead time, and 13 September is simply the last day
	 * that works. Nothing here adds a time of day to a needed-by date.
	 *
	 * <h2>What sending late costs, and who it costs</h2>
	 *
	 * <p><em>"That is a FAVOR we are asking."</em> So this never refuses outright — a temple that
	 * genuinely needs a sack of rice tomorrow must be able to ask for it, and a rule that made that
	 * impossible would only teach people to write a date they do not mean. What it does is make the
	 * consequence explicit before the press, and then record it: the order is left out of the
	 * vendor's on-time figure, and cancelling it later does not offer the "Vendor Never Delivered
	 * this Order" tick, <em>"BECAUSE it is their fault NOT the vendors"</em> — and here the fault is
	 * ours. That extends T-129, which already required an order to have been sent; now it also
	 * requires one sent in time.
	 *
	 * <h2>The stamp, and why the column exists at all</h2>
	 *
	 * <p>{@code lead_time_days} and {@code sent_after_lead_time} are written here and read
	 * everywhere afterwards. Nothing downstream may go back to {@code vendor_supplies} for a sent
	 * order: <em>"Any SLA Adjustments made to a vendor's profile will take effect for the Orders
	 * after the change. No retroactive change here."</em> Without the stamp, one edit to a vendor's
	 * profile would silently re-judge every order already placed.
	 *
	 * <p>The lead time is stamped <strong>whether or not the order is late</strong>. It is the
	 * figure that applied, and a reader asking why an on-time delivery counted as on time needs it
	 * as much as one asking why a late order was excused.
	 *
	 * @param sendAnyway the person was shown the refusal and meant it — the override Rajeev asked
	 *                   for. False for every other caller, which is the reading that keeps the
	 *                   promise.
	 */
	@Transactional
	public void send(AuthenticatedUser actor, UUID id, boolean sendAnyway) {
		PurchaseOrderView po = findHeader(id).orElseThrow(() -> notFound(id));
		if (po.status() != PoStatus.DRAFT) {
			throw new ApplicationException(ErrorCode.PO_INVALID_TRANSITION, Map.of("purchaseOrderId", id));
		}
		// The zone is already on the view, worked out by LeadTimes for every read of this order —
		// so the refusal below is decided by the same value the person was looking at when they
		// pressed the button, rather than by a second sum that could disagree with it.
		boolean tooLate = po.orderUrgency() == org.iskcon.kms.vendor.OrderUrgency.TOO_LATE;
		if (tooLate && !sendAnyway) {
			// The detail is what the screen needs to say the sentence properly: whose promise, how
			// long it was, and which day has gone. The words a person reads are ErrorCode's.
			Map<String, Object> detail = new LinkedHashMap<>();
			detail.put("purchaseOrderId", id);
			detail.put("vendorName", po.vendorName());
			detail.put("leadTimeDays", po.leadTimeDays());
			detail.put("orderBy", po.orderBy());
			detail.put("neededBy", po.neededBy());
			throw new ApplicationException(ErrorCode.ORDER_PAST_VENDOR_LEAD_TIME, detail);
		}
		jdbc.update("""
				UPDATE purchase_orders
				SET status = 'SENT', sent_at = now(), lead_time_days = ?, sent_after_lead_time = ?,
					updated_at = now()
				WHERE id = ?
				""", po.leadTimeDays(), tooLate, id);
		recordEvent(id, "SENT", tooLate
				// Said on the order's own trail, because this is the one record a person reading the
				// order back will find — and it is the sentence that explains a figure on somebody
				// else's scorecard. The vendor is named because the promise was theirs.
				? po.poNumber() + " sent to vendor after the last day it could be ordered ("
						+ po.orderBy() + "). " + po.vendorName() + " asked for " + po.leadTimeDays()
						+ " day(s) notice, so a late delivery on this order is not counted against them."
				: po.poNumber() + " sent to vendor", actor);
		Map<String, Object> after = new LinkedHashMap<>();
		after.put("status", "SENT");
		after.put("poNumber", po.poNumber());
		// In the after-state because this is what the order now permanently is, and because a claim
		// that a supplier is excused from a delivery belongs beside who did it and when — which the
		// audit actor and sent_at already carry.
		after.put("leadTimeDays", po.leadTimeDays());
		after.put("sentAfterLeadTime", tooLate);
		auditService.record(actor, AuditAction.PO_SENT, AuditEntityType.PURCHASE_ORDER, id,
				Map.of("status", "DRAFT"), after, null);

		// A sent PO gets its vendor sheet automatically (E5-S4); best-effort, so a worker-less
		// context (or node) never blocks the send.
		documentService.autoGeneratePurchaseOrderPdf(id);
	}

	/**
	 * Cancels an order, and records whether the vendor is why (T-124).
	 *
	 * <p><strong>{@code vendorAbandoned} is a statement about the supplier, not about the order.</strong>
	 * Rajeev's fifth delivery scenario of 2026-09-09 — <em>"nothing ever came; we cancelled and went
	 * elsewhere"</em> — is the only one the application could not record at all, because the vendor
	 * scorecard excludes every cancellation on the perfectly good ground that a cancellation is
	 * usually the temple's own decision. The tick box is what tells the two apart, and it is the one
	 * new fact the whole of T-124 asks anybody to enter.
	 *
	 * <p>Nothing already stored could have inferred it. A cancelled order with no goods receipt is
	 * equally consistent with a supplier who never came and with a festival called off the day after
	 * the order went out.
	 *
	 * <p><strong>An order nobody sent cannot be marked that way (T-129).</strong> Rajeev ruled on
	 * 2026-09-10, from three options he was given, that the box is only offered once an order has
	 * been sent. The occasion was a real one: the coordinator raised PO-2026-0036 as a draft on
	 * staging, never pressed <em>Mark sent</em>, cancelled it with the box ticked, and Heritage Fresh
	 * Dairy read "0% on time, 1 order never delivered" for an order the vendor had never heard of.
	 * A permanent claim about somebody else's business needs, at the very least, something to have
	 * been asked of them.
	 *
	 * <p>The refusal lives here rather than only on the cancel panel, for the same reason the
	 * needed-by guard above does: the screen hides the box, and the endpoint takes the same field
	 * from anything that can post to it. {@code sent_at} is the fact it reads, and that column is
	 * stamped in exactly one place — {@link #send} — so "was it sent?" has one answer and not two.
	 *
	 * <p><strong>D-25 extends that rule to an order we sent too late, and it is refused here for the
	 * same reason</strong> (T-137). An order submitted after the vendor's agreed notice period asked
	 * them for something they never promised — <em>"That is a FAVOR we are asking"</em> — so when it
	 * is not delivered, that is our doing and not theirs. <em>"BECAUSE it is their fault NOT the
	 * vendors"</em>: on such an order the fault is ours, and putting it on their permanent record is
	 * the specific thing the ruling forbids. {@code KMS-400149}.
	 *
	 * <p>The two refusals are one idea said twice — nothing was asked of this vendor, or nothing was
	 * asked of them <em>in time</em> — and both read a column stamped in exactly one place by {@link
	 * #send}, so each question has one answer and not two. The cancel form hides the box in both
	 * cases; these are what stop it anyway.
	 *
	 * <p><strong>What was given up by choosing this, said plainly, because it is a real loss.</strong>
	 * <em>Sent</em> in this application means somebody pressed a button, not that a vendor knows. A
	 * temple that rings its dairy, never marks the order sent and is then let down now has no way to
	 * record it. The route back is to mark the order sent first, which is true — it was asked for —
	 * and then cancel it.
	 *
	 * <p><strong>It is not a schema CHECK, and that is deliberate.</strong> V118's
	 * {@code purchase_orders_abandoned_is_a_cancellation} constrains two columns written by this one
	 * statement, and no product decision could ever make an un-cancelled no-show meaningful. This
	 * rule is a different kind of thing: it is a policy about what a person may assert, chosen from
	 * three defensible options on one day, against a recommendation to leave it alone. Policy that
	 * may be revisited belongs where reverting it is an edit to a method rather than a migration and
	 * a second rewrite of stored rows. V120 corrects the one row that predates the ruling; it adds
	 * no constraint.
	 *
	 * <p><strong>It goes into the audit record's after-state, and into the order's own trail.</strong>
	 * The after-state because that is where a permanent claim about a third party belongs — who
	 * ticked it and when are already carried by the audit actor and {@code cancelled_at}, so the
	 * flag beside them makes the record readable as one act. The trail because the order screen
	 * shows that trail and would otherwise offer a person a box to tick and then never show it back;
	 * the sentence is appended to the reason rather than replacing it, so the operator's own words
	 * survive verbatim in {@code cancel_reason} and in the line beneath them.
	 */
	@Transactional
	public void cancel(AuthenticatedUser actor, UUID id, String reason, boolean vendorAbandoned) {
		PurchaseOrderView po = findHeader(id).orElseThrow(() -> notFound(id));
		// CLOSED joins the two terminal states here (T-142, D-26). A closed order has already been
		// ended by a person who said how it ended for the vendor, and its remainder has been
		// released to the shopping list and very likely re-ordered since; cancelling it afterwards
		// would withdraw an order 300 kg of real rice arrived against and take that delivery out of
		// the supplier's record. The ending is the decision, and there is one of them.
		if (po.status() == PoStatus.RECEIVED || po.status() == PoStatus.CANCELLED
				|| po.status() == PoStatus.CLOSED) {
			throw new ApplicationException(ErrorCode.PO_INVALID_TRANSITION, Map.of("purchaseOrderId", id));
		}
		// And a part-delivered order is not cancellable at all any more (T-142, D-26).
		//
		// Until closing existed this was the only ending such an order had, and it was the wrong
		// one in a way that matters rather than in a way that is untidy. A cancellation leaves the
		// scorecard's LIVE_ORDER predicate entirely, so cancelling this order would erase 300 kg of
		// real rice from the supplier's record — and the "Vendor Never Delivered this Order" tick
		// could then be put against a vendor who demonstrably DID deliver. That is a number this
		// product promises is defensible becoming indefensible, which is the exact standard D-25,
		// T-129 and KMS-400149 were all built to hold.
		//
		// KMS-400150 says so and names the door that is right: close it instead, and what arrived
		// stays on the vendor's record while what did not goes back on the shopping list.
		//
		// Checked before the two claims below rather than after, because this is about the order's
		// state and holds whether or not anybody ticked anything: an unticked cancellation of a
		// part-delivered order still erases the delivery.
		if (po.status() == PoStatus.PARTIALLY_RECEIVED) {
			throw new ApplicationException(ErrorCode.PO_PART_DELIVERED_CANNOT_CANCEL,
					Map.of("purchaseOrderId", id));
		}
		// Nothing was asked of the vendor, so nothing can be held against them (T-129). Read off
		// sent_at rather than off the status, because a cancelled order's status no longer says
		// whether it was ever sent — that is precisely the case this refuses.
		if (vendorAbandoned && po.sentAt() == null) {
			throw new ApplicationException(ErrorCode.PO_NEVER_SENT_TO_VENDOR, Map.of("purchaseOrderId", id));
		}
		// And nothing was asked of them IN TIME, which D-25 makes the same kind of claim (T-137).
		// Read off the stamp rather than recomputed: what this order went out under was decided when
		// it went out, and a vendor whose profile changed last week must not become blameable — or
		// unblameable — for an order nobody has touched since.
		if (vendorAbandoned && po.sentAfterLeadTime()) {
			Map<String, Object> detail = new LinkedHashMap<>();
			detail.put("purchaseOrderId", id);
			detail.put("vendorName", po.vendorName());
			detail.put("leadTimeDays", po.leadTimeDays());
			detail.put("orderBy", po.orderBy());
			throw new ApplicationException(ErrorCode.PO_SENT_AFTER_LEAD_TIME, detail);
		}
		jdbc.update("""
				UPDATE purchase_orders SET status = 'CANCELLED', cancel_reason = ?, cancelled_at = now(),
					vendor_abandoned = ?, updated_at = now() WHERE id = ?
				""", reason.trim(), vendorAbandoned, id);
		recordEvent(id, "CANCELLED", vendorAbandoned
				? reason.trim() + " — recorded as never delivered by the vendor."
				: reason.trim(), actor);
		auditService.record(actor, AuditAction.PO_CANCELLED, AuditEntityType.PURCHASE_ORDER, id,
				Map.of("status", po.status().name()),
				Map.of("status", "CANCELLED", "vendorAbandoned", vendorAbandoned), reason.trim());
	}

	/**
	 * Ends a part-delivered order, releasing what the vendor never brought (T-142, D-26).
	 *
	 * <h2>The scenario, and why the remainder waited this long</h2>
	 *
	 * <p>Rajeev, 2026-09-10, walking through a real delivery: 500 kg of rice ordered, the vendor has
	 * 300 on hand and <em>sends it immediately so the kitchen can cook</em>, expecting stock in two
	 * days for the rest. <em>"The 200 KG should still be tied to the PO that raised and sent the
	 * 500KG rice order and it should sit in a partially delivered state and the clock keeps
	 * ticking."</em>
	 *
	 * <p>So a short delivery does not put its remainder back on the shopping list. The vendor still
	 * owes it and the order is still live; what the temple sees instead is a purchase order past due,
	 * on the clock, asking for a decision — which is better than a shopping-list line, because it
	 * names the vendor who owes it. <strong>This method is that decision</strong>, and it is the
	 * third door beside D-24a's two: a line leaves the list when an order is created, comes back when
	 * one is cancelled, and comes back here when one is closed.
	 *
	 * <p><strong>Nothing is written to the shopping list, and nothing needs to be.</strong> T-132
	 * derives that list on every read, so "released" is not a restore — it is the moment this order
	 * stops answering {@code ShoppingListService.ingredientsOnLiveOrders}. See that method for the
	 * two predicates this status moves between.
	 *
	 * <h2>PARTIALLY_RECEIVED only, and the other two doors stay where they are</h2>
	 *
	 * <p>An order nothing ever arrived against is a cancellation, ticked "Vendor Never Delivered
	 * this Order" if that is what happened (T-124) — there is nothing to close, because nothing was
	 * delivered and nothing is owed for. A fully received order is finished. A draft is swept or
	 * cancelled. Closing exists for the one case none of those describe: goods came, and not all of
	 * them, and somebody has decided the rest never will.
	 *
	 * <h2>The computed score is shown on the way in, and cannot be touched</h2>
	 *
	 * <p>The screen that calls this shows what the vendor actually scored on this order — the figure
	 * {@code VendorPerformanceService} will report, read from the same arithmetic, not a second copy
	 * of it. Rajeev asked for that transparency and then ruled out the control he originally wanted
	 * beside it: <em>"Let us not let the admin adjust the score. Just show it to them."</em> There is
	 * no parameter here that moves a number, and {@link CloseOutcome} carries the four costs that
	 * decided it.
	 *
	 * <h2>What the outcome does</h2>
	 *
	 * <p>{@code VENDOR_LET_US_DOWN} scores exactly as computed — the missing 200 kg is already in the
	 * percentage — and records that the temple holds the supplier responsible.
	 * {@code SHORTFALL_EXCUSED} takes the order out of that vendor's on-time figure and fill rate
	 * entirely, with the count visible beside both percentages. {@code AS_COMPUTED} says nothing
	 * about anybody. Anything other than "as computed" requires a sentence, refused by
	 * {@link ClosePoRequest} and by the row itself (V126).
	 *
	 * <h2>The trail, and the audit row that is missing</h2>
	 *
	 * <p>The closing is written to the order's own append-only trail with the actor named, which is
	 * where the order screen shows it and where a person asking "why did this end like that?" looks.
	 *
	 * <p>It also writes an {@code audit_events} row under {@link AuditAction#PO_CLOSED}, carrying
	 * the outcome and the sentence in its after-state. That is not bookkeeping: excusing a shortfall
	 * is the one act an admin can take that changes what a supplier's scorecard reports, and D-26's
	 * third cost is that a score must be defensible — <em>"an admin changed it" is not an answer to
	 * a vendor who disputes their score</em>. Who waived it, when, and with what sentence is.
	 *
	 * <p>Unlike T-137's sweep there is no obstacle to writing it: closing is a human act, the actor
	 * is verified, and {@code audit_events.actor_user_id} is satisfied.
	 */
	@Transactional
	public void close(AuthenticatedUser actor, UUID id, CloseOutcome outcome, String note) {
		PurchaseOrderView po = findHeader(id).orElseThrow(() -> notFound(id));
		if (po.status() != PoStatus.PARTIALLY_RECEIVED) {
			throw new ApplicationException(ErrorCode.PO_INVALID_TRANSITION, Map.of("purchaseOrderId", id));
		}
		String sentence = trimToNull(note);
		jdbc.update("""
				UPDATE purchase_orders
				SET status = 'CLOSED', closed_at = now(), close_outcome = ?, close_note = ?,
					updated_at = now()
				WHERE id = ?
				""", outcome.name(), sentence, id);
		recordEvent(id, "CLOSED", closureSaid(po, outcome, sentence), actor);
		// The outcome and the sentence go into the after-state, shaped like PO_CANCELLED's, because
		// that is where a permanent claim about a third party belongs — who and when are already
		// carried by the audit actor and closed_at, so the three together read as one act.
		//
		// This row is the answer to D-26's third cost. Excusing a shortfall is the single thing an
		// admin can do that changes what a supplier's scorecard reports, and "an admin changed it"
		// is not an answer to a vendor who disputes their score: who waived it, when, and with what
		// sentence is. A LinkedHashMap because the after-state may carry a null note and Map.of
		// refuses one.
		Map<String, Object> after = new LinkedHashMap<>();
		after.put("status", "CLOSED");
		after.put("closeOutcome", outcome.name());
		after.put("closeNote", sentence);
		auditService.record(actor, AuditAction.PO_CLOSED, AuditEntityType.PURCHASE_ORDER, id,
				Map.of("status", po.status().name()), after, sentence);
	}

	/**
	 * The closing as a line on the order's own trail, in words a person reads months later.
	 *
	 * <p>Three sentences at most, and each earns its place: what happened to the order, what it
	 * means for the vendor's record, and — where somebody made a claim — the claim in their own
	 * words. The operator's sentence is appended rather than folded in, exactly as the cancellation
	 * does it, so what they wrote survives verbatim in {@code close_note} and in the line beneath it.
	 */
	private static String closureSaid(PurchaseOrderView po, CloseOutcome outcome, String note) {
		String opening = po.poNumber() + " was closed with part of it never delivered. "
				+ "What it still asked for is back on the shopping list.";
		String verdict = switch (outcome) {
			case VENDOR_LET_US_DOWN -> " Recorded as the vendor letting us down; it is scored on what"
					+ " actually arrived, as every order is.";
			case SHORTFALL_EXCUSED -> " " + po.vendorName() + " fell short and made it right, so this"
					+ " order is left out of their delivery record entirely.";
			case AS_COMPUTED -> " Nothing was recorded for or against " + po.vendorName()
					+ "; the order is scored on what actually arrived.";
		};
		return note == null ? opening + verdict : opening + verdict + " " + note;
	}

	/**
	 * The reason a swept draft carries, in Rajeev's own words (D-24a): <em>"mark it as Auto
	 * Cancelled. Reason: Past need by date."</em> Written into {@code cancel_reason} exactly as he
	 * said it, because it is what a person will read on the order.
	 */
	static final String PAST_NEED_BY_DATE = "Past need by date";

	/**
	 * Cancels every draft in this temple whose needed-by date has gone (T-137, D-24a).
	 *
	 * <h2>The hole this closes</h2>
	 *
	 * <p>Since D-24a a line leaves the shopping list the moment a purchase order is created, draft
	 * or not — Rajeev: <em>"IF we take it off on send, they will be there in the shopping list
	 * begging to be ordered, someone else will take pity and generate another PO. Same ingredients,
	 * 2 PO's."</em> That is right, and it opens a hole he closed in the same breath: a draft nobody
	 * ever sends holds its ingredients hostage, off the list and never ordered.
	 *
	 * <p>So a draft past its needed-by date is abandoned, and the sweep cancels it. The loop closes
	 * on its own: the cancellation hands those ingredients back to the list (T-132 derives the list,
	 * so nothing has to be written back), where they are suggested again with a fresh date. And
	 * because the order was never sent, nothing is held against the vendor — T-129's rule already
	 * sees to that, with no special case here.
	 *
	 * <h2>It never touches a part-delivered order, and that is not an accident of the filter</h2>
	 *
	 * <p>Rajeev agreed this explicitly (D-26). A draft nobody sent can be swept away by a machine.
	 * An order with 300 kg of real rice against it and a vendor relationship behind it needs a
	 * human: the admin closes it and says which of the two endings it was — the vendor let us down,
	 * or they fell short and made it right. {@code status = 'DRAFT'} is what keeps this job away
	 * from that decision, and it is load-bearing rather than incidental.
	 *
	 * <h2>It has to read afterwards as legibly as a human cancellation</h2>
	 *
	 * <p>This is a scheduled job acting with no human in the room, on a shared record, and the
	 * person who finds the order tomorrow will want to know who cancelled it before they go looking
	 * for who to ask. So it writes the same three columns a person's cancellation writes, and leaves
	 * a line on the order's own append-only trail with <strong>no actor</strong> — which is how the
	 * trail says "the system did this" rather than naming somebody who was asleep.
	 *
	 * <p><strong>No {@code audit_events} row, and that is a limitation worth stating.</strong> That
	 * table's {@code actor_user_id} is NOT NULL and {@code AuditService} reads the actor's id, name
	 * and role off a verified user — there is no system principal in this application, and inventing
	 * one here would be a new shared concept smuggled in as a side effect. The order's own trail is
	 * what the order screen shows and is append-only in exactly the same way, so the act is on the
	 * record where a reader will actually look for it.
	 *
	 * <h2>Idempotent</h2>
	 *
	 * <p>Required of every job in this application, and true here by construction: the swept orders
	 * are no longer drafts, so a second run in the same minute matches nothing. The UPDATE and its
	 * trail line are one transaction.
	 *
	 * @return how many drafts were cancelled, for the job's log line
	 */
	@Transactional
	public int autoCancelAbandonedDrafts() {
		// The temple's own today, like every other date decision here: a draft is not abandoned
		// because it is already tomorrow somewhere the server happens to be running.
		LocalDate today = LocalDate.now(clock.zone());
		List<UUID> swept = new ArrayList<>();
		List<String> numbers = new ArrayList<>();
		List<LocalDate> dates = new ArrayList<>();
		jdbc.query("""
				UPDATE purchase_orders
				SET status = 'CANCELLED', auto_cancelled = TRUE, cancel_reason = ?,
					cancelled_at = now(), updated_at = now()
				WHERE status = 'DRAFT' AND needed_by IS NOT NULL AND needed_by < ?
				RETURNING id, po_number, needed_by
				""", rs -> {
			swept.add(rs.getObject("id", UUID.class));
			numbers.add(rs.getString("po_number"));
			dates.add(rs.getObject("needed_by", LocalDate.class));
		}, PAST_NEED_BY_DATE, today);

		for (int i = 0; i < swept.size(); i++) {
			recordEvent(swept.get(i), "AUTO_CANCELLED",
					numbers.get(i) + " was still a draft on " + dates.get(i)
							+ ", the day it was needed, so it was cancelled automatically. Nothing was "
							+ "sent to the vendor, so nothing counts against them; what it asked for is "
							+ "back on the shopping list.",
					null);
		}
		return swept.size();
	}

	/**
	 * Moves a PO to PARTIALLY_RECEIVED or RECEIVED as receiving progresses (E5-S6). Only a SENT or
	 * already-partially-received PO can receive — receiving a DRAFT or a done PO is refused here.
	 */
	@Transactional
	public void applyReceivedStatus(AuthenticatedUser actor, UUID id, boolean fullyReceived) {
		applyReceivedStatus(actor, id, fullyReceived, "Delivery received");
	}

	/**
	 * The same transition, with the trail line said in the words of whatever caused it.
	 *
	 * <p>"Delivery received" is right when a lorry was unloaded and wrong when the only thing that
	 * happened was somebody confirming that four plastic stools turned up — nothing was received,
	 * in the sense the rest of this application uses the word, and the trail is the record a person
	 * reads back months later.
	 */
	@Transactional
	public void applyReceivedStatus(AuthenticatedUser actor, UUID id, boolean fullyReceived,
			String detail) {
		PurchaseOrderView po = findHeader(id).orElseThrow(() -> notFound(id));
		if (po.status() != PoStatus.SENT && po.status() != PoStatus.PARTIALLY_RECEIVED) {
			throw new ApplicationException(ErrorCode.PO_INVALID_TRANSITION, Map.of("purchaseOrderId", id));
		}
		PoStatus target = fullyReceived ? PoStatus.RECEIVED : PoStatus.PARTIALLY_RECEIVED;
		jdbc.update("UPDATE purchase_orders SET status = ?, updated_at = now() WHERE id = ?", target.name(), id);
		recordEvent(id, target.name(), detail, actor);
		auditService.record(actor,
				fullyReceived ? AuditAction.PO_RECEIVED : AuditAction.PO_PARTIALLY_RECEIVED,
				AuditEntityType.PURCHASE_ORDER, id,
				Map.of("status", po.status().name()), Map.of("status", target.name()), null);
	}

	/**
	 * Records that described lines on this order turned up, and moves the order on (T-066).
	 *
	 * <p><strong>A status transition and not a stock movement</strong>, which is Rajeev's ruling 4 of
	 * the 2026-09-08 review word for word. Nothing is written to {@code goods_receipts},
	 * {@code goods_receipt_lines}, {@code stock_movements} or {@code inventory_items}: the store room
	 * does not track a plastic stool, and there is no batch, no expiry and no on-hand quantity that
	 * would mean anything about one. What is written is a date on the line saying it arrived.
	 *
	 * <p><strong>This is the action {@code KMS-400129} already promises.</strong> The refusal a
	 * storekeeper meets when they try to receive a described line tells them to record it as
	 * delivered on the order. Until this method existed there was nowhere to do that, so the line
	 * stayed outstanding, the order never left SENT, and the vendor scorecard aged it past 31 days
	 * for ever and scored it late for ever.
	 *
	 * <p><strong>Why it lives here and not in {@code ReceivingService}.</strong> Receiving is about
	 * the ledger — a receipt header, a batch, a movement, a price written back to the vendor. This
	 * touches none of them; it is a purchase order's own lifecycle, in the class that already owns
	 * every other transition on it. The dependency runs that way too: receiving depends on purchase
	 * orders, so the completion arithmetic both of them need can live here and be called from there,
	 * where the reverse would be a cycle.
	 *
	 * <p><strong>SENT and PARTIALLY_RECEIVED only</strong>, exactly as receiving is. A draft has not
	 * been sent, so nothing can have arrived against it; a cancelled order was withdrawn; and a
	 * RECEIVED order is already closed — which for an order raised before T-066 may mean it closed
	 * with its described lines noted on the trail and never acknowledged, and reopening a finished
	 * order to tidy that up would be a worse answer than leaving the trail as the record.
	 *
	 * <p>Every id must name a described line on <em>this</em> order that has not already been
	 * accounted for; anything else is RESOURCE_NOT_FOUND naming the id. The UPDATE carries all four
	 * of those conditions in its own WHERE and returns the description, so the check and the write
	 * are one statement and cannot disagree — a second storekeeper pressing the same button at the
	 * same moment loses the race rather than recording the arrival twice. A refusal part-way through
	 * rolls the whole method back; the arrival of an order is one statement, not three.
	 */
	@Transactional
	public void recordArrivals(AuthenticatedUser actor, UUID poId, List<UUID> poLineIds) {
		PurchaseOrderView po = findHeader(poId).orElseThrow(() -> notFound(poId));
		if (po.status() != PoStatus.SENT && po.status() != PoStatus.PARTIALLY_RECEIVED) {
			throw new ApplicationException(ErrorCode.PO_INVALID_TRANSITION, Map.of("purchaseOrderId", poId));
		}
		// The temple's own day, never CURRENT_DATE: an arrival recorded at 02:00 in Bengaluru is
		// dated the previous day by a server running in UTC, and this is the date the vendor
		// scorecard measures against needed_by.
		LocalDate arrivedOn = LocalDate.now(clock.zone());
		List<String> subjects = new ArrayList<>();
		for (UUID lineId : poLineIds) {
			List<String> updated = jdbc.queryForList("""
					UPDATE purchase_order_lines
					SET arrived_on = ?, arrived_recorded_at = now(), arrived_recorded_by = ?
					WHERE id = ? AND po_id = ? AND ingredient_id IS NULL AND arrived_on IS NULL
					RETURNING description
					""", String.class, arrivedOn, actor.getUserId(), lineId, poId);
			if (updated.isEmpty()) {
				throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND,
						Map.of("purchaseOrderId", poId, "poLineId", lineId));
			}
			subjects.add(updated.get(0));
		}

		// Safe to join: the WHERE above selected only lines with ingredient_id IS NULL, and the
		// exclusivity CHECK guarantees a description on exactly those. Joining a null would append
		// the four characters "null" with no warning at all.
		recordEvent(poId, "ARRIVED",
				"Arrived, and not taken into stock because the store room doesn't track them: "
						+ String.join(", ", subjects),
				actor);
		applyReceivedStatus(actor, poId, isFullyAccountedFor(poId), "Recorded as arrived");
	}

	/**
	 * True once every line on this order has been accounted for — and "accounted for" means a
	 * different thing on each of the two kinds of line, which is the whole of it.
	 *
	 * <p>A <strong>catalogue line</strong> is covered when the receipts against it add up to what was
	 * ordered. A <strong>described line</strong> is covered when somebody has recorded that it
	 * arrived, because it can never have a receipt: {@code ReceivingService} refuses one
	 * ({@code KMS-400129}) and the NOT NULL on {@code goods_receipt_lines.ingredient_id} would refuse
	 * it after that.
	 *
	 * <p><strong>This changed at T-066, deliberately, and the old rule is worth stating.</strong>
	 * T-024 left described lines out of this arithmetic entirely, so an order carrying four plastic
	 * stools reached RECEIVED on its ingredient lines alone. That was right at the time and for one
	 * stated reason: there was no way to account for a described line at all, so counting one would
	 * have pinned the order at PARTIALLY_RECEIVED for ever. T-066 removes that reason. There is now
	 * an action, it takes one press, and the storekeeper is standing at the lorry with the answer.
	 *
	 * <p>So the exception goes and the rule becomes uniform: an order is finished when every line on
	 * it is. A mixed order whose rice has been received but whose stools nobody has confirmed sits at
	 * PARTIALLY_RECEIVED — visible, in the aging bucket, with a form on its own screen naming exactly
	 * what is outstanding. That is a truthful open order rather than a silent claim that an order is
	 * complete while a line on it has never been confirmed by anybody.
	 *
	 * <p>Rejected quantity is not counted here, unchanged from T-024: a refused sack was delivered
	 * and sent back, and the line is still owed. {@code VendorPerformanceService}'s class comment
	 * relies on that when it explains why on-time is measured at the first receipt rather than at
	 * completion.
	 */
	@Transactional(readOnly = true)
	public boolean isFullyAccountedFor(UUID poId) {
		Integer outstanding = jdbc.queryForObject("""
				SELECT count(*)
				FROM purchase_order_lines l
				WHERE l.po_id = ?
				  AND CASE
						  WHEN l.ingredient_id IS NULL THEN l.arrived_on IS NULL
						  ELSE COALESCE((SELECT SUM(grl.received_qty) FROM goods_receipt_lines grl
										 WHERE grl.po_line_id = l.id), 0) < l.quantity
					  END
				""", Integer.class, poId);
		return outstanding != null && outstanding == 0;
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

	/**
	 * One order's header, with its lead-time facts already worked out — see {@link #withLeadTimes}.
	 *
	 * <p>Every caller gets them, including {@link #send}, which is what makes the gate there a
	 * question about a value somebody has already seen on their screen rather than a second sum.
	 */
	private Optional<PurchaseOrderView> findHeader(UUID id) {
		return withLeadTimes(jdbc.query(HEADER_SELECT + " WHERE po.id = ?", HEADER_MAPPER, id))
				.stream().findFirst();
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
				   po.needed_by, po.delivery_location, po.notes, po.cancel_reason,
				   po.vendor_abandoned, po.auto_cancelled, po.sent_at, po.cancelled_at, po.created_at,
				   po.lead_time_days, po.sent_after_lead_time,
				   po.closed_at, po.close_outcome, po.close_note
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
			// getBoolean and not getObject: the column is NOT NULL DEFAULT FALSE (V118), so there is
			// no third state to carry and a primitive says that plainly. Every order raised before
			// V118 reads false, which is the reading that blames nobody.
			rs.getBoolean("vendor_abandoned"),
			// Same reading, same reason (V125): NOT NULL DEFAULT FALSE, so every order raised
			// before the sweep existed reads "no machine touched this", which is the truth.
			rs.getBoolean("auto_cancelled"),
			instant(rs.getObject("sent_at", OffsetDateTime.class)),
			instant(rs.getObject("cancelled_at", OffsetDateTime.class)),
			instant(rs.getObject("created_at", OffsetDateTime.class)),
			// getObject and not getInt: the column is nullable and null MEANS something here —
			// nobody has said how long this vendor needs — where getInt would read it as 0, which
			// is "they deliver the same day". That is the one confusion V119 exists to prevent.
			(Integer) rs.getObject("lead_time_days"),
			// Left null by the mapper and filled by withLeadTimes: the order-by date and the zone
			// are not on this row, and a draft's governing lead time is not either.
			null,
			null,
			rs.getBoolean("sent_after_lead_time"),
			instant(rs.getObject("closed_at", OffsetDateTime.class)),
			// Null when nobody has closed the order, which is every order until somebody does. The
			// database refuses the other three pairings (V126's
			// purchase_orders_closure_is_a_closed_order), so a reader never has to defend against a
			// live order carrying an ending or a CLOSED one carrying none.
			rs.getString("close_outcome") == null
					? null : CloseOutcome.valueOf(rs.getString("close_outcome")),
			rs.getString("close_note"));

	private static final RowMapper<PurchaseOrderLineView> LINE_MAPPER = (rs, n) -> new PurchaseOrderLineView(
			rs.getObject("id", UUID.class),
			rs.getObject("ingredient_id", UUID.class),
			rs.getString("ingredient_name"),
			rs.getString("description"),
			rs.getBigDecimal("quantity"),
			rs.getString("unit"),
			(BigDecimal) rs.getObject("expected_price"),
			rs.getObject("arrived_on", LocalDate.class));

	private static final RowMapper<PoEventView> EVENT_MAPPER = (rs, n) -> new PoEventView(
			rs.getString("event_type"),
			rs.getString("detail"),
			rs.getString("actor_name"),
			instant(rs.getObject("created_at", OffsetDateTime.class)));

	private static java.time.Instant instant(OffsetDateTime odt) {
		return odt == null ? null : odt.toInstant();
	}
}
