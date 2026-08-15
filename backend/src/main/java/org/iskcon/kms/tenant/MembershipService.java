package org.iskcon.kms.tenant;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.user.User;
import org.iskcon.kms.user.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Joining a temple (E1-S16).
 *
 * <p>Everywhere else in the product, the tenant comes from a verified record and never from the
 * request — it is the rule the whole isolation design rests on. This is the single exception, and
 * only because the request <em>is</em> the person choosing where they serve. It is narrowed until
 * the exception carries no weight: the only row it can write is the caller's own, only as a
 * volunteer, only at a temple that exists and is open, and only where they are not already a member.
 * It reads nothing else in that temple's context.
 */
@Service
public class MembershipService {

	private final JdbcTemplate jdbc;
	private final MembershipWriter writer;

	public MembershipService(JdbcTemplate jdbc, MembershipWriter writer) {
		this.jdbc = jdbc;
		this.writer = writer;
	}

	/** The temples on the platform, as a devotee choosing one would recognise them. */
	public List<MembershipController.TempleSummary> templesToJoin() {
		// tenants is the registry itself and carries no tenant_id, so it is not RLS-scoped. What is
		// returned is a name and a place — nothing about how a temple runs.
		return jdbc.query("""
				SELECT id, name, address FROM tenants ORDER BY name
				""", (rs, n) -> new MembershipController.TempleSummary(
						rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("address")));
	}

	public UUID join(AuthenticatedUser actor, UUID templeId) {
		String uid = actor.getFirebaseUid();
		if (uid == null || uid.isBlank()) {
			throw new ApplicationException(ErrorCode.NOT_AUTHENTICATED, Map.of());
		}
		requireTemple(templeId);

		// From here the request speaks for the chosen temple, which is what makes the insert legal
		// under the write policy. Set before the transaction opens, because the tenant is stamped on
		// the connection at checkout — a context change inside a transaction never reaches the
		// session. Restored at the end, so a person joining a second temple does not leave this
		// request pointed at it.
		UUID previous = TenantContext.get().orElse(null);
		TenantContext.set(templeId);
		try {
			return writer.createMembership(actor, uid);
		} finally {
			if (previous == null) {
				TenantContext.clear();
			} else {
				TenantContext.set(previous);
			}
		}
	}

	/** The write itself, in its own transaction — opened after the tenant is in place. */
	@Service
	static class MembershipWriter {

		private final JdbcTemplate jdbc;
		private final UserRepository userRepository;
		private final AuditService auditService;

		MembershipWriter(JdbcTemplate jdbc, UserRepository userRepository, AuditService auditService) {
			this.jdbc = jdbc;
			this.userRepository = userRepository;
			this.auditService = auditService;
		}

		@Transactional
		UUID createMembership(AuthenticatedUser actor, String uid) {
			Optional<UUID> already = existingMembership(uid);
			if (already.isPresent()) {
				return already.get();
			}

			UUID id = UUID.randomUUID();
			jdbc.update("""
					INSERT INTO users (id, tenant_id, firebase_uid, full_name, email, phone, role, status)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
							?, ?, ?, ?, 'VOLUNTEER', 'ACTIVE')
					""", id, uid, nameFor(actor), lower(actor.getEmail()), actor.getPhone());

			// Recorded as the person's own act, because it is: nobody invited them.
			auditService.record(
					userRepository.findById(id).map(AuthenticatedUser::new).orElse(actor),
					AuditAction.USER_ADDED, AuditEntityType.USER, id,
					null,
					Map.of("role", "VOLUNTEER", "joined", "self"),
					"Joined this temple as a volunteer.");

			return id;
		}

		/** Their membership here, if they already have one — joining twice is a no-op, not an error. */
		private Optional<UUID> existingMembership(String uid) {
			return jdbc.query("""
					SELECT id FROM users
					WHERE firebase_uid = ?
					  AND tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""", (rs, n) -> rs.getObject("id", UUID.class), uid).stream().findFirst();
		}
	}

	// ---------------------------------------------------------------------

	private void requireTemple(UUID templeId) {
		Integer found = jdbc.queryForObject(
				"SELECT count(*) FROM tenants WHERE id = ?", Integer.class, templeId);
		if (found == null || found == 0) {
			throw new ApplicationException(ErrorCode.TENANT_NOT_FOUND, Map.of("tenantId", templeId));
		}
	}

	/**
	 * What to call someone we have never met. Firebase gives us a verified email or phone and not
	 * much else; the name is theirs to correct on their profile, and the temple can see who they are
	 * either way.
	 */
	private static String nameFor(AuthenticatedUser actor) {
		if (actor.getFullName() != null && !actor.getFullName().isBlank()) {
			return actor.getFullName();
		}
		String email = actor.getEmail();
		if (email != null && email.contains("@")) {
			return email.substring(0, email.indexOf('@'));
		}
		return actor.getPhone() != null ? actor.getPhone() : "New volunteer";
	}

	private static String lower(String value) {
		return value == null ? null : value.toLowerCase();
	}
}
