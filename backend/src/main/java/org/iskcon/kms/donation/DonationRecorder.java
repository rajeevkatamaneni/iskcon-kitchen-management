package org.iskcon.kms.donation;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.equipment.EquipmentService;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.inventory.MovementReference;
import org.iskcon.kms.inventory.MovementType;
import org.iskcon.kms.inventory.RecordMovement;
import org.iskcon.kms.inventory.StockMovementService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of in-kind donation intake (E3-S5): everything that must commit together.
 *
 * <p>One donation row, plus the goods it brought — donated food as DONATION_IN_KIND movements into
 * fresh batches, donated equipment as DONATED assets — and the audit event, all in one transaction.
 * Either the whole intake lands or none of it does; there is no state where the stock went up but the
 * donation it came from was never recorded. The thank-you is deliberately <em>not</em> here — it is a
 * post-commit best-effort step ({@link DonationIntakeService}), because a message we couldn't queue
 * must never undo goods already received.
 */
@Service
public class DonationRecorder {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final StockMovementService stockMovementService;
	private final EquipmentService equipmentService;

	public DonationRecorder(
			JdbcTemplate jdbc, AuditService auditService,
			StockMovementService stockMovementService, EquipmentService equipmentService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.stockMovementService = stockMovementService;
		this.equipmentService = equipmentService;
	}

	@Transactional
	public DonationReceipt record(AuthenticatedUser actor, RecordInKindDonationRequest request) {
		List<IngredientDonationLine> ingredients = request.ingredients() == null ? List.of() : request.ingredients();
		List<EquipmentDonationLine> equipment = request.equipment() == null ? List.of() : request.equipment();
		validate(request, ingredients, equipment);

		String donorName = request.anonymous() ? null : request.donorName().trim();
		UUID donationId = insertDonation(actor, request, donorName);

		for (IngredientDonationLine line : ingredients) {
			IngredientRef ref = findIngredient(line.ingredientId()).orElseThrow(() ->
					new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("ingredientId", line.ingredientId())));
			Unit unit = parseUnit(line.unit());
			if (unit.family() != ref.canonicalUnit().family()) {
				throw new ApplicationException(
						ErrorCode.VALIDATION_FAILED, Map.of("field", "unit", "value", line.unit()));
			}
			stockMovementService.record(actor, new RecordMovement(
					line.ingredientId(), null, UUID.randomUUID(),
					line.quantity(), unit, MovementType.DONATION_IN_KIND,
					line.expiryDate(), request.donatedOn(), null,
					MovementReference.DONATION, donationId, null));
		}

		for (EquipmentDonationLine line : equipment) {
			equipmentService.registerDonated(actor, line.name(), line.category(), line.notes(), donationId);
		}

		auditService.record(actor, AuditAction.DONATION_RECORDED, AuditEntityType.DONATION, donationId,
				null, donationSnapshot(donorName, request, ingredients.size(), equipment.size()), null);

		return new DonationReceipt(donationId, request.anonymous(), donorName,
				trimToNull(request.donorPhone()), trimToNull(request.donorEmail()),
				request.donatedOn(), templeName());
	}

	@Transactional
	public void markAcknowledged(UUID donationId) {
		jdbc.update("UPDATE donations SET acknowledged_at = now() WHERE id = ?", donationId);
	}

	@Transactional(readOnly = true)
	public List<DonationView> list() {
		return jdbc.query("""
				SELECT d.id, d.type, d.donor_name, d.is_anonymous, d.donated_on, d.estimated_value_inr,
					   d.acknowledged_at, d.notes, d.created_at,
					   (SELECT count(DISTINCT batch_id) FROM stock_movements m
						WHERE m.reference_type = 'DONATION' AND m.reference_id = d.id) AS ingredient_count,
					   (SELECT count(*) FROM equipment_items e WHERE e.donation_id = d.id) AS equipment_count
				FROM donations d
				ORDER BY d.donated_on DESC, d.created_at DESC
				""", VIEW_MAPPER);
	}

	// ---------------------------------------------------------------------

	private void validate(
			RecordInKindDonationRequest request,
			List<IngredientDonationLine> ingredients, List<EquipmentDonationLine> equipment) {

		if (ingredients.isEmpty() && equipment.isEmpty()) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "items", "reason", "a donation must include at least one item"));
		}
		if (!request.anonymous() && (request.donorName() == null || request.donorName().isBlank())) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "donorName"));
		}
	}

	private UUID insertDonation(AuthenticatedUser actor, RecordInKindDonationRequest request, String donorName) {
		UUID id = UUID.randomUUID();
		jdbc.update(connection -> {
			var ps = connection.prepareStatement("""
					INSERT INTO donations (
						id, tenant_id, type, donor_name, donor_phone, donor_email, is_anonymous,
						estimated_value_inr, donated_on, notes, recorded_by)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, 'IN_KIND',
						?, ?, ?, ?, ?, ?, ?, ?)
					""");
			ps.setObject(1, id);
			ps.setString(2, donorName);
			ps.setString(3, request.anonymous() ? null : trimToNull(request.donorPhone()));
			ps.setString(4, request.anonymous() ? null : trimToNull(request.donorEmail()));
			ps.setBoolean(5, request.anonymous());
			ps.setBigDecimal(6, request.estimatedValueInr());
			ps.setObject(7, request.donatedOn());
			ps.setString(8, trimToNull(request.notes()));
			ps.setObject(9, actor.getUserId());
			return ps;
		});
		return id;
	}

	private Optional<IngredientRef> findIngredient(UUID id) {
		return jdbc.query("SELECT name, canonical_unit FROM ingredients WHERE id = ?",
				(rs, n) -> new IngredientRef(rs.getString("name"), Unit.valueOf(rs.getString("canonical_unit"))),
				id).stream().findFirst();
	}

	private String templeName() {
		return jdbc.query("""
				SELECT name FROM tenants WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", (rs, n) -> rs.getString("name")).stream().findFirst().orElse("your temple");
	}

	private Unit parseUnit(String unit) {
		try {
			return Unit.valueOf(unit);
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED, Map.of("field", "unit", "value", String.valueOf(unit)));
		}
	}

	private Map<String, Object> donationSnapshot(
			String donorName, RecordInKindDonationRequest request, int ingredientCount, int equipmentCount) {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("donor", request.anonymous() ? "Anonymous" : donorName);
		s.put("donatedOn", request.donatedOn().toString());
		s.put("estimatedValueInr", request.estimatedValueInr());
		s.put("ingredientCount", ingredientCount);
		s.put("equipmentCount", equipmentCount);
		return s;
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private record IngredientRef(String name, Unit canonicalUnit) {
	}

	private static final RowMapper<DonationView> VIEW_MAPPER = (rs, n) -> new DonationView(
			rs.getObject("id", UUID.class),
			rs.getString("type"),
			rs.getString("donor_name"),
			rs.getBoolean("is_anonymous"),
			rs.getObject("donated_on", java.time.LocalDate.class),
			rs.getBigDecimal("estimated_value_inr"),
			rs.getInt("ingredient_count"),
			rs.getInt("equipment_count"),
			rs.getObject("acknowledged_at") != null,
			rs.getString("notes"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());
}
