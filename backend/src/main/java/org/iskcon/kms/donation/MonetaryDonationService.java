package org.iskcon.kms.donation;

import org.iskcon.kms.security.PanCipher;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Monetary donations and the 80G donor-capture rules (E7-S4). Creates the local donation record with
 * exactly the fields the donor's chosen path allows — a named gift keeps name and contact, an 80G
 * one adds address and PAN (encrypted). The payment lifecycle (order, webhook confirmation) is
 * layered on by E7-S2/S3/S6; this owns the record and the donor-data integrity.
 *
 * <p>There were three paths until 2026-08-29, and the third was anonymous: no account, no name,
 * nothing kept. Giving now requires a signed-in devotee and every online gift carries their name,
 * so two paths remain — named, and named with an 80G certificate. Anonymity survives only where a
 * person never touched this application at all: a member of staff recording a gift somebody left at
 * the temple, which is {@code DonationRecorder}, not this class.
 */
@Service
public class MonetaryDonationService {

	private static final org.slf4j.Logger log =
			org.slf4j.LoggerFactory.getLogger(MonetaryDonationService.class);

	private static final Pattern PAN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
	private static final int PENDING_TTL_MINUTES = 30;

	private final JdbcTemplate jdbc;
	private final PanCipher panCipher;
	private final AuditService auditService;
	private final org.iskcon.kms.payment.PaymentGatewayResolver gateways;
	private final org.iskcon.kms.notification.NotificationService notificationService;
	private final org.iskcon.kms.wishlist.WishlistService wishlistService;
	private final org.springframework.transaction.support.TransactionTemplate transactions;

	public MonetaryDonationService(JdbcTemplate jdbc, PanCipher panCipher, AuditService auditService,
			org.iskcon.kms.payment.PaymentGatewayResolver gateways,
			org.iskcon.kms.notification.NotificationService notificationService,
			org.iskcon.kms.wishlist.WishlistService wishlistService,
			org.springframework.transaction.PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.panCipher = panCipher;
		this.auditService = auditService;
		this.gateways = gateways;
		this.notificationService = notificationService;
		this.wishlistService = wishlistService;
		this.transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
	}

	/**
	 * Opens a one-time donation (E7-S2): creates the provider order, records a PENDING donation
	 * against it, and returns what the client needs to open hosted checkout. Confirmation is by
	 * webhook, never by the client's redirect — a PENDING record only becomes COMPLETED when the
	 * signed webhook says so.
	 */
	@Transactional
	public DonationCheckout startCheckout(DonorDetails donor, java.math.BigDecimal amountInr,
			UUID wishlistItemId, UUID accountUserId) {
		var paymentGateway = gateways.forCurrentTenant();
		long minorUnits = amountInr.movePointRight(2).longValueExact();
		String idempotencyKey = UUID.randomUUID().toString();
		org.iskcon.kms.payment.PaymentOrder order = paymentGateway.createOrder(
				minorUnits, "INR", "donation-" + idempotencyKey, Map.of("idempotencyKey", idempotencyKey));
		UUID donationId = createDonation(new DonationDraft("ONE_TIME", amountInr, paymentGateway.name(),
				order.orderId(), idempotencyKey, wishlistItemId, null, accountUserId, donor));
		return new DonationCheckout(donationId, order.orderId(), paymentGateway.publicKey(),
				amountInr, "INR", paymentGateway.name());
	}

	/**
	 * Towards a wish-list item (E7-S6): {@code amountInr} rupees of what the item costs. An item is
	 * owed its price times the quantity wanted, and a devotee may put in any part of that — the
	 * temple buys the thing whole, so what is given towards one is money and never a share of the
	 * object. What is left is re-checked here, but the race for the last of it is settled at webhook
	 * confirmation (see {@link #completePayment}).
	 *
	 * <p>{@code accountUserId} ties the gift to the devotee who made it. It is null only where a
	 * donation has no devotee behind it at all — a gift recorded at the temple office by a member of
	 * staff — and never for anything begun on a screen.
	 */
	@Transactional
	public DonationCheckout startWishlistCheckout(DonorDetails donor, UUID itemId,
			java.math.BigDecimal amountInr, UUID accountUserId) {
		Map<String, Object> item;
		try {
			item = jdbc.queryForMap(
					"SELECT price_inr, quantity_wanted, status FROM wishlist_items WHERE id = ?", itemId);
		} catch (org.springframework.dao.EmptyResultDataAccessException e) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("wishlistItemId", itemId), e);
		}
		if (!"ACTIVE".equals(item.get("status"))) {
			throw new ApplicationException(ErrorCode.WISHLIST_ITEM_UNAVAILABLE, Map.of("wishlistItemId", itemId));
		}
		java.math.BigDecimal price = (java.math.BigDecimal) item.get("price_inr");

		// The temple buys a grinder outright, so a devotee putting ₹500 towards one is giving money,
		// not buying half a grinder. Capped at what is still owed, so an item cannot be over-funded
		// by a page drawn before somebody else finished paying for it.
		java.math.BigDecimal owed = price
				.multiply(java.math.BigDecimal.valueOf(((Number) item.get("quantity_wanted")).intValue()))
				.subtract(completedAmount(itemId));
		if (amountInr == null || amountInr.signum() <= 0 || owed.signum() <= 0) {
			throw new ApplicationException(ErrorCode.WISHLIST_ITEM_UNAVAILABLE,
					Map.of("wishlistItemId", itemId));
		}
		java.math.BigDecimal amount = amountInr.min(owed);

		var paymentGateway = gateways.forCurrentTenant();
		long minorUnits = amount.movePointRight(2).longValueExact();
		String idempotencyKey = UUID.randomUUID().toString();
		org.iskcon.kms.payment.PaymentOrder order = paymentGateway.createOrder(
				minorUnits, "INR", "sponsor-" + idempotencyKey, Map.of("idempotencyKey", idempotencyKey));
		UUID donationId = createDonation(new DonationDraft("ONE_TIME", amount, paymentGateway.name(),
				order.orderId(), idempotencyKey, itemId, null, accountUserId, donor));
		return new DonationCheckout(donationId, order.orderId(), paymentGateway.publicKey(),
				amount, "INR", paymentGateway.name());
	}

	/** Confirms a donation from a captured-payment webhook (E7-S2). Idempotent: only PENDING advances. */
	public void completePayment(String orderId, String paymentId, String method) {
		Located located = locateByOrder(orderId);
		if (located == null || !"PENDING".equals(located.status())) {
			return; // unknown order, or already terminal
		}
		org.iskcon.kms.tenancy.TenantContext.set(located.tenantId());
		try {
			// The money is settled in one transaction opened *after* the tenant is set, because the
			// tenant reaches the database when the connection is checked out — a transaction begun
			// before it would hold a connection that every RLS policy refuses.
			Settlement settlement = transactions.execute(status -> settle(located, paymentId, method));

			// Notifications are sent after the money has committed, so a notification that fails
			// cannot undo a payment the provider has already taken.
			if (settlement == Settlement.CONVERTED) {
				notifyConverted(located.id());
			} else if (settlement == Settlement.COMPLETED) {
				sendThankYou(located.id());
			}
		} finally {
			org.iskcon.kms.tenancy.TenantContext.clear();
		}
	}

	/**
	 * Completes one donation against its wish-list item, with the item held for the duration.
	 *
	 * <p>The lock is the point. Two devotees who press "cover the rest" within the same second both
	 * opened a checkout against an item that was still owed the full amount, and both payments are
	 * captured before either is recorded — so the room left has to be re-read here, one payment at a
	 * time, rather than trusted from when the page was drawn. A gift that no longer fits is honoured
	 * as a general donation, which is what E7-S6 already does when the last unit is taken: the temple
	 * keeps money it can spend on the kitchen, and the donor is told plainly what happened.
	 */
	private Settlement settle(Located located, String paymentId, String method) {
		boolean convert = located.wishlistItemId() != null && noLongerFits(located);
		if (convert) {
			int updated = jdbc.update("""
					UPDATE donations SET status = 'COMPLETED', provider_payment_id = ?, payment_mode = ?,
						wishlist_item_id = NULL
					WHERE id = ? AND status = 'PENDING'
					""", paymentId, method, located.id());
			return updated > 0 ? Settlement.CONVERTED : Settlement.NOTHING;
		}
		int updated = jdbc.update("""
				UPDATE donations SET status = 'COMPLETED', provider_payment_id = ?, payment_mode = ?
				WHERE id = ? AND status = 'PENDING'
				""", paymentId, method, located.id());
		if (updated == 0) {
			return Settlement.NOTHING;
		}
		if (located.wishlistItemId() != null) {
			wishlistService.markFulfilledIfComplete(located.wishlistItemId());
		}
		return Settlement.COMPLETED;
	}

	/**
	 * Whether this gift still fits in what the item is owed: its price times the quantity wanted,
	 * less the money already given.
	 *
	 * <p>Takes the item's row for the duration of the transaction, so a second payment being settled
	 * at the same moment waits rather than reading the same room twice.
	 */
	private boolean noLongerFits(Located located) {
		Map<String, Object> item;
		try {
			item = jdbc.queryForMap(
					"SELECT price_inr, quantity_wanted FROM wishlist_items WHERE id = ? FOR UPDATE",
					located.wishlistItemId());
		} catch (EmptyResultDataAccessException e) {
			return true; // the item is gone; the gift becomes a general donation
		}
		int wanted = ((Number) item.get("quantity_wanted")).intValue();

		java.math.BigDecimal cost = ((java.math.BigDecimal) item.get("price_inr"))
				.multiply(java.math.BigDecimal.valueOf(wanted));
		java.math.BigDecimal owed = cost.subtract(completedAmount(located.wishlistItemId()));
		return located.amountInr().compareTo(owed) > 0;
	}

	/** What became of a payment being settled: the donation it was for, a general gift, or nothing. */
	private enum Settlement { COMPLETED, CONVERTED, NOTHING }

	/** Marks a donation FAILED from a failed-payment webhook (E7-S2). Idempotent. */
	public void failPayment(String orderId) {
		Located located = locateByOrder(orderId);
		if (located == null || !"PENDING".equals(located.status())) {
			return;
		}
		org.iskcon.kms.tenancy.TenantContext.set(located.tenantId());
		try {
			jdbc.update("UPDATE donations SET status = 'FAILED' WHERE id = ? AND status = 'PENDING'", located.id());
		} finally {
			org.iskcon.kms.tenancy.TenantContext.clear();
		}
	}

	/**
	 * Expires the current tenant's abandoned PENDING online donations (E7-S2 cleanup sweep), after
	 * asking the provider whether each one was in fact paid for.
	 *
	 * <p>The question has to be asked. A PENDING donation past its TTL usually means a donor closed
	 * the checkout, but it can also mean the donor paid and the webhook never reached us — most
	 * plainly when a temple has not yet registered the webhook in its provider's dashboard, which is
	 * a step a person has to remember. Expiring on the clock alone would take a real gift, leave the
	 * money sitting at the provider, and record nothing anywhere the temple would ever look.
	 *
	 * <p>So each one is checked, and only an order the provider says was never paid is written off.
	 * A payment that was taken is completed through the same path a webhook uses. If the provider
	 * cannot be reached, the donation is left PENDING for the next sweep to ask about again: an
	 * intent nobody can see is a much smaller problem than a gift nobody can find.
	 *
	 * <p>Returns the number actually expired, which is what the job logs.
	 */
	public int expirePendingForCurrentTenant() {
		UUID tenantId = org.iskcon.kms.tenancy.TenantContext.get().orElse(null);
		List<Map<String, Object>> due = jdbc.queryForList("""
				SELECT id, provider_order_id, provider FROM donations
				WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at < now()
				""");
		if (due.isEmpty()) {
			return 0;
		}

		org.iskcon.kms.payment.PaymentGateway gateway = gateways.forCurrentTenant();
		int expired = 0;
		for (Map<String, Object> row : due) {
			UUID id = (UUID) row.get("id");
			String orderId = (String) row.get("provider_order_id");
			String orderProvider = (String) row.get("provider");

			// Only the provider that created an order can be asked about it. A temple that has since
			// connected a real gateway still has old orders belonging to the one before it, and asking
			// Razorpay about an order_stub_* it has never heard of fails every time — which left those
			// donations pending for ever, warning once an hour, and never resolving either way. An
			// order whose provider is gone cannot have been paid through the one that is here now.
			boolean askable = orderId != null && gateway.name().equalsIgnoreCase(orderProvider);

			java.util.Optional<org.iskcon.kms.payment.PaymentGateway.CapturedPayment> paid;
			try {
				paid = askable ? gateway.findCapturedPayment(orderId) : java.util.Optional.empty();
			} catch (RuntimeException e) {
				log.warn("Could not ask the provider about order {}; leaving it pending: {}",
						orderId, e.toString());
				continue;
			}

			if (paid.isPresent()) {
				log.info("Donation {} was paid but never confirmed by webhook; completing it now.", id);
				completePayment(orderId, paid.get().paymentId(), paid.get().method());
				// completePayment sets and then clears the tenant for its own transaction, so the
				// context this sweep is running under has to be put back before the next row.
				if (tenantId != null) {
					org.iskcon.kms.tenancy.TenantContext.set(tenantId);
				}
				continue;
			}

			expired += jdbc.update(
					"UPDATE donations SET status = 'EXPIRED' WHERE id = ? AND status = 'PENDING'", id);
		}
		return expired;
	}

	/** Creates a PENDING monetary donation from a draft, applying the donor path rules. */
	@Transactional
	public UUID createDonation(DonationDraft draft) {
		DonorDetails d = draft.donor();
		Resolved r = resolveDonor(d);

		UUID id = UUID.randomUUID();
		jdbc.update(connection -> {
			PreparedStatement ps = connection.prepareStatement("""
					INSERT INTO donations (
						id, tenant_id, type, amount_inr, currency, status, is_anonymous,
						donor_name, donor_phone, donor_email, donor_address, donor_pan_ciphertext,
						wants_80g, section, consent_at, provider, provider_order_id, idempotency_key,
						wishlist_item_id, recurring_plan_id, donor_account_user_id,
						donated_on, expires_at)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, 'INR', 'PENDING', ?,
						?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_DATE,
						now() + (interval '1 minute' * ?))
					""");
			ps.setObject(1, id);
			ps.setString(2, draft.type());
			ps.setBigDecimal(3, draft.amountInr());
			// Never anonymous. Every gift that begins on a screen begins in somebody's account, and
			// the column exists for the other path entirely: a gift left at the temple and recorded
			// by a member of staff, which DonationRecorder writes and which may well be anonymous.
			ps.setBoolean(4, false);
			ps.setString(5, r.name());
			ps.setString(6, r.phone());
			ps.setString(7, r.email());
			ps.setString(8, r.address());
			ps.setBytes(9, r.panCiphertext());
			ps.setBoolean(10, r.wants80g());
			ps.setString(11, r.section());
			ps.setObject(12, r.consentAt());
			ps.setString(13, draft.provider());
			ps.setString(14, draft.providerOrderId());
			ps.setString(15, draft.idempotencyKey());
			ps.setObject(16, draft.wishlistItemId());
			ps.setObject(17, draft.recurringPlanId());
			ps.setObject(18, draft.donorAccountUserId());
			ps.setInt(19, PENDING_TTL_MINUTES);
			return ps;
		});
		if (r.panFingerprint() != null) {
			jdbc.update("UPDATE donations SET pan_fingerprint = ? WHERE id = ?", r.panFingerprint(), id);
		}
		return id;
	}

	/** Decrypts a donor's PAN for a Temple Admin, recording the access. Null if the donation has none. */
	@Transactional
	public String revealPan(UUID donationId, AuthenticatedUser actor) {
		byte[] ciphertext;
		try {
			ciphertext = jdbc.queryForObject(
					"SELECT donor_pan_ciphertext FROM donations WHERE id = ?", byte[].class, donationId);
		} catch (EmptyResultDataAccessException e) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("donationId", donationId), e);
		}
		if (ciphertext == null) {
			return null;
		}
		String pan = panCipher.decrypt(ciphertext);
		auditService.record(actor, AuditAction.DONOR_PAN_VIEWED, AuditEntityType.DONATION, donationId,
				null, Map.of("panLast4", pan.substring(Math.max(0, pan.length() - 4))), null);
		return pan;
	}

	/** The Form 10BD-shaped dataset for completed 80G donations (E7-S4 contract for the Phase-2 export). */
	@Transactional(readOnly = true)
	public List<Form10bdRow> form10bdRows() {
		return jdbc.query("""
				SELECT donor_name, donor_address, donor_pan_ciphertext, amount_inr, payment_mode, section
				FROM donations WHERE wants_80g = true AND status = 'COMPLETED' ORDER BY created_at
				""", (rs, n) -> {
			byte[] ct = rs.getBytes("donor_pan_ciphertext");
			return new Form10bdRow(rs.getString("donor_name"), rs.getString("donor_address"),
					ct == null ? null : panCipher.decrypt(ct), rs.getBigDecimal("amount_inr"),
					rs.getString("payment_mode"), rs.getString("section"));
		});
	}

	// ---------------------------------------------------------------------

	/**
	 * The donor's own details, as far as the path they chose allows.
	 *
	 * <p>Two arms went on 2026-08-29 with the public giving page: an anonymous branch that returned
	 * a record with every field null, and a consent check. Both were only ever reachable from a
	 * stranger's form — a signed-in devotee arrives through {@code DonorDetails.ofAccount}, which
	 * has always supplied a name and a consent already given by the act of holding an account.
	 * Keeping unreachable branches here would have left the shape of the old feature in the code
	 * for somebody to wonder about, or to wire back up by accident.
	 */
	private Resolved resolveDonor(DonorDetails d) {
		String name = trimToNull(d.name());
		if (name == null) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "name"));
		}
		OffsetDateTime consentAt = OffsetDateTime.now();
		String phone = trimToNull(d.phone());
		String email = trimToNull(d.email());

		if (!d.wants80g()) {
			return new Resolved(name, phone, email, null, null, false, null, consentAt, null);
		}
		if (!tenantIs80gApproved()) {
			throw new ApplicationException(ErrorCode.DONOR_80G_NOT_AVAILABLE, Map.of());
		}
		String address = trimToNull(d.address());
		if (address == null) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "address"));
		}
		String pan = d.pan() == null ? null : d.pan().trim().toUpperCase();
		if (pan == null || !PAN.matcher(pan).matches()) {
			throw new ApplicationException(ErrorCode.INVALID_PAN, Map.of());
		}
		return new Resolved(name, phone, email, address, panCipher.encrypt(pan), true, "80G", consentAt,
				panCipher.fingerprint(pan));
	}

	/** Finds a donation by its provider order id through the webhook RLS escape, before the tenant is known. */
	private Located locateByOrder(String orderId) {
		org.iskcon.kms.tenancy.TenantContext.setWebhookMessageId(orderId);
		try {
			List<Located> rows = jdbc.query("""
					SELECT id, tenant_id, status, amount_inr, wishlist_item_id
					FROM donations WHERE provider_order_id = ?
					""",
					(rs, n) -> new Located(rs.getObject("id", UUID.class),
							rs.getObject("tenant_id", UUID.class), rs.getString("status"),
							rs.getBigDecimal("amount_inr"),
							rs.getObject("wishlist_item_id", UUID.class)), orderId);
			return rows.isEmpty() ? null : rows.get(0);
		} finally {
			org.iskcon.kms.tenancy.TenantContext.clearWebhookMessageId();
		}
	}

	/** Money already given towards this item, by any road: gateway, or cash taken at the office. */
	private java.math.BigDecimal completedAmount(UUID itemId) {
		java.math.BigDecimal paid = jdbc.queryForObject("""
				SELECT COALESCE(SUM(amount_inr), 0) FROM donations
				WHERE wishlist_item_id = ? AND status = 'COMPLETED'
				""", java.math.BigDecimal.class, itemId);
		return paid == null ? java.math.BigDecimal.ZERO : paid;
	}

	private void notifyConverted(UUID donationId) {
		try {
			Map<String, Object> d = jdbc.queryForMap("""
					SELECT donor_name, donor_phone, donor_email, is_anonymous FROM donations WHERE id = ?
					""", donationId);
			if ((Boolean) d.get("is_anonymous") || (d.get("donor_phone") == null && d.get("donor_email") == null)) {
				return;
			}
			String temple = jdbc.queryForObject("""
					SELECT name FROM tenants WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""", String.class);
			notificationService.notify(
					org.iskcon.kms.notification.NotificationRecipient.contact(
							(String) d.get("donor_phone"), (String) d.get("donor_email")),
					org.iskcon.kms.notification.NotificationTemplate.WISHLIST_SPONSORSHIP_CONVERTED,
					Map.of("temple", temple == null ? "the temple" : temple), null);
			jdbc.update("UPDATE donations SET acknowledged_at = now() WHERE id = ?", donationId);
		} catch (RuntimeException e) {
			// best-effort
		}
	}

	/** Best-effort acknowledgement to a reachable, non-anonymous donor (E7-S2). Not the 80G certificate. */
	private void sendThankYou(UUID donationId) {
		try {
			Map<String, Object> d = jdbc.queryForMap("""
					SELECT donor_name, donor_phone, donor_email, is_anonymous FROM donations WHERE id = ?
					""", donationId);
			if ((Boolean) d.get("is_anonymous") || (d.get("donor_phone") == null && d.get("donor_email") == null)) {
				return;
			}
			String temple = jdbc.queryForObject("""
					SELECT name FROM tenants WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""", String.class);
			notificationService.notify(
					org.iskcon.kms.notification.NotificationRecipient.contact(
							(String) d.get("donor_phone"), (String) d.get("donor_email")),
					org.iskcon.kms.notification.NotificationTemplate.DONATION_THANK_YOU,
					Map.of("donor", d.get("donor_name") == null ? "" : d.get("donor_name").toString(),
							"temple", temple == null ? "the temple" : temple,
							"date", java.time.LocalDate.now().toString()),
					null);
			jdbc.update("UPDATE donations SET acknowledged_at = now() WHERE id = ?", donationId);
		} catch (RuntimeException e) {
			// The gift is recorded; a thank-you we couldn't queue must not undo that.
		}
	}

	private boolean tenantIs80gApproved() {
		Boolean approved = jdbc.queryForObject("""
				SELECT is_80g_approved FROM tenants WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", Boolean.class);
		return Boolean.TRUE.equals(approved);
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	private record Resolved(String name, String phone, String email, String address,
			byte[] panCiphertext, boolean wants80g, String section, OffsetDateTime consentAt,
			String panFingerprint) {
	}

	private record Located(UUID id, UUID tenantId, String status, java.math.BigDecimal amountInr,
			UUID wishlistItemId) {
	}
}
