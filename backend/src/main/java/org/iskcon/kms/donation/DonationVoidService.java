package org.iskcon.kms.donation;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.inventory.StockMovementService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Striking a gift that was recorded wrongly (T-012).
 *
 * <p>Recording a gift was a one-way door. {@link DonationController} was POST-only for hand-recorded
 * gifts and {@link DonationLedgerController} is read-only throughout, so a gift entered twice, or
 * against the wrong donor, permanently inflated the figures the temple reports under 80G — and where
 * it was in kind it had put real kilograms into the store-room in the same transaction, which could
 * not be undone either. Both are undone here, together.
 *
 * <p><strong>Two records, two kinds of correction, one transaction.</strong> The donation row is
 * <em>marked</em>: it is read one row at a time by an administrator answering what somebody gave, and
 * a gift of ₹5,000 sitting beside a gift of -₹5,000 answers that badly twice over. The stock ledger
 * is <em>compensated</em>: it is append-only and its only consumer is a sum, so the reverse entry
 * that nets the batch back to where it was is exactly right. V104's comment argues both at length.
 * What matters here is that they commit together — {@link StockMovementService#compensate} is
 * {@code @Transactional} and joins this transaction, so a failure in either half rolls the other one
 * back and the two records can never drift apart.
 *
 * <p><strong>The donation is marked first and the stock reversed after, deliberately.</strong> The
 * other order would make the atomicity claim untestable: a stock failure would simply happen before
 * anything was written, and a green test would prove nothing about the rollback. This way a failing
 * compensation has a committed-looking mark to undo, which is what {@code DonationVoidIT} asserts.
 *
 * <p><strong>What this does not undo, and cannot.</strong> Donated equipment
 * ({@code DonationRecorder:83}) is registered as an asset, and reversing that registration is a
 * different act with no primitive behind it — an asset the temple has is not a number in a sum. A
 * void therefore strikes the gift, reverses the food, and leaves any donated equipment standing in
 * the register, still pointing at the donation it came in on. The screen says so before anybody
 * presses the button; the alternative — refusing to void a gift that brought a vessel — would leave
 * the 80G figure permanently wrong to protect a register entry that an admin can already correct by
 * hand.
 */
@Service
public class DonationVoidService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final StockMovementService stockMovementService;

	public DonationVoidService(
			JdbcTemplate jdbc, AuditService auditService, StockMovementService stockMovementService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.stockMovementService = stockMovementService;
	}

	/**
	 * Strikes one gift and reverses whatever it put on the shelf.
	 *
	 * <p>Refused with {@code KMS-400134} if it has already been struck. Not idempotent, and that is a
	 * departure from {@code StaffPayService.voidPayment}, which returns quietly on a second call: this
	 * one also reverses stock, and answering a second void with a silent success would leave somebody
	 * believing a second reversal had happened. Where the two disagree, the one that moves goods says
	 * so out loud.
	 */
	@Transactional
	public void voidDonation(AuthenticatedUser actor, UUID donationId, String reason) {
		String written = reason == null ? "" : reason.trim();
		if (written.isEmpty()) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "reason"));
		}

		// FOR UPDATE, because the check below and the write after it must not straddle another
		// admin's void. Without the lock two tabs both read "not voided" and both reverse the stock,
		// and the second reversal is the one nobody would ever go looking for.
		Map<String, Object> donation = jdbc.queryForList("""
				SELECT id, type, donor_name, is_anonymous, amount_inr, estimated_value_inr,
					   donated_on, status, voided_at
				FROM donations WHERE id = ? FOR UPDATE
				""", donationId).stream().findFirst()
				.orElseThrow(() -> new ApplicationException(
						ErrorCode.RESOURCE_NOT_FOUND, Map.of("donationId", donationId)));

		if (donation.get("voided_at") != null) {
			throw new ApplicationException(
					ErrorCode.DONATION_ALREADY_VOIDED, Map.of("donationId", donationId));
		}

		jdbc.update("""
				UPDATE donations SET voided_at = now(), voided_by = ?, void_reason = ? WHERE id = ?
				""", actor.getUserId(), written, donationId);

		int reversed = reverseGoods(actor, donationId, written);

		auditService.record(actor, AuditAction.DONATION_VOIDED, AuditEntityType.DONATION, donationId,
				before(donation), after(donationId, reversed), written);
	}

	// ---------------------------------------------------------------------

	/**
	 * Appends the reverse of every stock movement this donation brought in, and returns how many.
	 *
	 * <p>There is no link column to find them by and none is needed: {@code DonationRecorder:77}
	 * writes each in-kind line with {@code reference_type = 'DONATION'} and the donation's own id, so
	 * the donation's movements are exactly that pair. RLS scopes the query to the one temple, as it
	 * does every other query here.
	 *
	 * <p><strong>A movement somebody has already corrected by hand is skipped rather than refused.</strong>
	 * {@code compensate} allows one correction per movement and answers a second with
	 * {@code MOVEMENT_ALREADY_CORRECTED} — right for the inventory screen, wrong here. The point of a
	 * void is that the shelf ends up where it was, and for an already-corrected movement it already
	 * is; letting that refusal out would leave the 80G figure permanently inflated, and would explain
	 * why by naming a stock movement to somebody who was striking a donation. So the query asks for
	 * the movements that still stand, and the audit entry below records both counts so the trail says
	 * what was actually reversed rather than what was asked for.
	 */
	private int reverseGoods(AuthenticatedUser actor, UUID donationId, String reason) {
		List<UUID> standing = jdbc.query("""
				SELECT m.id FROM stock_movements m
				WHERE m.reference_type = 'DONATION' AND m.reference_id = ?
				  AND NOT EXISTS (
					  SELECT 1 FROM stock_movements c
					  WHERE c.reference_type = 'CORRECTION' AND c.reference_id = m.id)
				ORDER BY m.created_at, m.id
				""", (rs, n) -> rs.getObject("id", UUID.class), donationId);

		for (UUID movementId : standing) {
			// compensate() files its own STOCK_MOVEMENT_CORRECTED. Two audit entries for one act, and
			// deliberately: the store-room's ledger has its own readers, and an entry about a
			// donation is not one they would find.
			stockMovementService.compensate(actor, movementId, "Donation voided: " + reason);
		}
		return standing.size();
	}

	/** The gift as it stood, from the row itself. */
	private static Map<String, Object> before(Map<String, Object> donation) {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("type", donation.get("type"));
		s.put("donor", Boolean.TRUE.equals(donation.get("is_anonymous"))
				? "Anonymous" : donation.get("donor_name"));
		s.put("donatedOn", String.valueOf(donation.get("donated_on")));
		s.put("amountInr", plain(donation.get("amount_inr")));
		s.put("estimatedValueInr", plain(donation.get("estimated_value_inr")));
		s.put("status", donation.get("status"));
		s.put("voided", false);
		return s;
	}

	/**
	 * The gift as it now stands — <strong>read back from the row, never rebuilt from the request.</strong>
	 *
	 * <p>An audit trail that reports what was asked for rather than what was stored is worse than no
	 * entry at all, because it is believed. Wave 4b found one claiming a temple's coordinates had
	 * moved from "12.971600" to "12.9716" in a field nobody had edited; the same shape of mistake here
	 * would have this entry report a timestamp the database never wrote. So the values below come out
	 * of the table after the UPDATE, at whatever precision it actually kept.
	 */
	private Map<String, Object> after(UUID donationId, int reversed) {
		Map<String, Object> stored = jdbc.queryForMap("""
				SELECT voided_at, voided_by, void_reason, status FROM donations WHERE id = ?
				""", donationId);

		Map<String, Object> s = new LinkedHashMap<>();
		s.put("voided", true);
		s.put("voidedAt", String.valueOf(stored.get("voided_at") instanceof OffsetDateTime t
				? t.toInstant() : stored.get("voided_at")));
		s.put("voidedBy", String.valueOf(stored.get("voided_by")));
		s.put("voidReason", stored.get("void_reason"));
		// Untouched, and said out loud: `status` is how the payment went and a gift can complete and
		// then be voided. Folding the two would make one column answer two questions.
		s.put("status", stored.get("status"));
		s.put("stockMovementsReversed", reversed);
		s.put("stockMovementsFromThisGift", movementCount(donationId));
		return s;
	}

	private int movementCount(UUID donationId) {
		Integer count = jdbc.queryForObject("""
				SELECT count(*) FROM stock_movements WHERE reference_type = 'DONATION' AND reference_id = ?
				""", Integer.class, donationId);
		return count == null ? 0 : count;
	}

	private static String plain(Object value) {
		return value instanceof BigDecimal amount ? amount.toPlainString() : null;
	}
}
