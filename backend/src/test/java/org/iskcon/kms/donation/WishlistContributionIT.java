package org.iskcon.kms.donation;

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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Money towards a wish-list item, rather than whole units of one (E7-S6).
 *
 * <p>A temple buys a grinder outright, so what a devotee gives towards one is money — which means
 * everything the unit path settles in units has to be settled here in rupees: an item is covered when
 * the money is all there, and a gift that no longer fits is honoured as a general donation instead of
 * over-funding the item.
 *
 * <p>It also covers giving from inside the app, where the donor is the account rather than a form.
 */
@AutoConfigureMockMvc
@Import(WishlistContributionIT.StubVerifierConfiguration.class)
class WishlistContributionIT extends AbstractIntegrationTest {

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
	private UUID devotee;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		devotee = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-devotee', 'Radha Devi', 'radha@example.com', '+919812345678', 'VOLUNTEER', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant);
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
	@DisplayName("a grinder paid for in pieces is a grinder: the money covering the cost fulfils the item")
	void moneyCoveringTheCostFulfilsTheItem() throws Exception {
		UUID item = item("Commercial wet grinder", 42000, 1);

		captured(contribute(item, 40000), "pay_stub_a", "evt-a");
		assert "ACTIVE".equals(statusOf(item)) : "₹40,000 of ₹42,000 is not a grinder yet";

		captured(contribute(item, 2000), "pay_stub_b", "evt-b");
		assert "FULFILLED".equals(statusOf(item)) : "the whole cost is in hand, was " + statusOf(item);
		assert paidFor(item) == 42000 : "paid was " + paidFor(item);
	}

	@Test
	@DisplayName("two devotees covering the rest at once: one covers it, the other's gift becomes a general donation")
	void aGiftThatNoLongerFitsBecomesAGeneralDonation() throws Exception {
		UUID item = item("Steam cooking vessel", 18000, 1);

		// Both pages were drawn while nothing had completed, so both were allowed to open a checkout
		// for the whole ₹18,000 — the race the cap at checkout cannot see.
		String first = contribute(item, 18000);
		String second = contribute(item, 18000);

		captured(first, "pay_stub_a", "evt-a");
		captured(second, "pay_stub_b", "evt-b");

		assert paidFor(item) == 18000 : "the item must not be over-funded, was " + paidFor(item);
		assert "FULFILLED".equals(statusOf(item));

		Map<String, Object> converted = admin.queryForMap(
				"SELECT status, wishlist_item_id, amount_inr FROM donations WHERE provider_order_id = ?", second);
		assert "COMPLETED".equals(converted.get("status")) : "the money was taken; it is never refused here";
		assert converted.get("wishlist_item_id") == null : "the second gift is general, not against the item";
		assert ((java.math.BigDecimal) converted.get("amount_inr")).intValue() == 18000 : "kept whole";
	}

	@Test
	@DisplayName("a devotee giving from inside the app is the donor, without typing their own name")
	void givingFromInsideTheAppNeedsNoForm() throws Exception {
		signedIn();
		String body = mvc.perform(post("/api/v1/donations/one-time")
						.header("Authorization", "Bearer valid-token")
						.contentType("application/json").content("{\"amountInr\":1100}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.amountInr").value(1100))
				.andReturn().getResponse().getContentAsString();

		Map<String, Object> row = admin.queryForMap("""
				SELECT donor_name, donor_email, donor_account_user_id, is_anonymous, consent_at
				FROM donations WHERE provider_order_id = ?
				""", JSON.readTree(body).get("orderId").asText());
		assert "Radha Devi".equals(row.get("donor_name")) : "the name comes from the account";
		assert "radha@example.com".equals(row.get("donor_email"));
		assert devotee.equals(row.get("donor_account_user_id")) : "the gift is tied to the devotee";
		assert Boolean.FALSE.equals(row.get("is_anonymous"));
		assert row.get("consent_at") != null : "consent is recorded, not asked for again";
	}

	@Test
	@DisplayName("the same devotee giving towards a piece of equipment carries the item and the account")
	void givingTowardsEquipmentFromInsideTheApp() throws Exception {
		signedIn();
		UUID item = item("Commercial wet grinder", 42000, 1);
		String body = mvc.perform(post("/api/v1/donations/wishlist/{id}", item)
						.header("Authorization", "Bearer valid-token")
						.contentType("application/json").content("{\"amountInr\":500}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.amountInr").value(500))
				.andReturn().getResponse().getContentAsString();

		captured(JSON.readTree(body).get("orderId").asText(), "pay_stub_a", "evt-a");

		Map<String, Object> row = admin.queryForMap("""
				SELECT donor_name, donor_account_user_id, wishlist_item_id FROM donations
				WHERE wishlist_item_id = ?
				""", item);
		assert "Radha Devi".equals(row.get("donor_name"));
		assert devotee.equals(row.get("donor_account_user_id"));
		assert paidFor(item) == 500;
	}

	@Test
	@DisplayName("giving is for anyone signed in to the temple, and for no one who is not")
	void givingNeedsAnAccountButNoPermission() throws Exception {
		mvc.perform(post("/api/v1/donations/one-time")
						.contentType("application/json").content("{\"amountInr\":1100}"))
				.andExpect(status().isUnauthorized());

		// A VOLUNTEER holds no donation permission at all, and may still give.
		signedIn();
		mvc.perform(post("/api/v1/donations/one-time")
						.header("Authorization", "Bearer valid-token")
						.contentType("application/json").content("{\"amountInr\":1100}"))
				.andExpect(status().isCreated());
	}

	// ---- helpers ----------------------------------------------------------

	private void signedIn() {
		stubVerifier.accept("uid-devotee", "radha@example.com", "+919812345678");
	}

	private UUID item(String title, int price, int qty) {
		return admin.queryForObject("""
				INSERT INTO wishlist_items (tenant_id, title, price_inr, category, quantity_wanted, status)
				VALUES (?, ?, ?::numeric, 'EQUIPMENT', ?, 'ACTIVE') RETURNING id
				""", UUID.class, tenant, title, price, qty);
	}

	/** Opens a public contribution of {@code amount} rupees towards the item, and returns its order. */
	private String contribute(UUID item, int amount) throws Exception {
		String body = mvc.perform(post("/api/v1/public/t/{slug}/wishlist/{id}/sponsor", "radha-govinda", item)
						.contentType("application/json")
						.content("{\"quantity\":0,\"amountInr\":" + amount + ",\"anonymous\":true,\"consent\":false}"))
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

	private int paidFor(UUID item) {
		return admin.queryForObject("""
				SELECT COALESCE(SUM(amount_inr), 0) FROM donations
				WHERE wishlist_item_id = ? AND status = 'COMPLETED'
				""", java.math.BigDecimal.class, item).intValue();
	}

	static class StubVerifierConfiguration {

		@Bean
		@Primary
		StubTokenVerifier stubTokenVerifier() {
			return new StubTokenVerifier();
		}
	}

	static class StubTokenVerifier implements TokenVerifier {

		private final Map<String, VerifiedSubject> accepted = new HashMap<>();

		void accept(String uid, String email, String phone) {
			accepted.put("valid-token", new VerifiedSubject(uid, email, phone));
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
