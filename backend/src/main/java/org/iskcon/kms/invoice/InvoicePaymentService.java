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
 * and it flips PAID when that reaches the invoiced amount. Overpayment is refused, and every payment
 * is audited.
 */
@Service
public class InvoicePaymentService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public InvoicePaymentService(JdbcTemplate jdbc, AuditService auditService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	@Transactional
	public UUID recordPayment(AuthenticatedUser actor, UUID invoiceId, RecordInvoicePaymentRequest request) {
		BigDecimal invoiceAmount;
		String status;
		try {
			Map<String, Object> inv = jdbc.queryForMap(
					"SELECT amount, status FROM vendor_invoices WHERE id = ?", invoiceId);
			invoiceAmount = (BigDecimal) inv.get("amount");
			status = (String) inv.get("status");
		} catch (EmptyResultDataAccessException e) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("invoiceId", invoiceId), e);
		}

		BigDecimal paid = paidToDate(invoiceId);
		if (request.amount().signum() > 0 && paid.compareTo(invoiceAmount) >= 0) {
			throw new ApplicationException(ErrorCode.INVOICE_ALREADY_PAID, Map.of("invoiceId", invoiceId));
		}
		BigDecimal newPaid = paid.add(request.amount());
		if (newPaid.compareTo(invoiceAmount) > 0) {
			throw new ApplicationException(ErrorCode.INVOICE_OVERPAYMENT,
					Map.of("invoiceId", invoiceId, "outstanding", invoiceAmount.subtract(paid)));
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

		String newStatus = newPaid.compareTo(invoiceAmount) >= 0 ? "PAID" : "PENDING";
		jdbc.update("UPDATE vendor_invoices SET status = ?, updated_at = now() WHERE id = ?", newStatus, invoiceId);

		auditService.record(actor, AuditAction.INVOICE_PAYMENT_RECORDED, AuditEntityType.VENDOR_INVOICE, invoiceId,
				Map.of("status", status, "paidToDate", paid.toPlainString()),
				Map.of("status", newStatus, "paidToDate", newPaid.toPlainString(),
						"amount", request.amount().toPlainString(), "method", request.method().name()),
				null);
		return id;
	}

	@Transactional(readOnly = true)
	public List<InvoicePaymentView> payments(UUID invoiceId) {
		return jdbc.query("""
				SELECT p.id, p.paid_on, p.amount, p.method, p.reference, p.note, u.full_name AS recorded_by_name,
					   p.created_at
				FROM invoice_payments p LEFT JOIN users u ON u.id = p.recorded_by
				WHERE p.invoice_id = ? ORDER BY p.paid_on, p.created_at
				""", (rs, n) -> new InvoicePaymentView(
				rs.getObject("id", UUID.class), rs.getObject("paid_on", LocalDate.class),
				rs.getBigDecimal("amount"), rs.getString("method"), rs.getString("reference"),
				rs.getString("note"), rs.getString("recorded_by_name"),
				instant(rs.getObject("created_at", OffsetDateTime.class))), invoiceId);
	}

	/** Outstanding invoices with their aging bucket, for the payables view (E7-S8). */
	@Transactional(readOnly = true)
	public List<PayableView> payables() {
		LocalDate today = LocalDate.now();
		List<PayableView> out = new java.util.ArrayList<>();
		jdbc.query("""
				SELECT vi.id, vi.invoice_number, v.name AS vendor_name, vi.amount, vi.due_date,
					   COALESCE((SELECT SUM(p.amount) FROM invoice_payments p WHERE p.invoice_id = vi.id), 0) AS paid
				FROM vendor_invoices vi JOIN vendors v ON v.id = vi.vendor_id
				WHERE vi.status = 'PENDING'
				ORDER BY vi.due_date NULLS LAST, vi.invoice_date
				""", rs -> {
			BigDecimal amount = rs.getBigDecimal("amount");
			BigDecimal paid = rs.getBigDecimal("paid");
			BigDecimal outstanding = amount.subtract(paid);
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
