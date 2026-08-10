package org.iskcon.kms.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the audit log for the viewer.
 *
 * <p>Reads never carry a tenant filter in their SQL: RLS supplies it. For a Temple Admin the
 * current tenant context is their own temple, so {@link #forCurrentTenant} sees only their
 * temple's events — a forgotten filter cannot widen that. For a super-admin there is no ambient
 * tenant, so {@link #asPlatformOperator} establishes a chosen temple's context transaction-locally
 * (the same mechanism provisioning uses) and reads within it — one temple at a time, never a
 * cross-tenant feed, and the drill-in is itself recorded.
 */
@Service
public class AuditQueryService {

	private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;
	private final AuditService auditService;

	public AuditQueryService(JdbcTemplate jdbc, ObjectMapper objectMapper, AuditService auditService) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
		this.auditService = auditService;
	}

	/** The current tenant's log, as a Temple Admin sees their own temple's history. */
	@Transactional(readOnly = true)
	public AuditPage forCurrentTenant(AuditQuery query) {
		return page(query);
	}

	/**
	 * A super-admin drilling into one temple's log.
	 *
	 * <p>The chosen tenant's context is set transaction-locally so RLS scopes the read to exactly
	 * that temple — the operator holds no BYPASSRLS, so this is the only way they see it, and they
	 * see one temple, not all. The access is recorded in that temple's own log (once per drill-in,
	 * not per page) so an operator reading a temple's history is never invisible to the temple.
	 */
	@Transactional
	public AuditPage asPlatformOperator(AuthenticatedUser operator, UUID tenantId, AuditQuery query) {
		jdbc.queryForObject(
				"SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());

		AuditPage page = page(query);

		// Only the initial view is recorded; paging deeper into the same drill-in is not a fresh
		// access. Without this, scrolling would bury the log in the operator's own footprints.
		if (query.cursor() == null) {
			auditService.record(
					operator, AuditAction.AUDIT_LOG_VIEWED, AuditEntityType.AUDIT_LOG, tenantId,
					null, null, "platform operator viewed this temple's audit log");
		}

		return page;
	}

	private AuditPage page(AuditQuery query) {
		StringBuilder sql = new StringBuilder(
				"SELECT id, actor_user_id, actor_label, action, entity_type, entity_id, "
						+ "before_state, after_state, reason, created_at FROM audit_events WHERE TRUE");
		List<Object> params = new ArrayList<>();

		if (query.from() != null) {
			sql.append(" AND created_at >= ?");
			params.add(atUtc(query.from()));
		}
		if (query.to() != null) {
			sql.append(" AND created_at <= ?");
			params.add(atUtc(query.to()));
		}
		if (query.action() != null) {
			sql.append(" AND action = ?");
			params.add(query.action());
		}
		if (query.actorUserId() != null) {
			sql.append(" AND actor_user_id = ?");
			params.add(query.actorUserId());
		}
		if (query.cursor() != null) {
			// Row comparison against the tie-broken sort key: everything strictly "older" than the
			// last row of the previous page, in the same DESC order the index is built for.
			sql.append(" AND (created_at, id) < (?, ?)");
			params.add(atUtc(query.cursor().createdAt()));
			params.add(query.cursor().id());
		}

		// One more than asked, to learn whether a further page exists without a second query.
		sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
		params.add(query.limit() + 1);

		List<AuditEventView> rows = jdbc.query(sql.toString(), this::mapRow, params.toArray());

		if (rows.size() > query.limit()) {
			AuditEventView last = rows.get(query.limit() - 1);
			return new AuditPage(
					rows.subList(0, query.limit()),
					new AuditCursor(last.createdAt(), last.id()).encode());
		}
		return new AuditPage(rows, null);
	}

	private AuditEventView mapRow(ResultSet rs, int rowNum) throws SQLException {
		return new AuditEventView(
				rs.getObject("id", UUID.class),
				rs.getString("action"),
				rs.getString("entity_type"),
				rs.getObject("entity_id", UUID.class),
				rs.getObject("actor_user_id", UUID.class),
				rs.getString("actor_label"),
				parseJson(rs.getString("before_state")),
				parseJson(rs.getString("after_state")),
				rs.getString("reason"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant());
	}

	private Map<String, Object> parseJson(String json) {
		if (json == null) {
			return null;
		}
		try {
			return objectMapper.readValue(json, JSON_OBJECT);
		} catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			// The column only ever holds JSON this application wrote. If it does not parse, that is
			// corruption worth surfacing, not swallowing.
			throw new IllegalStateException("Unreadable audit JSON in row", e);
		}
	}

	private static OffsetDateTime atUtc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
