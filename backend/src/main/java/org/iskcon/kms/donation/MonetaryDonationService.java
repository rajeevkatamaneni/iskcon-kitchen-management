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

	public MonetaryDonationService(JdbcTemplate jdbc, PanCipher panCipher, AuditService auditService) {
		this.jdbc = jdbc;
		this.panCipher = panCipher;
		this.auditService = auditService;
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
}
