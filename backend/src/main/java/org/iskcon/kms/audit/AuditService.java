package org.iskcon.kms.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place the application writes to the audit log.
 *
 * <p>Modules call {@link #record} rather than inserting rows themselves, so the shape of an
 * audit event, and the rule that it is attributed to the active tenant, live in exactly one
 * place (SYSTEM_DESIGN.md §5: "written by the shared kernel, not per-module ad-hoc").
 *
 * <p><strong>Tenancy is taken from the connection, not from an argument.</strong> The row's
 * {@code tenant_id} is filled from {@code current_setting('app.tenant_id')} — the very setting
 * RLS checks. A caller therefore cannot attribute an event to a tenant it is not already
 * operating within: if the context says temple A, the event is temple A's, and if there is no
 * context the insert fails closed against the {@code NOT NULL} column. This is why provisioning
 * (which sets the new tenant's context transaction-locally before calling here) and an ordinary
 * tenant request both land the event in the right place with no special-casing.
 *
 * <p><strong>Two propagation modes, for two needs.</strong> {@link #record} joins the caller's
 * transaction, so a provisioning event or a successful role change is atomic with the action it
 * describes — no action without its audit, no audit without its action. {@link #recordSeparately}
 * commits in its own transaction, for the one case where an event must outlive the caller's
 * rollback: a <em>refused</em> attempt, recorded and then followed by a thrown 403.
 */
@Service
public class AuditService {

	private static final String INSERT = """
			INSERT INTO audit_events (
				tenant_id, actor_user_id, actor_label, action, entity_type, entity_id,
				before_state, after_state, reason)
			VALUES (
				NULLIF(current_setting('app.tenant_id', true), '')::uuid,
				?, ?, ?, ?, ?, ?, ?, ?)
			""";

	// The platform log carries no tenant_id: its events belong to no temple (E1-S14). The
	// parameter list is otherwise identical, so the same binding serves both.
	private static final String PLATFORM_INSERT = """
			INSERT INTO platform_audit_events (
				actor_user_id, actor_label, action, entity_type, entity_id,
				before_state, after_state, reason)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?)
			""";

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;

	public AuditService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
	}

	/**
	 * Records one auditable action.
	 *
	 * @param actor       who did it; their identity and role are captured into {@code actor_label}
	 * @param action      what kind of action (the stored vocabulary)
	 * @param entityType  what kind of thing it was about
	 * @param entityId    the id of that thing
	 * @param beforeState prior state, serialized to JSONB; null for a creation or a pure access
	 * @param afterState  resulting state, serialized to JSONB; null for a pure access record
	 * @param reason      optional human context (e.g. why an attempt was refused)
	 */
	@Transactional
	public void record(
			AuthenticatedUser actor,
			AuditAction action,
			AuditEntityType entityType,
			UUID entityId,
			Map<String, ?> beforeState,
			Map<String, ?> afterState,
			String reason) {

		write(INSERT, actor, action, entityType, entityId, beforeState, afterState, reason);
	}

	/**
	 * Records an event in its own committed transaction, independent of the caller's.
	 *
	 * <p>For events that must survive the caller then rolling back — a <em>refused</em> role
	 * change records the attempt and then throws a 403, and the record of the refusal must not
	 * vanish with the exception. {@code REQUIRES_NEW} suspends the caller's transaction and commits
	 * this event on its own; because a fresh connection re-reads the tenant from the request's
	 * thread-local context on checkout, the event still lands in the acting tenant.
	 *
	 * <p>Not for use where the context is only transaction-local (provisioning): a new transaction
	 * would not inherit that, and the write would fail closed. Those callers use {@link #record}.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordSeparately(
			AuthenticatedUser actor,
			AuditAction action,
			AuditEntityType entityType,
			UUID entityId,
			Map<String, ?> beforeState,
			Map<String, ?> afterState,
			String reason) {

		write(INSERT, actor, action, entityType, entityId, beforeState, afterState, reason);
	}

	/**
	 * Records a platform-level action — one belonging to no temple — in {@code platform_audit_events}.
	 *
	 * <p>The counterpart to {@link #record} for the platform super-admin, who sits outside every
	 * tenant and so cannot use the tenant-scoped log (E1-S14). The row carries no {@code tenant_id};
	 * the table's RLS admits the write only when the connection's verified identity is a super-admin.
	 * Joins the caller's transaction, like {@link #record}.
	 */
	public void recordPlatform(
			AuthenticatedUser actor,
			AuditAction action,
			AuditEntityType entityType,
			UUID entityId,
			Map<String, ?> beforeState,
			Map<String, ?> afterState,
			String reason) {

		write(PLATFORM_INSERT, actor, action, entityType, entityId, beforeState, afterState, reason);
	}

	private void write(
			String sql,
			AuthenticatedUser actor,
			AuditAction action,
			AuditEntityType entityType,
			UUID entityId,
			Map<String, ?> beforeState,
			Map<String, ?> afterState,
			String reason) {

		String before = toJson(beforeState);
		String after = toJson(afterState);

		jdbc.update(sql, ps -> {
			ps.setObject(1, actor.getUserId());
			ps.setString(2, describe(actor));
			ps.setString(3, action.name());
			ps.setString(4, entityType.name());
			ps.setObject(5, entityId);
			setJson(ps, 6, before);
			setJson(ps, 7, after);
			ps.setString(8, reason);
		});
	}

	/**
	 * A self-contained description of the actor, captured at write time so the log stays legible
	 * without resolving a user row — which, for a super-admin acting inside a temple's context,
	 * RLS would in any case hide.
	 */
	private String describe(AuthenticatedUser actor) {
		return "%s <%s> (%s)".formatted(actor.getFullName(), actor.getEmail(), actor.getRole());
	}

	private String toJson(Map<String, ?> value) {
		if (value == null) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException e) {
			// Our own before/after maps failing to serialize is a programming error, not a user
			// one. Surfacing it fails the surrounding action rather than dropping the audit — we
			// would rather not perform an action than perform it unrecorded.
			throw new ApplicationException(ErrorCode.UNEXPECTED_FAILURE, Map.of(), e);
		}
	}

	/**
	 * Binds a JSON string to a {@code jsonb} column without the Postgres driver on the compile
	 * classpath. {@code Types.OTHER} tells the driver to send the value untyped; Postgres then
	 * coerces it to the column's {@code jsonb} type. A null maps to SQL NULL.
	 */
	private void setJson(PreparedStatement ps, int index, String json) throws SQLException {
		if (json == null) {
			ps.setNull(index, Types.OTHER);
		} else {
			ps.setObject(index, json, Types.OTHER);
		}
	}
}
