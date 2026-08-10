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
			requirePurchaseOrder(request.purchaseOrderId());
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

	// ---------------------------------------------------------------------

	/** Fills in the informational variance for PO invoices whose lines carry prices. */
	private List<VendorInvoiceView> withVariance(List<VendorInvoiceView> rows) {
		List<VendorInvoiceView> out = new ArrayList<>(rows.size());
		for (VendorInvoiceView v : rows) {
			BigDecimal expected = v.purchaseOrderId() == null ? null : expectedReceivedValue(v.purchaseOrderId());
			BigDecimal variance = expected == null ? null : v.amount().subtract(expected);
			out.add(new VendorInvoiceView(v.id(), v.vendorId(), v.vendorName(), v.purchaseOrderId(),
					v.poNumber(), v.direct(), v.description(), v.invoiceNumber(), v.invoiceDate(),
					v.amount(), v.dueDate(), v.scanRef(), v.status(), expected, variance, v.overdue(),
					v.createdAt()));
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

	private void requirePurchaseOrder(UUID poId) {
		Integer n = jdbc.queryForObject("SELECT count(*) FROM purchase_orders WHERE id = ?", Integer.class, poId);
		if (n == null || n == 0) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("purchaseOrderId", poId));
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
				instant(rs.getObject("created_at", OffsetDateTime.class)));
	}

	private static final String SELECT = """
			SELECT vi.id, vi.vendor_id, v.name AS vendor_name, vi.po_id, po.po_number, vi.direct,
				   vi.description, vi.invoice_number, vi.invoice_date, vi.amount, vi.due_date,
				   vi.scan_ref, vi.status, vi.created_at,
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
