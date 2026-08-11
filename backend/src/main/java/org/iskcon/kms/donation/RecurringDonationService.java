package org.iskcon.kms.donation;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.notification.NotificationRecipient;
import org.iskcon.kms.notification.NotificationService;
import org.iskcon.kms.notification.NotificationTemplate;
import org.iskcon.kms.payment.PaymentGateway;
import org.iskcon.kms.payment.SubscriptionResult;
import org.iskcon.kms.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recurring donations (E7-S3). A signed-in donor sets a frequency and amount; a provider subscription
 * is created and a local plan records it with a donor snapshot. Each cycle's charge webhook creates a
 * COMPLETED donation attached to the plan; a failed cycle notifies the donor; the plan's status
 * mirrors the provider's. The donor can cancel, which cancels the mandate.
 */
@Service
public class RecurringDonationService {

	private static final Pattern PAN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
	private static final Logger log = LoggerFactory.getLogger(RecurringDonationService.class);

	private final JdbcTemplate jdbc;
	private final PanCipher panCipher;
	private final PaymentGateway paymentGateway;
	private final NotificationService notificationService;

	public RecurringDonationService(JdbcTemplate jdbc, PanCipher panCipher, PaymentGateway paymentGateway,
			NotificationService notificationService) {
		this.jdbc = jdbc;
		this.panCipher = panCipher;
		this.paymentGateway = paymentGateway;
		this.notificationService = notificationService;
	}

	@Transactional
	public RecurringPlanView createPlan(AuthenticatedUser actor, CreateRecurringRequest req) {
		Snapshot s = resolveDonor(actor, req);
		long minorUnits = req.amountInr().movePointRight(2).longValueExact();
		SubscriptionResult sub = paymentGateway.createSubscription(
				req.frequency().name(), minorUnits, "INR", Map.of("donor", actor.getUserId().toString()));

		UUID id = UUID.randomUUID();
		jdbc.update(connection -> {
			PreparedStatement ps = connection.prepareStatement("""
					INSERT INTO recurring_plans (id, tenant_id, donor_account_user_id, frequency, amount_inr,
						provider, provider_subscription_id, status, donor_name, donor_phone, donor_email,
						donor_address, donor_pan_ciphertext, wants_80g, section, consent_at)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, 'ACTIVE',
						?, ?, ?, ?, ?, ?, ?, now())
					""");
			ps.setObject(1, id);
			ps.setObject(2, actor.getUserId());
			ps.setString(3, req.frequency().name());
			ps.setBigDecimal(4, req.amountInr());
			ps.setString(5, paymentGateway.name());
			ps.setString(6, sub.subscriptionId());
			ps.setString(7, s.name());
			ps.setString(8, s.phone());
			ps.setString(9, s.email());
			ps.setString(10, s.address());
			ps.setBytes(11, s.panCiphertext());
			ps.setBoolean(12, s.wants80g());
			ps.setString(13, s.section());
			return ps;
		});
		if (s.panFingerprint() != null) {
			jdbc.update("UPDATE recurring_plans SET pan_fingerprint = ? WHERE id = ?", s.panFingerprint(), id);
		}
		return new RecurringPlanView(id, req.frequency().name(), req.amountInr(), "ACTIVE",
				sub.subscriptionId(), sub.shortUrl(), null);
	}

	@Transactional(readOnly = true)
	public List<RecurringPlanView> myPlans(UUID userId) {
		return jdbc.query("""
				SELECT id, frequency, amount_inr, status, provider_subscription_id, created_at
				FROM recurring_plans WHERE donor_account_user_id = ? ORDER BY created_at DESC
				""", (rs, n) -> new RecurringPlanView(rs.getObject("id", UUID.class), rs.getString("frequency"),
				rs.getBigDecimal("amount_inr"), rs.getString("status"),
				rs.getString("provider_subscription_id"), null,
				instant(rs.getObject("created_at", OffsetDateTime.class))), userId);
	}

	@Transactional(readOnly = true)
	public List<DonationView> planHistory(UUID planId, UUID userId) {
		requireOwned(planId, userId);
		return jdbc.query("""
				SELECT id, amount_inr, status, payment_mode, created_at
				FROM donations WHERE recurring_plan_id = ? ORDER BY created_at DESC
				""", (rs, n) -> new DonationView(rs.getObject("id", UUID.class), rs.getBigDecimal("amount_inr"),
				rs.getString("status"), rs.getString("payment_mode"),
				instant(rs.getObject("created_at", OffsetDateTime.class))), planId);
	}

	@Transactional
	public void cancelPlan(AuthenticatedUser actor, UUID planId) {
		String subscriptionId = requireOwned(planId, actor.getUserId());
		if (subscriptionId != null) {
			paymentGateway.cancelSubscription(subscriptionId);
		}
		jdbc.update("UPDATE recurring_plans SET status = 'CANCELLED', updated_at = now() WHERE id = ?", planId);
	}

	// ---- Webhook-driven (called by the handler, resolves tenant via the escape) ----

	/** A subscription cycle charged: records a COMPLETED RECURRING donation attached to the plan. */
	public void recordCharge(String subscriptionId, String paymentId, String method) {
		Plan plan = locate(subscriptionId);
		if (plan == null) {
			return;
		}
		TenantContext.set(plan.tenantId());
		try {
			Integer existing = jdbc.queryForObject(
					"SELECT count(*) FROM donations WHERE recurring_plan_id = ? AND provider_payment_id = ?",
					Integer.class, plan.id(), paymentId);
			if (existing != null && existing > 0) {
				return; // idempotent — this cycle already recorded
			}
			jdbc.update("""
					INSERT INTO donations (id, tenant_id, type, amount_inr, currency, status, is_anonymous,
						donor_name, donor_phone, donor_email, donor_address, donor_pan_ciphertext, wants_80g,
						section, provider, provider_payment_id, recurring_plan_id, pan_fingerprint, donated_on)
					SELECT gen_random_uuid(), tenant_id, 'RECURRING', amount_inr, 'INR', 'COMPLETED', false,
						donor_name, donor_phone, donor_email, donor_address, donor_pan_ciphertext, wants_80g,
						section, provider, ?, id, pan_fingerprint, CURRENT_DATE
					FROM recurring_plans WHERE id = ?
					""", paymentId, plan.id());
			// payment_mode is separate to keep the SELECT-INSERT simple.
			jdbc.update("UPDATE donations SET payment_mode = ? WHERE provider_payment_id = ? AND recurring_plan_id = ?",
					method, paymentId, plan.id());
		} finally {
			TenantContext.clear();
		}
	}

	/** Reflects a subscription status change (halted / cancelled / active) onto the local plan. */
	public void updateStatus(String subscriptionId, String status) {
		Plan plan = locate(subscriptionId);
		if (plan == null) {
			return;
		}
		TenantContext.set(plan.tenantId());
		try {
			jdbc.update("UPDATE recurring_plans SET status = ?, updated_at = now() WHERE id = ?", status, plan.id());
		} finally {
			TenantContext.clear();
		}
	}

	/** A cycle failed: notifies the donor with retry guidance (E7-S3). */
	public void notifyFailedCycle(String subscriptionId) {
		Plan plan = locate(subscriptionId);
		if (plan == null) {
			return;
		}
		TenantContext.set(plan.tenantId());
		try {
			Map<String, Object> p = jdbc.queryForMap(
					"SELECT donor_phone, donor_email FROM recurring_plans WHERE id = ?", plan.id());
			if (p.get("donor_phone") == null && p.get("donor_email") == null) {
				return;
			}
			String temple = jdbc.queryForObject("""
					SELECT name FROM tenants WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""", String.class);
			notificationService.notify(
					NotificationRecipient.contact((String) p.get("donor_phone"), (String) p.get("donor_email")),
					NotificationTemplate.RECURRING_CHARGE_FAILED,
					Map.of("temple", temple == null ? "the temple" : temple), null);
		} catch (RuntimeException e) {
			log.warn("Could not notify failed recurring cycle for {}: {}", subscriptionId, e.toString());
		} finally {
			TenantContext.clear();
		}
	}

	// ---------------------------------------------------------------------

	private Snapshot resolveDonor(AuthenticatedUser actor, CreateRecurringRequest req) {
		if (!req.consent()) {
			throw new ApplicationException(ErrorCode.DONOR_CONSENT_REQUIRED, Map.of());
		}
		String name = req.name() != null && !req.name().isBlank() ? req.name().trim() : actor.getFullName();
		String phone = trimToNull(req.phone());
		String email = trimToNull(req.email());
		if (!req.wants80g()) {
			return new Snapshot(name, phone, email, null, null, false, null, null);
		}
		Boolean approved = jdbc.queryForObject("""
				SELECT is_80g_approved FROM tenants WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", Boolean.class);
		if (!Boolean.TRUE.equals(approved)) {
			throw new ApplicationException(ErrorCode.DONOR_80G_NOT_AVAILABLE, Map.of());
		}
		String address = trimToNull(req.address());
		String pan = req.pan() == null ? null : req.pan().trim().toUpperCase();
		if (address == null) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "address"));
		}
		if (pan == null || !PAN.matcher(pan).matches()) {
			throw new ApplicationException(ErrorCode.INVALID_PAN, Map.of());
		}
		return new Snapshot(name, phone, email, address, panCipher.encrypt(pan), true, "80G",
				panCipher.fingerprint(pan));
	}

	private String requireOwned(UUID planId, UUID userId) {
		try {
			return jdbc.queryForObject("""
					SELECT provider_subscription_id FROM recurring_plans WHERE id = ? AND donor_account_user_id = ?
					""", String.class, planId, userId);
		} catch (EmptyResultDataAccessException e) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("recurringPlanId", planId), e);
		}
	}

	private Plan locate(String subscriptionId) {
		TenantContext.setWebhookMessageId(subscriptionId);
		try {
			List<Plan> rows = jdbc.query(
					"SELECT id, tenant_id FROM recurring_plans WHERE provider_subscription_id = ?",
					(rs, n) -> new Plan(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class)),
					subscriptionId);
			return rows.isEmpty() ? null : rows.get(0);
		} finally {
			TenantContext.clearWebhookMessageId();
		}
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

	private record Snapshot(String name, String phone, String email, String address,
			byte[] panCiphertext, boolean wants80g, String section, String panFingerprint) {
	}

	private record Plan(UUID id, UUID tenantId) {
	}

	/** A cycle donation on the plan's history (E7-S3). */
	public record DonationView(UUID id, BigDecimal amountInr, String status, String paymentMode, java.time.Instant at) {
	}
}
