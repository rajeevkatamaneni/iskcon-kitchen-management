package org.iskcon.kms.donation;

import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
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
 * Monetary donations and the 80G donor-capture rules (E7-S4). Creates the local donation record with
 * exactly the fields the donor's chosen path allows — anonymous keeps zero PII, 80G captures name,
 * address and PAN (encrypted). The payment lifecycle (order, webhook confirmation) is layered on by
 * E7-S2/S3/S6; this owns the record and the donor-data integrity.
 */
@Service
public class MonetaryDonationService {

	private static final Pattern PAN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
	private static final int PENDING_TTL_MINUTES = 30;

	private final JdbcTemplate jdbc;
	private final PanCipher panCipher;
	private final AuditService auditService;
	private final org.iskcon.kms.payment.PaymentGateway paymentGateway;
	private final org.iskcon.kms.notification.NotificationService notificationService;

	public MonetaryDonationService(JdbcTemplate jdbc, PanCipher panCipher, AuditService auditService,
			org.iskcon.kms.payment.PaymentGateway paymentGateway,
			org.iskcon.kms.notification.NotificationService notificationService) {
		this.jdbc = jdbc;
		this.panCipher = panCipher;
		this.auditService = auditService;
		this.paymentGateway = paymentGateway;
		this.notificationService = notificationService;
	}

	/**
	 * Opens a one-time donation (E7-S2): creates the provider order, records a PENDING donation
	 * against it, and returns what the client needs to open hosted checkout. Confirmation is by
	 * webhook, never by the client's redirect — a PENDING record only becomes COMPLETED when the
	 * signed webhook says so.
	 */
	@Transactional
	public DonationCheckout startCheckout(DonorDetails donor, java.math.BigDecimal amountInr, UUID wishlistItemId) {
		long minorUnits = amountInr.movePointRight(2).longValueExact();
		String idempotencyKey = UUID.randomUUID().toString();
		org.iskcon.kms.payment.PaymentOrder order = paymentGateway.createOrder(
				minorUnits, "INR", "donation-" + idempotencyKey, Map.of("idempotencyKey", idempotencyKey));
		UUID donationId = createDonation(new DonationDraft("ONE_TIME", amountInr, paymentGateway.name(),
				order.orderId(), idempotencyKey, wishlistItemId, null, null, donor));
		return new DonationCheckout(donationId, order.orderId(), paymentGateway.publicKey(),
				amountInr, "INR", paymentGateway.name());
	}

	/** Confirms a donation from a captured-payment webhook (E7-S2). Idempotent: only PENDING advances. */
	public void completePayment(String orderId, String paymentId, String method) {
		Located located = locateByOrder(orderId);
		if (located == null || !"PENDING".equals(located.status())) {
			return; // unknown order, or already terminal
		}
		org.iskcon.kms.tenancy.TenantContext.set(located.tenantId());
		try {
			int updated = jdbc.update("""
					UPDATE donations SET status = 'COMPLETED', provider_payment_id = ?, payment_mode = ?
					WHERE id = ? AND status = 'PENDING'
					""", paymentId, method, located.id());
			if (updated > 0) {
				sendThankYou(located.id());
			}
		} finally {
			org.iskcon.kms.tenancy.TenantContext.clear();
		}
	}

	/** Marks a donation FAILED from a failed-payment webhook (E7-S2). Idempotent. */
	public void failPayment(String orderId) {
		Located located = locateByOrder(orderId);
		if (located == null || !"PENDING".equals(located.status())) {
			return;
		}
		org.iskcon.kms.tenancy.TenantContext.set(located.tenantId());
		try {
			jdbc.update("UPDATE donations SET status = 'FAILED' WHERE id = ? AND status = 'PENDING'", located.id());
		} finally {
			org.iskcon.kms.tenancy.TenantContext.clear();
		}
	}

	/** Expires the current tenant's abandoned PENDING online donations (E7-S2 cleanup sweep). */
	@Transactional
	public int expirePendingForCurrentTenant() {
		return jdbc.update("""
				UPDATE donations SET status = 'EXPIRED'
				WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at < now()
				""");
	}

	/** Creates a PENDING monetary donation from a draft, applying the donor path rules. */
	@Transactional
	public UUID createDonation(DonationDraft draft) {
		DonorDetails d = draft.donor();
		Resolved r = resolveDonor(d);

		UUID id = UUID.randomUUID();
		jdbc.update(connection -> {
			PreparedStatement ps = connection.prepareStatement("""
					INSERT INTO donations (
						id, tenant_id, type, amount_inr, currency, status, is_anonymous,
						donor_name, donor_phone, donor_email, donor_address, donor_pan_ciphertext,
						wants_80g, section, consent_at, provider, provider_order_id, idempotency_key,
						wishlist_item_id, recurring_plan_id, donor_account_user_id, donated_on, expires_at)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, 'INR', 'PENDING', ?,
						?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_DATE,
						now() + (interval '1 minute' * ?))
					""");
			ps.setObject(1, id);
			ps.setString(2, draft.type());
			ps.setBigDecimal(3, draft.amountInr());
			ps.setBoolean(4, d.anonymous());
			ps.setString(5, r.name());
			ps.setString(6, r.phone());
			ps.setString(7, r.email());
			ps.setString(8, r.address());
			ps.setBytes(9, r.panCiphertext());
			ps.setBoolean(10, r.wants80g());
			ps.setString(11, r.section());
			ps.setObject(12, r.consentAt());
			ps.setString(13, draft.provider());
			ps.setString(14, draft.providerOrderId());
			ps.setString(15, draft.idempotencyKey());
			ps.setObject(16, draft.wishlistItemId());
			ps.setObject(17, draft.recurringPlanId());
			ps.setObject(18, draft.donorAccountUserId());
			ps.setInt(19, PENDING_TTL_MINUTES);
			return ps;
		});
		return id;
	}

	/** Decrypts a donor's PAN for a Temple Admin, recording the access. Null if the donation has none. */
	@Transactional
	public String revealPan(UUID donationId, AuthenticatedUser actor) {
		byte[] ciphertext;
		try {
			ciphertext = jdbc.queryForObject(
					"SELECT donor_pan_ciphertext FROM donations WHERE id = ?", byte[].class, donationId);
		} catch (EmptyResultDataAccessException e) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("donationId", donationId), e);
		}
		if (ciphertext == null) {
			return null;
		}
		String pan = panCipher.decrypt(ciphertext);
		auditService.record(actor, AuditAction.DONOR_PAN_VIEWED, AuditEntityType.DONATION, donationId,
				null, Map.of("panLast4", pan.substring(Math.max(0, pan.length() - 4))), null);
		return pan;
	}

	/** The Form 10BD-shaped dataset for completed 80G donations (E7-S4 contract for the Phase-2 export). */
	@Transactional(readOnly = true)
	public List<Form10bdRow> form10bdRows() {
		return jdbc.query("""
				SELECT donor_name, donor_address, donor_pan_ciphertext, amount_inr, payment_mode, section
				FROM donations WHERE wants_80g = true AND status = 'COMPLETED' ORDER BY created_at
				""", (rs, n) -> {
			byte[] ct = rs.getBytes("donor_pan_ciphertext");
			return new Form10bdRow(rs.getString("donor_name"), rs.getString("donor_address"),
					ct == null ? null : panCipher.decrypt(ct), rs.getBigDecimal("amount_inr"),
					rs.getString("payment_mode"), rs.getString("section"));
		});
	}

	// ---------------------------------------------------------------------

	private Resolved resolveDonor(DonorDetails d) {
		if (d.anonymous()) {
			return new Resolved(null, null, null, null, null, false, null, null);
		}
		String name = trimToNull(d.name());
		if (name == null) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "name"));
		}
		if (!d.consent()) {
			throw new ApplicationException(ErrorCode.DONOR_CONSENT_REQUIRED, Map.of());
		}
		OffsetDateTime consentAt = OffsetDateTime.now();
		String phone = trimToNull(d.phone());
		String email = trimToNull(d.email());

		if (!d.wants80g()) {
			return new Resolved(name, phone, email, null, null, false, null, consentAt);
		}
		if (!tenantIs80gApproved()) {
			throw new ApplicationException(ErrorCode.DONOR_80G_NOT_AVAILABLE, Map.of());
		}
		String address = trimToNull(d.address());
		if (address == null) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "address"));
		}
		String pan = d.pan() == null ? null : d.pan().trim().toUpperCase();
		if (pan == null || !PAN.matcher(pan).matches()) {
			throw new ApplicationException(ErrorCode.INVALID_PAN, Map.of());
		}
		return new Resolved(name, phone, email, address, panCipher.encrypt(pan), true, "80G", consentAt);
	}

	/** Finds a donation by its provider order id through the webhook RLS escape, before the tenant is known. */
	private Located locateByOrder(String orderId) {
		org.iskcon.kms.tenancy.TenantContext.setWebhookMessageId(orderId);
		try {
			List<Located> rows = jdbc.query(
					"SELECT id, tenant_id, status FROM donations WHERE provider_order_id = ?",
					(rs, n) -> new Located(rs.getObject("id", UUID.class),
							rs.getObject("tenant_id", UUID.class), rs.getString("status")), orderId);
			return rows.isEmpty() ? null : rows.get(0);
		} finally {
			org.iskcon.kms.tenancy.TenantContext.clearWebhookMessageId();
		}
	}

	/** Best-effort acknowledgement to a reachable, non-anonymous donor (E7-S2). Not the 80G certificate. */
	private void sendThankYou(UUID donationId) {
		try {
			Map<String, Object> d = jdbc.queryForMap("""
					SELECT donor_name, donor_phone, donor_email, is_anonymous FROM donations WHERE id = ?
					""", donationId);
			if ((Boolean) d.get("is_anonymous") || (d.get("donor_phone") == null && d.get("donor_email") == null)) {
				return;
			}
			String temple = jdbc.queryForObject("""
					SELECT name FROM tenants WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""", String.class);
			notificationService.notify(
					org.iskcon.kms.notification.NotificationRecipient.contact(
							(String) d.get("donor_phone"), (String) d.get("donor_email")),
					org.iskcon.kms.notification.NotificationTemplate.DONATION_THANK_YOU,
					Map.of("donor", d.get("donor_name") == null ? "" : d.get("donor_name").toString(),
							"temple", temple == null ? "the temple" : temple,
							"date", java.time.LocalDate.now().toString()),
					null);
			jdbc.update("UPDATE donations SET acknowledged_at = now() WHERE id = ?", donationId);
		} catch (RuntimeException e) {
			// The gift is recorded; a thank-you we couldn't queue must not undo that.
		}
	}

	private boolean tenantIs80gApproved() {
		Boolean approved = jdbc.queryForObject("""
				SELECT is_80g_approved FROM tenants WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", Boolean.class);
		return Boolean.TRUE.equals(approved);
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	private record Resolved(String name, String phone, String email, String address,
			byte[] panCiphertext, boolean wants80g, String section, OffsetDateTime consentAt) {
	}

	private record Located(UUID id, UUID tenantId, String status) {
	}
}
