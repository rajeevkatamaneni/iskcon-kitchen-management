package org.iskcon.kms.donation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.equipment.EquipmentService;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.ingredient.IngredientUnits;
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
	private final IngredientUnits ingredientUnits;

	public DonationRecorder(
			JdbcTemplate jdbc, AuditService auditService,
			StockMovementService stockMovementService, EquipmentService equipmentService,
			org.iskcon.kms.wishlist.WishlistService wishlistService, IngredientUnits ingredientUnits) {
		this.ingredientUnits = ingredientUnits;
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

		// Every line is judged before anything is written, so a gift is refused whole. The
		// transaction would roll a half-written gift back anyway; checking first means there is
		// never a donation row and a first line's movement sitting in the transaction waiting to be
		// undone because the second line was "2 Kg of ghee". The rule is the one every quantity
		// obeys (BL-9): it refuses an ingredient this temple cannot see as not found, and a unit
		// from another family as KMS-400013 naming the ingredient, which on a many-line gift is the
		// difference between "fix Ghee" and "one of these is wrong".
		List<Unit> units = new java.util.ArrayList<>(ingredients.size());
		for (IngredientDonationLine line : ingredients) {
			Unit unit = parseUnit(line.unit());
			ingredientUnits.requireSameFamily(line.ingredientId(), unit);
			units.add(unit);
		}

		String donorName = request.anonymous() ? null : request.donorName().trim();
		UUID donationId = insertDonation(actor, request, donorName);

		for (int i = 0; i < ingredients.size(); i++) {
			IngredientDonationLine line = ingredients.get(i);
			Unit unit = units.get(i);
			stockMovementService.record(actor, new RecordMovement(
					line.ingredientId(), null, UUID.randomUUID(),
					line.quantity(), unit, MovementType.DONATION_IN_KIND,
					line.expiryDate(), request.donatedOn(), null,
					MovementReference.DONATION, donationId, null));
		}

		for (EquipmentDonationLine line : equipment) {
			equipmentService.registerDonated(actor, line.name(), line.notes(), donationId);
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
						wishlist_item_id)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?,
						?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
			// Cash towards a wish-list item is linked to it and is worth its amount, which is all
			// there is to say: progress towards an item is the money in hand against its cost.
			ps.setObject(13, request.wishlistItemId());
			return ps;
		});
		return id;
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
}
