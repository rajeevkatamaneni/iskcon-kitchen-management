package org.iskcon.kms.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Capturing vendor invoices for the payment queue (E5-S8). An invoice references a PO, or — for a
 * cash-market purchase with none — is recorded direct with a description. Capture only ever sets
 * PENDING; the flip to PAID is payment execution (E7-S9). Where the PO's lines carry prices, the
 * value of what was actually received is computed and shown against the invoiced amount as an
 * informational variance — surfaced, never enforced.
 *
 * <p>A captured bill can afterwards be withdrawn or reduced (T-010), and the two are different acts:
 * {@link #voidInvoice} says the bill was never owed and takes it out of the pay cycle for good, while
 * {@link #creditInvoice} says it was owed and is now owed less and leaves it in. Nothing is deleted
 * by either.
 *
 * <p>This service also owns {@link #restateStatus}, the one place in the application that decides
 * whether an invoice is PAID. It lives here rather than beside the payments that trigger it because
 * the status is a fact about the invoice row, and two places computing it would eventually disagree.
 */
@Service
public class VendorInvoiceService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public VendorInvoiceService(JdbcTemplate jdbc, AuditService auditService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	@Transactional
	public RecordInvoiceResponse record(AuthenticatedUser actor, RecordInvoiceRequest request) {
		boolean direct = request.purchaseOrderId() == null;
		if (direct && (request.description() == null || request.description().isBlank())) {
			throw new ApplicationException(ErrorCode.INVOICE_DIRECT_NEEDS_DESCRIPTION, Map.of());
		}
		requireVendor(request.vendorId());
		if (!direct) {
			requirePurchaseOrderForVendor(request.purchaseOrderId(), request.vendorId());
		}

		boolean duplicate = countByVendorAndNumber(request.vendorId(), request.invoiceNumber()) > 0;

		UUID id = UUID.randomUUID();
		jdbc.update(connection -> {
			var ps = connection.prepareStatement("""
					INSERT INTO vendor_invoices (
						id, tenant_id, vendor_id, po_id, direct, description, invoice_number, invoice_date,
						amount, due_date, scan_ref, created_by)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""");
			ps.setObject(1, id);
			ps.setObject(2, request.vendorId());
			ps.setObject(3, request.purchaseOrderId());
			ps.setBoolean(4, direct);
			ps.setString(5, trimToNull(request.description()));
			ps.setString(6, request.invoiceNumber().trim());
			ps.setObject(7, request.invoiceDate());
			ps.setBigDecimal(8, request.amount());
			ps.setObject(9, request.dueDate());
			ps.setString(10, trimToNull(request.scanRef()));
			ps.setObject(11, actor.getUserId());
			return ps;
		});

		auditService.record(actor, AuditAction.INVOICE_RECORDED, AuditEntityType.VENDOR_INVOICE, id,
				null,
				Map.of("invoiceNumber", request.invoiceNumber().trim(),
						"amount", request.amount().toPlainString(),
						"direct", direct),
				null);

		return new RecordInvoiceResponse(get(id), duplicate);
	}

	@Transactional(readOnly = true)
	public List<VendorInvoiceView> list(InvoiceStatus status, boolean overdueOnly) {
		StringBuilder sql = new StringBuilder(SELECT + " WHERE 1 = 1");
		List<Object> args = new ArrayList<>();
		if (status != null) {
			sql.append(" AND vi.status = ?");
			args.add(status.name());
		}
		if (overdueOnly) {
			sql.append(" AND vi.status = 'PENDING' AND vi.due_date IS NOT NULL AND vi.due_date < CURRENT_DATE");
		}
		sql.append(" ORDER BY vi.due_date NULLS LAST, vi.invoice_date DESC");
		List<VendorInvoiceView> rows = jdbc.query(sql.toString(), mapper(), args.toArray());
		return withVariance(rows);
	}

	@Transactional(readOnly = true)
	public VendorInvoiceView get(UUID id) {
		List<VendorInvoiceView> rows = jdbc.query(SELECT + " WHERE vi.id = ?", mapper(), id);
		if (rows.isEmpty()) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("invoiceId", id));
		}
		return withVariance(rows).get(0);
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
	 * <p>Payments already recorded against the bill are left exactly as they are. Striking the
	 * invoice does not un-spend money that left the temple's account, and the audit entry below
	 * records what had been paid at the moment it was struck so that a reader is not left to wonder.
	 */
	@Transactional
	public void voidInvoice(AuthenticatedUser actor, UUID id, VoidInvoiceRequest request) {
		Map<String, Object> before = invoiceRow(id);
		if ("VOIDED".equals(before.get("status"))) {
			throw new ApplicationException(ErrorCode.INVOICE_ALREADY_VOIDED, Map.of("invoiceId", id));
		}

		jdbc.update("""
				UPDATE vendor_invoices
				SET status = 'VOIDED', voided_at = now(), voided_by = ?, void_reason = ?, updated_at = now()
				WHERE id = ?
				""", actor.getUserId(), request.reason().trim(), id);

		// Read the after-state back from the row rather than building it from the request: what was
		// asked for and what was stored are not the same claim, and an audit trail that reports the
		// request will eventually report a change that did not happen.
		Map<String, Object> after = invoiceRow(id);
		auditService.record(actor, AuditAction.INVOICE_VOIDED, AuditEntityType.VENDOR_INVOICE, id,
				Map.of("status", String.valueOf(before.get("status")),
						"amount", ((BigDecimal) before.get("amount")).toPlainString(),
						"paidToDate", paidToDate(id).toPlainString()),
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
	 * Fills in the informational variance for PO invoices whose lines carry prices.
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
	 */
	private List<VendorInvoiceView> withVariance(List<VendorInvoiceView> rows) {
		List<VendorInvoiceView> out = new ArrayList<>(rows.size());
		for (VendorInvoiceView v : rows) {
			BigDecimal expected = v.purchaseOrderId() == null ? null : expectedReceivedValue(v.purchaseOrderId());
			// credited_amount is NOT NULL DEFAULT 0 (V103), so this never needs a null guard — and
			// "no credits" reads as zero rather than as absent for exactly that reason.
			BigDecimal variance = expected == null ? null
					: v.amount().subtract(v.creditedAmount()).subtract(expected);
			out.add(new VendorInvoiceView(v.id(), v.vendorId(), v.vendorName(), v.purchaseOrderId(),
					v.poNumber(), v.direct(), v.description(), v.invoiceNumber(), v.invoiceDate(),
					v.amount(), v.dueDate(), v.scanRef(), v.status(), expected, variance, v.overdue(),
					v.voidedAt(), v.voidReason(), v.creditedAmount(), v.createdAt()));
		}
		return out;
	}

	/**
	 * The value of what has actually been received against a PO, at its line prices — null when no
	 * line carries a price, so the caller shows a variance only when there is one to show.
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
	 * The order exists <em>and</em> it was raised for the vendor being invoiced (T-082).
	 *
	 * <p>This used to be a bare {@code count(*)} sitting beside an equally bare one for the vendor,
	 * and two independent existence checks can never notice that the pair disagrees: an invoice
	 * naming Vendor A while quoting Vendor B's order passed both. That is not a tidiness point. The
	 * variance figure is computed from {@code po_id} — the worth of what was received against that
	 * order, at that order's line prices — so an invoice matched to the wrong order reports the wrong
	 * money owed, on a screen whose whole purpose is money owed, with nothing anywhere saying so.
	 *
	 * <p>Reading {@code vendor_id} rather than counting rows answers both questions in one query, so
	 * the guard costs nothing over the check it replaces. The screen makes the mismatch impossible by
	 * construction — the order is a dropdown of the chosen vendor's own open orders — and this is
	 * here because the endpoint is reachable without the screen.
	 *
	 * <p>An order belonging to another tenant is invisible under RLS, so it reads as absent and comes
	 * back as {@code RESOURCE_NOT_FOUND}. That is deliberate: a cross-tenant probe must not be able to
	 * tell "no such order" apart from "somebody else's order".
	 */
	private void requirePurchaseOrderForVendor(UUID poId, UUID vendorId) {
		List<UUID> owner = jdbc.queryForList(
				"SELECT vendor_id FROM purchase_orders WHERE id = ?", UUID.class, poId);
		if (owner.isEmpty()) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("purchaseOrderId", poId));
		}
		if (!owner.get(0).equals(vendorId)) {
			throw new ApplicationException(ErrorCode.INVOICE_ORDER_NOT_FOR_VENDOR,
					Map.of("purchaseOrderId", poId, "vendorId", vendorId));
		}
	}

	private int countByVendorAndNumber(UUID vendorId, String invoiceNumber) {
		Integer n = jdbc.queryForObject(
				"SELECT count(*) FROM vendor_invoices WHERE vendor_id = ? AND invoice_number = ?",
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
