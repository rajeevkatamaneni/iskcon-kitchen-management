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
	private final ShoppingListService shoppingListService;
	/**
	 * Only ever asked one question: has this temple's WhatsApp ever actually sent anything (T-136)?
	 *
	 * <p>Injected rather than reading {@code tenant_settings} with SQL of our own, because the
	 * column and the meaning of its NULL belong to the notification side. Two packages holding two
	 * copies of "and NULL means never" is how the second copy comes to be wrong.
	 */
	private final TenantWhatsAppSettingsService whatsappSettings;

	public PurchaseOrderService(JdbcTemplate jdbc, AuditService auditService,
			org.iskcon.kms.document.DocumentService documentService,
			IngredientUnits ingredientUnits, ShoppingListService shoppingListService,
			TenantWhatsAppSettingsService whatsappSettings,
			TempleClock clock) {
		this.clock = clock;
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
				header, lines, events, whatsappSettings.hasEverSentSuccessfully());
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
		if (po.status() == PoStatus.RECEIVED || po.status() == PoStatus.CANCELLED) {
			throw new ApplicationException(ErrorCode.PO_INVALID_TRANSITION, Map.of("purchaseOrderId", id));
		}
		// Nothing was asked of the vendor, so nothing can be held against them (T-129). Read off
		// sent_at rather than off the status, because a cancelled order's status no longer says
		// whether it was ever sent — that is precisely the case this refuses.
		if (vendorAbandoned && po.sentAt() == null) {
			throw new ApplicationException(ErrorCode.PO_NEVER_SENT_TO_VENDOR, Map.of("purchaseOrderId", id));
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
				   po.needed_by, po.delivery_location, po.notes, po.cancel_reason,
				   po.vendor_abandoned, po.sent_at, po.cancelled_at, po.created_at
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
