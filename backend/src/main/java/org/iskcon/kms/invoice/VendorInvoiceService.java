package org.iskcon.kms.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.iskcon.kms.attachment.AttachmentService;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.error.ErrorResponse;
import org.iskcon.kms.ingredient.IngredientUnits;
import org.iskcon.kms.ingredient.Quantities;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.inventory.InventoryUnits;
import org.iskcon.kms.tenancy.TempleClock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Capturing vendor invoices for the payment queue (E5-S8), itemised since stage 6 (R-INV-1..7, T-271).
 *
 * <p><strong>What an invoice is now.</strong> A bill has item lines, the deliveries it bills, the
 * bill's own totals and a required copy of the bill. It bills one or more of the vendor's deliveries
 * — whose delivered lines become its lines, one each, locked — or none, and is then a direct invoice
 * whose lines are typed by hand. Its {@code amount} is the bill's grand total, so every figure that
 * already sums {@code vendor_invoices.amount} (what is owed, the pay status, the giving page's cost per
 * plate) goes on meaning exactly what it meant: what the temple has been billed. Capture only ever
 * sets PENDING; the flip to PAID is payment execution (E7-S9).
 *
 * <p>Saving a line also moves prices ({@link InvoicePriceStep}): the vendor's list price, its history
 * and the ingredient's market rate come from the bills the temple actually pays (R-VEN-4, R-ING-3).
 *
 * <p>Where the order's lines carry prices, the value of what was delivered is shown against what was
 * billed as an informational variance — surfaced, never enforced ({@link #withVariance}).
 *
 * <p>A captured bill can afterwards be withdrawn or reduced (T-010), and the two are different acts:
 * {@link #voidInvoice} says the bill was never owed and takes it out of the pay cycle for good — and
 * releases the deliveries it billed, so they can be billed correctly — while {@link #creditInvoice}
 * says it was owed and is now owed less and leaves it in. Nothing is deleted by either.
 *
 * <p>This service also owns {@link #restateStatus}, the one place in the application that decides
 * whether an invoice is PAID. It lives here rather than beside the payments that trigger it because
 * the status is a fact about the invoice row, and two places computing it would eventually disagree.
 */
@Service
public class VendorInvoiceService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final AttachmentService attachments;
	private final InvoicePriceStep priceStep;
	private final TempleClock clock;

	public VendorInvoiceService(JdbcTemplate jdbc, AuditService auditService, AttachmentService attachments,
			InvoicePriceStep priceStep, TempleClock clock) {
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.attachments = attachments;
		this.priceStep = priceStep;
		this.clock = clock;
	}

	/**
	 * Saves an itemised invoice: its row, its lines, its links to the deliveries it bills, its totals,
	 * the claim on the uploaded bill, and the price step for each line — one transaction, so a refusal
	 * anywhere leaves nothing behind, the upload included (it stays unclaimed and can be used again).
	 *
	 * <p>Every refusal that can be decided from the request is decided before anything is written, in
	 * the order a person would want to hear them: the deliveries (KMS-400169), then the lines against
	 * them (KMS-400170) or the direct lines' own shape, then the arithmetic (KMS-400168). The one check
	 * that cannot be decided in advance — another invoice for the same delivery being saved at this
	 * moment — is the database's ({@link #linkDeliveries}).
	 */
	@Transactional
	public RecordInvoiceResponse record(AuthenticatedUser actor, RecordInvoiceRequest request) {
		requireVendor(request.vendorId());
		List<UUID> receiptIds = request.receiptIds() == null ? List.of()
				: request.receiptIds().stream().filter(Objects::nonNull).distinct().toList();
		boolean direct = receiptIds.isEmpty();

		List<BillableReceipt> receipts = direct ? List.of() : billableReceipts(request.vendorId(), receiptIds);
		List<ResolvedLine> lines = direct
				? resolveDirectLines(request.lines())
				: resolveDeliveredLines(request.lines(), deliveredLines(receiptIds));

		// The sub total is the server's: the sum of the lines as they will be stored (two places, the
		// column's scale), never a figure the client sent. Then the bill's own check (R-INV-5), exact:
		// a bill that is out by a paisa is a bill somebody mis-keyed.
		BigDecimal subTotal = lines.stream().map(ResolvedLine::amount)
				.reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
		BigDecimal addsUpTo = subTotal.add(request.gstAmount()).add(request.otherCharges())
				.subtract(request.discount());
		if (addsUpTo.compareTo(request.grandTotal()) != 0) {
			throw new ApplicationException(ErrorCode.INVOICE_TOTALS_DONT_ADD_UP,
					Map.of("subTotal", subTotal.toPlainString(), "addsUpTo", addsUpTo.toPlainString(),
							"grandTotal", request.grandTotal().toPlainString()));
		}

		// The order is the server's to name: set when every billed delivery is on one order, so the
		// list and the payables still say "against PO-2026-0044"; null when the bill spans orders.
		List<UUID> orders = receipts.stream().map(BillableReceipt::purchaseOrderId).distinct().toList();
		UUID poId = orders.size() == 1 ? orders.get(0) : null;

		boolean duplicate = countByVendorAndNumber(request.vendorId(), request.invoiceNumber()) > 0;

		UUID id = UUID.randomUUID();
		jdbc.update(connection -> {
			var ps = connection.prepareStatement("""
					INSERT INTO vendor_invoices (
						id, tenant_id, vendor_id, po_id, direct, description, invoice_number, invoice_date,
						amount, due_date, created_by, sub_total, gst_amount, other_charges,
						other_charges_note, discount, grand_total)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""");
			ps.setObject(1, id);
			ps.setObject(2, request.vendorId());
			ps.setObject(3, poId);
			ps.setBoolean(4, direct);
			ps.setString(5, trimToNull(request.description()));
			ps.setString(6, request.invoiceNumber().trim());
			ps.setObject(7, request.invoiceDate());
			// amount IS the grand total. Everything that reads amount — owed, PAID, credits, the giving
			// page — reads the bill's bottom line, exactly as it did when amount was typed by hand.
			ps.setBigDecimal(8, request.grandTotal());
			ps.setObject(9, request.dueDate());
			ps.setObject(10, actor.getUserId());
			ps.setBigDecimal(11, subTotal);
			ps.setBigDecimal(12, request.gstAmount());
			ps.setBigDecimal(13, request.otherCharges());
			ps.setString(14, trimToNull(request.otherChargesNote()));
			ps.setBigDecimal(15, request.discount());
			ps.setBigDecimal(16, request.grandTotal());
			return ps;
		});

		linkDeliveries(id, receiptIds);
		List<UUID> lineIds = insertLines(id, lines);

		// After the invoice row exists and in this transaction, as T-267's claim requires: a refusal
		// (KMS-400167 — already used, another kind, another temple's) rolls the whole save back.
		attachments.claimBill(request.billAttachmentId(), id);

		int priced = 0;
		for (int i = 0; i < lines.size(); i++) {
			ResolvedLine line = lines.get(i);
			BigDecimal set = priceStep.apply(actor, request.vendorId(), request.invoiceDate(),
					new InvoicePriceStep.SavedLine(lineIds.get(i), line.ingredientId(), line.billedQty(),
							line.unit(), line.packSizeId(), line.packCount(), line.amount()));
			if (set != null) {
				priced++;
			}
		}

		Map<String, Object> after = new LinkedHashMap<>();
		after.put("invoiceNumber", request.invoiceNumber().trim());
		after.put("amount", request.grandTotal().toPlainString());
		after.put("subTotal", subTotal.toPlainString());
		after.put("direct", direct);
		after.put("lines", lines.size());
		after.put("deliveries", receiptIds.size());
		after.put("pricesSet", priced);
		auditService.record(actor, AuditAction.INVOICE_RECORDED, AuditEntityType.VENDOR_INVOICE, id,
				null, after, null);

		return new RecordInvoiceResponse(row(id), duplicate);
	}

	// ---- The deliveries an invoice bills (R-INV-3) ------------------------------------------

	/** A delivery about to be billed: the receipt and the order it was received against. */
	private record BillableReceipt(UUID receiptId, UUID purchaseOrderId) {
	}

	/**
	 * The requested deliveries, each checked to be billable: this vendor's, and billed by no standing
	 * invoice. Any that is not — absent, another temple's (invisible under RLS, so absent), another
	 * vendor's, already on a standing bill, or with nothing kept to bill — refuses the whole request
	 * with KMS-400169, naming none of them to the person: the screen only ever offers billable ones,
	 * so reaching this means the page is stale, and "reload and choose again" is the whole answer.
	 *
	 * <p>"Standing" is "not released": a void releases its links ({@link #voidInvoice}), so a delivery
	 * on a struck bill is billable again (conductor's ruling). This check is the friendly early answer;
	 * the guarantee is the unique index {@code invoice_deliveries_billed_once} (V148), which
	 * {@link #linkDeliveries} relies on.
	 */
	private List<BillableReceipt> billableReceipts(UUID vendorId, List<UUID> receiptIds) {
		List<BillableReceipt> found = jdbc.query("""
				SELECT r.id, r.po_id
				FROM goods_receipts r
				JOIN purchase_orders po ON po.id = r.po_id
				WHERE r.id IN (%s)
				  AND po.vendor_id = ?
				  AND NOT EXISTS (SELECT 1 FROM vendor_invoice_deliveries d
								  WHERE d.receipt_id = r.id AND d.released_at IS NULL)
				  AND EXISTS (SELECT 1 FROM goods_receipt_lines gl
							  WHERE gl.receipt_id = r.id AND gl.received_qty > 0)
				""".formatted(placeholders(receiptIds.size())),
				(rs, n) -> new BillableReceipt(rs.getObject("id", UUID.class), rs.getObject("po_id", UUID.class)),
				args(receiptIds, vendorId));
		if (found.size() != receiptIds.size()) {
			Set<UUID> ok = new HashSet<>();
			found.forEach(r -> ok.add(r.receiptId()));
			throw new ApplicationException(ErrorCode.INVOICE_DELIVERY_NOT_BILLABLE,
					Map.of("vendorId", vendorId,
							"refused", receiptIds.stream().filter(r -> !ok.contains(r)).map(UUID::toString).toList()));
		}
		return found;
	}

	/**
	 * Links the invoice to its deliveries, and is where two bills for one delivery become impossible.
	 *
	 * <p>{@link #billableReceipts} read the links a moment ago, but two people saving bills for the
	 * same delivery at the same moment both read "not billed" and both reach here. V148's partial
	 * unique index — one row per delivery among links not yet released — makes the second insert wait
	 * for the first transaction, then fail when it commits. That failure is translated to the same
	 * KMS-400169 the early check gives, and the whole save rolls back, upload claim and prices
	 * included. Under READ COMMITTED no read-then-write in this method could promise that; the index
	 * can, because it is checked at the moment of writing, across transactions. ({@code InvoiceLinesIT}
	 * holds one link uncommitted on a second connection and proves the request is refused.)
	 */
	private void linkDeliveries(UUID invoiceId, List<UUID> receiptIds) {
		for (UUID receiptId : receiptIds) {
			try {
				jdbc.update("""
						INSERT INTO vendor_invoice_deliveries (tenant_id, invoice_id, receipt_id)
						VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?)
						""", invoiceId, receiptId);
			} catch (DuplicateKeyException e) {
				throw new ApplicationException(ErrorCode.INVOICE_DELIVERY_NOT_BILLABLE,
						Map.of("receiptId", receiptId, "reason", "billed by another invoice saved at the same time"));
			}
		}
	}

	/** A delivered line of a delivery being billed: what the bill's line must match. */
	/** An ingredient's own two facts, as a bill line needs them: what to call it, and what it is in. */
	private record Ingredient(String name, Unit canonicalUnit) {
	}

	private record DeliveredLine(UUID goodsReceiptLineId, UUID ingredientId, Unit unit, int order) {
	}

	/**
	 * Every line the requested deliveries kept (received more than nothing), in delivery order and,
	 * within a delivery, the order's own line order — the order the bill's lines are stored and read in. A line whose goods were all rejected at the gate has nothing to bill and is not one.
	 */
	private Map<UUID, DeliveredLine> deliveredLines(List<UUID> receiptIds) {
		Map<UUID, DeliveredLine> out = new LinkedHashMap<>();
		jdbc.query("""
				SELECT gl.id, gl.ingredient_id, gl.unit
				FROM goods_receipt_lines gl
				JOIN goods_receipts r ON r.id = gl.receipt_id
				JOIN purchase_order_lines pol ON pol.id = gl.po_line_id
				WHERE gl.receipt_id IN (%s) AND gl.received_qty > 0
				ORDER BY r.received_at, r.id, pol.line_order, gl.id
				""".formatted(placeholders(receiptIds.size())), rs -> {
			UUID id = rs.getObject("id", UUID.class);
			out.put(id, new DeliveredLine(id, rs.getObject("ingredient_id", UUID.class),
					Unit.valueOf(rs.getString("unit")), out.size()));
		}, receiptIds.toArray());
		return out;
	}

	// ---- The lines (R-INV-4) ----------------------------------------------------------------

	/** A line checked and ready to store. {@code unit} is parsed; the pack, when there is one, agrees. */
	private record ResolvedLine(UUID goodsReceiptLineId, UUID ingredientId, String description,
			BigDecimal billedQty, Unit unit, UUID packSizeId, BigDecimal packCount, BigDecimal amount) {
	}

	/**
	 * A bill for deliveries carries exactly their lines (R-INV-3): one bill line per delivered line,
	 * none added and none removed, each naming the delivered line it bills. The person types the billed
	 * quantity and the amount; everything else is the delivery's. Anything else is KMS-400170, decided
	 * before anything is written. "Anything else on the bill goes in Other charges", as its next step
	 * says.
	 *
	 * <p>The ingredient comes from the delivery. A line that names a different one, or describes a
	 * one-off, is not the delivered line, and is refused the same way. Billed more than delivered is
	 * allowed — the screen warns (R-INV-4) — and so is less, down to 0 for an item not billed.
	 */
	private List<ResolvedLine> resolveDeliveredLines(List<InvoiceLineInput> input, Map<UUID, DeliveredLine> delivered) {
		Set<UUID> seen = new HashSet<>();
		List<ResolvedLine> out = new ArrayList<>(input.size());
		// Every fractional count on the bill in one refusal — a bill is a page of lines, and telling
		// somebody keying it about one of them at a time is telling them to key it again.
		IngredientUnits.Whole whole = IngredientUnits.wholeNumbers();
		for (int i = 0; i < input.size(); i++) {
			InvoiceLineInput line = input.get(i);
			DeliveredLine d = line.goodsReceiptLineId() == null ? null : delivered.get(line.goodsReceiptLineId());
			if (d == null || !seen.add(d.goodsReceiptLineId())
					|| (line.ingredientId() != null && !line.ingredientId().equals(d.ingredientId()))
					|| trimToNull(line.description()) != null) {
				throw linesDontMatch(i);
			}
			out.add(resolve(line, i, d.goodsReceiptLineId(), d.ingredientId(), null, whole));
		}
		if (seen.size() != delivered.size()) {
			throw new ApplicationException(ErrorCode.INVOICE_LINES_DONT_MATCH_DELIVERIES,
					Map.of("expectedLines", delivered.size(), "sentLines", seen.size()));
		}
		// Stored in the delivery's own order, whatever order the lines came in, so the bill reads as
		// the delivery did.
		whole.refuseAnyPart();
		out.sort(Comparator.comparingInt(l -> delivered.get(l.goodsReceiptLineId()).order()));
		return out;
	}

	/**
	 * A direct invoice's lines, typed by hand (R-INV-3): each an ingredient or a one-off described
	 * item, never both and never neither — the same exclusive either/or V144 puts on the table and
	 * V100 on an order line. None may name a delivered line: a bill that bills a delivery bills it
	 * through {@code receiptIds}, where the one-standing-bill rule can hold it.
	 */
	private List<ResolvedLine> resolveDirectLines(List<InvoiceLineInput> input) {
		List<ResolvedLine> out = new ArrayList<>(input.size());
		IngredientUnits.Whole whole = IngredientUnits.wholeNumbers();
		for (int i = 0; i < input.size(); i++) {
			InvoiceLineInput line = input.get(i);
			if (line.goodsReceiptLineId() != null) {
				throw linesDontMatch(i);
			}
			String description = trimToNull(line.description());
			if ((line.ingredientId() == null) == (description == null)) {
				throw fieldError("lines[" + i + "].ingredientId",
						"Choose an ingredient, or describe the item instead.");
			}
			out.add(resolve(line, i, null, line.ingredientId(), description, whole));
		}
		whole.refuseAnyPart();
		return out;
	}

	/**
	 * The checks every line shares: a real unit in the ingredient's own family, and a pack that is the
	 * ingredient's and agrees with the quantity.
	 *
	 * <p><strong>The pack must say what the quantity says</strong> (R-INV-4, "a price never floats untied
	 * to a unit"). 4 × Bag (25 Kg) is 100 Kg, and the line must send 100 KG — or 100000 GM, the same
	 * amount — as its billed quantity. The comparison is in the family's base unit through
	 * {@link InventoryUnits#toBase}, the one conversion the application has; no table of factors is
	 * written here. Unlike an order line, where the pack silently decides the amount (T-260), a bill
	 * line that disagrees with itself is refused: a bill is a record of what a vendor charged for, and
	 * quietly storing a different quantity from the one typed would put words in the vendor's mouth.
	 */
	private ResolvedLine resolve(InvoiceLineInput line, int i, UUID receiptLineId, UUID ingredientId,
			String description, IngredientUnits.Whole whole) {
		Unit unit = parseUnit(line.unit(), i);
		String billedThing = description;
		if (ingredientId != null) {
			List<Ingredient> canonical = jdbc.query(
					"SELECT name, canonical_unit FROM ingredients WHERE id = ?",
					(rs, n) -> new Ingredient(rs.getString("name"), Unit.valueOf(rs.getString("canonical_unit"))),
					ingredientId);
			if (canonical.isEmpty()) {
				throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("ingredientId", ingredientId));
			}
			if (canonical.get(0).canonicalUnit().family() != unit.family()) {
				throw new ApplicationException(ErrorCode.INCOMPATIBLE_UNIT,
						Map.of("line", i, "ingredientId", ingredientId, "unit", unit.name()));
			}
			billedThing = canonical.get(0).name();
		}

		// A counted thing cannot be a fraction (T-423), on the billed quantity as on every other
		// figure anybody enters. A one-off described item is held to it too — "Plastic stool", 4
		// PIECES — because a bill for 2.5 stools is as unreadable as an order for them.
		//
		// <strong>This is the door with the sharpest cost, and it is worth stating plainly.</strong>
		// The rate a vendor charged is derived, Amount / Billed qty, which is exactly why a fraction
		// here is the one that does quiet damage: it produces a per-unit price for a fraction of a
		// thing and writes it into the price history every later order reads. Against that: a bill
		// records what somebody else asserted, and this file argues elsewhere that storing a
		// different quantity from the one typed "would put words in the vendor's mouth". The reason
		// it is still checked is that no delivery can be fractional from today, so the only bills
		// this can refuse are those against the receipts written before the rule — and R-INV-4
		// already allows a bill for more than was delivered, which is the way out of those.
		whole.check(billedThing, line.billedQty(), unit);

		if ((line.packSizeId() == null) != (line.packCount() == null)) {
			throw fieldError("lines[" + i + "]." + (line.packSizeId() == null ? "packSizeId" : "packCount"),
					line.packSizeId() == null ? "Choose the pack the bill is in." : "Enter how many packs were billed.");
		}
		if (line.packSizeId() != null) {
			// Another ingredient's pack, another temple's (invisible under RLS), or a pack on a one-off
			// item: all the same refusal, KMS-400162, as on the vendor page and an order line.
			List<BigDecimal> packBase = ingredientId == null ? List.of() : jdbc.queryForList(
					"SELECT base_quantity FROM ingredient_pack_sizes WHERE id = ? AND ingredient_id = ?",
					BigDecimal.class, line.packSizeId(), ingredientId);
			if (packBase.isEmpty()) {
				throw new ApplicationException(ErrorCode.SUPPLY_PACK_NOT_THIS_INGREDIENT,
						Map.of("packSizeId", line.packSizeId(), "line", i));
			}
			BigDecimal packsInBase = line.packCount().multiply(packBase.get(0));
			if (packsInBase.compareTo(InventoryUnits.toBase(line.billedQty(), unit)) != 0) {
				throw fieldError("lines[" + i + "].billedQty",
						"The quantity billed doesn't match the number of packs.");
			}
		}
		return new ResolvedLine(receiptLineId, ingredientId, description, line.billedQty(), unit,
				line.packSizeId(), line.packCount(), line.amount().setScale(2, RoundingMode.HALF_UP));
	}

	private List<UUID> insertLines(UUID invoiceId, List<ResolvedLine> lines) {
		List<UUID> ids = new ArrayList<>(lines.size());
		for (int i = 0; i < lines.size(); i++) {
			ResolvedLine l = lines.get(i);
			ids.add(jdbc.queryForObject("""
					INSERT INTO vendor_invoice_lines (
						tenant_id, invoice_id, goods_receipt_line_id, ingredient_id, description, billed_qty,
						unit, pack_size_id, pack_count, line_amount, line_order)
					VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					RETURNING id
					""", UUID.class, invoiceId, l.goodsReceiptLineId(), l.ingredientId(), l.description(),
					l.billedQty(), l.unit().name(), l.packSizeId(), l.packCount(), l.amount(), i));
		}
		return ids;
	}

	private static Unit parseUnit(String raw, int i) {
		try {
			return Unit.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw fieldError("lines[" + i + "].unit", "Choose the unit the quantity is in.");
		}
	}

	private static ApplicationException linesDontMatch(int line) {
		return new ApplicationException(ErrorCode.INVOICE_LINES_DONT_MATCH_DELIVERIES, Map.of("line", line));
	}

	/** A field error in the same shape Bean Validation's would have, for a rule an annotation cannot state. */
	private static ApplicationException fieldError(String field, String message) {
		return new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", field),
				List.of(new ErrorResponse.FieldError(field, message)), null);
	}

	// ---- Reading ----------------------------------------------------------------------------

	/**
	 * "Money is still owed on this bill", as a WHERE condition over {@code vendor_invoices vi}.
	 *
	 * <p>THE TWIN of this condition lives in {@code InvoicePaymentService.payables()}, which answers
	 * the same question in Java (PENDING, then amount - credited_amount - SUM(payments) &gt; 0) for the
	 * payables view and the Invoices list's total owed. The two must say the same thing: the Unpaid
	 * filter is built on this one, the total owed beside it on that one, and a payer reading "Unpaid"
	 * next to "Total owed" expects the rows to add up to the figure (T-281). If either changes, change
	 * both; {@code InvoiceListOwedIT} compares their answers invoice for invoice and will fail if they
	 * drift.
	 *
	 * <p>Why the payments are summed and PENDING is not trusted alone: status is restated only when a
	 * payment or credit goes through the service, and an invoice paid off before a rule changed (or
	 * written by an import) can stand PENDING with nothing left to pay. Showing that under Unpaid is
	 * showing a bill the temple does not owe. Every payment is summed, reversals included, because a
	 * reversal is a negative entry in an append-only ledger (V40), not a mark on the payment it undoes.
	 */
	private static final String STILL_OWED = """
			 AND vi.status = 'PENDING'
			 AND vi.amount - vi.credited_amount
				 - COALESCE((SELECT SUM(p.amount) FROM invoice_payments p WHERE p.invoice_id = vi.id), 0) > 0\
			""";

	/** The list without the owed filter, for callers that predate it (the Today page's overdue bills). */
	@Transactional(readOnly = true)
	public List<VendorInvoiceView> list(InvoiceStatus status, boolean overdueOnly) {
		return list(status, overdueOnly, false);
	}

	/**
	 * The Invoices list. {@code owedOnly} is the Unpaid filter: the bills money is still owed on, the
	 * same set {@code /api/v1/payables} returns, so it means the same thing to a Kitchen Manager (who
	 * cannot read payables) as to the Temple Admin (who can). Before T-281 a non-payer's Unpaid was
	 * {@code status=PENDING}, which also caught a PENDING bill with nothing left to pay.
	 *
	 * <p>{@code overdueOnly} is past its due date AND still owed. A bill with nothing left to pay is not
	 * late, whatever its status column says, so the same condition is applied rather than PENDING alone.
	 * That also narrows the Today page's overdue bills, which go through here, to ones actually owed.
	 */
	@Transactional(readOnly = true)
	public List<VendorInvoiceView> list(InvoiceStatus status, boolean overdueOnly, boolean owedOnly) {
		StringBuilder sql = new StringBuilder(SELECT + " WHERE 1 = 1");
		List<Object> args = new ArrayList<>();
		if (status != null) {
			sql.append(" AND vi.status = ?");
			args.add(status.name());
		}
		if (owedOnly || overdueOnly) {
			sql.append(STILL_OWED);
		}
		if (overdueOnly) {
			sql.append(" AND vi.due_date IS NOT NULL AND vi.due_date < CURRENT_DATE");
		}
		sql.append(" ORDER BY vi.due_date NULLS LAST, vi.invoice_date DESC");
		List<VendorInvoiceView> rows = jdbc.query(sql.toString(), mapper(), args.toArray());
		return withVariance(rows);
	}

	/** One invoice as the list shows it: the row, with its variance. */
	VendorInvoiceView row(UUID id) {
		List<VendorInvoiceView> rows = jdbc.query(SELECT + " WHERE vi.id = ?", mapper(), id);
		if (rows.isEmpty()) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("invoiceId", id));
		}
		return withVariance(rows).get(0);
	}

	/**
	 * One invoice's page (R-INV-7): the row, its items, the deliveries it bills, the bill's totals and
	 * the copy of the bill. An invoice recorded before stage 6 comes back with empty lists and null
	 * totals and bill, and its variance computed exactly as it always was.
	 */
	@Transactional(readOnly = true)
	public VendorInvoiceDetailView get(UUID id) {
		VendorInvoiceView view = row(id);
		VendorInvoiceDetailView.Totals totals = jdbc.queryForObject("""
				SELECT sub_total, gst_amount, other_charges, other_charges_note, discount, grand_total
				FROM vendor_invoices WHERE id = ?
				""", (rs, n) -> new VendorInvoiceDetailView.Totals(
						rs.getBigDecimal("sub_total"), rs.getBigDecimal("gst_amount"),
						rs.getBigDecimal("other_charges"), rs.getString("other_charges_note"),
						rs.getBigDecimal("discount"), rs.getBigDecimal("grand_total")), id);
		return VendorInvoiceDetailView.of(view, lines(id), deliveries(id), totals,
				attachments.billOf(id).orElse(null));
	}

	/**
	 * The invoice's items, in the order they were entered. On a delivered line the ordered and
	 * delivered quantities are restated in the line's own unit, so the row's three quantities compare
	 * as they stand; the conversion is {@link InventoryUnits}, the application's only one.
	 */
	private List<InvoiceLineView> lines(UUID invoiceId) {
		return jdbc.query("""
				SELECT l.id, l.goods_receipt_line_id, l.ingredient_id, COALESCE(i.name, l.description) AS item_name,
					   l.billed_qty, l.unit, l.pack_size_id, l.pack_count, l.line_amount, l.rate,
					   gl.received_qty, gl.unit AS delivered_unit, pol.quantity AS ordered_qty,
					   pol.unit AS ordered_unit,
					   ps.name AS pack_name, ps.quantity AS pack_quantity, ps.unit AS pack_unit
				FROM vendor_invoice_lines l
				LEFT JOIN ingredients i ON i.id = l.ingredient_id
				LEFT JOIN goods_receipt_lines gl ON gl.id = l.goods_receipt_line_id
				LEFT JOIN purchase_order_lines pol ON pol.id = gl.po_line_id
				LEFT JOIN ingredient_pack_sizes ps ON ps.id = l.pack_size_id
				WHERE l.invoice_id = ?
				ORDER BY l.line_order, l.id
				""", (rs, n) -> {
			Unit unit = Unit.valueOf(rs.getString("unit"));
			BigDecimal packCount = rs.getBigDecimal("pack_count");
			String packUnit = rs.getString("pack_unit");
			BigDecimal amount = rs.getBigDecimal("line_amount");
			return new InvoiceLineView(
					rs.getObject("id", UUID.class),
					rs.getObject("goods_receipt_line_id", UUID.class),
					rs.getObject("ingredient_id", UUID.class),
					rs.getString("item_name"),
					restate(rs.getBigDecimal("ordered_qty"), rs.getString("ordered_unit"), unit),
					restate(rs.getBigDecimal("received_qty"), rs.getString("delivered_unit"), unit),
					plain(rs.getBigDecimal("billed_qty")),
					unit.name(),
					rs.getObject("pack_size_id", UUID.class),
					packUnit == null ? null
							: packLabel(rs.getString("pack_name"), rs.getBigDecimal("pack_quantity"), Unit.valueOf(packUnit)),
					packUnit == null ? null : plain(rs.getBigDecimal("pack_quantity")),
					packCount == null ? null : plain(packCount),
					amount,
					rs.getBigDecimal("rate"),
					packCount == null ? null : amount.divide(packCount, 2, RoundingMode.HALF_UP));
		}, invoiceId);
	}

	/** The deliveries an invoice bills, oldest first, whether the invoice stands or was voided. */
	private List<InvoiceDeliveryView> deliveries(UUID invoiceId) {
		ZoneId zone = clock.zone();
		return jdbc.query("""
				SELECT r.id, r.po_id, po.po_number, r.received_at, u.full_name AS received_by_name
				FROM vendor_invoice_deliveries d
				JOIN goods_receipts r ON r.id = d.receipt_id
				JOIN purchase_orders po ON po.id = r.po_id
				LEFT JOIN users u ON u.id = r.received_by
				WHERE d.invoice_id = ?
				ORDER BY r.received_at, r.id
				""", (rs, n) -> new InvoiceDeliveryView(
						rs.getObject("id", UUID.class),
						rs.getObject("po_id", UUID.class),
						rs.getString("po_number"),
						rs.getObject("received_at", OffsetDateTime.class).atZoneSameInstant(zone).toLocalDate(),
						rs.getString("received_by_name")), invoiceId);
	}

	/**
	 * A vendor's deliveries that no standing invoice bills yet (R-INV-3), each with the lines it kept,
	 * in order number order and, within an order, oldest first (T-290). They used to come newest
	 * first, which put one order's parts apart in the list (VERIFY-D saw 0051, 0052, 0050, 0051,
	 * 0053); a person matching a bill to its deliveries looks for the order number, so that is the
	 * sort, and an order's deliveries follow one another in the order they came. The form numbers two
	 * otherwise identical deliveries on one day in this order ("2nd that day"), so the time, not just
	 * the day, is the second key. {@code r.id} only makes the order total. The same rule {@link #billableReceipts} enforces on save, read the same way: not
	 * linked to an invoice that stands (a void releases the link), and something kept to bill. Another
	 * vendor's, and another temple's, never appear.
	 */
	@Transactional(readOnly = true)
	public List<BillableDeliveryView> billableDeliveries(UUID vendorId) {
		ZoneId zone = clock.zone();
		record Header(UUID receiptId, UUID poId, String poNumber, LocalDate receivedOn, String receivedBy) {
		}
		List<Header> headers = jdbc.query("""
				SELECT r.id, r.po_id, po.po_number, r.received_at, u.full_name AS received_by_name
				FROM goods_receipts r
				JOIN purchase_orders po ON po.id = r.po_id
				LEFT JOIN users u ON u.id = r.received_by
				WHERE po.vendor_id = ?
				  AND NOT EXISTS (SELECT 1 FROM vendor_invoice_deliveries d
								  WHERE d.receipt_id = r.id AND d.released_at IS NULL)
				  AND EXISTS (SELECT 1 FROM goods_receipt_lines gl
							  WHERE gl.receipt_id = r.id AND gl.received_qty > 0)
				ORDER BY po.po_number, r.received_at, r.id
				""", (rs, n) -> new Header(
						rs.getObject("id", UUID.class),
						rs.getObject("po_id", UUID.class),
						rs.getString("po_number"),
						rs.getObject("received_at", OffsetDateTime.class).atZoneSameInstant(zone).toLocalDate(),
						rs.getString("received_by_name")), vendorId);
		if (headers.isEmpty()) {
			return List.of();
		}
		Map<UUID, List<BillableDeliveryLineView>> lines = new LinkedHashMap<>();
		jdbc.query("""
				SELECT gl.receipt_id, gl.id, gl.ingredient_id, i.name AS item_name, gl.received_qty, gl.unit,
					   pol.quantity AS ordered_qty, pol.unit AS ordered_unit, pol.pack_size_id,
					   ps.name AS pack_name, ps.quantity AS pack_quantity, ps.unit AS pack_unit
				FROM goods_receipt_lines gl
				JOIN ingredients i ON i.id = gl.ingredient_id
				JOIN purchase_order_lines pol ON pol.id = gl.po_line_id
				LEFT JOIN ingredient_pack_sizes ps ON ps.id = pol.pack_size_id
				WHERE gl.receipt_id IN (%s) AND gl.received_qty > 0
				ORDER BY gl.receipt_id, pol.line_order, gl.id
				""".formatted(placeholders(headers.size())), rs -> {
			Unit unit = Unit.valueOf(rs.getString("unit"));
			String packUnit = rs.getString("pack_unit");
			lines.computeIfAbsent(rs.getObject("receipt_id", UUID.class), k -> new ArrayList<>())
					.add(new BillableDeliveryLineView(
							rs.getObject("id", UUID.class),
							rs.getObject("ingredient_id", UUID.class),
							rs.getString("item_name"),
							restate(rs.getBigDecimal("ordered_qty"), rs.getString("ordered_unit"), unit),
							plain(rs.getBigDecimal("received_qty")),
							unit.name(),
							rs.getObject("pack_size_id", UUID.class),
							packUnit == null ? null
									: packLabel(rs.getString("pack_name"), rs.getBigDecimal("pack_quantity"), Unit.valueOf(packUnit)),
							packUnit == null ? null : plain(rs.getBigDecimal("pack_quantity"))));
		}, headers.stream().map(Header::receiptId).toArray());
		return headers.stream().map(h -> new BillableDeliveryView(h.receiptId(), h.poId(), h.poNumber(),
				h.receivedOn(), h.receivedBy(), List.copyOf(lines.getOrDefault(h.receiptId(), List.of()))))
				.toList();
	}

	/** "?, ?, ?" for an IN list of {@code n} values; every value is still a bound parameter. */
	private static String placeholders(int n) {
		return String.join(", ", java.util.Collections.nCopies(n, "?"));
	}

	private static Object[] args(List<UUID> ids, Object... more) {
		List<Object> out = new ArrayList<>(ids);
		out.addAll(List.of(more));
		return out.toArray();
	}

	/** A quantity in {@code from}, restated in {@code to} (same family); null stays null. */
	private static BigDecimal restate(BigDecimal quantity, String from, Unit to) {
		if (quantity == null) {
			return null;
		}
		Unit source = Unit.valueOf(from);
		return source == to ? plain(quantity) : InventoryUnits.fromBase(InventoryUnits.toBase(quantity, source), to);
	}

	/**
	 * A pack as an order and a bill word it: "Bag (25 Kg)", or the plain size, "500 gm", for a pack
	 * with no name — the form {@code PurchaseOrderService.packLabel} and {@code DeliveriesService}
	 * give, since the bill is in the unit the order used (R-INV-4). Restated here because theirs are
	 * package-private to other packages; the size goes through {@link Quantities#exact}, so no unit word
	 * is written by hand ({@code UnitLabelAgreementTest}).
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


	/**
	 * Strikes a bill that should never have been recorded, with the reason kept on the row.
	 *
	 * <p>A mark and not a compensating entry, which is the opposite of how a payment is undone two
	 * classes over — and the difference is the table, not an inconsistency. {@code vendor_invoices}
	 * is read one row at a time by somebody asking what was owed on this bill, so a struck bill has
	 * to say so on its face; {@code invoice_payments} is read as a sum and is append-only, so it
	 * corrects itself with a negative row. V63 argues the same distinction at length from the other
	 * side.
	 *
	 * <p>Terminal, and refused a second time with {@code KMS-400132} rather than returning quietly
	 * the way {@code StaffPayService.voidPayment} does. That one carries no body, so a second call is
	 * literally a double-click; this one carries a reason, and a second reason is a second act that
	 * must not be swallowed.
	 *
	 * <p>Refused with {@code KMS-400154} while money still stands paid against the bill (T-206,
	 * Rajeev's decision for Phase B item 7). Before this the void went through and left the payments
	 * where they were, which made the one bill say two contradictory things: struck, so never owed,
	 * and paid, so owed and settled. The status could not show both, and every screen that sums
	 * payments went on counting money against a bill that every screen that filters on status had
	 * dropped. Refusing puts the two acts in the order that keeps the record honest: reverse each
	 * payment first — itself a recorded act with a reason, under the same MANAGE_VENDOR_PAYMENTS
	 * permission voiding needs — and then strike a bill that nothing is paid against.
	 *
	 * <p>"Still paid" is the <em>net</em> of {@code invoice_payments}, not a count of positive rows.
	 * That table is append-only, so a reversal is a second, negative row naming the first rather than
	 * a mark on it, and a hand-entered negative correction (which V40 has allowed since 2025) is the
	 * same shape without the link. Summing is the one reading that treats both correctly, and it is
	 * the same {@link #paidToDate} that {@link #restateStatus} uses to decide PAID, so the question
	 * "is anything paid on this bill" cannot get two answers. A bill whose payments were all reversed
	 * nets to zero and voids as it always did.
	 *
	 * <p>The check runs before anything is written, so a refused void changes neither the bill nor
	 * its payments, and leaves no audit entry for an act that did not happen. The audit entry on a
	 * successful void still records paid-to-date, which is now always zero there; it is kept because
	 * bills struck before T-206 can carry a non-zero figure, and a reader comparing the two eras
	 * should find the field in both.
	 */
	@Transactional
	public void voidInvoice(AuthenticatedUser actor, UUID id, VoidInvoiceRequest request) {
		Map<String, Object> before = invoiceRow(id);
		if ("VOIDED".equals(before.get("status"))) {
			throw new ApplicationException(ErrorCode.INVOICE_ALREADY_VOIDED, Map.of("invoiceId", id));
		}
		BigDecimal paid = paidToDate(id);
		if (paid.signum() > 0) {
			throw new ApplicationException(ErrorCode.INVOICE_HAS_UNREVERSED_PAYMENTS,
					Map.of("invoiceId", id, "paidToDate", paid.toPlainString()));
		}

		jdbc.update("""
				UPDATE vendor_invoices
				SET status = 'VOIDED', voided_at = now(), voided_by = ?, void_reason = ?, updated_at = now()
				WHERE id = ?
				""", actor.getUserId(), request.reason().trim(), id);

		// A struck bill releases the deliveries it billed, in the same transaction (conductor's ruling
		// for T-271): the bill was never owed, so the goods it claimed to bill are unbilled, and they
		// must be offered again to be billed correctly. The link row is kept and marked, not deleted,
		// so the struck bill's page still says which deliveries it was keyed against. This is what
		// takes the link out of V148's one-standing-bill index.
		jdbc.update("""
				UPDATE vendor_invoice_deliveries SET released_at = now()
				WHERE invoice_id = ? AND released_at IS NULL
				""", id);

		// Read the after-state back from the row rather than building it from the request: what was
		// asked for and what was stored are not the same claim, and an audit trail that reports the
		// request will eventually report a change that did not happen.
		Map<String, Object> after = invoiceRow(id);
		auditService.record(actor, AuditAction.INVOICE_VOIDED, AuditEntityType.VENDOR_INVOICE, id,
				Map.of("status", String.valueOf(before.get("status")),
						"amount", ((BigDecimal) before.get("amount")).toPlainString(),
						"paidToDate", paid.toPlainString()),
				Map.of("status", String.valueOf(after.get("status")),
						"voidReason", String.valueOf(after.get("void_reason"))),
				request.reason().trim());
	}

	/**
	 * Records a credit note against a bill that stands: it was owed, and it is now owed less.
	 *
	 * <p>A running total on the invoice rather than a table of credit notes, because each individual
	 * credit — its amount, its words, its author and its date — is kept by the audit trail under
	 * {@code INVOICE_CREDITED}, which is this project's record of who did what and why. Nothing in
	 * the product lists credit notes one by one, and a table nobody reads is a place for the two
	 * accounts of the same fact to drift apart.
	 *
	 * <p>The credit reduces what is owed, so the status is restated immediately: a bill of ₹1,000
	 * with ₹600 paid and ₹400 credited is settled, and leaving it PENDING would mean the temple
	 * could never close it. That is also why a credit is refused when it would take what is owed
	 * below what has already been paid — the temple would then be owed money by the vendor, which is
	 * a refund and not a credit note, and this product has no such record to put it in.
	 */
	@Transactional
	public void creditInvoice(AuthenticatedUser actor, UUID id, CreditInvoiceRequest request) {
		Map<String, Object> before = invoiceRow(id);
		if ("VOIDED".equals(before.get("status"))) {
			throw new ApplicationException(ErrorCode.INVOICE_ALREADY_VOIDED, Map.of("invoiceId", id));
		}

		BigDecimal amount = (BigDecimal) before.get("amount");
		BigDecimal credited = (BigDecimal) before.get("credited_amount");
		BigDecimal paid = paidToDate(id);
		BigDecimal room = amount.subtract(credited).subtract(paid.max(BigDecimal.ZERO));
		if (request.amount().compareTo(room) > 0) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "amount", "invoiceId", id, "maximum", room.toPlainString(),
							"reason", "a credit cannot take what is owed below what has been paid"));
		}

		BigDecimal newCredited = credited.add(request.amount());
		jdbc.update("UPDATE vendor_invoices SET credited_amount = ?, updated_at = now() WHERE id = ?",
				newCredited, id);
		String newStatus = restateStatus(id);

		Map<String, Object> after = invoiceRow(id);
		auditService.record(actor, AuditAction.INVOICE_CREDITED, AuditEntityType.VENDOR_INVOICE, id,
				Map.of("status", String.valueOf(before.get("status")),
						"creditedAmount", credited.toPlainString()),
				Map.of("status", newStatus,
						"creditedAmount", ((BigDecimal) after.get("credited_amount")).toPlainString(),
						"credit", request.amount().toPlainString()),
				request.reason().trim());
	}

	/**
	 * Restates one invoice's status from what it owes and what has been paid, and returns it.
	 *
	 * <p>The single place that decides PAID. It was in {@code InvoicePaymentService.recordPayment}
	 * and moved here when reversing and crediting became two more ways of reaching the same
	 * question: three callers each deciding "is this paid now" is three chances to answer it
	 * differently, and the disagreement would show up as an invoice that says PAID on one screen and
	 * sits in the payables queue on another.
	 *
	 * <p>What is owed is {@code amount - credited_amount}, so a credit can settle a bill on its own.
	 * Because paid-to-date is a <em>sum</em>, a compensating negative entry drops an invoice back out
	 * of PAID here without a line of code to say so — which is the property V40 was designed for.
	 *
	 * <p>VOIDED is terminal and is left alone. Without that guard, reversing a payment on a bill that
	 * had been struck would quietly bring the bill back to PENDING and put it in front of somebody to
	 * pay a second time.
	 */
	String restateStatus(UUID invoiceId) {
		Map<String, Object> invoice = invoiceRow(invoiceId);
		String status = (String) invoice.get("status");
		if ("VOIDED".equals(status)) {
			return status;
		}
		BigDecimal owed = ((BigDecimal) invoice.get("amount"))
				.subtract((BigDecimal) invoice.get("credited_amount"));
		String newStatus = paidToDate(invoiceId).compareTo(owed) >= 0 ? "PAID" : "PENDING";
		jdbc.update("UPDATE vendor_invoices SET status = ?, updated_at = now() WHERE id = ?",
				newStatus, invoiceId);
		return newStatus;
	}

	/** The invoice's own row, or {@code KMS-404xxx} if there is none. */
	private Map<String, Object> invoiceRow(UUID id) {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT status, amount, credited_amount, void_reason FROM vendor_invoices WHERE id = ?", id);
		if (rows.isEmpty()) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("invoiceId", id));
		}
		return rows.get(0);
	}

	private BigDecimal paidToDate(UUID invoiceId) {
		BigDecimal paid = jdbc.queryForObject(
				"SELECT COALESCE(SUM(amount), 0) FROM invoice_payments WHERE invoice_id = ?",
				BigDecimal.class, invoiceId);
		return paid == null ? BigDecimal.ZERO : paid;
	}

	// ---------------------------------------------------------------------

	/**
	 * Fills in the informational variance: what was billed against what the delivered goods were worth
	 * at the order's prices, where the order's lines carry prices.
	 *
	 * <p><strong>Two readings, by the kind of invoice (R-INV-7).</strong> An itemised invoice (stage 6,
	 * sub total present) is recomputed from its lines: its sub total — "Items billed" — against its
	 * delivered lines at their order lines' prices ({@link #expectedFromLines}). An invoice recorded
	 * before stage 6 has no lines and keeps exactly the reading it always had: its amount against what
	 * was received on its order ({@link #expectedReceivedValue}). A direct invoice has no order, so
	 * neither reading has a basis and both figures are null.
	 *
	 * <p>Computed against what is <em>owed</em> — {@code amount - credited_amount} — and not against
	 * the gross invoiced amount, which is the same arithmetic {@link #restateStatus} uses to decide
	 * PAID and {@code InvoicePaymentService} uses to compute what is outstanding. Three places
	 * answering "how much does this bill come to" have to answer it the same way or the screens
	 * disagree with each other.
	 *
	 * <p>The reason it matters here rather than being a tidiness point: the single most common reason
	 * to record a credit note is exactly the thing this variance exists to surface — a short delivery,
	 * a damaged sack, a price argued down after the bill was cut. Against the gross amount, a credit
	 * raised precisely to settle a variance leaves the variance showing the full discrepancy for ever,
	 * so the one act that resolves the query is the one act that appears to do nothing.
	 *
	 * <p>{@code expectedValue} is deliberately left alone. It is the worth of the goods actually
	 * received at the PO's line prices — a fact about the delivery, which a credit note does not
	 * change. The credit changes what the temple is being asked to pay, which is the other side of the
	 * subtraction. Both operands stay on the view alongside {@code creditedAmount}, so a screen that
	 * ever wants the gross figure back can still work it out.
	 *
	 * <p><strong>A voided bill has neither figure (T-207, Rajeev's decision for Phase B item 8).</strong>
	 * A voided bill is the record of one that should never have been recorded, so it is owed nothing
	 * and has nothing to be out by. Computed as before, a struck bill against a priced order went on
	 * showing a variance for ever — often the very discrepancy it was struck over — and nothing an
	 * admin could do would clear it, because {@link #creditInvoice} refuses a voided bill. That is the
	 * same reading {@link #countByVendorAndNumber} already takes of a voided bill: "a voided bill is
	 * not a bill". Both are left null rather than zero: zero would claim the bill matched its
	 * delivery, which is a statement about a bill the temple has said does not exist. The PO link,
	 * amount and credits stay on the view, so the record of what was struck is untouched.
	 */
	private List<VendorInvoiceView> withVariance(List<VendorInvoiceView> rows) {
		Map<UUID, BigDecimal> itemised = subTotals(rows);
		List<VendorInvoiceView> out = new ArrayList<>(rows.size());
		for (VendorInvoiceView v : rows) {
			BigDecimal expected;
			BigDecimal billed;
			if (v.status() == InvoiceStatus.VOIDED) {
				expected = null;
				billed = null;
			} else if (itemised.containsKey(v.id())) {
				// An itemised bill (stage 6): the items billed against the delivered lines at the order's
				// own prices, the comparison mock design D draws — "Items billed" against "Delivered, at
				// the order's prices". The sub total, not the grand total: GST and transport are not on
				// the order, so comparing them against it would report the tax as an overcharge.
				expected = expectedFromLines(v.id());
				billed = itemised.get(v.id());
			} else {
				// An invoice recorded before stage 6 keeps today's reading exactly: its amount against
				// what was received on its order at the order's prices.
				expected = v.purchaseOrderId() == null ? null : expectedReceivedValue(v.purchaseOrderId());
				billed = v.amount();
			}
			// credited_amount is NOT NULL DEFAULT 0 (V103), so this never needs a null guard — and
			// "no credits" reads as zero rather than as absent for exactly that reason. Credits still
			// come off on an itemised bill (conductor's ruling for T-271): a credit raised to settle the
			// difference must settle it, on either kind of bill.
			BigDecimal variance = expected == null ? null
					: billed.subtract(v.creditedAmount()).subtract(expected);
			out.add(new VendorInvoiceView(v.id(), v.vendorId(), v.vendorName(), v.purchaseOrderId(),
					v.poNumber(), v.direct(), v.description(), v.invoiceNumber(), v.invoiceDate(),
					v.amount(), v.dueDate(), v.scanRef(), v.status(), expected, variance, v.overdue(),
					v.voidedAt(), v.voidReason(), v.creditedAmount(), v.createdAt()));
		}
		return out;
	}

	/** The sub total of each itemised invoice among {@code rows}; an older invoice has none and is absent. */
	private Map<UUID, BigDecimal> subTotals(List<VendorInvoiceView> rows) {
		Map<UUID, BigDecimal> out = new HashMap<>();
		if (rows.isEmpty()) {
			return out;
		}
		List<UUID> ids = rows.stream().map(VendorInvoiceView::id).toList();
		jdbc.query("SELECT id, sub_total FROM vendor_invoices WHERE sub_total IS NOT NULL AND id IN (%s)"
				.formatted(placeholders(ids.size())),
				rs -> {
					out.put(rs.getObject("id", UUID.class), rs.getBigDecimal("sub_total"));
				}, ids.toArray());
		return out;
	}

	/**
	 * What the goods an itemised invoice bills were worth at the order's own prices (R-INV-7): each
	 * delivered line's kept quantity times its order line's expected price, summed. The quantity is
	 * restated in the order line's unit first, because that is the unit the price is per (T-260: a
	 * price is sent per the line's unit). Only priced order lines count, as on the older reading;
	 * when none is priced — or the bill is direct, with no delivered lines at all — there is no basis,
	 * and the answer is null, shown as "—", never ₹0 (conductor's ruling for T-271).
	 */
	private BigDecimal expectedFromLines(UUID invoiceId) {
		List<BigDecimal> values = jdbc.query("""
				SELECT gl.received_qty, gl.unit AS delivered_unit, pol.unit AS ordered_unit, pol.expected_price
				FROM vendor_invoice_lines l
				JOIN goods_receipt_lines gl ON gl.id = l.goods_receipt_line_id
				JOIN purchase_order_lines pol ON pol.id = gl.po_line_id
				WHERE l.invoice_id = ? AND pol.expected_price IS NOT NULL
				""", (rs, n) -> {
			Unit orderedIn = Unit.valueOf(rs.getString("ordered_unit"));
			BigDecimal kept = restate(rs.getBigDecimal("received_qty"), rs.getString("delivered_unit"), orderedIn);
			return kept.multiply(rs.getBigDecimal("expected_price"));
		}, invoiceId);
		if (values.isEmpty()) {
			return null;
		}
		return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
	}

	/**
	 * The value of what has actually been received against a PO, at its line prices — null when no
	 * line carries a price, so the caller shows a variance only when there is one to show. The reading
	 * for invoices recorded before stage 6, unchanged.
	 */
	private BigDecimal expectedReceivedValue(UUID poId) {
		Map<String, Object> row = jdbc.queryForMap("""
				SELECT COUNT(pol.expected_price) AS priced,
					   COALESCE(SUM(COALESCE(r.received, 0) * pol.expected_price), 0) AS expected
				FROM purchase_order_lines pol
				LEFT JOIN (
					SELECT po_line_id, SUM(received_qty) AS received
					FROM goods_receipt_lines GROUP BY po_line_id
				) r ON r.po_line_id = pol.id
				WHERE pol.po_id = ? AND pol.expected_price IS NOT NULL
				""", poId);
		long priced = ((Number) row.get("priced")).longValue();
		if (priced == 0) {
			return null;
		}
		return (BigDecimal) row.get("expected");
	}

	private void requireVendor(UUID vendorId) {
		Integer n = jdbc.queryForObject("SELECT count(*) FROM vendors WHERE id = ?", Integer.class, vendorId);
		if (n == null || n == 0) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("vendorId", vendorId));
		}
	}

	/**
	 * How many bills this vendor already has under this number — <strong>struck ones excluded</strong>.
	 *
	 * <p>Rajeev's ruling of 2026-09-10, on being shown that this counted voided invoices: the false
	 * warning fires on the <em>ordinary correction path</em>. A clerk records a bill, spots a mistake,
	 * voids it, and re-enters it under the same number — which is the only honest way to correct one,
	 * because {@link #voidInvoice} is a mark on the row and nothing is ever deleted. The old count saw
	 * the struck row and warned that the re-entry duplicated a bill the temple had already said was
	 * never owed. In his words, <em>"a warning that fires when somebody is being careful is one they
	 * learn to dismiss"</em> — and a soft warning is worth exactly as much as the attention it still
	 * gets.
	 *
	 * <p>A voided bill is not a bill. It is the record of one that should never have been recorded, so
	 * it cannot be the thing a new invoice duplicates. Two <em>standing</em> bills under one number
	 * still warn, which is the case this check exists for: vendors reuse numbering schemes
	 * imperfectly, and being billed twice for one delivery is the money that goes out of the door.
	 *
	 * <p>Still soft either way. This decides a flag on the response, never whether the invoice saves —
	 * see {@link RecordInvoiceResponse}.
	 */
	private int countByVendorAndNumber(UUID vendorId, String invoiceNumber) {
		Integer n = jdbc.queryForObject(
				"SELECT count(*) FROM vendor_invoices WHERE vendor_id = ? AND invoice_number = ? "
						+ "AND status <> 'VOIDED'",
				Integer.class, vendorId, invoiceNumber.trim());
		return n == null ? 0 : n;
	}

	private RowMapper<VendorInvoiceView> mapper() {
		return (rs, n) -> new VendorInvoiceView(
				rs.getObject("id", UUID.class),
				rs.getObject("vendor_id", UUID.class),
				rs.getString("vendor_name"),
				rs.getObject("po_id", UUID.class),
				rs.getString("po_number"),
				rs.getBoolean("direct"),
				rs.getString("description"),
				rs.getString("invoice_number"),
				rs.getObject("invoice_date", LocalDate.class),
				rs.getBigDecimal("amount"),
				rs.getObject("due_date", LocalDate.class),
				rs.getString("scan_ref"),
				InvoiceStatus.valueOf(rs.getString("status")),
				null,
				null,
				rs.getBoolean("overdue"),
				instant(rs.getObject("voided_at", OffsetDateTime.class)),
				rs.getString("void_reason"),
				rs.getBigDecimal("credited_amount"),
				instant(rs.getObject("created_at", OffsetDateTime.class)));
	}

	private static final String SELECT = """
			SELECT vi.id, vi.vendor_id, v.name AS vendor_name, vi.po_id, po.po_number, vi.direct,
				   vi.description, vi.invoice_number, vi.invoice_date, vi.amount, vi.due_date,
				   vi.scan_ref, vi.status, vi.voided_at, vi.void_reason, vi.credited_amount,
				   vi.created_at,
				   (vi.status = 'PENDING' AND vi.due_date IS NOT NULL AND vi.due_date < CURRENT_DATE) AS overdue
			FROM vendor_invoices vi
			JOIN vendors v ON v.id = vi.vendor_id
			LEFT JOIN purchase_orders po ON po.id = vi.po_id
			""";

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
