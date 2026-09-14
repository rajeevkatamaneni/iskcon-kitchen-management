package org.iskcon.kms.wishlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Wish-list management (E7-S5): CRUD with manual ordering, the fulfilment flip when the money given
 * reaches what the item costs, and the auto-archive of long-fulfilled items.
 */
@AutoConfigureMockMvc
class WishlistIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private WishlistService service;

	@Autowired
	private StubTokenVerifier stubVerifier;

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
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-admin', 'Admin', 'admin@example.com', '+919876500001', 'TEMPLE_ADMIN', 'ACTIVE')
				""", tenant);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-vol', 'Vol', 'vol@example.com', '+919876500002', 'VOLUNTEER', 'ACTIVE')
				""", tenant);
		signIn("uid-admin");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM donations");
		admin.execute("DELETE FROM wishlist_items");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("items can be created and reordered; the manual order is reflected")
	void crudAndReorder() throws Exception {
		UUID a = create("Rice sacks", 1000, 10);
		UUID b = create("New mixer", 15000, 1);

		mvc.perform(authed(post("/api/v1/wishlist/reorder"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"itemIds\":[\"" + b + "\",\"" + a + "\"]}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/wishlist")))
				.andExpect(jsonPath("$[0].title").value("New mixer"))
				.andExpect(jsonPath("$[1].title").value("Rice sacks"));
	}

	@Test
	@DisplayName("an item flips FULFILLED only when the money given reaches what it costs")
	void fulfilmentFlip() throws Exception {
		// Ten sacks at ₹1,000 is a ₹10,000 item, however many gifts it takes to get there.
		UUID item = create("Rice sacks", 1000, 10);

		give(item, 3000);
		within(() -> service.markFulfilledIfComplete(item));
		assert statusOf(item).equals("ACTIVE") : "partly paid for stays active";

		give(item, 7000); // ₹10,000 of ₹10,000
		within(() -> service.markFulfilledIfComplete(item));
		assert statusOf(item).equals("FULFILLED") : "paid for in full flips to FULFILLED";
	}

	@Test
	@DisplayName("a struck gift stops counting, and an item it would have completed does not flip")
	void aVoidedGiftBuysNothing() throws Exception {
		// V104 (T-012) marks a struck gift with `voided_at` rather than giving it a status of its
		// own, deliberately — the row must stay readable one at a time. The cost is that a struck
		// gift keeps `status = 'COMPLETED'`, so a sum filtering on status alone kept spending it.
		// Hand-recorded cash can carry a wishlist_item_id, which is how a gift entered twice at the
		// gate and then struck could buy the temple a grinder nobody gave it.
		UUID item = create("Rice sacks", 1000, 10); // ₹10,000 owed

		give(item, 3000);
		UUID mistake = give(item, 7000); // ₹10,000 of ₹10,000 — enough to flip it
		assertThat(view(item).paidInr()).isEqualByComparingTo("10000");

		strike(mistake, "Entered twice at the gate.");

		// The figure moves. Asserting only that a number comes back would pass against the defect.
		assertThat(view(item).paidInr())
				.as("a struck gift is not money and must leave the progress figure")
				.isEqualByComparingTo("3000");

		within(() -> service.markFulfilledIfComplete(item));
		assertThat(statusOf(item))
				.as("₹3,000 of a ₹10,000 item is not a fulfilled item")
				.isEqualTo("ACTIVE");

		// And the gift really is still COMPLETED — the point of the whole defect.
		assertThat(admin.queryForObject("SELECT status FROM donations WHERE id = ?", String.class, mistake))
				.isEqualTo("COMPLETED");
	}

	@Test
	@DisplayName("a FULFILLED item whose gift is struck reopens, stays off the sweep, and can be fulfilled again")
	void aFulfilledItemReopensWhenItsMoneyIsStruck() throws Exception {
		// This test used to pin the opposite: markFulfilledIfComplete only ever ran ACTIVE -> FULFILLED
		// and nothing looked at an item again, so striking the gift behind a fulfilled mixer left it
		// FULFILLED at ₹0 of ₹15,000 until the sweep archived it. Rajeev ruled (T-069's open question,
		// built in T-205) that such an item reopens. The void calls reopenIfNoLongerCovered in its own
		// transaction; DonationVoidIT drives that end to end, and this drives the wish-list half.
		UUID item = create("New mixer", 15000, 1);
		UUID gift = give(item, 15000);
		within(() -> service.markFulfilledIfComplete(item));
		assertThat(statusOf(item)).isEqualTo("FULFILLED");

		strike(gift, "Recorded against the wrong temple.");
		var reopening = withinGet(() -> service.reopenIfNoLongerCovered(item));

		assertThat(reopening).isPresent();
		assertThat(statusOf(item)).isEqualTo("ACTIVE");
		assertThat(admin.queryForObject(
				"SELECT fulfilled_at FROM wishlist_items WHERE id = ?", java.sql.Timestamp.class, item)).isNull();
		assertThat(reopening.get().before()).containsEntry("status", "FULFILLED").containsEntry("standingInr", "0");
		assertThat(reopening.get().before().get("fulfilledAt")).isNotNull();
		assertThat(reopening.get().after()).containsEntry("status", "ACTIVE")
				// At the precision the column keeps (numeric(12,2)), because the trail reports what was stored.
				.containsEntry("costInr", "15000.00");
		assertThat(reopening.get().after().get("fulfilledAt")).isNull();

		// Asked again, nothing further happens: it is ACTIVE, and only FULFILLED reopens.
		assertThat(withinGet(() -> service.reopenIfNoLongerCovered(item))).isEmpty();

		// Still on the giving list, now as something asking for money.
		assertThat(withinGet(() -> service.forGiving()).stream().map(WishlistItemView::id)).contains(item);

		// And off the sweep's schedule: the sweep only takes FULFILLED items, counted from fulfilled_at,
		// and this one is neither. Without clearing fulfilled_at it would still have been skipped on
		// status, but a re-fulfilment would then have inherited the old date.
		within(() -> service.archiveFulfilledForCurrentTenant());
		assertThat(statusOf(item)).isEqualTo("ACTIVE");

		// A new gift covering it goes through the ordinary flip, with a fresh fulfilled_at.
		give(item, 15000);
		within(() -> service.markFulfilledIfComplete(item));
		assertThat(statusOf(item)).isEqualTo("FULFILLED");
		assertThat(admin.queryForObject(
				"SELECT fulfilled_at FROM wishlist_items WHERE id = ?", java.sql.Timestamp.class, item)).isNotNull();
	}

	@Test
	@DisplayName("an item still covered, an ACTIVE item, and an ARCHIVED item are none of them reopened")
	void onlyAnUncoveredFulfilledItemReopens() throws Exception {
		// Jointly funded, over-covered: ₹20,000 towards ₹15,000. Striking ₹5,000 leaves ₹15,000.
		UUID joint = create("Commercial wet grinder", 15000, 1);
		give(joint, 10000);
		give(joint, 5000);
		UUID extra = give(joint, 5000);
		within(() -> service.markFulfilledIfComplete(joint));
		strike(extra, "Entered twice at the gate.");
		assertThat(withinGet(() -> service.reopenIfNoLongerCovered(joint))).isEmpty();
		assertThat(statusOf(joint)).isEqualTo("FULFILLED");

		// Archived: the temple has stopped hoping for it, so struck money does not bring it back.
		UUID archived = create("Old tandoor", 8000, 1);
		UUID gift = give(archived, 8000);
		within(() -> service.markFulfilledIfComplete(archived));
		within(() -> service.archive(archived));
		strike(gift, "Chargeback.");
		assertThat(withinGet(() -> service.reopenIfNoLongerCovered(archived))).isEmpty();
		assertThat(statusOf(archived)).isEqualTo("ARCHIVED");

		// Never fulfilled: already asking for money.
		UUID active = create("Rice sacks", 1000, 10);
		give(active, 3000);
		assertThat(withinGet(() -> service.reopenIfNoLongerCovered(active))).isEmpty();
		assertThat(statusOf(active)).isEqualTo("ACTIVE");
	}

	@Test
	@DisplayName("fulfilled items auto-archive after their visibility window")
	void autoArchive() throws Exception {
		UUID item = create("New mixer", 15000, 1);
		admin.update("UPDATE wishlist_items SET status = 'FULFILLED', fulfilled_at = now() - interval '10 days' WHERE id = ?", item);
		within(() -> service.archiveFulfilledForCurrentTenant());
		assert statusOf(item).equals("ARCHIVED");
	}

	@Test
	@DisplayName("a category the list does not have is a field error, not our fault")
	void unknownCategoryIsRefusedPlainly() throws Exception {
		// The database has enforced these three since V41 and nothing in front of it did, so a temple
		// typing its own word for a category got KMS-500001, "Something went wrong at our end" — for a
		// plain bad input, with no field named. Found while seeding a real temple on 2026-08-19.
		mvc.perform(authed(post("/api/v1/wishlist")).contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"Prasadam trays\",\"priceInr\":120,"
								+ "\"category\":\"Provisions\",\"quantityWanted\":100}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));

		// And the ones it does have work whatever case they arrive in.
		mvc.perform(authed(post("/api/v1/wishlist")).contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"Prasadam trays\",\"priceInr\":120,"
								+ "\"category\":\"equipment\",\"quantityWanted\":100}"))
				.andExpect(status().isCreated());
	}

	@Test
	@DisplayName("a volunteer cannot manage the wish list")
	void volunteerForbidden() throws Exception {
		signIn("uid-vol");
		mvc.perform(authed(get("/api/v1/wishlist"))).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private UUID create(String title, int price, int qty) throws Exception {
		String body = mvc.perform(authed(post("/api/v1/wishlist"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"" + title + "\",\"priceInr\":" + price
								+ ",\"category\":\"CONSUMABLE\",\"quantityWanted\":" + qty + "}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(JSON.readTree(body).get("id").asText());
	}

	/** A completed gift of {@code amountInr} towards the item, however it arrived. */
	private UUID give(UUID item, int amountInr) {
		return admin.queryForObject("""
				INSERT INTO donations (tenant_id, type, amount_inr, status, is_anonymous, wishlist_item_id,
					donated_on)
				VALUES (?, 'ONE_TIME', ?, 'COMPLETED', true, ?, CURRENT_DATE)
				RETURNING id
				""", UUID.class, tenant, amountInr, item);
	}

	/**
	 * Strikes a gift the way {@code DonationVoidService} does (V104): the row stays, its status
	 * untouched, marked with who struck it and why. Written here directly rather than through the
	 * void endpoint so that this file keeps testing the wish list and not the donation module.
	 */
	private void strike(UUID donationId, String reason) {
		UUID actor = admin.queryForObject(
				"SELECT id FROM users WHERE firebase_uid = 'uid-admin'", UUID.class);
		admin.update("""
				UPDATE donations SET voided_at = now(), voided_by = ?, void_reason = ? WHERE id = ?
				""", actor, reason, donationId);
	}

	private String statusOf(UUID item) {
		return admin.queryForObject("SELECT status FROM wishlist_items WHERE id = ?", String.class, item);
	}

	/** The item as the service reads it — progress figure included, under the tenant's own RLS. */
	private WishlistItemView view(UUID item) {
		return withinGet(() -> service.get(item));
	}

	private void within(Runnable action) {
		withinGet(() -> {
			action.run();
			return null;
		});
	}

	private <T> T withinGet(java.util.function.Supplier<T> action) {
		TenantContext.set(tenant);
		try {
			return action.get();
		} finally {
			TenantContext.clear();
		}
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}
}
