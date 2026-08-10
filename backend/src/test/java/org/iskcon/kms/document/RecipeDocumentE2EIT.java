package org.iskcon.kms.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * The whole async path (E2-S5): requesting a recipe PDF queues the work, the background job renders
 * and stores it off the request thread, and the record reaches READY. Quartz is enabled here; the
 * property set matches {@code NotificationSendE2EIT}/{@code BackgroundJobIT} so all three share one
 * scheduler-enabled context. Uses the default stub renderer + local storage.
 */
@TestPropertySource(properties = {
		"spring.autoconfigure.exclude=",
		"spring.quartz.auto-startup=true"})
class RecipeDocumentE2EIT extends AbstractIntegrationTest {

	@Autowired
	private DocumentService documentService;

	private JdbcTemplate admin;
	private UUID temple;
	private UUID recipe;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		temple = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-doc-e2e', 'Admin', 'doc-e2e@govinda.example', '+919876500091', 'TEMPLE_ADMIN', 'ACTIVE')
				""", temple);
		UUID category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name, fasting_compatible)
				VALUES (?, 'Rice', false) RETURNING id
				""", UUID.class, temple);
		UUID rice = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Rice', 'Grains', 'KG') RETURNING id
				""", UUID.class, temple);
		recipe = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit, method)
				VALUES (?, 'Plain Rice', ?, 100, 'SERVINGS', 'Boil the rice.') RETURNING id
				""", UUID.class, temple, category);
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, 5, 'KG', 0)
				""", temple, recipe, rice);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM documents");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("requesting a recipe PDF queues it, and the worker renders it to READY")
	void requestGeneratesInBackground() {
		TenantContext.set(temple);
		TenantContext.setAuthLookupUid("uid-doc-e2e");
		UUID documentId;
		try {
			documentId = documentService.requestRecipePdf(recipe, null, "en");
		} finally {
			TenantContext.clear();
		}

		// Nothing rendered inline — the worker does it. Poll the record (as the privileged role).
		// 30s: clustered-Quartz trigger acquisition can lag under CI load (see NotificationSendE2EIT).
		await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
			String status = admin.queryForObject(
					"SELECT status FROM documents WHERE id = ?", String.class, documentId);
			assertThat(status).isEqualTo("READY");
		});

		String key = admin.queryForObject(
				"SELECT storage_key FROM documents WHERE id = ?", String.class, documentId);
		assertThat(key).isNotNull();
	}
}
