package org.iskcon.kms.notification;

import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.tenancy.TenantContext;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whose name is on an email, and where a reply to it goes (E1).
 *
 * <p>Small on purpose. Sending is the platform's — one address, one domain, one set of SPF and DKIM
 * records — and the only things that vary per temple are the name a devotee reads in the From line
 * and the address their reply reaches. Both come from here.
 */
@Service
public class TenantEmailIdentityService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public TenantEmailIdentityService(JdbcTemplate jdbc, AuditService auditService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	/** The temple this message belongs to, or an empty identity outside any tenant. */
	@Transactional(readOnly = true)
	public Identity current() {
		if (TenantContext.get().isEmpty()) {
			return new Identity(null, null);
		}
		try {
			return jdbc.queryForObject("""
					SELECT t.name AS temple_name, s.contact_email
					FROM tenants t
					LEFT JOIN tenant_settings s ON s.tenant_id = t.id
					WHERE t.id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""", (rs, n) -> new Identity(rs.getString("temple_name"), rs.getString("contact_email")));
		} catch (EmptyResultDataAccessException noTemple) {
			return new Identity(null, null);
		}
	}

	/** What a temple administrator sees and can change. */
	@Transactional(readOnly = true)
	public String readContactEmail() {
		return current().replyTo();
	}

	/**
	 * Sets the address replies come back to.
	 *
	 * <p>Blank clears it, which is allowed: a temple that would rather not publish an address still
	 * gets its messages sent, and a reply simply arrives at the platform instead.
	 */
	@Transactional
	public String saveContactEmail(AuthenticatedUser actor, String email) {
		UUID tenantId = TenantContext.get().orElseThrow(
				() -> new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "tenant")));
		String cleaned = email == null || email.isBlank() ? null : email.trim();

		jdbc.update("""
				INSERT INTO tenant_settings (tenant_id, contact_email, updated_at)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, now())
				ON CONFLICT (tenant_id) DO UPDATE SET contact_email = EXCLUDED.contact_email, updated_at = now()
				""", cleaned);

		auditService.record(actor, AuditAction.SETTINGS_UPDATED, AuditEntityType.TENANT, tenantId,
				null, Map.of("contactEmail", cleaned == null ? "" : cleaned),
				"Temple contact email updated.");
		return cleaned;
	}

	/**
	 * @param templeName the name a devotee reads in the From line
	 * @param replyTo    where their reply goes; null means it comes back to the platform
	 */
	public record Identity(String templeName, String replyTo) {
	}
}
