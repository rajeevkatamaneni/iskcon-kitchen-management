package org.iskcon.kms.donation;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of hand-recorded donation intake (E3-S5): everything that must commit
 * together.
 *
 * <p>One donation row, plus the goods it brought — donated food as DONATION_IN_KIND movements into
 * fresh batches, donated equipment as DONATED assets — and the audit event, all in one transaction.
 * Either the whole intake lands or none of it does; there is no state where the stock went up but the
 * donation it came from was never recorded. The thank-you is deliberately <em>not</em> here — it is a
 * post-commit best-effort step ({@link DonationIntakeService}), because a message we couldn't queue
 * must never undo goods already received.
 *
 * <p>Cash takes the same road with nothing to move: a ONE_TIME donation of {@code amount_inr}, paid
 * in CASH, already COMPLETED because the money is in hand. What marks every row written here as
 * hand-recorded rather than collected is that {@code provider} stays null — a gateway always sets it.
 */
@Service
public class DonationRecorder {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final StockMovementService stockMovementService;
	private final EquipmentService equipmentService;
	private final org.iskcon.kms.wishlist.WishlistService wishlistService;

	public DonationRecorder(
			JdbcTemplate jdbc, AuditService auditService,
			StockMovementService stockMovementService, EquipmentService equipmentService,
			org.iskcon.kms.wishlist.WishlistService wishlistService) {
		this.wishlistService = wishlistService;
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.stockMovementService = stockMovementService;
		this.equipmentService = equipmentService;
	}

	@Transactional
	public DonationReceipt record(AuthenticatedUser actor, RecordDonationRequest request) {
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

		// Cash given towards a wish-list item can complete it, exactly as money through the gateway
		// does. Until this, an item could only ever be finished online — a temple handed the last
		// ₹5,000 in cash went on showing the grinder as still wanted.
		if (request.wishlistItemId() != null) {
			wishlistService.markFulfilledIfComplete(request.wishlistItemId());
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

	// ---------------------------------------------------------------------

	private void validate(
			RecordDonationRequest request,
			List<IngredientDonationLine> ingredients, List<EquipmentDonationLine> equipment) {

		boolean hasGoods = !ingredients.isEmpty() || !equipment.isEmpty();
		boolean hasCash = request.cashAmountInr() != null;

		if (!hasCash && !hasGoods) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "items", "reason", "a donation must be cash or at least one item"));
		}
		if (hasCash && hasGoods) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of(
					"field", "cashAmountInr",
					"reason", "record cash and goods as separate donations"));
		}
		if (!request.anonymous() && (request.donorName() == null || request.donorName().isBlank())) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "donorName"));
		}
		if (request.wishlistItemId() != null) {
			if (!hasCash) {
				throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of(
						"field", "wishlistItemId",
						"reason", "only cash can be given towards a wish-list item"));
			}
			// The same guard the online road applies: a devotee cannot give towards something the
			// temple has stopped asking for, whether the money arrives by gateway or by hand.
			String status = jdbc.query("SELECT status FROM wishlist_items WHERE id = ?",
					(rs, n) -> rs.getString("status"), request.wishlistItemId())
					.stream().findFirst()
					.orElseThrow(() -> new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND,
							Map.of("wishlistItemId", request.wishlistItemId())));
			if (!"ACTIVE".equals(status)) {
				throw new ApplicationException(ErrorCode.WISHLIST_ITEM_UNAVAILABLE,
						Map.of("wishlistItemId", request.wishlistItemId()));
			}
		}
	}

	/**
	 * Cash is a COMPLETED ONE_TIME gift of {@code amount_inr}; goods are IN_KIND, worth whatever the
	 * temple estimated. Neither sets {@code provider} — that is what tells the ledger a person, not a
	 * gateway, wrote this row.
	 */
	private UUID insertDonation(AuthenticatedUser actor, RecordDonationRequest request, String donorName) {
		UUID id = UUID.randomUUID();
		boolean cash = request.cashAmountInr() != null;
		jdbc.update(connection -> {
			var ps = connection.prepareStatement("""
					INSERT INTO donations (
						id, tenant_id, type, donor_name, donor_phone, donor_email, is_anonymous,
						amount_inr, payment_mode, estimated_value_inr, donated_on, notes, recorded_by,
						wishlist_item_id, wishlist_quantity)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?,
						?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""");
			ps.setObject(1, id);
			ps.setString(2, cash ? "ONE_TIME" : "IN_KIND");
			ps.setString(3, donorName);
			ps.setString(4, request.anonymous() ? null : trimToNull(request.donorPhone()));
			ps.setString(5, request.anonymous() ? null : trimToNull(request.donorEmail()));
			ps.setBoolean(6, request.anonymous());
			ps.setBigDecimal(7, request.cashAmountInr());
			ps.setString(8, cash ? "CASH" : null);
			ps.setBigDecimal(9, cash ? null : request.estimatedValueInr());
			ps.setObject(10, request.donatedOn());
			ps.setString(11, trimToNull(request.notes()));
			ps.setObject(12, actor.getUserId());
			ps.setObject(13, request.wishlistItemId());
			// Zero units, as the online part-payment road does: this is money towards the cost, and
			// the unit count is what says "a whole one of these was bought".
			ps.setObject(14, request.wishlistItemId() == null ? null : 0);
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
			String donorName, RecordDonationRequest request, int ingredientCount, int equipmentCount) {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("donor", request.anonymous() ? "Anonymous" : donorName);
		s.put("donatedOn", request.donatedOn().toString());
		s.put("cashAmountInr", request.cashAmountInr());
		s.put("estimatedValueInr", request.estimatedValueInr());
		s.put("wishlistItemId", request.wishlistItemId() == null ? null : request.wishlistItemId().toString());
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
}
