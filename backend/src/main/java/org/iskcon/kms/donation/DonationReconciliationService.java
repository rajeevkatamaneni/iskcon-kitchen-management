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
 * Reconciles the local ledger against the provider (E7-S9): for each donation that <em>stands</em> as
 * COMPLETED in a date range, asks the gateway whether the payment really captured, and returns those
 * it can't confirm. The daily job runs this per tenant and the super-admin ops page can run it on
 * demand; a mismatch is exactly what silent money drift looks like, so it is surfaced rather than
 * swallowed.
 *
 * <p><strong>A struck gift is not asked about at all (T-072).</strong> V104 (T-012) makes a void a
 * <em>mark</em> rather than a status change — {@code DonationVoidService} stamps {@code voided_at}
 * and leaves {@code status} at {@code COMPLETED} — so a query that selects on status alone still
 * picks up every gift the temple has struck. That matters here more than anywhere else, because a
 * voided gift is by definition one that was <em>recorded wrongly</em>, which makes it exactly the row
 * most likely to have no real payment behind it for the gateway to confirm. Left in, each struck row
 * came back UNKNOWN on every run, for ever, with nothing an operator could do to clear it — permanent
 * noise in the one report whose entire value is that it is normally empty — and cost a live gateway
 * API call each time to produce it.
 *
 * <p>Excluding them loses no signal, which is the argument for excluding rather than for reporting
 * them separately. A struck gift that <em>did</em> capture at the provider was never reported anyway:
 * the gateway answers CAPTURED and the loop below says nothing. So the only struck rows this report
 * ever named were the ones that could not be confirmed — that is, the ones whose gift never happened,
 * which is precisely what striking them recorded. Every figure the temple actually quotes already
 * reads the same way ({@code DonationLedgerService:186}, {@code MonetaryDonationService:379} and
 * {@code :463}); this reader was the one that did not.
 */
@Service
public class DonationReconciliationService {

	private final JdbcTemplate jdbc;
	private final org.iskcon.kms.payment.PaymentGatewayResolver gateways;

	public DonationReconciliationService(JdbcTemplate jdbc,
			org.iskcon.kms.payment.PaymentGatewayResolver gateways) {
		this.jdbc = jdbc;
		this.gateways = gateways;
	}

	public List<ReconciliationMismatch> reconcile(UUID tenantId, LocalDate from, LocalDate to) {
		TenantContext.set(tenantId);
		try {
			List<Row> rows = jdbc.query("""
					SELECT id, provider_payment_id, amount_inr FROM donations
					WHERE status = 'COMPLETED' AND voided_at IS NULL AND type <> 'IN_KIND'
					  AND created_at::date BETWEEN ? AND ?
					""", (rs, n) -> new Row(rs.getObject("id", UUID.class),
					rs.getString("provider_payment_id"), rs.getBigDecimal("amount_inr")), from, to);

			List<ReconciliationMismatch> mismatches = new ArrayList<>();
			for (Row r : rows) {
				PaymentGateway.PaymentStatus status =
						gateways.forCurrentTenant().fetchPaymentStatus(r.paymentId());
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
