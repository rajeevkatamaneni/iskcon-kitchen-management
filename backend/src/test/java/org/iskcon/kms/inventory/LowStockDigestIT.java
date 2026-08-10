package org.iskcon.kms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.notification.NotificationRecipient;
import org.iskcon.kms.notification.NotificationService;
import org.iskcon.kms.notification.NotificationTemplate;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * Reorder thresholds and the nightly low-stock digest (E3-S3): the sweep sends a digest only to
 * temples that have something low, only to the people who can act on it, and only once a day.
 *
 * <p>The notification service is mocked — this story owns <em>who</em> gets a digest and <em>when</em>,
 * not the delivery mechanics — which also keeps the test off the Quartz scheduler the base excludes.
 */
@AutoConfigureMockMvc
@Import(LowStockDigestIT.StubVerifierConfiguration.class)
class LowStockDigestIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private LowStockDigestRunner runner;

	@MockBean
	private NotificationService notificationService;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID templeB;
	private UUID adminA;
	private UUID staffA;
	private UUID volA;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();

		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");
		adminA = insertUser(templeA, "uid-admin-a", "admin-a@example.com", "TEMPLE_ADMIN");
		staffA = insertUser(templeA, "uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		volA = insertUser(templeA, "uid-vol-a", "vol-a@example.com", "VOLUNTEER");

		// Temple A: an item below its reorder threshold (threshold 5, nothing on hand).
		UUID dalA = insertIngredient(templeA, "Toor Dal", "KG");
		insertItem(templeA, dalA, "5");

		// Temple B: an item well above threshold, so nothing is low there.
		UUID riceB = insertIngredient(templeB, "Rice", "KG");
		insertItem(templeB, riceB, "5");
		seedReceipt(templeB, riceB, "10");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM low_stock_digest_runs");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("the sweep digests only temples with low stock, only to staff and admins")
	void sweepDigestsLowStockTemples() {
		int templesNotified = runner.sweep();

		assertThat(templesNotified).as("only temple A has something low").isEqualTo(1);

		// Temple A's admin and kitchen staff each get one; the volunteer, who can't act on it, none.
		verify(notificationService).notify(
				eq(NotificationRecipient.user(adminA)), eq(NotificationTemplate.LOW_STOCK_DIGEST), any(), any());
		verify(notificationService).notify(
				eq(NotificationRecipient.user(staffA)), eq(NotificationTemplate.LOW_STOCK_DIGEST), any(), any());
		verify(notificationService, never()).notify(
				eq(NotificationRecipient.user(volA)), any(), any(), any());
		verify(notificationService, times(2)).notify(any(), any(), any(), any());

		// Temple B, with nothing low, is suppressed — no run recorded.
		assertThat(digestRuns(templeA)).isEqualTo(1);
		assertThat(digestRuns(templeB)).isZero();
	}

	@Test
	@DisplayName("a second sweep the same day sends nothing new")
	void sweepIsIdempotentPerDay() {
		runner.sweep();
		int secondPass = runner.sweep();

		assertThat(secondPass).as("today's digest already went out").isZero();
		verify(notificationService, times(2)).notify(any(), any(), any(), any()); // still just the first sweep's two
		assertThat(digestRuns(templeA)).isEqualTo(1);
	}

	@Test
	@DisplayName("the low-stock endpoint lists the below-threshold items")
	void lowStockEndpoint() throws Exception {
		stubVerifier.accept("uid-admin-a");
		mvc.perform(get("/api/v1/inventory/items/low-stock").header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].ingredientName").value("Toor Dal"))
				.andExpect(jsonPath("$[0].belowThreshold").value(true));
	}

	@Test
	@DisplayName("a volunteer cannot read the low-stock list")
	void volunteerForbidden() throws Exception {
		stubVerifier.accept("uid-vol-a");
		mvc.perform(get("/api/v1/inventory/items/low-stock").header("Authorization", "Bearer valid-token"))
				.andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private int digestRuns(UUID tenantId) {
		Integer c = admin.queryForObject(
				"SELECT count(*) FROM low_stock_digest_runs WHERE tenant_id = ?", Integer.class, tenantId);
		return c == null ? 0 : c;
	}

	private void seedReceipt(UUID tenant, UUID ingredient, String qtyKg) {
		admin.update("""
				INSERT INTO stock_movements (
					tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id)
				VALUES (?, ?, ?, ?::numeric, 'KG', 'PO_RECEIPT', ?)
				""", tenant, ingredient, UUID.randomUUID(), qtyKg, adminA);
	}

	private UUID insertItem(UUID tenant, UUID ingredient, String threshold) {
		return admin.queryForObject("""
				INSERT INTO inventory_items (tenant_id, ingredient_id, reorder_threshold)
				VALUES (?, ?, ?::numeric) RETURNING id
				""", UUID.class, tenant, ingredient, threshold);
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
