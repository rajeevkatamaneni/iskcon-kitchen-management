package org.iskcon.kms.donation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.payment.PaymentWebhookVerifier;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Wish-list sponsorship (E7-S6): a sponsorship completes and links to its item, and the race for the
 * last of what an item is owed resolves as one sponsorship + one converted general donation, never an
 * orphaned charge.
 *
 * <p>It sponsored through a public form until 2026-08-29 and now sponsors from a signed-in account,
 * which is a change of who may open a checkout and of nothing that happens afterwards.
 *
 * <p>A third test went with that form: it proved that a sponsor who asked to stay anonymous was left
 * out of the "Sponsored by…" list a stranger could read. There is no such list any more — public
 * recognition was the one thing on the withdrawn controller with nothing to move to — and no
 * anonymous online donor either, since every gift now carries the account that made it.
 */
@AutoConfigureMockMvc
@Import(WishlistSponsorshipIT.StubVerifierConfiguration.class)
class WishlistSponsorshipIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PaymentWebhookVerifier verifier;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-devotee', 'Radha Devi', 'radha@example.com', '+919812345678', 'VOLUNTEER', 'ACTIVE')
				""", tenant);
		stubVerifier.accept("uid-devotee");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM payment_events");
		admin.execute("DELETE FROM donations");
		admin.execute("DELETE FROM wishlist_items");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a sponsorship completes, links to the item, and covers it under the sponsor's own name")
	void sponsorshipCompletes() throws Exception {
		// Two sacks at ₹1,000: an item is owed its price times what is wanted, so covering it is
		// ₹2,000 — the multi-unit case, which single-item wish lists never reach.
		UUID item = item("Rice sacks", 1000, 2);
		String orderId = sponsor(item, 2000);
		captured(orderId, "pay_stub_1", "evt-1");

		var row = admin.queryForMap("""
				SELECT status, wishlist_item_id, donor_name FROM donations WHERE provider_order_id = ?
				""", orderId);
		assert "COMPLETED".equals(row.get("status"));
		assert item.equals(row.get("wishlist_item_id"));
		// The sponsor is named, and named from their account rather than from anything they typed.
		assert "Radha Devi".equals(row.get("donor_name")) : "was " + row.get("donor_name");
		assert statusOf(item).equals("FULFILLED") : "the whole ₹2,000 is in hand → fulfilled";
	}

	@Test
	@DisplayName("two checkouts for the last of what an item is owed: one keeps it, the other becomes a general gift")
	void oversubscriptionRace() throws Exception {
		UUID item = item("New mixer", 15000, 1);
		// Both start while nothing is COMPLETED yet, so both are allowed as PENDING.
		String orderA = sponsor(item, 15000);
		String orderB = sponsor(item, 15000);

		captured(orderA, "pay_stub_a", "evt-a"); // covers the item
		captured(orderB, "pay_stub_b", "evt-b"); // arrives after — converted

		Integer sponsored = admin.queryForObject(
				"SELECT count(*) FROM donations WHERE wishlist_item_id = ? AND status = 'COMPLETED'", Integer.class, item);
		assert sponsored == 1 : "exactly one sponsorship keeps the item, was " + sponsored;
		Integer converted = admin.queryForObject("""
				SELECT count(*) FROM donations WHERE provider_order_id = ? AND status = 'COMPLETED' AND wishlist_item_id IS NULL
				""", Integer.class, orderB);
		assert converted == 1 : "the loser is a completed general donation, not a failed charge";
		assert statusOf(item).equals("FULFILLED");
	}

	@Test
	@DisplayName("striking a gift puts an item back within a donor's reach at checkout")
	void aStruckGiftReleasesTheCheckoutCap() throws Exception {
		// The room left in an item is its cost less what has been given, and a struck gift (V104,
		// T-012) was never given. The mark is a `voided_at` column rather than a status value, so a
		// struck gift keeps `status = 'COMPLETED'` and this cap went on counting it — refusing a
		// devotee a gift the temple genuinely still needs. This is the donor-facing half of the
		// defect: the display figure is only a number on a screen, but the cap is a closed door.
		UUID item = item("New mixer", 15000, 1);

		// ₹15,000 taken at the office and entered twice. Recorded by hand, so nothing flips the item:
		// it is still ACTIVE and, on paper, already paid for.
		UUID mistake = cashGift(item, 15000);

		mvc.perform(post("/api/v1/donations/wishlist/{id}", item)
						.header("Authorization", "Bearer valid-token")
						.contentType("application/json").content("{\"amountInr\":15000}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400068"));

		strike(mistake, "Entered twice at the gate.");

		// The same devotee, the same item, the same amount — and now it is taken.
		String orderId = sponsor(item, 15000);
		captured(orderId, "pay_stub_released", "evt-released");

		var row = admin.queryForMap("""
				SELECT status, wishlist_item_id, amount_inr FROM donations WHERE provider_order_id = ?
				""", orderId);
		assertThat(row.get("status")).isEqualTo("COMPLETED");
		assertThat(row.get("wishlist_item_id")).isEqualTo(item);
		assertThat((java.math.BigDecimal) row.get("amount_inr"))
				.as("the cap moved with the void, so the whole gift was allowed")
				.isEqualByComparingTo("15000");
		assertThat(statusOf(item)).isEqualTo("FULFILLED");
	}

	@Test
	@DisplayName("a payment settling after the gift that covered the item was struck is kept, not converted")
	void aStruckGiftMakesRoomOnTheCapturePath() throws Exception {
		// The other side of the same sum. `noLongerFits` re-reads the room left as each payment
		// settles, and a gift that no longer fits is honoured as a general donation and the donor
		// told so. If a struck gift still counted here, a devotee's money would be diverted away
		// from the item they chose because of a gift the temple had already decided never happened.
		UUID item = item("New mixer", 15000, 1);
		String orderA = sponsor(item, 15000);
		String orderB = sponsor(item, 15000); // both opened while nothing was COMPLETED

		captured(orderA, "pay_stub_a", "evt-a");
		assertThat(statusOf(item)).isEqualTo("FULFILLED");

		strike(donationFor(orderA), "Charged against the wrong donor's card.");

		captured(orderB, "pay_stub_b", "evt-b");

		var row = admin.queryForMap("""
				SELECT status, wishlist_item_id FROM donations WHERE provider_order_id = ?
				""", orderB);
		assertThat(row.get("status")).isEqualTo("COMPLETED");
		assertThat(row.get("wishlist_item_id"))
				.as("the item is owed its full price again, so the gift stays with it")
				.isEqualTo(item);
	}

	@Test
	@DisplayName("an item already FULFILLED still refuses a new gift after its own gift is struck")
	void anAlreadyFulfilledItemStaysClosedToGiving() throws Exception {
		// Recorded rather than fixed. Nothing re-evaluates an item once it is FULFILLED, and a
		// checkout is refused on the item's status before the sum is ever consulted
		// (MonetaryDonationService:105). So striking the gift behind a fulfilled item empties its
		// progress figure and leaves the door shut: the giving page shows the item at ₹0 of ₹15,000
		// and a devotee who presses Give is turned away. Whether such an item should reopen is
		// Rajeev's to decide — see docs/work/proof/T-069.md.
		UUID item = item("New mixer", 15000, 1);
		String orderId = sponsor(item, 15000);
		captured(orderId, "pay_stub_f", "evt-f");
		assertThat(statusOf(item)).isEqualTo("FULFILLED");

		strike(donationFor(orderId), "Chargeback: the payment was reversed by the bank.");

		mvc.perform(post("/api/v1/donations/wishlist/{id}", item)
						.header("Authorization", "Bearer valid-token")
						.contentType("application/json").content("{\"amountInr\":15000}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400068"));

		assertThat(statusOf(item)).isEqualTo("FULFILLED");
		assertThat(admin.queryForObject("""
				SELECT COALESCE(SUM(amount_inr), 0) FROM donations
				WHERE wishlist_item_id = ? AND status = 'COMPLETED' AND voided_at IS NULL
				""", java.math.BigDecimal.class, item))
				.as("nothing stands towards it any more, yet it reads as fulfilled")
				.isEqualByComparingTo("0");
	}

	// ---------------------------------------------------------------------

	private UUID item(String title, int price, int qty) {
		return admin.queryForObject("""
				INSERT INTO wishlist_items (tenant_id, title, price_inr, category, quantity_wanted, status)
				VALUES (?, ?, ?::numeric, 'CONSUMABLE', ?, 'ACTIVE') RETURNING id
				""", UUID.class, tenant, title, price, qty);
	}

	/** A signed-in devotee putting {@code amountInr} rupees towards the item, and the order it opened. */
	private String sponsor(UUID item, int amountInr) throws Exception {
		String body = mvc.perform(post("/api/v1/donations/wishlist/{id}", item)
						.header("Authorization", "Bearer valid-token")
						.contentType("application/json").content("{\"amountInr\":" + amountInr + "}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(body).get("orderId").asText();
	}

	private void captured(String orderId, String paymentId, String eventId) throws Exception {
		byte[] payload = ("{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{"
				+ "\"id\":\"" + paymentId + "\",\"order_id\":\"" + orderId + "\",\"method\":\"upi\"}}}}")
				.getBytes(StandardCharsets.UTF_8);
		mvc.perform(post("/api/v1/public/webhooks/razorpay")
						.header("X-Razorpay-Signature", verifier.sign(payload))
						.header("X-Razorpay-Event-Id", eventId)
						.content(payload))
				.andExpect(status().isOk());
	}

	private String statusOf(UUID item) {
		return admin.queryForObject("SELECT status FROM wishlist_items WHERE id = ?", String.class, item);
	}

	/** Cash handed over at the office and entered against the item — completed the moment it is written. */
	private UUID cashGift(UUID item, int amountInr) {
		return admin.queryForObject("""
				INSERT INTO donations (tenant_id, type, amount_inr, status, is_anonymous, wishlist_item_id,
					donated_on)
				VALUES (?, 'ONE_TIME', ?, 'COMPLETED', true, ?, CURRENT_DATE)
				RETURNING id
				""", UUID.class, tenant, amountInr, item);
	}

	private UUID donationFor(String orderId) {
		return admin.queryForObject(
				"SELECT id FROM donations WHERE provider_order_id = ?", UUID.class, orderId);
	}

	/**
	 * Strikes a gift the way {@code DonationVoidService} does (V104): the row stays and its status is
	 * untouched — striking a gift says nothing about how the payment went — marked with who struck it
	 * and why. Written directly rather than through the void endpoint so these tests stay about the
	 * sums that read the mark, not about the act of voiding.
	 */
	private void strike(UUID donationId, String reason) {
		UUID actor = admin.queryForObject(
				"SELECT id FROM users WHERE firebase_uid = 'uid-devotee'", UUID.class);
		admin.update("""
				UPDATE donations SET voided_at = now(), voided_by = ?, void_reason = ? WHERE id = ?
				""", actor, reason, donationId);
	}

	@TestConfiguration
	static class StubVerifierConfiguration {

		@Bean
		@Primary
		StubTokenVerifier stubTokenVerifier() {
			return new StubTokenVerifier();
		}
	}

	static class StubTokenVerifier implements TokenVerifier {

		private final Map<String, VerifiedSubject> accepted = new HashMap<>();

		void accept(String uid) {
			accepted.put("valid-token", new VerifiedSubject(uid, uid + "@example.com", "+919812345678"));
		}

		void reset() {
			accepted.clear();
		}

		@Override
		public VerifiedSubject verify(String idToken) throws InvalidTokenException {
			VerifiedSubject subject = accepted.get(idToken);
			if (subject == null) {
				throw new InvalidTokenException("Unrecognised token");
			}
			return subject;
		}
	}
}
