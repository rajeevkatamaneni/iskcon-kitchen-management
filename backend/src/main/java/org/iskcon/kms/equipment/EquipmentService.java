package org.iskcon.kms.equipment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
import org.iskcon.kms.shift.TenantSettingsService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Equipment inventory (E3-S4), and its servicing (E3-S10): durable assets tracked by condition, not
 * quantity.
 *
 * <p>An item's condition changes only through {@link #changeCondition}, which records why and by
 * whom in an append-only history — "sent for repair", "scrapped" — so the state of a temple's assets
 * is always explainable. Descriptive edits go through {@link #update} and never touch condition.
 * SCRAPPED is terminal: a scrapped item keeps its history but drops out of the default list.
 *
 * <p><strong>A service is a second kind of event, kept apart from the first.</strong> A wet grinder
 * can be serviced every six months for five years without its condition ever moving off GOOD, so
 * {@link #recordService} writes to its own append-only table rather than into the condition trail.
 * "Last serviced" is read from the newest row there and can never be typed: there is no field for
 * it anywhere, which is the point — the moment somebody can type over it, the previous service has
 * never happened.
 *
 * <p><strong>Nothing about the next service is stored.</strong> {@link #derive} works it out on
 * every read from three facts that are already here: the interval, the newest service (or, failing
 * that, the acquisition date), and the temple's own warning horizon. A stored next-service column
 * would go stale the moment an interval changed and would need a backfill nobody would remember to
 * run (E3-S10 D4).
 */
@Service
public class EquipmentService {

	// "Today" is the temple's today, for the same India-first reason InventoryItemService gives:
	// a machine falls due against the Indian calendar day, not the server's UTC one. A service
	// recorded at nine in the evening in Bengaluru must not be a service recorded tomorrow.
	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final TenantSettingsService tenantSettings;

	public EquipmentService(
			JdbcTemplate jdbc, AuditService auditService, TenantSettingsService tenantSettings) {
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.tenantSettings = tenantSettings;
	}

	@Transactional(readOnly = true)
	public List<EquipmentView> list(
			boolean includeScrapped, EquipmentCategory category, String location,
			ServiceStatus serviceStatus) {

		StringBuilder sql = new StringBuilder(SELECT + " WHERE 1 = 1");
		List<Object> args = new ArrayList<>();
		if (!includeScrapped) {
			sql.append(" AND e.condition <> 'SCRAPPED'");
		}
		if (category != null) {
			sql.append(" AND e.category = ?");
			args.add(category.name());
		}
		if (location != null && !location.isBlank()) {
			sql.append(" AND e.storage_location = ?");
			args.add(location.trim());
		}
		sql.append(" ORDER BY e.name");

		List<EquipmentView> items = jdbc.query(sql.toString(), mapper(), args.toArray());

		// The service filter is applied here and not in the WHERE clause, because the thing being
		// filtered on does not exist in the database — it is derived from the interval, the newest
		// service and the temple's horizon (D4). Pushing it into SQL would mean writing the
		// derivation a second time, in a second language, and keeping the two in step forever.
		// The register is tens of rows, not thousands.
		if (serviceStatus == null) {
			return items;
		}
		return items.stream().filter(item -> item.serviceStatus() == serviceStatus).toList();
	}

	/** Everything past its next service date, for the Temple Admin's dashboard (E3-S10 D5, D6). */
	@Transactional(readOnly = true)
	public List<EquipmentView> overdueForService() {
		return list(false, null, null, ServiceStatus.OVERDUE);
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

		List<EquipmentServiceRecord> services = jdbc.query("""
				SELECT s.id, s.serviced_on, s.service_provider_id, p.name AS service_provider_name,
					   s.work_done, s.cost_inr, s.actor_user_id, u.full_name AS actor_name, s.created_at
				FROM equipment_services s
				LEFT JOIN service_providers p ON p.id = s.service_provider_id
				LEFT JOIN users u ON u.id = s.actor_user_id
				WHERE s.equipment_id = ?
				ORDER BY s.serviced_on DESC, s.created_at DESC, s.id DESC
				""", SERVICE_MAPPER, id);

		return new EquipmentDetailView(equipment, history, services);
	}

	@Transactional
	public UUID create(AuthenticatedUser actor, CreateEquipmentRequest request) {
		EquipmentCondition condition = request.condition() == null
				? EquipmentCondition.GOOD : request.condition();
		UUID id = UUID.randomUUID();

		String serial = trimToNull(request.serialNumber());
		insertItem(id, request, condition, serial);

		// Seed the history so an item's condition always has a recorded origin.
		recordStateChange(actor, id, null, condition, "Registered");

		auditService.record(actor, AuditAction.EQUIPMENT_ADDED, AuditEntityType.EQUIPMENT, id,
				null, snapshot(request.name().trim(), request.category(), condition), null);
		return id;
	}

	@Transactional
	public void update(AuthenticatedUser actor, UUID id, UpdateEquipmentRequest request) {
		EquipmentView before = findById(id).orElseThrow(() -> notFound(id));
		String serial = trimToNull(request.serialNumber());

		try {
			jdbc.update("""
					UPDATE equipment_items
					SET name = ?, category = ?, storage_location = ?, acquisition_date = ?,
						source = ?, notes = ?, serial_number = ?, purchase_cost_inr = ?,
						warranty_expiry = ?, updated_at = now()
					WHERE id = ?
					""",
					request.name().trim(), request.category().name(), trimToNull(request.storageLocation()),
					request.acquisitionDate(), request.source() == null ? null : request.source().name(),
					trimToNull(request.notes()), serial, request.purchaseCostInr(),
					request.warrantyExpiry(), id);
		} catch (DuplicateKeyException e) {
			throw serialAlreadyUsed(serial);
		}

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

	// ---- Servicing (E3-S10) ----------------------------------------------

	/**
	 * Sets, or clears, how often this thing must be serviced and who does it (E3-S10 D3, D7).
	 *
	 * <p>The count and the unit arrive separately and are stored as a day total plus the unit, so
	 * that "every six months" and "every ninety days" are both sayable and both come back in the
	 * words they were entered in. One without the other is refused: a day count with no unit cannot
	 * be shown back, and a unit with no count is not an interval. Both absent clears the schedule,
	 * which a temple that has decided a trestle table needs no servicing is entitled to say.
	 */
	@Transactional
	public void setServiceSchedule(AuthenticatedUser actor, UUID id, ServiceScheduleRequest request) {
		EquipmentView before = findById(id).orElseThrow(() -> notFound(id));

		boolean hasCount = request.intervalCount() != null;
		boolean hasUnit = request.intervalUnit() != null;
		if (hasCount != hasUnit) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of(
					"field", hasCount ? "intervalUnit" : "intervalCount",
					"reason", "an interval is a number and a unit, or neither"));
		}

		Integer intervalDays = hasCount ? request.intervalUnit().toDays(request.intervalCount()) : null;
		String unit = hasUnit ? request.intervalUnit().name() : null;
		UUID providerId = requireOwnProvider(request.serviceProviderId());

		jdbc.update("""
				UPDATE equipment_items
				SET service_interval_days = ?, service_interval_unit = ?, service_provider_id = ?,
					updated_at = now()
				WHERE id = ?
				""", intervalDays, unit, providerId, id);

		auditService.record(actor, AuditAction.EQUIPMENT_SERVICE_SCHEDULE_SET, AuditEntityType.EQUIPMENT,
				id,
				scheduleSnapshot(before.name(), before.serviceIntervalDays(), before.serviceIntervalUnit(),
						before.serviceProviderId()),
				scheduleSnapshot(before.name(), intervalDays, request.intervalUnit(), providerId),
				null);
	}

	/**
	 * Records one service that has happened (E3-S10 D2).
	 *
	 * <p>A date in the future is refused with KMS-4016: a service booked for next Tuesday has not
	 * happened, and a register that accepts it would report the grinder as looked after by a visit
	 * nobody has made. Checked here rather than by an annotation on the request because "future" is
	 * measured against the temple's own day.
	 *
	 * <p>Recording is allowed whatever the condition, scrapped included. It reads oddly and it is
	 * the right way round: the alternative refuses somebody entering last month's invoices because
	 * the machine has since been thrown away, and a service that happened happened.
	 */
	@Transactional
	public UUID recordService(AuthenticatedUser actor, UUID equipmentId, RecordServiceRequest request) {
		EquipmentView equipment = findById(equipmentId).orElseThrow(() -> notFound(equipmentId));

		LocalDate today = LocalDate.now(TEMPLE_ZONE);
		if (request.servicedOn().isAfter(today)) {
			throw new ApplicationException(ErrorCode.SERVICE_DATE_IN_FUTURE, Map.of(
					"equipmentId", equipmentId,
					"servicedOn", request.servicedOn().toString()));
		}

		UUID providerId = requireOwnProvider(request.serviceProviderId());
		UUID id = UUID.randomUUID();

		jdbc.update(connection -> {
			var ps = connection.prepareStatement("""
					INSERT INTO equipment_services (
						id, tenant_id, equipment_id, serviced_on, service_provider_id,
						work_done, cost_inr, actor_user_id)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?)
					""");
			ps.setObject(1, id);
			ps.setObject(2, equipmentId);
			ps.setObject(3, request.servicedOn());
			ps.setObject(4, providerId);
			ps.setString(5, trimToNull(request.workDone()));
			ps.setBigDecimal(6, request.costInr());
			ps.setObject(7, actor.getUserId());
			return ps;
		});

		Map<String, Object> after = new LinkedHashMap<>();
		after.put("name", equipment.name());
		after.put("servicedOn", request.servicedOn().toString());
		after.put("costInr", request.costInr());
		auditService.record(actor, AuditAction.EQUIPMENT_SERVICED, AuditEntityType.EQUIPMENT, equipmentId,
				null, after, trimToNull(request.workDone()));
		return id;
	}

	/**
	 * Registers a donated asset (E3-S5): source DONATED, linked to its donation, condition GOOD, with
	 * the same history origin and audit trail as any other registration. Called by donation intake
	 * within the intake transaction, so the asset and its donation record commit together.
	 */
	@Transactional
	public UUID registerDonated(
			AuthenticatedUser actor, String name, EquipmentCategory category, String notes, UUID donationId) {
		UUID id = UUID.randomUUID();
		jdbc.update(connection -> {
			var ps = connection.prepareStatement("""
					INSERT INTO equipment_items (
						id, tenant_id, name, category, condition, source, notes, donation_id)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						?, ?, 'GOOD', 'DONATED', ?, ?)
					""");
			ps.setObject(1, id);
			ps.setString(2, name.trim());
			ps.setString(3, category.name());
			ps.setString(4, trimToNull(notes));
			ps.setObject(5, donationId);
			return ps;
		});

		recordStateChange(actor, id, null, EquipmentCondition.GOOD, "Donated");
		auditService.record(actor, AuditAction.EQUIPMENT_ADDED, AuditEntityType.EQUIPMENT, id,
				null, snapshot(name.trim(), category, EquipmentCondition.GOOD), null);
		return id;
	}

	// ---------------------------------------------------------------------

	private void insertItem(
			UUID id, CreateEquipmentRequest request, EquipmentCondition condition, String serial) {
		try {
			jdbc.update(connection -> {
				var ps = connection.prepareStatement("""
						INSERT INTO equipment_items (
							id, tenant_id, name, category, storage_location, condition,
							acquisition_date, source, notes, serial_number, purchase_cost_inr,
							warranty_expiry)
						VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
							?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
						""");
				ps.setObject(1, id);
				ps.setString(2, request.name().trim());
				ps.setString(3, request.category().name());
				ps.setString(4, trimToNull(request.storageLocation()));
				ps.setString(5, condition.name());
				ps.setObject(6, request.acquisitionDate());
				ps.setString(7, request.source() == null ? null : request.source().name());
				ps.setString(8, trimToNull(request.notes()));
				ps.setString(9, serial);
				ps.setBigDecimal(10, request.purchaseCostInr());
				ps.setObject(11, request.warrantyExpiry());
				return ps;
			});
		} catch (DuplicateKeyException e) {
			throw serialAlreadyUsed(serial);
		}
	}

	/**
	 * Checks a service provider is this temple's, and hands the id back.
	 *
	 * <p>An RLS-scoped existence check rather than trust in the foreign key, for the reason
	 * {@code InventoryItemService.create} spells out: foreign-key validation runs as the table owner
	 * and does not see row-level security, so a stray id from another temple would otherwise be
	 * accepted and would then be invisible on every read.
	 */
	private UUID requireOwnProvider(UUID providerId) {
		if (providerId == null) {
			return null;
		}
		Integer found = jdbc.queryForObject(
				"SELECT count(*) FROM service_providers WHERE id = ?", Integer.class, providerId);
		if (found == null || found == 0) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND,
					Map.of("serviceProviderId", providerId));
		}
		return providerId;
	}

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
		return jdbc.query(SELECT + " WHERE e.id = ?", mapper(), id).stream().findFirst();
	}

	private Map<String, Object> snapshot(String name, EquipmentCategory category, EquipmentCondition condition) {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("name", name);
		s.put("category", category.name());
		s.put("condition", condition.name());
		return s;
	}

	private Map<String, Object> scheduleSnapshot(
			String name, Integer intervalDays, ServiceInterval unit, UUID providerId) {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("name", name);
		s.put("serviceIntervalDays", intervalDays);
		s.put("serviceIntervalUnit", unit == null ? null : unit.name());
		s.put("serviceProviderId", providerId == null ? null : providerId.toString());
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

	private ApplicationException serialAlreadyUsed(String serial) {
		return new ApplicationException(ErrorCode.EQUIPMENT_SERIAL_ALREADY_USED,
				Map.of("serialNumber", serial == null ? "" : serial));
	}

	private static EquipmentCondition condition(String value) {
		return value == null ? null : EquipmentCondition.valueOf(value);
	}

	// ---- The derivation (E3-S10 D4, D5, D6) ------------------------------

	/**
	 * Where a machine stands, worked out from what is stored and nothing else.
	 *
	 * <p>Four states and one order of questions, and the order is the specification:
	 *
	 * <ol>
	 *   <li>SCRAPPED is never scheduled, whatever its dates say. A dashboard that nags every
	 *       morning about a grinder thrown away last year teaches its reader to ignore it, and
	 *       then it is worth nothing when a real one comes due (D6).
	 *   <li>No interval means nobody has decided how often this needs looking at, which is the
	 *       honest state for a trestle table. Not scheduled — not overdue.
	 *   <li>Newest service plus the interval, said to be counted from the service.
	 *   <li>Failing that, acquisition date plus the interval, said to be counted from the purchase
	 *       — so the screen can print "due 12 Mar 2027, from purchase, never serviced" and nobody
	 *       reads it as a service that happened.
	 *   <li>Neither: not scheduled.
	 * </ol>
	 *
	 * <p>Then the date against today. Past it is OVERDUE; on or before today plus the temple's
	 * horizon is DUE_SOON; anything further off is OK. Due <em>today</em> is DUE_SOON and not
	 * OVERDUE — the date has not passed, and red on the morning a service falls due is the fire
	 * alarm D5 exists to avoid.
	 */
	static Derived derive(
			EquipmentCondition condition, Integer intervalDays, LocalDate lastServicedOn,
			LocalDate acquisitionDate, LocalDate today, int warningDays) {

		if (condition == EquipmentCondition.SCRAPPED || intervalDays == null) {
			return new Derived(null, NextServiceBasis.NONE, ServiceStatus.NOT_SCHEDULED);
		}

		LocalDate next;
		NextServiceBasis basis;
		if (lastServicedOn != null) {
			next = lastServicedOn.plusDays(intervalDays);
			basis = NextServiceBasis.SERVICED;
		} else if (acquisitionDate != null) {
			next = acquisitionDate.plusDays(intervalDays);
			basis = NextServiceBasis.PURCHASED;
		} else {
			return new Derived(null, NextServiceBasis.NONE, ServiceStatus.NOT_SCHEDULED);
		}

		ServiceStatus status;
		if (next.isBefore(today)) {
			status = ServiceStatus.OVERDUE;
		} else if (!next.isAfter(today.plusDays(warningDays))) {
			status = ServiceStatus.DUE_SOON;
		} else {
			status = ServiceStatus.OK;
		}
		return new Derived(next, basis, status);
	}

	/** What {@link #derive} works out. Never stored, and never written to any column. */
	record Derived(LocalDate nextServiceOn, NextServiceBasis basis, ServiceStatus status) {
	}

	// ---------------------------------------------------------------------

	/**
	 * The mapper, built per query rather than held as a constant.
	 *
	 * <p>Every other mapper in this class is a static constant, and this one cannot be: it closes
	 * over the temple's warning horizon and over today's date, and both are read once here rather
	 * than per row. A static mapper would have to fetch the horizon for every machine in the list.
	 */
	private RowMapper<EquipmentView> mapper() {
		int warningDays = tenantSettings.equipmentServiceWarningDays();
		LocalDate today = LocalDate.now(TEMPLE_ZONE);

		return (rs, n) -> {
			EquipmentCondition itemCondition = EquipmentCondition.valueOf(rs.getString("condition"));
			Integer intervalDays = (Integer) rs.getObject("service_interval_days");
			String unitName = rs.getString("service_interval_unit");
			ServiceInterval unit = unitName == null ? null : ServiceInterval.valueOf(unitName);
			LocalDate lastServiced = rs.getObject("last_serviced_on", LocalDate.class);
			LocalDate acquisition = rs.getObject("acquisition_date", LocalDate.class);

			Derived derived = derive(
					itemCondition, intervalDays, lastServiced, acquisition, today, warningDays);

			return new EquipmentView(
					rs.getObject("id", UUID.class),
					rs.getString("name"),
					EquipmentCategory.valueOf(rs.getString("category")),
					rs.getString("storage_location"),
					itemCondition,
					acquisition,
					rs.getString("source") == null ? null : EquipmentSource.valueOf(rs.getString("source")),
					rs.getString("notes"),
					rs.getObject("created_at", OffsetDateTime.class).toInstant(),
					rs.getString("serial_number"),
					rs.getBigDecimal("purchase_cost_inr"),
					rs.getObject("warranty_expiry", LocalDate.class),
					intervalDays,
					unit,
					intervalDays == null || unit == null ? null : unit.countIn(intervalDays),
					rs.getObject("service_provider_id", UUID.class),
					rs.getString("service_provider_name"),
					lastServiced,
					derived.nextServiceOn(),
					derived.basis(),
					derived.status());
		};
	}

	// The newest service is a scalar subquery rather than a join, so a machine with twenty services
	// stays one row. Both sides sit inside the tenant's RLS policy, so it can only ever see this
	// temple's services — which is what makes "last serviced" un-forgeable from another tenant.
	private static final String SELECT = """
			SELECT e.id, e.name, e.category, e.storage_location, e.condition, e.acquisition_date,
				   e.source, e.notes, e.created_at, e.serial_number, e.purchase_cost_inr,
				   e.warranty_expiry, e.service_interval_days, e.service_interval_unit,
				   e.service_provider_id, p.name AS service_provider_name,
				   (SELECT max(s.serviced_on) FROM equipment_services s
					 WHERE s.equipment_id = e.id) AS last_serviced_on
			FROM equipment_items e
			LEFT JOIN service_providers p ON p.id = e.service_provider_id
			""";

	private static final RowMapper<EquipmentStateChange> HISTORY_MAPPER = (rs, n) -> new EquipmentStateChange(
			rs.getObject("id", UUID.class),
			condition(rs.getString("from_condition")),
			EquipmentCondition.valueOf(rs.getString("to_condition")),
			rs.getString("reason"),
			rs.getObject("actor_user_id", UUID.class),
			rs.getString("actor_name"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());

	private static final RowMapper<EquipmentServiceRecord> SERVICE_MAPPER =
			(rs, n) -> new EquipmentServiceRecord(
					rs.getObject("id", UUID.class),
					rs.getObject("serviced_on", LocalDate.class),
					rs.getObject("service_provider_id", UUID.class),
					rs.getString("service_provider_name"),
					rs.getString("work_done"),
					(BigDecimal) rs.getObject("cost_inr"),
					rs.getObject("actor_user_id", UUID.class),
					rs.getString("actor_name"),
					rs.getObject("created_at", OffsetDateTime.class).toInstant());
}
