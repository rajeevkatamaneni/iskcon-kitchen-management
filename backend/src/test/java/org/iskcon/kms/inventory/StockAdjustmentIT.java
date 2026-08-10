package org.iskcon.kms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Manual stock adjustment (E3-S7) through the full stack: a signed ADJUSTMENT movement with a
 * mandatory reason, the negative-stock guard, the large-adjustment approval split, and the audit
 * trail that a large write-off leaves behind.
 */
@AutoConfigureMockMvc
@Import(StockAdjustmentIT.StubVerifierConfiguration.class)
class StockAdjustmentIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID actorA;
	private UUID itemId;
	private UUID ingredientId;
	private UUID batch;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		actorA = insertUser(templeA, "uid-admin-a", "admin-a@example.com", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		ingredientId = insertIngredient(templeA, "Toor Dal", "KG");
		itemId = admin.queryForObject("""
				INSERT INTO inventory_items (tenant_id, ingredient_id, storage_location)
				VALUES (?, ?, 'Main store') RETURNING id
				""", UUID.class, templeA, ingredientId);
		// A 100 KG batch on hand.
		batch = UUID.randomUUID();
		seedReceipt(batch, "100");
		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("kitchen staff can make a small adjustment; it lands in the ledger but not the audit log")
	void smallAdjustmentByStaff() throws Exception {
		mvc.perform(adjust(batch, "-3", "KG", "SPOILAGE", null)).andExpect(status().isCreated());

		assertThat(batchStock()).isEqualByComparingTo("97");
		assertThat(auditCount("STOCK_ADJUSTED")).as("small adjustments live in the ledger alone").isZero();
	}

	@Test
	@DisplayName("a large adjustment is refused for kitchen staff, allowed for an admin, and audited")
	void largeAdjustmentNeedsAdmin() throws Exception {
		// 30 KG of 100 is 30% — over the 20% line.
		mvc.perform(adjust(batch, "-30", "KG", "DAMAGE", null))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-4305"));
		assertThat(batchStock()).as("nothing was written on the refusal").isEqualByComparingTo("100");

		signIn("uid-admin-a");
		mvc.perform(adjust(batch, "-30", "KG", "DAMAGE", null)).andExpect(status().isCreated());
		assertThat(batchStock()).isEqualByComparingTo("70");
		assertThat(auditCount("STOCK_ADJUSTED")).isEqualTo(1);
	}

	@Test
	@DisplayName("an adjustment cannot take a batch below zero")
	void cannotGoNegative() throws Exception {
		signIn("uid-admin-a"); // admin, so the large-approval gate isn't what stops it
		mvc.perform(adjust(batch, "-150", "KG", "COUNT_CORRECTION", null))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4910"));
		assertThat(batchStock()).isEqualByComparingTo("100");
	}

	@Test
	@DisplayName("a small correction upward adds to the batch")
	void smallCorrectionUpward() throws Exception {
		mvc.perform(adjust(batch, "2", "KG", "COUNT_CORRECTION", null)).andExpect(status().isCreated());
		assertThat(batchStock()).isEqualByComparingTo("102");
	}

	@Test
	@DisplayName("the reason OTHER requires a note")
	void otherReasonNeedsNote() throws Exception {
		mvc.perform(adjust(batch, "-1", "KG", "OTHER", null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4001"));
	}

	@Test
	@DisplayName("the adjustment unit must belong to the ingredient's measurement family")
	void unitMustMatchFamily() throws Exception {
		mvc.perform(adjust(batch, "-1", "L", "SPOILAGE", null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4001"));
	}

	@Test
	@DisplayName("adjusting a batch that doesn't exist is a not-found")
	void unknownBatch() throws Exception {
		mvc.perform(adjust(UUID.randomUUID(), "-1", "KG", "SPOILAGE", null))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-4402"));
	}

	// ---------------------------------------------------------------------

	private MockHttpServletRequestBuilder adjust(
			UUID batchId, String qty, String unit, String reason, String note) {
		StringBuilder json = new StringBuilder("{")
				.append("\"batchId\":\"").append(batchId).append("\",")
				.append("\"quantity\":").append(qty).append(",")
				.append("\"unit\":\"").append(unit).append("\",")
				.append("\"reason\":\"").append(reason).append("\"");
		if (note != null) {
			json.append(",\"note\":\"").append(note).append("\"");
		}
		json.append("}");
		return post("/api/v1/inventory/items/{id}/adjustments", itemId)
				.header("Authorization", "Bearer valid-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json.toString());
	}

	private BigDecimal batchStock() {
		return admin.queryForObject(
				"SELECT COALESCE(SUM(quantity), 0) FROM stock_movements WHERE batch_id = ?",
				BigDecimal.class, batch);
	}

	private void seedReceipt(UUID batchId, String qty) {
		admin.update("""
				INSERT INTO stock_movements (
					tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id)
				VALUES (?, ?, ?, ?::numeric, 'KG', 'PO_RECEIPT', ?)
				""", templeA, ingredientId, batchId, qty, actorA);
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

	private UUID insertUser(UUID tenantId, String uid, String email, String role) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE')
				RETURNING id
				""", UUID.class, tenantId, uid, email, role);
	}

	private UUID insertIngredient(UUID tenantId, String name, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Pulses', ?)
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
