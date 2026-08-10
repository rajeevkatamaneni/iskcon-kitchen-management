package org.iskcon.kms.donation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * In-kind donation intake (E3-S5) through the full stack: goods land in the ledger and the equipment
 * register linked to one donation record, a named donor with contact details gets a thank-you queued,
 * anonymous and contactless gifts don't, and the two permissions (record vs read) are enforced.
 *
 * <p>Quartz is enabled here (the base test excludes it) so the thank-you actually reaches the
 * notification service and a notifications row appears — matching {@code NotificationSendE2EIT}.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
		"spring.autoconfigure.exclude=",
		"spring.quartz.auto-startup=true"})
@Import(DonationIntakeIT.StubVerifierConfiguration.class)
class DonationIntakeIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID rice;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		insertUser(templeA, "uid-admin-a", "admin-a@example.com", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		insertUser(templeA, "uid-vol-a", "vol-a@example.com", "VOLUNTEER");
		rice = insertIngredient(templeA, "Rice", "KG");
		signIn("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM equipment_state_changes");
		admin.execute("DELETE FROM equipment_items");
		admin.execute("DELETE FROM donations");
		admin.execute("DELETE FROM audit_events");
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
				 "equipment":[{"name":"Serving Vessel","category":"TOOL"}]}
				""".formatted(rice);
		UUID donationId = record(body);

		// Food is in the ledger as a donation.
		assertThat(admin.queryForObject("""
				SELECT COALESCE(SUM(quantity * CASE unit WHEN 'KG' THEN 1000 ELSE 1 END), 0)
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

		// A thank-you was queued to the donor, and the donation is marked acknowledged.
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM notifications WHERE to_phone = '+919812345678'", Integer.class))
				.isEqualTo(1);
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
		assertThat(admin.queryForObject("SELECT count(*) FROM notifications", Integer.class)).isZero();
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
		assertThat(admin.queryForObject("SELECT count(*) FROM notifications", Integer.class)).isZero();
	}

	@Test
	@DisplayName("a donation must include at least one item")
	void mustHaveAnItem() throws Exception {
		mvc.perform(recordRequest("{\"anonymous\":true,\"donatedOn\":\"2026-08-10\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4001"));
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
				.andExpect(jsonPath("$.code").value("KMS-4001"));
	}

	@Test
	@DisplayName("kitchen staff may record a donation but not read the donations list")
	void permissionSplit() throws Exception {
		signIn("uid-staff-a");
		String body = """
				{"anonymous":true,"donatedOn":"2026-08-10",
				 "ingredients":[{"ingredientId":"%s","quantity":1,"unit":"KG"}]}
				""".formatted(rice);
		mvc.perform(recordRequest(body)).andExpect(status().isCreated());
		mvc.perform(authed(get("/api/v1/donations"))).andExpect(status().isForbidden());

		// An admin can read it.
		signIn("uid-admin-a");
		mvc.perform(authed(get("/api/v1/donations")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].ingredientCount").value(1));
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
		return authed(post("/api/v1/donations/in-kind"))
				.contentType(MediaType.APPLICATION_JSON).content(body);
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

	// ---------------------------------------------------------------------

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
