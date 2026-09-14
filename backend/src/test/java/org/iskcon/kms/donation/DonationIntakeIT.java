package org.iskcon.kms.donation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.notification.NotificationRecipient;
import org.iskcon.kms.notification.NotificationService;
import org.iskcon.kms.notification.NotificationTemplate;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Hand-recorded donation intake (E3-S5) through the full stack: goods land in the ledger and the
 * equipment register linked to one donation record, cash lands as a completed one-time gift with no
 * goods to move, a named donor with contact details gets a thank-you (via the notification service),
 * anonymous and contactless gifts don't, and the two permissions (record vs read) are enforced.
 *
 * <p>The notification service is mocked — the thank-you decision is what this story owns; whether the
 * message actually leaves is {@code NotificationSendE2EIT}'s concern. Mocking it also keeps this test
 * off the Quartz scheduler, which the base test deliberately excludes.
 */
@AutoConfigureMockMvc
class DonationIntakeIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private NotificationService notificationService;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID rice;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		insertUser(templeA, "uid-admin-a", "admin-a@example.com", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		insertUser(templeA, "uid-vol-a", "vol-a@example.com", "VOLUNTEER");
		rice = insertIngredient(templeA, "Rice", "KG");
		signIn("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM equipment_state_changes");
		admin.execute("DELETE FROM equipment_items");
		admin.execute("DELETE FROM donations");
		admin.execute("DELETE FROM wishlist_items");
		admin.execute("DELETE FROM audit_events");
		// Anything that moved through the stock ledger is tracked now, so the item rows exist
		// even where the test never asked for them, and they hold the ingredient down.
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a gift of food and equipment lands in stock and the register under one donation")
	void recordsGoodsAndEquipment() throws Exception {
		String body = """
				{"anonymous":false,"donorName":"Govind Das","donorPhone":"+919812345678",
				 "estimatedValueInr":2500,"donatedOn":"2026-08-10",
				 "ingredients":[{"ingredientId":"%s","quantity":5,"unit":"KG"}],
				 "equipment":[{"name":"Serving Vessel"}]}
				""".formatted(rice);
		UUID donationId = record(body);

		// Food is in the ledger as a donation.
		assertThat(admin.queryForObject("""
				SELECT COALESCE(SUM(to_base_qty(quantity, unit)), 0)
				FROM stock_movements WHERE ingredient_id = ? AND movement_type = 'DONATION_IN_KIND'
				""", BigDecimal.class, rice)).isEqualByComparingTo("5000");
		assertThat(admin.queryForObject("""
				SELECT count(*) FROM stock_movements WHERE reference_type = 'DONATION' AND reference_id = ?
				""", Integer.class, donationId)).isEqualTo(1);

		// Equipment is a donated asset linked to the donation.
		assertThat(admin.queryForObject("""
				SELECT count(*) FROM equipment_items WHERE donation_id = ? AND source = 'DONATED'
				""", Integer.class, donationId)).isEqualTo(1);

		assertThat(auditCount("DONATION_RECORDED")).isEqualTo(1);

		// A thank-you went to the donor through the notification service, and it's acknowledged.
		verify(notificationService, times(1)).notify(
				eq(NotificationRecipient.contact("+919812345678", null)),
				eq(NotificationTemplate.DONATION_THANK_YOU), any(), any());
		assertThat(admin.queryForObject(
				"SELECT acknowledged_at IS NOT NULL FROM donations WHERE id = ?", Boolean.class, donationId))
				.isTrue();
	}

	@Test
	@DisplayName("an anonymous gift is recorded without a thank-you")
	void anonymousGiftNotAcknowledged() throws Exception {
		String body = """
				{"anonymous":true,"donatedOn":"2026-08-10",
				 "ingredients":[{"ingredientId":"%s","quantity":3,"unit":"KG"}]}
				""".formatted(rice);
		UUID donationId = record(body);

		assertThat(admin.queryForObject(
				"SELECT is_anonymous FROM donations WHERE id = ?", Boolean.class, donationId)).isTrue();
		assertThat(admin.queryForObject(
				"SELECT donor_name FROM donations WHERE id = ?", String.class, donationId)).isNull();
		verify(notificationService, never()).notify(any(), any(), any(), any());
		assertThat(admin.queryForObject(
				"SELECT acknowledged_at FROM donations WHERE id = ?", java.sql.Timestamp.class, donationId))
				.isNull();
	}

	@Test
	@DisplayName("a named donor with no contact details gets no thank-you")
	void noContactNoThankYou() throws Exception {
		String body = """
				{"anonymous":false,"donorName":"Walk-in Devotee","donatedOn":"2026-08-10",
				 "ingredients":[{"ingredientId":"%s","quantity":2,"unit":"KG"}]}
				""".formatted(rice);
		record(body);
		verify(notificationService, never()).notify(any(), any(), any(), any());
	}

	@Test
	@DisplayName("a phone typed as 98765 43210 is stored, and thanked, as +919876543210 (T-186)")
	void counterPhoneIsSavedInPlusNinetyOneForm() throws Exception {
		UUID donationId = record("""
				{"anonymous":false,"donorName":"Govind Das","donorPhone":"98765 43210",
				 "cashAmountInr":500,"donatedOn":"2026-08-10"}
				""");

		assertThat(admin.queryForObject("SELECT donor_phone FROM donations WHERE id = ?", String.class, donationId))
				.isEqualTo("+919876543210");
		// The thank-you goes to the number that was stored, never to a different spelling of it.
		verify(notificationService, times(1)).notify(
				eq(NotificationRecipient.contact("+919876543210", null)),
				eq(NotificationTemplate.DONATION_THANK_YOU), any(), any());
	}

	@Test
	@DisplayName("a Mumbai landline or a foreign number is stored exactly as typed, with no guessing (T-186)")
	void unrecognisedPhoneIsStoredAsTyped() throws Exception {
		UUID landline = record("""
				{"anonymous":false,"donorName":"Govind Das","donorPhone":" 022 2345 6789 ",
				 "cashAmountInr":500,"donatedOn":"2026-08-10"}
				""");
		UUID foreign = record("""
				{"anonymous":false,"donorName":"Govind Das","donorPhone":"+1 555 0100",
				 "cashAmountInr":500,"donatedOn":"2026-08-10"}
				""");

		assertThat(admin.queryForObject("SELECT donor_phone FROM donations WHERE id = ?", String.class, landline))
				.isEqualTo("022 2345 6789");
		assertThat(admin.queryForObject("SELECT donor_phone FROM donations WHERE id = ?", String.class, foreign))
				.isEqualTo("+1 555 0100");
	}

	/**
	 * T-186 changes new counter gifts only. Whether to rewrite the rows already recorded is Rajeev's
	 * decision, and this is the read-only query that sizes it, held here so that what goes in front of
	 * him is a query that has been run rather than one that looks right. Per temple: (a) counter gifts
	 * whose stored phone the rule would change, and (b) how many of those would newly match a volunteer
	 * of that temple, which is the part that widens who can download a receipt.
	 *
	 * <p>Proven against these fixtures only. The real numbers need staging data, which no test reads.
	 */
	static final String BACKFILL_COUNT_SQL = """
			WITH counter AS (
			    SELECT d.tenant_id, d.donor_phone, d.status, d.voided_at, d.is_anonymous,
			           regexp_replace(d.donor_phone, '%s', '', 'g') AS bare
			    FROM donations d
			    WHERE d.provider IS NULL
			      AND d.donor_account_user_id IS NULL
			      AND d.donor_phone IS NOT NULL
			),
			rewritten AS (
			    SELECT c.*, '+91' || right(c.bare, 10) AS saved
			    FROM counter c
			    WHERE c.bare ~ '^(\\+91|91|0)?[6-9][0-9]{9}$'
			      AND '+91' || right(c.bare, 10) <> c.donor_phone
			)
			SELECT t.id AS tenant_id, t.name AS temple,
			       count(r.donor_phone) AS would_change,
			       count(r.donor_phone) FILTER (
			           WHERE r.status = 'COMPLETED' AND r.voided_at IS NULL AND r.is_anonymous = false
			             AND r.bare <> r.saved
			             AND EXISTS (SELECT 1 FROM users u
			                         WHERE u.tenant_id = r.tenant_id AND u.role = 'VOLUNTEER'
			                           AND u.status = 'ACTIVE' AND u.phone = r.saved)
			       ) AS would_newly_match
			FROM tenants t
			LEFT JOIN rewritten r ON r.tenant_id = t.id
			GROUP BY t.id, t.name
			ORDER BY t.name
			""";

	/**
	 * T-157's separators, {@code [\s\p{Z}\p{Pd}\p{Cf}]}, spelled out code point by code point for
	 * PostgreSQL, whose regular expressions have no Unicode categories and whose {@code \s} follows the
	 * database's locale. Checked against Java's own pattern over every code point below.
	 */
	static final String SQL_SEPARATORS = "[\\u0009-\\u000D\\u0020\\u002D\\u00A0\\u00AD\\u058A\\u05BE"
			+ "\\u0600-\\u0605\\u061C\\u06DD\\u070F\\u0890-\\u0891\\u08E2\\u1400\\u1680\\u1806\\u180E"
			+ "\\u2000-\\u2015\\u2028-\\u202F\\u205F-\\u2064\\u2066-\\u206F\\u2E17\\u2E1A\\u2E3A-\\u2E3B"
			+ "\\u2E40\\u2E5D\\u3000\\u301C\\u3030\\u30A0\\uFE31-\\uFE32\\uFE58\\uFE63\\uFEFF\\uFF0D"
			+ "\\uFFF9-\\uFFFB\\U00010EAD\\U000110BD\\U000110CD\\U00013430-\\U0001343F"
			+ "\\U0001BCA0-\\U0001BCA3\\U0001D173-\\U0001D17A\\U000E0001\\U000E0020-\\U000E007F]";

	@Test
	@DisplayName("the backfill count query's separators are exactly T-157's, code point for code point (T-186)")
	void backfillQuerySeparatorsMatchJava() {
		Pattern java = Pattern.compile("[\\s\\p{Z}\\p{Pd}\\p{Cf}]");
		TreeSet<Integer> expected = new TreeSet<>();
		for (int cp = 1; cp <= 0x10FFFF; cp++) {
			if ((cp < 0xD800 || cp > 0xDFFF) && java.matcher(new String(Character.toChars(cp))).matches()) {
				expected.add(cp);
			}
		}
		List<Integer> sql = admin.queryForList("""
				SELECT cp FROM generate_series(1, 1114111) cp
				WHERE (cp < 55296 OR cp > 57343) AND chr(cp) ~ ?
				ORDER BY cp
				""", Integer.class, SQL_SEPARATORS);
		assertThat(sql).hasSize(expected.size()).containsExactlyElementsOf(expected);
	}

	@Test
	@DisplayName("the backfill count query counts, per temple, the rows the rule would change and those that would newly match (T-186)")
	void backfillCountQueryAgainstFixtures() {
		UUID templeB = insertTenant("jagannath", "Sri Jagannath Temple");
		volunteer(templeA, "uid-gopal", "+919876543210", "VOLUNTEER");
		volunteer(templeA, "uid-office", "+919811122233", "TEMPLE_ADMIN");

		// Temple A.
		counterGift(templeA, "98765 43210", "COMPLETED", false);          // changes; newly matches Gopal
		counterGift(templeA, "98765\u201343210", "COMPLETED", false);    // en dash; changes; newly matches
		counterGift(templeA, "+91 98765 43210", "COMPLETED", false);      // changes; already matches today
		counterGift(templeA, "+919876543210", "COMPLETED", false);        // already saved form; no change
		counterGift(templeA, "022 2345 6789", "COMPLETED", false);        // Mumbai landline; no change
		counterGift(templeA, "+91 (0) 98765 43210", "COMPLETED", false);  // no guessing; no change
		counterGift(templeA, "09812345678", "COMPLETED", false);          // changes; nobody's phone
		counterGift(templeA, "98111 22233", "COMPLETED", false);          // changes; an admin's phone only
		counterGift(templeA, "98765 43210", "COMPLETED", true);           // changes; struck, never listed
		admin.update("""
				INSERT INTO donations (tenant_id, type, amount_inr, status, is_anonymous, donor_name, donor_phone,
					payment_mode, provider, donated_on)
				VALUES (?, 'ONE_TIME', 100, 'COMPLETED', false, 'Online', '98765 43210', 'UPI', 'razorpay', CURRENT_DATE)
				""", templeA);                                               // online, not a counter gift
		// Temple B: the same typing, but Gopal is not a volunteer there.
		counterGift(templeB, "98765 43210", "COMPLETED", false);          // changes; matches nobody here

		Map<UUID, Map<String, Object>> byTemple = new HashMap<>();
		for (Map<String, Object> row : admin.queryForList(BACKFILL_COUNT_SQL.formatted(SQL_SEPARATORS))) {
			byTemple.put((UUID) row.get("tenant_id"), row);
		}
		assertThat(((Number) byTemple.get(templeA).get("would_change")).longValue()).isEqualTo(6);
		assertThat(((Number) byTemple.get(templeA).get("would_newly_match")).longValue()).isEqualTo(2);
		assertThat(((Number) byTemple.get(templeB).get("would_change")).longValue()).isEqualTo(1);
		assertThat(((Number) byTemple.get(templeB).get("would_newly_match")).longValue()).isZero();

		// And (a) agrees with the rule the server applies, row by row, not only with the figures above.
		for (UUID tenant : List.of(templeA, templeB)) {
			long javaCount = admin.queryForList("""
					SELECT donor_phone FROM donations
					WHERE tenant_id = ? AND provider IS NULL AND donor_phone IS NOT NULL
					""", String.class, tenant).stream()
					.filter(phone -> !CounterPhone.normalise(phone).equals(phone))
					.count();
			assertThat(((Number) byTemple.get(tenant).get("would_change")).longValue()).isEqualTo(javaCount);
		}
	}

	private void counterGift(UUID tenantId, String phone, String status, boolean voided) {
		admin.update("""
				INSERT INTO donations (tenant_id, type, amount_inr, status, is_anonymous, donor_name, donor_phone,
					payment_mode, donated_on, voided_at, voided_by, void_reason)
				VALUES (?, 'ONE_TIME', 100, ?, false, 'Govind Das', ?, 'CASH', CURRENT_DATE,
					CASE WHEN ?::boolean THEN now() END,
					CASE WHEN ?::boolean THEN (SELECT id FROM users WHERE tenant_id = ? AND role = 'TEMPLE_ADMIN' LIMIT 1) END,
					CASE WHEN ?::boolean THEN 'Entered twice' END)
				""", tenantId, status, phone, voided, voided, tenantId, voided);
	}

	private void volunteer(UUID tenantId, String uid, String phone, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, ?, ?, 'ACTIVE')
				""", tenantId, uid, uid + "@example.com", phone, role);
	}

	@Test
	@DisplayName("cash is a completed one-time gift, paid in CASH, with no provider")
	void recordsCash() throws Exception {
		String body = """
				{"anonymous":false,"donorName":"Govind Das","cashAmountInr":5000,"donatedOn":"2026-08-10"}
				""";
		UUID donationId = record(body);

		Map<String, Object> row = admin.queryForMap("""
				SELECT type, amount_inr, estimated_value_inr, payment_mode, status, provider
				FROM donations WHERE id = ?
				""", donationId);
		assertThat(row.get("type")).isEqualTo("ONE_TIME");
		assertThat((BigDecimal) row.get("amount_inr")).isEqualByComparingTo("5000");
		assertThat(row.get("estimated_value_inr")).isNull();
		assertThat(row.get("payment_mode")).isEqualTo("CASH");
		assertThat(row.get("status")).isEqualTo("COMPLETED");
		// Null provider is what the ledger reads as "a person recorded this".
		assertThat(row.get("provider")).isNull();

		// No goods moved: cash has nothing to put on a shelf.
		assertThat(admin.queryForObject("""
				SELECT count(*) FROM stock_movements WHERE reference_type = 'DONATION' AND reference_id = ?
				""", Integer.class, donationId)).isZero();
		assertThat(auditCount("DONATION_RECORDED")).isEqualTo(1);
	}

	@Test
	@DisplayName("cash appears in the ledger as a manual gift")
	void cashShowsInLedgerAsManual() throws Exception {
		record("""
				{"anonymous":false,"donorName":"Govind Das","cashAmountInr":5000,"donatedOn":"2026-08-10"}
				""");

		mvc.perform(authed(get("/api/v1/donations/ledger?type=MANUAL")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].category").value("MANUAL"))
				.andExpect(jsonPath("$[0].donorDisplay").value("Govind Das"))
				.andExpect(jsonPath("$[0].amountInr").value(5000))
				.andExpect(jsonPath("$[0].paymentMode").value("CASH"));
	}

	/**
	 * The office takes ₹5,000 in cash "for the grinder". Until this it could only be written in the
	 * notes, where nothing reads it: the ledger said the gift was linked to nothing, and the wish list
	 * never saw the money — a grinder paid for in cash stayed on the list as still wanted.
	 */
	@Test
	@DisplayName("cash given towards a wish-list item is linked to it and can complete it")
	void cashTowardsAWishlistItem() throws Exception {
		UUID item = admin.queryForObject("""
				INSERT INTO wishlist_items (tenant_id, title, price_inr, category, quantity_wanted, status)
				VALUES (?, 'Wet grinder', 15000, 'EQUIPMENT', 1, 'ACTIVE') RETURNING id
				""", UUID.class, templeA);

		UUID part = record("""
				{"anonymous":false,"donorName":"Govind Das","cashAmountInr":5000,"donatedOn":"2026-08-10",
				 "wishlistItemId":"%s"}
				""".formatted(item));

		// The gift is linked to the item and is worth its own amount: progress is money towards the
		// price, because nobody hands over part of a grinder.
		Map<String, Object> row = admin.queryForMap(
				"SELECT wishlist_item_id, amount_inr FROM donations WHERE id = ?", part);
		assertThat(row.get("wishlist_item_id")).isEqualTo(item);
		assertThat((java.math.BigDecimal) row.get("amount_inr")).isEqualByComparingTo("5000");
		assertThat(statusOf(item)).isEqualTo("ACTIVE"); // ₹5,000 of ₹15,000 does not finish it

		mvc.perform(authed(get("/api/v1/donations/ledger")))
				.andExpect(jsonPath("$[0].linkedTo").value("Wish list: Wet grinder"));

		// The rest of the price, also in cash: the item is bought, and the list must stop asking.
		record("""
				{"anonymous":false,"donorName":"Radha Devi","cashAmountInr":10000,"donatedOn":"2026-08-11",
				 "wishlistItemId":"%s"}
				""".formatted(item));
		assertThat(statusOf(item)).isEqualTo("FULFILLED");
	}

	@Test
	@DisplayName("goods cannot be given towards a wish-list item — the sack is already in stock")
	void goodsCannotCarryAWishlistLink() throws Exception {
		UUID item = admin.queryForObject("""
				INSERT INTO wishlist_items (tenant_id, title, price_inr, category, quantity_wanted, status)
				VALUES (?, 'Rice sacks', 1000, 'CONSUMABLE', 10, 'ACTIVE') RETURNING id
				""", UUID.class, templeA);

		mvc.perform(recordRequest("""
				{"anonymous":true,"donatedOn":"2026-08-10","wishlistItemId":"%s",
				 "ingredients":[{"ingredientId":"%s","quantity":2,"unit":"KG"}]}
				""".formatted(item, rice)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));
	}

	@Test
	@DisplayName("cash cannot be given towards an item the temple has stopped asking for")
	void cannotGiveTowardsAnInactiveItem() throws Exception {
		UUID item = admin.queryForObject("""
				INSERT INTO wishlist_items (tenant_id, title, price_inr, category, quantity_wanted, status)
				VALUES (?, 'Wet grinder', 15000, 'EQUIPMENT', 1, 'ARCHIVED') RETURNING id
				""", UUID.class, templeA);

		mvc.perform(recordRequest("""
				{"anonymous":true,"cashAmountInr":500,"donatedOn":"2026-08-10","wishlistItemId":"%s"}
				""".formatted(item)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400068"));
	}

	@Test
	@DisplayName("a donation must be cash or at least one item")
	void mustHaveAnItem() throws Exception {
		mvc.perform(recordRequest("{\"anonymous\":true,\"donatedOn\":\"2026-08-10\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));
	}

	@Test
	@DisplayName("cash and goods on one form are refused — they are two gifts, recorded separately")
	void cashAndGoodsAreSeparateGifts() throws Exception {
		String body = """
				{"anonymous":true,"cashAmountInr":500,"donatedOn":"2026-08-10",
				 "ingredients":[{"ingredientId":"%s","quantity":2,"unit":"KG"}]}
				""".formatted(rice);
		mvc.perform(recordRequest(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));
		assertThat(admin.queryForObject("SELECT count(*) FROM donations", Integer.class)).isZero();
	}

	@Test
	@DisplayName("a non-anonymous gift must name its donor")
	void nonAnonymousNeedsName() throws Exception {
		String body = """
				{"anonymous":false,"donatedOn":"2026-08-10",
				 "ingredients":[{"ingredientId":"%s","quantity":2,"unit":"KG"}]}
				""".formatted(rice);
		mvc.perform(recordRequest(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));
	}

	@Test
	@DisplayName("kitchen staff may record a donation but not read what was given")
	void permissionSplit() throws Exception {
		signIn("uid-staff-a");
		String body = """
				{"anonymous":true,"donatedOn":"2026-08-10",
				 "ingredients":[{"ingredientId":"%s","quantity":1,"unit":"KG"}]}
				""".formatted(rice);
		mvc.perform(recordRequest(body)).andExpect(status().isCreated());
		mvc.perform(authed(get("/api/v1/donations/ledger"))).andExpect(status().isForbidden());

		// An admin can read it.
		signIn("uid-admin-a");
		mvc.perform(authed(get("/api/v1/donations/ledger")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].category").value("IN_KIND"))
				.andExpect(jsonPath("$[0].linkedTo").value("In-kind intake"));
	}

	@Test
	@DisplayName("a volunteer cannot record a donation")
	void volunteerForbidden() throws Exception {
		signIn("uid-vol-a");
		String body = """
				{"anonymous":true,"donatedOn":"2026-08-10",
				 "ingredients":[{"ingredientId":"%s","quantity":1,"unit":"KG"}]}
				""".formatted(rice);
		mvc.perform(recordRequest(body)).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private UUID record(String body) throws Exception {
		String response = mvc.perform(recordRequest(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(response.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
	}

	private MockHttpServletRequestBuilder recordRequest(String body) {
		return authed(post("/api/v1/donations"))
				.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private String statusOf(UUID wishlistItem) {
		return admin.queryForObject(
				"SELECT status FROM wishlist_items WHERE id = ?", String.class, wishlistItem);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private int auditCount(String action) {
		Integer c = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = ?", Integer.class, action);
		return c == null ? 0 : c;
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private UUID insertTenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);
	}

	private void insertUser(UUID tenantId, String uid, String email, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE')
				""", tenantId, uid, email, role);
	}

	private UUID insertIngredient(UUID tenantId, String name, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', ?)
				RETURNING id
				""", UUID.class, tenantId, name, unit);
	}

}
