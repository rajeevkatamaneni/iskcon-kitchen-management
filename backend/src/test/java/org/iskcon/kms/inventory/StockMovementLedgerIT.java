package org.iskcon.kms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
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
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The stock ledger (E3-S2) through the full stack. Proves the two database-enforced guarantees —
 * the ledger is append-only and tenant-isolated — the way {@link org.iskcon.kms.audit.AuditEventsIT}
 * does, and proves the behaviour that makes it a ledger: stock is the sum of movements, a mistake is
 * undone by a compensating movement (never an edit), and a movement can be corrected only once.
 */
@AutoConfigureMockMvc
@Import(StockMovementLedgerIT.StubVerifierConfiguration.class)
class StockMovementLedgerIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate jdbc;
	private JdbcTemplate admin;
	private UUID templeA;
	private UUID templeB;
	private UUID actorA;
	private UUID ingredientA;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		jdbc = new JdbcTemplate(dataSource);
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();

		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");
		actorA = insertUser(templeA, "uid-admin-a", "admin-a@example.com", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		insertUser(templeA, "uid-vol-a", "vol-a@example.com", "VOLUNTEER");
		ingredientA = insertIngredient(templeA, "Toor Dal");
		signIn("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("the ledger's owner keeps the privileges PostgreSQL's foreign keys need")
	void appendOnlyDoesNotBreakForeignKeys() {
		// A foreign key that points at the ledger is checked by PostgreSQL as the ledger's owner,
		// and the check takes a FOR KEY SHARE lock — which requires UPDATE or DELETE. Revoking
		// those to make the table append-only is what made deleting an ingredient impossible on a
		// real deployment, whether or not any movement mentioned it.
		for (String table : new String[] {"stock_movements", "goods_receipt_lines", "audit_events"}) {
			String owner = admin.queryForObject(
					"SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = ?::regclass",
					String.class, "public." + table);
			assertThat(admin.queryForObject(
					"SELECT has_table_privilege(?, ?::regclass, 'UPDATE')", Boolean.class, owner, "public." + table))
					.as("%s: its owner (%s) must keep UPDATE, or foreign keys pointing at it cannot be checked",
							table, owner)
					.isTrue();
			assertThat(admin.queryForObject(
					"SELECT has_table_privilege(?, ?::regclass, 'DELETE')", Boolean.class, owner, "public." + table))
					.as("%s: its owner (%s) must keep DELETE, for the same reason", table, owner)
					.isTrue();
		}
	}

	@Test
	@DisplayName("the application role can INSERT a movement but never UPDATE or DELETE one")
	void ledgerIsAppendOnly() {
		UUID batch = UUID.randomUUID();
		UUID movement = seedMovement(templeA, ingredientA, batch, "100", MovementType.PO_RECEIPT);

		// Set the row's own tenant so RLS *permits* the write — what then stops it is the missing
		// privilege, which is the append-only guarantee (not RLS matching zero rows).
		TenantContext.set(templeA);

		assertThatThrownBy(() ->
				jdbc.update("UPDATE stock_movements SET quantity = 9 WHERE id = ?", movement))
				.as("append-only: UPDATE must be refused")
				.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() ->
				jdbc.update("DELETE FROM stock_movements WHERE id = ?", movement))
				.as("append-only: DELETE must be refused")
				.isInstanceOf(DataAccessException.class);

		assertThat(admin.queryForObject(
				"SELECT quantity FROM stock_movements WHERE id = ?", BigDecimal.class, movement))
				.isEqualByComparingTo("100");
	}

	@Test
	@DisplayName("stock is the sum of a consumable's movements, not a stored level")
	void stockIsDerivedFromMovements() {
		UUID batch = UUID.randomUUID();
		seedMovement(templeA, ingredientA, batch, "100", MovementType.PO_RECEIPT);
		seedMovement(templeA, ingredientA, batch, "-30", MovementType.CONSUMPTION);
		seedAdjustment(templeA, ingredientA, batch, "-5");

		BigDecimal onHand = admin.queryForObject(
				"SELECT sum(quantity) FROM stock_movements WHERE ingredient_id = ?",
				BigDecimal.class, ingredientA);
		assertThat(onHand).isEqualByComparingTo("65");
	}

	@Test
	@DisplayName("a correction is a compensating movement that reverses the original within its batch")
	void correctionReversesAndCrossReferences() throws Exception {
		UUID batch = UUID.randomUUID();
		UUID original = seedMovement(templeA, ingredientA, batch, "40", MovementType.PO_RECEIPT);

		mvc.perform(compensate(original, "Received 40, but only 25 arrived — miscount"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").exists());

		// The batch nets back to zero: the original +40 and its exact reverse -40.
		assertThat(admin.queryForObject(
				"SELECT sum(quantity) FROM stock_movements WHERE batch_id = ?", BigDecimal.class, batch))
				.isEqualByComparingTo("0");

		Map<String, Object> correction = admin.queryForMap("""
				SELECT tenant_id, quantity, movement_type, reference_type, reference_id
				FROM stock_movements
				WHERE reference_type = 'CORRECTION' AND reference_id = ?
				""", original);
		assertThat(correction.get("tenant_id")).isEqualTo(templeA);
		assertThat((BigDecimal) correction.get("quantity")).isEqualByComparingTo("-40");
		assertThat(correction.get("movement_type")).isEqualTo("ADJUSTMENT");

		assertThat(auditCount("STOCK_MOVEMENT_CORRECTED")).isEqualTo(1);
	}

	@Test
	@DisplayName("a movement can be corrected only once")
	void aMovementCanBeCorrectedOnce() throws Exception {
		UUID batch = UUID.randomUUID();
		UUID original = seedMovement(templeA, ingredientA, batch, "40", MovementType.PO_RECEIPT);

		mvc.perform(compensate(original, "wrong count")).andExpect(status().isCreated());
		mvc.perform(compensate(original, "again"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4908"));
	}

	@Test
	@DisplayName("history is newest-first and can be filtered to one movement type")
	void historyFiltersByType() throws Exception {
		UUID batch = UUID.randomUUID();
		seedMovement(templeA, ingredientA, batch, "100", MovementType.PO_RECEIPT);
		seedMovement(templeA, ingredientA, batch, "-20", MovementType.CONSUMPTION);
		seedAdjustment(templeA, ingredientA, batch, "-3");

		mvc.perform(authed(get("/api/v1/inventory/movements")).param("ingredientId", ingredientA.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3));

		mvc.perform(authed(get("/api/v1/inventory/movements"))
						.param("ingredientId", ingredientA.toString())
						.param("type", "ADJUSTMENT"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].type").value("ADJUSTMENT"));
	}

	@Test
	@DisplayName("another temple's movements are simply not in this temple's history")
	void historyIsTenantScoped() throws Exception {
		UUID otherIngredient = insertIngredient(templeB, "Payasam Base");
		seedMovement(templeB, otherIngredient, UUID.randomUUID(), "50", MovementType.PO_RECEIPT);

		mvc.perform(authed(get("/api/v1/inventory/movements")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	@DisplayName("kitchen staff may correct a movement; a volunteer may not")
	void permissionGuardsCorrection() throws Exception {
		UUID batch = UUID.randomUUID();
		UUID original = seedMovement(templeA, ingredientA, batch, "40", MovementType.PO_RECEIPT);

		signIn("uid-vol-a");
		mvc.perform(compensate(original, "nope")).andExpect(status().isForbidden());

		signIn("uid-staff-a");
		mvc.perform(compensate(original, "corrected on the floor")).andExpect(status().isCreated());
	}

	// ---------------------------------------------------------------------

	private MockHttpServletRequestBuilder compensate(UUID movementId, String note) {
		return authed(post("/api/v1/inventory/movements/{id}/compensate", movementId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"note\":\"" + note + "\"}");
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private UUID seedMovement(UUID tenant, UUID ingredient, UUID batch, String qty, MovementType type) {
		return admin.queryForObject("""
				INSERT INTO stock_movements (
					tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id)
				VALUES (?, ?, ?, ?::numeric, 'KG', ?, ?)
				RETURNING id
				""", UUID.class, tenant, ingredient, batch, qty, type.name(), actorA);
	}

	private UUID seedAdjustment(UUID tenant, UUID ingredient, UUID batch, String qty) {
		return admin.queryForObject("""
				INSERT INTO stock_movements (
					tenant_id, ingredient_id, batch_id, quantity, unit, movement_type,
					reason_category, actor_user_id)
				VALUES (?, ?, ?, ?::numeric, 'KG', 'ADJUSTMENT', 'SPOILAGE', ?)
				RETURNING id
				""", UUID.class, tenant, ingredient, batch, qty, actorA);
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

	private UUID insertIngredient(UUID tenantId, String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Pulses', 'KG')
				RETURNING id
				""", UUID.class, tenantId, name);
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
