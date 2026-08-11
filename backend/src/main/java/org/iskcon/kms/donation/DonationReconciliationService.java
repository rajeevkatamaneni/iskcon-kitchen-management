package org.iskcon.kms.donation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.payment.PaymentGateway;
import org.iskcon.kms.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reconciles the local ledger against the provider (E7-S9): for each donation we call COMPLETED in a
 * date range, asks the gateway whether the payment really captured, and returns those it can't
 * confirm. The daily job runs this per tenant and the super-admin ops page can run it on demand; a
 * mismatch is exactly what silent money drift looks like, so it is surfaced rather than swallowed.
 */
@Service
public class DonationReconciliationService {

	private final JdbcTemplate jdbc;
	private final PaymentGateway paymentGateway;

	public DonationReconciliationService(JdbcTemplate jdbc, PaymentGateway paymentGateway) {
		this.jdbc = jdbc;
		this.paymentGateway = paymentGateway;
	}

	public List<ReconciliationMismatch> reconcile(UUID tenantId, LocalDate from, LocalDate to) {
		TenantContext.set(tenantId);
		try {
			List<Row> rows = jdbc.query("""
					SELECT id, provider_payment_id, amount_inr FROM donations
					WHERE status = 'COMPLETED' AND type <> 'IN_KIND'
					  AND created_at::date BETWEEN ? AND ?
					""", (rs, n) -> new Row(rs.getObject("id", UUID.class),
					rs.getString("provider_payment_id"), rs.getBigDecimal("amount_inr")), from, to);

			List<ReconciliationMismatch> mismatches = new ArrayList<>();
			for (Row r : rows) {
				PaymentGateway.PaymentStatus status = paymentGateway.fetchPaymentStatus(r.paymentId());
				if (status != PaymentGateway.PaymentStatus.CAPTURED) {
					mismatches.add(new ReconciliationMismatch(r.id(), r.paymentId(), r.amount(), status.name()));
				}
			}
			return mismatches;
		} finally {
			TenantContext.clear();
		}
	}

	private record Row(UUID id, String paymentId, BigDecimal amount) {
	}
}
