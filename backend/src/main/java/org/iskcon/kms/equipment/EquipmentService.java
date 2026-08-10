package org.iskcon.kms.equipment;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Equipment inventory (E3-S4): durable assets tracked by condition, not quantity.
 *
 * <p>An item's condition changes only through {@link #changeCondition}, which records why and by whom
 * in an append-only history — "sent for repair", "scrapped" — so the state of a temple's assets is
 * always explainable. Descriptive edits go through {@link #update} and never touch condition.
 * SCRAPPED is terminal: a scrapped item keeps its history but drops out of the default list.
 */
@Service
public class EquipmentService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public EquipmentService(JdbcTemplate jdbc, AuditService auditService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	@Transactional(readOnly = true)
	public List<EquipmentView> list(boolean includeScrapped, EquipmentCategory category, String location) {
		StringBuilder sql = new StringBuilder(SELECT + " WHERE 1 = 1");
		List<Object> args = new ArrayList<>();
		if (!includeScrapped) {
			sql.append(" AND condition <> 'SCRAPPED'");
		}
		if (category != null) {
			sql.append(" AND category = ?");
			args.add(category.name());
		}
		if (location != null && !location.isBlank()) {
			sql.append(" AND storage_location = ?");
			args.add(location.trim());
		}
		sql.append(" ORDER BY name");
		return jdbc.query(sql.toString(), MAPPER, args.toArray());
	}

	@Transactional(readOnly = true)
	public EquipmentDetailView get(UUID id) {
		EquipmentView equipment = findById(id).orElseThrow(() -> notFound(id));
		List<EquipmentStateChange> history = jdbc.query("""
				SELECT c.id, c.from_condition, c.to_condition, c.reason,
					   c.actor_user_id, u.full_name AS actor_name, c.created_at
				FROM equipment_state_changes c
				LEFT JOIN users u ON u.id = c.actor_user_id
				WHERE c.equipment_id = ?
				ORDER BY c.created_at DESC, c.id DESC
				""", HISTORY_MAPPER, id);
		return new EquipmentDetailView(equipment, history);
	}

	@Transactional
	public UUID create(AuthenticatedUser actor, CreateEquipmentRequest request) {
		EquipmentCondition condition = request.condition() == null
				? EquipmentCondition.GOOD : request.condition();
		UUID id = UUID.randomUUID();

		jdbc.update(connection -> {
			var ps = connection.prepareStatement("""
					INSERT INTO equipment_items (
						id, tenant_id, name, category, storage_location, condition,
						acquisition_date, source, notes)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?, ?)
					""");
			ps.setObject(1, id);
			ps.setString(2, request.name().trim());
			ps.setString(3, request.category().name());
			ps.setString(4, trimToNull(request.storageLocation()));
			ps.setString(5, condition.name());
			ps.setObject(6, request.acquisitionDate());
			ps.setString(7, request.source() == null ? null : request.source().name());
			ps.setString(8, trimToNull(request.notes()));
			return ps;
		});

		// Seed the history so an item's condition always has a recorded origin.
		recordStateChange(actor, id, null, condition, "Registered");

		auditService.record(actor, AuditAction.EQUIPMENT_ADDED, AuditEntityType.EQUIPMENT, id,
				null, snapshot(request.name().trim(), request.category(), condition), null);
		return id;
	}

	@Transactional
	public void update(AuthenticatedUser actor, UUID id, UpdateEquipmentRequest request) {
		EquipmentView before = findById(id).orElseThrow(() -> notFound(id));

		jdbc.update("""
				UPDATE equipment_items
				SET name = ?, category = ?, storage_location = ?, acquisition_date = ?,
					source = ?, notes = ?, updated_at = now()
				WHERE id = ?
				""",
				request.name().trim(), request.category().name(), trimToNull(request.storageLocation()),
				request.acquisitionDate(), request.source() == null ? null : request.source().name(),
				trimToNull(request.notes()), id);

		auditService.record(actor, AuditAction.EQUIPMENT_UPDATED, AuditEntityType.EQUIPMENT, id,
				snapshot(before.name(), before.category(), before.condition()),
				snapshot(request.name().trim(), request.category(), before.condition()), null);
	}

	@Transactional
	public void changeCondition(AuthenticatedUser actor, UUID id, ChangeConditionRequest request) {
		EquipmentView before = findById(id).orElseThrow(() -> notFound(id));
		if (before.condition() == EquipmentCondition.SCRAPPED) {
			throw new ApplicationException(ErrorCode.EQUIPMENT_SCRAPPED, Map.of("equipmentId", id));
		}
		if (before.condition() == request.condition()) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "condition", "value", request.condition().name()));
		}

		jdbc.update("UPDATE equipment_items SET condition = ?, updated_at = now() WHERE id = ?",
				request.condition().name(), id);
		recordStateChange(actor, id, before.condition(), request.condition(), request.reason().trim());

		auditService.record(actor, AuditAction.EQUIPMENT_CONDITION_CHANGED, AuditEntityType.EQUIPMENT, id,
				Map.of("name", before.name(), "condition", before.condition().name()),
				Map.of("name", before.name(), "condition", request.condition().name()),
				request.reason().trim());
	}

	// ---------------------------------------------------------------------

	private void recordStateChange(
			AuthenticatedUser actor, UUID equipmentId, EquipmentCondition from, EquipmentCondition to,
			String reason) {
		jdbc.update(connection -> {
			var ps = connection.prepareStatement("""
					INSERT INTO equipment_state_changes (
						tenant_id, equipment_id, from_condition, to_condition, reason, actor_user_id)
					VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?)
					""");
			ps.setObject(1, equipmentId);
			ps.setString(2, from == null ? null : from.name());
			ps.setString(3, to.name());
			ps.setString(4, reason);
			ps.setObject(5, actor.getUserId());
			return ps;
		});
	}

	private Optional<EquipmentView> findById(UUID id) {
		return jdbc.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
	}

	private Map<String, Object> snapshot(String name, EquipmentCategory category, EquipmentCondition condition) {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("name", name);
		s.put("category", category.name());
		s.put("condition", condition.name());
		return s;
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private ApplicationException notFound(UUID id) {
		return new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("equipmentId", id));
	}

	private static EquipmentCondition condition(String value) {
		return value == null ? null : EquipmentCondition.valueOf(value);
	}

	private static final String SELECT = """
			SELECT id, name, category, storage_location, condition, acquisition_date, source, notes, created_at
			FROM equipment_items
			""";

	private static final RowMapper<EquipmentView> MAPPER = (rs, n) -> new EquipmentView(
			rs.getObject("id", UUID.class),
			rs.getString("name"),
			EquipmentCategory.valueOf(rs.getString("category")),
			rs.getString("storage_location"),
			EquipmentCondition.valueOf(rs.getString("condition")),
			rs.getObject("acquisition_date", LocalDate.class),
			rs.getString("source") == null ? null : EquipmentSource.valueOf(rs.getString("source")),
			rs.getString("notes"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());

	private static final RowMapper<EquipmentStateChange> HISTORY_MAPPER = (rs, n) -> new EquipmentStateChange(
			rs.getObject("id", UUID.class),
			condition(rs.getString("from_condition")),
			EquipmentCondition.valueOf(rs.getString("to_condition")),
			rs.getString("reason"),
			rs.getObject("actor_user_id", UUID.class),
			rs.getString("actor_name"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());
}
