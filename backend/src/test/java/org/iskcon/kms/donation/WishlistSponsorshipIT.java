package org.iskcon.kms.donation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Public wish-list sponsorship (E7-S6): a sponsorship completes and links to its item; the race for
 * the last unit resolves as one sponsorship + one converted general donation (never an orphaned
 * charge); and anonymity controls public recognition.
 */
@AutoConfigureMockMvc
class WishlistSponsorshipIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PaymentWebhookVerifier verifier;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM payment_events");
		admin.execute("DELETE FROM donations");
		admin.execute("DELETE FROM wishlist_items");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a sponsorship completes, links to the item, updates progress, and lists a named sponsor")
	void sponsorshipCompletes() throws Exception {
		UUID item = item("Rice sacks", 1000, 2);
		String orderId = sponsor(item, "{\"quantity\":2,\"anonymous\":false,\"name\":\"Radha Devi\","
				+ "\"phone\":\"+919812345678\",\"consent\":true}");
		captured(orderId, "pay_stub_1", "evt-1");

		var row = admin.queryForMap("SELECT status, wishlist_item_id, wishlist_quantity FROM donations WHERE provider_order_id = ?", orderId);
		assert "COMPLETED".equals(row.get("status"));
		assert item.equals(row.get("wishlist_item_id"));
		assert ((Number) row.get("wishlist_quantity")).intValue() == 2;
		assert statusOf(item).equals("FULFILLED") : "2 of 2 sponsored → fulfilled";

		mvc.perform(get("/api/v1/public/t/{slug}/wishlist/{id}/sponsors", "radha-govinda", item))
				.andExpect(jsonPath("$[0]").value("Radha Devi"));
	}

	@Test
	@DisplayName("two checkouts for the last unit resolve to one sponsorship + one converted general donation")
	void oversubscriptionRace() throws Exception {
		UUID item = item("New mixer", 15000, 1);
		// Both start while nothing is COMPLETED yet, so both are allowed as PENDING.
		String orderA = sponsor(item, "{\"quantity\":1,\"anonymous\":true,\"consent\":false}");
		String orderB = sponsor(item, "{\"quantity\":1,\"anonymous\":true,\"consent\":false}");

		captured(orderA, "pay_stub_a", "evt-a"); // wins the unit
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
	@DisplayName("an anonymous sponsor is never shown in public recognition")
	void anonymityHidesSponsor() throws Exception {
		UUID item = item("Books", 500, 5);
		String orderId = sponsor(item, "{\"quantity\":1,\"anonymous\":true,\"consent\":false}");
		captured(orderId, "pay_stub_x", "evt-x");

		mvc.perform(get("/api/v1/public/t/{slug}/wishlist/{id}/sponsors", "radha-govinda", item))
				.andExpect(jsonPath("$.length()").value(0));
	}

	// ---------------------------------------------------------------------

	private UUID item(String title, int price, int qty) {
		return admin.queryForObject("""
				INSERT INTO wishlist_items (tenant_id, title, price_inr, category, quantity_wanted, status)
				VALUES (?, ?, ?::numeric, 'CONSUMABLE', ?, 'ACTIVE') RETURNING id
				""", UUID.class, tenant, title, price, qty);
	}

	private String sponsor(UUID item, String json) throws Exception {
		String body = mvc.perform(post("/api/v1/public/t/{slug}/wishlist/{id}/sponsor", "radha-govinda", item)
						.contentType("application/json").content(json))
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
}
