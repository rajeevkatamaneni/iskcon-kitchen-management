package org.iskcon.kms.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Deleting a temple erases every trace of it — including its append-only rows — while leaving every
 * other temple untouched, and records a durable proof on the platform audit log. Verified against a
 * real database because both guards it crosses (ON DELETE RESTRICT, append-only) are database
 * behaviours; mocking them would prove nothing.
 */
@AutoConfigureMockMvc
@Import(TenantDeletionIT.StubVerifierConfiguration.class)
class TenantDeletionIT extends AbstractIntegrationTest {

	// Every tenant-owned table seedTemple() writes to. audit_events is append-only and references
	// users (ON DELETE RESTRICT), so it exercises the append-only lift and the FK-ordered purge.
	private static final List<String> SEEDED_TABLES =
			List.of("users", "ingredients", "notifications", "audit_events");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
	}

	@AfterEach
	void tearDown() {
		// As the superuser, so append-only and RLS don't obstruct the cleanup.
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM platform_audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("deleting a temple erases all its data, keeps the audit proof, and spares other temples")
	void deleteWipesEverythingAndSparesOthers() throws Exception {
		UUID doomed = seedTemple("radha-govinda", "Sri Sri Radha Govinda Temple");
		UUID survivor = seedTemple("krishna-balaram", "Sri Krishna Balaram Temple");
		signInAsSuperAdmin();

		mvc.perform(authed(delete("/api/v1/tenants/{id}", doomed))).andExpect(status().isNoContent());

		// The doomed temple is gone from every seeded table, and the temple row itself.
		assertThat(rowsFor(doomed)).isZero();
		assertThat(count("SELECT count(*) FROM tenants WHERE id = ?", doomed)).isZero();

		// The only durable record is on the platform log, which is not tenant-owned.
		assertThat(count(
				"SELECT count(*) FROM platform_audit_events WHERE action = 'TENANT_DELETED' AND entity_id = ?",
				doomed)).isEqualTo(1);

		// The other temple is entirely untouched — one row in each of the four seeded tables.
		assertThat(rowsFor(survivor)).isEqualTo(SEEDED_TABLES.size());
		assertThat(count("SELECT count(*) FROM tenants WHERE id = ?", survivor)).isEqualTo(1);
	}

	@Test
	@DisplayName("a temple admin cannot delete a temple")
	void templeAdminIsForbidden() throws Exception {
		UUID temple = seedTemple("radha-govinda", "Sri Sri Radha Govinda Temple");
		stubVerifier.accept("uid-radha-govinda"); // the temple's own admin, seeded by seedTemple

		mvc.perform(authed(delete("/api/v1/tenants/{id}", temple))).andExpect(status().isForbidden());

		// And nothing was deleted.
		assertThat(count("SELECT count(*) FROM tenants WHERE id = ?", temple)).isEqualTo(1);
	}

	@Test
	@DisplayName("deleting an unknown temple is a 404, not a silent success")
	void deletingUnknownTempleIsNotFound() throws Exception {
		signInAsSuperAdmin();

		mvc.perform(authed(delete("/api/v1/tenants/{id}", UUID.randomUUID())))
				.andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------------

	/** A temple with a row in each seeded table, including the append-only audit log. */
	private UUID seedTemple(String slug, String name) {
		UUID temple = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);

		UUID user = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Temple Admin', ?, '+919876500050', 'TEMPLE_ADMIN', 'ACTIVE')
				RETURNING id
				""", UUID.class, temple, "uid-" + slug, slug + "@example.com");

		admin.update(
				"INSERT INTO ingredients (tenant_id, name, category, canonical_unit) VALUES (?, 'Rice', 'Grains', 'KG')",
				temple);
		admin.update("""
				INSERT INTO notifications (tenant_id, recipient_label, to_phone, template, preferred_channel, status)
				VALUES (?, 'Test Devotee', '+919876500051', 'SHIFT_REMINDER', 'WHATSAPP', 'SENT')
				""", temple);
		// Append-only, and references the user above — so the purge must lift append-only and delete
		// this before it can delete the user.
		admin.update("""
				INSERT INTO audit_events (tenant_id, actor_user_id, actor_label, action, entity_type, entity_id)
				VALUES (?, ?, 'Temple Admin', 'ROLE_CHANGED', 'USER', ?)
				""", temple, user, user);

		return temple;
	}

	private int rowsFor(UUID tenantId) {
		int total = 0;
		for (String table : SEEDED_TABLES) {
			total += count("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", tenantId);
		}
		return total;
	}

	private int count(String sql, UUID id) {
		Integer n = admin.queryForObject(sql, Integer.class, id);
		return n == null ? 0 : n;
	}

	private void signInAsSuperAdmin() {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (NULL, 'uid-super', 'Platform Operator', 'super@example.com', '+919000000001',
						'SUPER_ADMIN', 'ACTIVE')
				""");
		stubVerifier.accept("uid-super");
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
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
