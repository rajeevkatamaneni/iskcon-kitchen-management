package org.iskcon.kms.inventory;

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
 * The one place the application writes to the stock ledger (E3-S2).
 *
 * <p>Inventory is <em>derived</em> from {@code stock_movements}: current stock is the sum of a
 * consumable's rows, batch stock the sum per batch (SYSTEM_DESIGN.md §5). Nothing sets a stock
 * level directly. Every operational write path — a purchase-order receipt (E5), an in-kind donation
 * (E3-S5), cooking a meal (E3-S6), a manual adjustment (E3-S7) — builds a {@link RecordMovement} and
 * hands it to {@link #record}, so the shape of a movement and the rule that it is signed, immutable,
 * and attributed to the connection's tenant live here and nowhere else.
 *
 * <p><strong>Tenancy comes from the connection, not an argument</strong> — the row's
 * {@code tenant_id} is filled from {@code current_setting('app.tenant_id')}, the same setting RLS
 * checks, exactly as {@link AuditService} does. A caller cannot attribute stock to a tenant it is
 * not operating within; with no context the insert fails closed against the {@code NOT NULL} column.
 *
 * <p><strong>The ledger is append-only</strong> (the {@code make_append_only} grant, tested like the
 * audit log). A mistake is undone by {@link #compensate}: a fresh movement that reverses the
 * original and points back at it. History is never edited.
 */
@Service
public class StockMovementService {

	private static final int MAX_HISTORY = 1000;
	private static final int DEFAULT_HISTORY = 200;

	private static final String INSERT = """
			INSERT INTO stock_movements (
				id, tenant_id, ingredient_id, storage_location, batch_id, quantity, unit,
				movement_type, expiry_date, received_date, reason_category, reference_type,
				reference_id, note, actor_user_id)
			VALUES (
				?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
				?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""";

	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public StockMovementService(JdbcTemplate jdbc, AuditService auditService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	/**
	 * Appends one movement to the ledger and returns its id. The kernel every write path calls;
	 * not exposed directly over HTTP — a movement is always the consequence of a named action
	 * (receive, donate, consume, adjust), and that action's service supplies the command.
	 */
	@Transactional
	public UUID record(AuthenticatedUser actor, RecordMovement cmd) {
		validate(cmd);
		track(actor, cmd);
		UUID id = UUID.randomUUID();
		jdbc.update(connection -> {
			var ps = connection.prepareStatement(INSERT);
			ps.setObject(1, id);
			ps.setObject(2, cmd.ingredientId());
			ps.setString(3, cmd.storageLocation());
			ps.setObject(4, cmd.batchId());
			ps.setBigDecimal(5, cmd.quantity());
			ps.setString(6, cmd.unit().name());
			ps.setString(7, cmd.type().name());
			ps.setObject(8, cmd.expiryDate());
			ps.setObject(9, cmd.receivedDate());
			ps.setString(10, cmd.reason() == null ? null : cmd.reason().name());
			ps.setString(11, cmd.referenceType() == null ? null : cmd.referenceType().name());
			ps.setObject(12, cmd.referenceId());
			ps.setString(13, cmd.note());
			ps.setObject(14, actor.getUserId());
			return ps;
		});
		return id;
	}

	/**
	 * Makes sure the consumable this movement is about is one the temple tracks.
	 *
	 * <p>Stock and tracking were two separate facts, and a store can only be trusted if they are
	 * one. The ledger is keyed by ingredient, so a delivery or an in-kind donation of something
	 * nobody had thought to add first wrote real kilograms into it — and the Inventory screen, which
	 * lists {@code inventory_items}, showed nothing at all. The rice was in the store and dark on the
	 * screen, and adding it later then produced the opposite surprise: a brand-new item already
	 * holding 652 kg it could not account for.
	 *
	 * <p>So nothing that arrives stays untracked. Anything that moves through the ledger is tracked
	 * from its first movement, in the place that movement went to, with no reorder threshold until a
	 * temple sets one — the row says only "we hold this", which is exactly what the movement proves.
	 * A temple that had already added it keeps its own location, threshold and notes: the insert
	 * defers to what is there rather than overwriting a decision somebody made.
	 */
	private void track(AuthenticatedUser actor, RecordMovement cmd) {
		List<UUID> created = jdbc.query(
				"""
				INSERT INTO inventory_items (id, tenant_id, ingredient_id, storage_location)
				VALUES (gen_random_uuid(),
						NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?)
				ON CONFLICT (tenant_id, ingredient_id) DO NOTHING
				RETURNING id
				""",
				(rs, n) -> rs.getObject("id", UUID.class),
				cmd.ingredientId(), cmd.storageLocation());

		if (created.isEmpty()) {
			return;
		}

		Map<String, Object> after = new LinkedHashMap<>();
		after.put("ingredientId", cmd.ingredientId());
		after.put("storageLocation", cmd.storageLocation());
		after.put("trackedBy", cmd.type().name());
		auditService.record(actor, AuditAction.INVENTORY_ITEM_ADDED, AuditEntityType.INVENTORY_ITEM,
				created.get(0), null, after, "Tracked automatically on its first stock movement.");
	}

	/**
	 * Undoes a movement by appending its exact reverse, cross-referencing the original. The
	 * correction is an {@code ADJUSTMENT} carrying {@link MovementReference#CORRECTION} and the
	 * original's id, reversing its quantity within the same batch — so the batch nets back to where
	 * it was, and both directions of the link are queryable without ever touching the immutable
	 * original. A movement may be corrected once; correct the correction if you need to go further.
	 */
	@Transactional
	public UUID compensate(AuthenticatedUser actor, UUID originalId, String note) {
		StockMovement original = findById(originalId).orElseThrow(() -> notFound(originalId));
		if (isAlreadyCorrected(originalId)) {
			throw new ApplicationException(
					ErrorCode.MOVEMENT_ALREADY_CORRECTED, Map.of("movementId", originalId));
		}

		RecordMovement reversal = new RecordMovement(
				original.ingredientId(),
				original.storageLocation(),
				original.batchId(),
				original.quantity().negate(),
				org.iskcon.kms.ingredient.Unit.valueOf(original.unit()),
				MovementType.ADJUSTMENT,
				null,
				null,
				AdjustmentReason.COUNT_CORRECTION,
				MovementReference.CORRECTION,
				originalId,
				note);
		UUID correctionId = record(actor, reversal);

		auditService.record(actor, AuditAction.STOCK_MOVEMENT_CORRECTED, AuditEntityType.STOCK_MOVEMENT,
				correctionId, snapshot(original), correctionSnapshot(reversal, correctionId), note);
		return correctionId;
	}

	/**
	 * Movement history, newest first. Both filters are optional: with an {@code ingredientId} it is
	 * one consumable's ledger; with a {@code type} it is (say) every adjustment. Bounded so a busy
	 * tenant's history can never return an unbounded result set.
	 */
	@Transactional(readOnly = true)
	public List<StockMovement> history(UUID ingredientId, MovementType type, Integer limit) {
		StringBuilder sql = new StringBuilder("""
				SELECT m.id, m.ingredient_id, i.name AS ingredient_name, m.storage_location,
					   m.batch_id, m.quantity, m.unit, m.movement_type, m.expiry_date,
					   m.received_date, m.reason_category, m.reference_type, m.reference_id,
					   m.note, m.actor_user_id, u.full_name AS actor_name, m.created_at
				FROM stock_movements m
				JOIN ingredients i ON i.id = m.ingredient_id
				LEFT JOIN users u ON u.id = m.actor_user_id
				WHERE 1 = 1
				""");
		List<Object> args = new ArrayList<>();
		if (ingredientId != null) {
			sql.append(" AND m.ingredient_id = ?");
			args.add(ingredientId);
		}
		if (type != null) {
			sql.append(" AND m.movement_type = ?");
			args.add(type.name());
		}
		sql.append(" ORDER BY m.created_at DESC, m.id DESC LIMIT ?");
		args.add(clampLimit(limit));

		return jdbc.query(sql.toString(), MAPPER, args.toArray());
	}

	// ---------------------------------------------------------------------

	private void validate(RecordMovement cmd) {
		if (cmd.quantity() == null || cmd.quantity().signum() == 0) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "quantity"));
		}
		if (cmd.type() == MovementType.ADJUSTMENT && cmd.reason() == null) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "reason"));
		}
		if (cmd.reason() == AdjustmentReason.OTHER && (cmd.note() == null || cmd.note().isBlank())) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "note"));
		}
	}

	private boolean isAlreadyCorrected(UUID originalId) {
		Integer count = jdbc.queryForObject(
				"SELECT count(*) FROM stock_movements WHERE reference_type = 'CORRECTION' AND reference_id = ?",
				Integer.class, originalId);
		return count != null && count > 0;
	}

	private Optional<StockMovement> findById(UUID id) {
		return jdbc.query("""
				SELECT m.id, m.ingredient_id, i.name AS ingredient_name, m.storage_location,
					   m.batch_id, m.quantity, m.unit, m.movement_type, m.expiry_date,
					   m.received_date, m.reason_category, m.reference_type, m.reference_id,
					   m.note, m.actor_user_id, u.full_name AS actor_name, m.created_at
				FROM stock_movements m
				JOIN ingredients i ON i.id = m.ingredient_id
				LEFT JOIN users u ON u.id = m.actor_user_id
				WHERE m.id = ?
				""", MAPPER, id).stream().findFirst();
	}

	private int clampLimit(Integer limit) {
		if (limit == null || limit <= 0) {
			return DEFAULT_HISTORY;
		}
		return Math.min(limit, MAX_HISTORY);
	}

	private Map<String, Object> snapshot(StockMovement m) {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("movementId", m.id());
		s.put("ingredientId", m.ingredientId());
		s.put("batchId", m.batchId());
		s.put("quantity", m.quantity());
		s.put("unit", m.unit());
		s.put("type", m.type().name());
		return s;
	}

	private Map<String, Object> correctionSnapshot(RecordMovement r, UUID correctionId) {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("movementId", correctionId);
		s.put("ingredientId", r.ingredientId());
		s.put("batchId", r.batchId());
		s.put("quantity", r.quantity());
		s.put("unit", r.unit().name());
		s.put("type", r.type().name());
		s.put("corrects", r.referenceId());
		return s;
	}

	private ApplicationException notFound(UUID id) {
		return new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("movementId", id));
	}

	private static AdjustmentReason reason(String value) {
		return value == null ? null : AdjustmentReason.valueOf(value);
	}

	private static MovementReference reference(String value) {
		return value == null ? null : MovementReference.valueOf(value);
	}

	private static final RowMapper<StockMovement> MAPPER = (rs, rowNum) -> new StockMovement(
			rs.getObject("id", UUID.class),
			rs.getObject("ingredient_id", UUID.class),
			rs.getString("ingredient_name"),
			rs.getString("storage_location"),
			rs.getObject("batch_id", UUID.class),
			rs.getBigDecimal("quantity"),
			rs.getString("unit"),
			MovementType.valueOf(rs.getString("movement_type")),
			rs.getObject("expiry_date", LocalDate.class),
			rs.getObject("received_date", LocalDate.class),
			reason(rs.getString("reason_category")),
			reference(rs.getString("reference_type")),
			rs.getObject("reference_id", UUID.class),
			rs.getString("note"),
			rs.getObject("actor_user_id", UUID.class),
			rs.getString("actor_name"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());
}
