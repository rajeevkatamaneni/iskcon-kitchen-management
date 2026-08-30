package org.iskcon.kms.wishlist;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Wish-list management (E7-S5): CRUD with manual ordering, the fulfilment flip when the money given
 * reaches what the item costs, and the auto-archive of long-fulfilled items.
 */
@AutoConfigureMockMvc
@Import(WishlistIT.StubVerifierConfiguration.class)
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
		stubVerifier.reset();
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
		// typing its own word for a category got KMS-5001, "Something went wrong at our end" — for a
		// plain bad input, with no field named. Found while seeding a real temple on 2026-08-19.
		mvc.perform(authed(post("/api/v1/wishlist")).contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"Prasadam trays\",\"priceInr\":120,"
								+ "\"category\":\"Provisions\",\"quantityWanted\":100}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4001"));

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
	private void give(UUID item, int amountInr) {
		admin.update("""
				INSERT INTO donations (tenant_id, type, amount_inr, status, is_anonymous, wishlist_item_id,
					donated_on)
				VALUES (?, 'ONE_TIME', ?, 'COMPLETED', true, ?, CURRENT_DATE)
				""", tenant, amountInr, item);
	}

	private String statusOf(UUID item) {
		return admin.queryForObject("SELECT status FROM wishlist_items WHERE id = ?", String.class, item);
	}

	private void within(Runnable action) {
		TenantContext.set(tenant);
		try {
			action.run();
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
			accepted.put("valid-token", new VerifiedSubject(uid, uid + "@example.com", "+919000000000"));
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
