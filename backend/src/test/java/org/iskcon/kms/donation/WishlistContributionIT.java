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
 * <p>Every gift here is made from inside the app by a signed-in devotee, which since 2026-08-29 is
 * the only kind there is — the donor is the account rather than anything typed into a form.
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

		// T-081 leaves this case exactly as it was, and these two lines are what say so. Nothing could
		// be applied — the vessel was paid for before this payment settled — so there is no split to
		// record and no "your gift completed it" to claim. A gift that could be partly applied gets
		// WISHLIST_GIFT_SPLIT instead, which is the test below.
		assertThat(convertedRow(second).get("wishlist_applied_inr"))
				.as("nothing was applied, so nothing is recorded as applied")
				.isNull();
		assertThat(templatesSent())
				.as("the gift that covered it is thanked; the one that could do nothing is told so")
				.containsExactly("DONATION_THANK_YOU", "WISHLIST_SPONSORSHIP_CONVERTED");
	}

	@Test
	@DisplayName("a gift bigger than what is left finishes the item and sends the rest to general funds")
	void aGiftLargerThanWhatIsOwedIsSplit() throws Exception {
		// Rajeev's grinder, ruling 7. It needs ₹14,000. One devotee gives ₹10,000; a second opens a
		// checkout for the whole ₹14,000 while that is still in flight, so both were allowed. The
		// second payment is captured before the item can be re-checked, because that is the only safe
		// order — and until T-081 the whole ₹14,000 was diverted to general funds, leaving the grinder
		// ₹4,000 short with ₹14,000 meant for it sitting somewhere else.
		UUID item = item("Commercial wet grinder", 14000, 1);
		String first = contribute(item, 10000);
		String second = contribute(item, 14000);

		captured(first, "pay_stub_a", "evt-a");
		assertThat(statusOf(item)).as("₹10,000 of ₹14,000 is not a grinder yet").isEqualTo("ACTIVE");

		captured(second, "pay_stub_b", "evt-b");

		Map<String, Object> split = convertedRow(second);
		assertThat(split.get("status")).isEqualTo("COMPLETED");
		assertThat(split.get("wishlist_item_id"))
				.as("the gift keeps its earmark: it is one payment, one row, and one 80G receipt")
				.isEqualTo(item);
		assertThat((java.math.BigDecimal) split.get("amount_inr"))
				.as("amount_inr is still what the donor paid — the receipt reads this and nothing else")
				.isEqualByComparingTo("14000");
		assertThat((java.math.BigDecimal) split.get("wishlist_applied_inr"))
				.as("exactly what the grinder was owed, so exactly what finishes it")
				.isEqualByComparingTo("4000");

		assertThat(statusOf(item)).as("the applied part is the remainder, so the item completes").isEqualTo("FULFILLED");

		// The live figure, read the way the giving page reads it rather than by re-running the sum
		// here: ₹14,000 towards a ₹14,000 grinder, not the ₹24,000 that was paid in total.
		assertThat(paidInrOnTheGivingPage(item))
				.as("the remaining figure counts what reached the item, not what was charged")
				.isEqualByComparingTo("14000");
	}

	@Test
	@DisplayName("the split gift's thank-you note names both amounts, and credits the donor with finishing it")
	void theSplitMessageIsHonestAboutBothHalves() throws Exception {
		UUID item = item("Commercial wet grinder", 14000, 1);
		String first = contribute(item, 10000);
		String second = contribute(item, 14000);
		captured(first, "pay_stub_a", "evt-a");
		captured(second, "pay_stub_b", "evt-b");

		assertThat(templatesSent())
				.as("the split case has words of its own; the converted one is a different fact")
				.contains("WISHLIST_GIFT_SPLIT")
				.doesNotContain("WISHLIST_SPONSORSHIP_CONVERTED");

		// Rendered from the parameters actually stored against the queued message, so this asserts on
		// the sentence a donor reads rather than on a figure the code passed to itself. The copy is
		// the deliverable of this task; it is the thing worth breaking a build over.
		String body = renderedSplitMessage();

		assertThat(body)
				.as("both actual amounts, because 'part of your gift' is the one thing forbidden here")
				.contains("₹4,000")
				.contains("₹10,000")
				.contains("₹14,000");
		assertThat(body)
				.as("the donor finished it, and is told so")
				.contains("that was the amount that finished it")
				.contains("you are the one who got it over the line");
		assertThat(body)
				.as("the remainder is a good in itself, not an apology")
				.contains("that is not second best")
				.doesNotContain("unfortunately")
				.doesNotContain("we regret");
		assertThat(body).as("warm, and in the house voice").startsWith("Dear Radha Devi,").endsWith("Hare Krishna.");
	}

	@Test
	@DisplayName("striking a split gift takes back the part that reached the item, and only that part")
	void strikingASplitGiftTakesBackOnlyWhatItGave() throws Exception {
		// A void says the payment was wrongly recorded, and a payment is the unit: there is no way to
		// strike half a card charge, so there is nothing ambiguous about striking a split gift. The
		// whole row is marked and the whole row stops counting — which for the item means losing
		// exactly the ₹4,000 that reached it, not the ₹14,000 that was charged and not nothing.
		UUID item = item("Commercial wet grinder", 14000, 1);
		String first = contribute(item, 10000);
		String second = contribute(item, 14000);
		captured(first, "pay_stub_a", "evt-a");
		captured(second, "pay_stub_b", "evt-b");
		assertThat(paidInrOnTheGivingPage(item)).isEqualByComparingTo("14000");

		// Struck the way DonationVoidService writes it (V104): the row and its status stay, marked.
		admin.update("""
				UPDATE donations SET voided_at = now(), voided_by = ?, void_reason = 'Chargeback.'
				WHERE provider_order_id = ?
				""", devotee, second);

		assertThat(paidInrOnTheGivingPage(item))
				.as("the ₹4,000 it gave goes; the ₹10,000 it never gave this item was never counted here")
				.isEqualByComparingTo("10000");
		assertThat((java.math.BigDecimal) convertedRow(second).get("wishlist_applied_inr"))
				.as("the split itself is a fact about what happened and is not rewritten by a void")
				.isEqualByComparingTo("4000");
	}

	@Test
	@DisplayName("a gift that fits entirely records no split at all")
	void aGiftThatFitsIsUnchanged() throws Exception {
		UUID item = item("Commercial wet grinder", 14000, 1);
		captured(contribute(item, 10000), "pay_stub_a", "evt-a");

		Map<String, Object> row = admin.queryForMap("""
				SELECT wishlist_item_id, amount_inr, wishlist_applied_inr FROM donations
				WHERE wishlist_item_id = ?
				""", item);
		assertThat(row.get("wishlist_item_id")).isEqualTo(item);
		assertThat((java.math.BigDecimal) row.get("amount_inr")).isEqualByComparingTo("10000");
		assertThat(row.get("wishlist_applied_inr"))
				.as("NULL is the whole gift — a split is only ever written when one happened")
				.isNull();
		assertThat(paidInrOnTheGivingPage(item)).isEqualByComparingTo("10000");
		assertThat(templatesSent()).containsExactly("DONATION_THANK_YOU");
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

	/**
	 * Opens a contribution of {@code amount} rupees towards the item, and returns its order.
	 *
	 * <p>Signed in, because since 2026-08-29 there is no other way to give: the form a stranger could
	 * fill in is gone, so a contribution is always some devotee's. What is being tested here is
	 * unaffected — the money, the cap, and what happens to a gift that no longer fits are the same
	 * arithmetic whoever gave it.
	 */
	private String contribute(UUID item, int amount) throws Exception {
		signedIn();
		String body = mvc.perform(post("/api/v1/donations/wishlist/{id}", item)
						.header("Authorization", "Bearer valid-token")
						.contentType("application/json")
						.content("{\"amountInr\":" + amount + "}"))
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

	/** The whole donation row behind one order, split columns included. */
	private Map<String, Object> convertedRow(String orderId) {
		return admin.queryForMap("""
				SELECT status, wishlist_item_id, amount_inr, wishlist_applied_inr FROM donations
				WHERE provider_order_id = ?
				""", orderId);
	}

	/** Every message queued so far, by template, oldest first. */
	private java.util.List<String> templatesSent() {
		return admin.queryForList(
				"SELECT template FROM notifications ORDER BY created_at, id", String.class);
	}

	/**
	 * The split thank-you note as the donor will read it: the stored parameters, put back through the
	 * template that will render them.
	 *
	 * <p>Reading the parameters out of the queued row rather than passing our own is the point. A test
	 * that renders a template with figures it made up proves the template compiles; this one proves
	 * the message a donor gets names the amounts the database actually holds.
	 */
	private String renderedSplitMessage() throws Exception {
		String json = admin.queryForObject("""
				SELECT params::text FROM notifications WHERE template = 'WISHLIST_GIFT_SPLIT'
				""", String.class);
		Map<String, Object> params = JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() { });
		return org.iskcon.kms.notification.NotificationTemplate.WISHLIST_GIFT_SPLIT.render(params).body();
	}

	/** What the giving page says has been given towards the item — the live figure, not a re-run sum. */
	private java.math.BigDecimal paidInrOnTheGivingPage(UUID item) throws Exception {
		signedIn();
		String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.get("/api/v1/donations/wishlist")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		for (com.fasterxml.jackson.databind.JsonNode node : JSON.readTree(body)) {
			if (item.toString().equals(node.get("id").asText())) {
				return new java.math.BigDecimal(node.get("paidInr").asText());
			}
		}
		throw new AssertionError("the item is not on the giving page at all: " + body);
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
