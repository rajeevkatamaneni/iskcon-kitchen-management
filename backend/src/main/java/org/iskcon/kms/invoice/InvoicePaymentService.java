package org.iskcon.kms.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recording payments against vendor invoices (E7-S8) — the temple pays outside the app and records it
 * here; nothing here moves money. Payments are append-only; an invoice's paid-to-date is their sum,
 * and it flips PAID when that reaches what is owed. Overpayment is refused, and every payment is
 * audited.
 *
 * <p>A payment recorded in error is <em>reversed</em> and never struck (T-010). {@code
 * invoice_payments} is append-only (V40:33, re-registered in V49 and V50), which since V49 is a
 * BEFORE UPDATE OR DELETE trigger refusing with 42501 and the hint "Correct an entry by adding a
 * compensating one; history is never edited." So there is no row to mark: {@link #reversePayment}
 * appends a compensating negative entry, exactly as the stock ledger corrects itself, and V40
 * designed for it — {@code amount} has been signed since 2025 and {@code CHECK (amount <> 0)}
 * already permitted negatives.
 *
 * <p>What is owed is the invoiced amount less any credit notes, and the status that follows from it
 * is decided in one place — {@link VendorInvoiceService#restateStatus} — which this service calls
 * rather than working it out a second time.
 */
@Service
public class InvoicePaymentService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final VendorInvoiceService invoices;

	public InvoicePaymentService(JdbcTemplate jdbc, AuditService auditService, VendorInvoiceService invoices) {
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.invoices = invoices;
	}

	@Transactional
	public UUID recordPayment(AuthenticatedUser actor, UUID invoiceId, RecordInvoicePaymentRequest request) {
		BigDecimal owed;
		String status;
		try {
			Map<String, Object> inv = jdbc.queryForMap(
					"SELECT amount, credited_amount, status FROM vendor_invoices WHERE id = ?", invoiceId);
			// What is owed, not what was invoiced: a credit note reduces the bill, and paying the
			// full invoiced amount on a bill the vendor has since reduced would be an overpayment
			// the temple never gets back.
			owed = ((BigDecimal) inv.get("amount")).subtract((BigDecimal) inv.get("credited_amount"));
			status = (String) inv.get("status");
		} catch (EmptyResultDataAccessException e) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("invoiceId", invoiceId), e);
		}

		// A bill that was never owed cannot be paid. Without this the payment would also restate the
		// status and quietly bring the struck invoice back into the pay cycle.
		if ("VOIDED".equals(status)) {
			throw new ApplicationException(ErrorCode.INVOICE_ALREADY_VOIDED, Map.of("invoiceId", invoiceId));
		}

		BigDecimal paid = paidToDate(invoiceId);
		if (request.amount().signum() > 0 && paid.compareTo(owed) >= 0) {
			throw new ApplicationException(ErrorCode.INVOICE_ALREADY_PAID, Map.of("invoiceId", invoiceId));
		}
		BigDecimal newPaid = paid.add(request.amount());
		if (newPaid.compareTo(owed) > 0) {
			throw new ApplicationException(ErrorCode.INVOICE_OVERPAYMENT,
					Map.of("invoiceId", invoiceId, "outstanding", owed.subtract(paid)));
		}
		if (newPaid.signum() < 0) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "amount", "reason", "would take paid-to-date below zero"));
		}

		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO invoice_payments (id, tenant_id, invoice_id, paid_on, amount, method, reference, note, recorded_by)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?, ?)
				""", id, invoiceId, request.paidOn(), request.amount(), request.method().name(),
				trimToNull(request.reference()), trimToNull(request.note()), actor.getUserId());

		String newStatus = invoices.restateStatus(invoiceId);

		auditService.record(actor, AuditAction.INVOICE_PAYMENT_RECORDED, AuditEntityType.VENDOR_INVOICE, invoiceId,
				Map.of("status", status, "paidToDate", paid.toPlainString()),
				Map.of("status", newStatus, "paidToDate", newPaid.toPlainString(),
						"amount", request.amount().toPlainString(), "method", request.method().name()),
				null);
		return id;
	}

	/**
	 * Undoes a payment recorded in error — a bounced cheque, a mistyped amount, a payment entered
	 * against the wrong bill.
	 *
	 * <p>Nothing is marked and nothing is removed. The original row is left exactly as it was, because
	 * the table forbids anything else, and a second row of the opposite sign is appended naming it.
	 * The invoice's status follows from the sum, so an invoice that reached PAID on the reversed
	 * payment drops back to PENDING without a line of code here to say so.
	 *
	 * <p>The compensating row is dated the same day as the payment it undoes. A reversal that carried
	 * today's date would show a monthly spend figure with money paid in July and returned in
	 * September, neither of which happened; when the correction was <em>made</em> is
	 * {@code created_at}, which is already recorded.
	 *
	 * <p>Only a positive payment can be reversed. A row that is already negative is itself a
	 * correction — either one of these reversals or the hand-entered compensating entry V40 has
	 * allowed since 2025 — and undoing a correction is recording a fresh payment, which is a
	 * different act with a different date. Both cases answer {@code KMS-400133}.
	 */
	@Transactional
	public UUID reversePayment(AuthenticatedUser actor, UUID invoiceId, UUID paymentId,
			ReverseInvoicePaymentRequest request) {
		Map<String, Object> payment = jdbc.queryForList("""
				SELECT p.paid_on, p.amount, p.method, p.reference,
					   (SELECT count(*) FROM invoice_payments r WHERE r.reverses = p.id) AS reversals
				FROM invoice_payments p WHERE p.id = ? AND p.invoice_id = ?
				""", paymentId, invoiceId).stream().findFirst()
				.orElseThrow(() -> new ApplicationException(
						ErrorCode.RESOURCE_NOT_FOUND, Map.of("paymentId", paymentId)));

		BigDecimal amount = (BigDecimal) payment.get("amount");
		long reversals = ((Number) payment.get("reversals")).longValue();
		if (reversals > 0 || amount.signum() <= 0) {
			throw new ApplicationException(ErrorCode.PAYMENT_ALREADY_VOIDED,
					Map.of("invoiceId", invoiceId, "paymentId", paymentId));
		}

		String status = jdbc.queryForObject(
				"SELECT status FROM vendor_invoices WHERE id = ?", String.class, invoiceId);
		BigDecimal paid = paidToDate(invoiceId);

		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO invoice_payments (id, tenant_id, invoice_id, paid_on, amount, method, reference,
					note, recorded_by, reverses, reverse_reason)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", id, invoiceId, payment.get("paid_on"), amount.negate(), payment.get("method"),
				payment.get("reference"), null, actor.getUserId(), paymentId, request.reason().trim());

		String newStatus = invoices.restateStatus(invoiceId);

		// The entity stays the invoice, as it is for INVOICE_PAYMENT_RECORDED. A reader following one
		// bill's history needs both halves of it under the same id, and the two payment rows are named
		// in the after-state where they can be told apart.
		auditService.record(actor, AuditAction.INVOICE_PAYMENT_VOIDED, AuditEntityType.VENDOR_INVOICE, invoiceId,
				Map.of("status", String.valueOf(status), "paidToDate", paid.toPlainString()),
				Map.of("status", newStatus, "paidToDate", paid.subtract(amount).toPlainString(),
						"paymentId", paymentId.toString(), "reversalId", id.toString(),
						"amount", amount.negate().toPlainString()),
				request.reason().trim());
		return id;
	}

	@Transactional(readOnly = true)
	public List<InvoicePaymentView> payments(UUID invoiceId) {
		// The self-join is the other end of a reversal. An append-only table cannot be told after the
		// fact that one of its rows has been undone, so "was this payment reversed" is a question
		// answered by looking for the row that names it — and a unique index on `reverses` is what
		// makes at most one such row possible.
		return jdbc.query("""
				SELECT p.id, p.paid_on, p.amount, p.method, p.reference, p.note, u.full_name AS recorded_by_name,
					   p.reverses, r.id AS reversed_by, p.reverse_reason, p.created_at
				FROM invoice_payments p
				LEFT JOIN users u ON u.id = p.recorded_by
				LEFT JOIN invoice_payments r ON r.reverses = p.id
				WHERE p.invoice_id = ? ORDER BY p.paid_on, p.created_at
				""", (rs, n) -> new InvoicePaymentView(
				rs.getObject("id", UUID.class), rs.getObject("paid_on", LocalDate.class),
				rs.getBigDecimal("amount"), rs.getString("method"), rs.getString("reference"),
				rs.getString("note"), rs.getString("recorded_by_name"),
				rs.getObject("reverses", UUID.class), rs.getObject("reversed_by", UUID.class),
				rs.getString("reverse_reason"),
				instant(rs.getObject("created_at", OffsetDateTime.class))), invoiceId);
	}

	/** Outstanding invoices with their aging bucket, for the payables view (E7-S8). */
	@Transactional(readOnly = true)
	public List<PayableView> payables() {
		LocalDate today = LocalDate.now();
		List<PayableView> out = new java.util.ArrayList<>();
		// PENDING alone, which is what keeps a struck bill out of here: VOIDED is a status and not a
		// flag precisely so that every query already asking this question skips it without being
		// changed. The credit note is subtracted from what is outstanding for the same reason a
		// payment is — both are money the temple will not be sending.
		jdbc.query("""
				SELECT vi.id, vi.invoice_number, v.name AS vendor_name, vi.amount, vi.credited_amount, vi.due_date,
					   COALESCE((SELECT SUM(p.amount) FROM invoice_payments p WHERE p.invoice_id = vi.id), 0) AS paid
				FROM vendor_invoices vi JOIN vendors v ON v.id = vi.vendor_id
				WHERE vi.status = 'PENDING'
				ORDER BY vi.due_date NULLS LAST, vi.invoice_date
				""", rs -> {
			BigDecimal amount = rs.getBigDecimal("amount");
			BigDecimal paid = rs.getBigDecimal("paid");
			BigDecimal outstanding = amount.subtract(rs.getBigDecimal("credited_amount")).subtract(paid);
			if (outstanding.signum() <= 0) {
				return;
			}
			LocalDate dueDate = rs.getObject("due_date", LocalDate.class);
			out.add(new PayableView(rs.getObject("id", UUID.class), rs.getString("invoice_number"),
					rs.getString("vendor_name"), amount, paid, outstanding, dueDate, agingBucket(dueDate, today)));
		});
		return out;
	}

	// ---------------------------------------------------------------------

	private BigDecimal paidToDate(UUID invoiceId) {
		BigDecimal paid = jdbc.queryForObject(
				"SELECT COALESCE(SUM(amount), 0) FROM invoice_payments WHERE invoice_id = ?",
				BigDecimal.class, invoiceId);
		return paid == null ? BigDecimal.ZERO : paid;
	}

	private static String agingBucket(LocalDate dueDate, LocalDate today) {
		if (dueDate == null || !dueDate.isBefore(today)) {
			return "CURRENT";
		}
		long overdue = ChronoUnit.DAYS.between(dueDate, today);
		return overdue <= 30 ? "DUE_1_30" : "OVERDUE_31_PLUS";
	}

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
