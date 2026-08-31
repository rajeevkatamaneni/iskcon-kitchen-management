package org.iskcon.kms.ingredientrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Recording what actually went over the counter (E10-S7): the batches it comes out of, the stock it
 * takes with it, and the four ways it is refused.
 *
 * <p>The store room is seeded straight into {@code stock_movements}, because that is where stock
 * actually lives — every balance in this application is the sum of those rows, and a fixture that
 * set a number somewhere else would be testing a fiction.
 */
@AutoConfigureMockMvc
@Import(IngredientIssueIT.StubVerifierConfiguration.class)
class IngredientIssueIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID adminA;
	private UUID kitchenA;
	private UUID rice;
	private UUID dal;
	private UUID oil;
	private UUID jaggery;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();

		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		adminA = insertUser(templeA, "uid-admin-a", "Gopal Das", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-manager-a", "Radha Devi", "KITCHEN_MANAGER");
		insertUser(templeA, "uid-cook-a", "Bhakta Shyam", "KITCHEN_STAFF");

		kitchenA = insertKitchen(templeA, "Deity kitchen");
		rice = insertIngredient(templeA, "Rice", "KG");
		dal = insertIngredient(templeA, "Toor dal", "KG");
		oil = insertIngredient(templeA, "Groundnut oil", "L");
		jaggery = insertIngredient(templeA, "Jaggery", "KG");

		signIn("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredient_request_events");
		admin.execute("DELETE FROM ingredient_request_lines");
		admin.execute("DELETE FROM ingredient_request_dishes");
		admin.execute("DELETE FROM ingredient_requests");
		admin.execute("DELETE FROM ingredient_request_sequence");
		admin.execute("DELETE FROM kitchens");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("issuing draws the oldest-expiry batch first, then the next, across one ingredient")
	void drawsOldestExpiryFirst() throws Exception {
		UUID september = seedBatch(rice, "5", LocalDate.of(2026, 9, 30));
		UUID december = seedBatch(rice, "10", LocalDate.of(2026, 12, 31));
		UUID noExpiry = seedBatch(rice, "20", null);

		String id = approvedRequest(line(rice, "12", "KG"));
		issue(id, "{}").andExpect(status().isNoContent());

		// Which batches, and how much out of each — not what order the rows landed in. Every movement
		// of one issue shares a created_at, because now() is the transaction's clock, so row order
		// says nothing and asserting on it would be asserting on a shuffle.
		Map<UUID, BigDecimal> drawn = drawnByBatch(id);
		assertThat(drawn).containsOnlyKeys(september, december);
		// September's lot spoils first, so it is emptied before anything else is touched.
		assertThat(drawn.get(september)).isEqualByComparingTo("-5000");
		assertThat(drawn.get(december)).isEqualByComparingTo("-7000");
		// The lot with no expiry at all is last in the queue and was never reached.
		assertThat(drawn).doesNotContainKey(noExpiry);
	}

	@Test
	@DisplayName("stock after issuing is stock before minus exactly what went out")
	void stockFallsByWhatWasIssued() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));
		BigDecimal before = onHandBase(rice);

		String id = approvedRequest(line(rice, "12", "KG"));
		issue(id, "{}").andExpect(status().isNoContent());

		assertThat(onHandBase(rice)).isEqualByComparingTo(before.subtract(new BigDecimal("12000")));
	}

	@Test
	@DisplayName("the movements point at the request and carry no storage location")
	void movementsCarryTheRequestAndNoLocation() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));
		String id = approvedRequest(line(rice, "12", "KG"));

		issue(id, "{}").andExpect(status().isNoContent());

		Map<String, Object> movement = issueMovements(id).get(0);
		assertThat(movement.get("movement_type")).isEqualTo("ISSUE");
		assertThat(movement.get("reference_type")).isEqualTo("INGREDIENT_REQUEST");
		assertThat(movement.get("reference_id")).isEqualTo(UUID.fromString(id));
		// It says where in the store a thing sits, not where it went. Writing the receiving kitchen
		// here would relocate the store room's rice to the Deity kitchen.
		assertThat(movement.get("storage_location")).isNull();
	}

	@Test
	@DisplayName("the request becomes issued, records who and when, and the issue is audited")
	void theRequestIsClosed() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));
		String id = approvedRequest(line(rice, "12", "KG"));

		issue(id, "{}").andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/ingredient-requests/{id}", id)))
				.andExpect(jsonPath("$.request.status").value("ISSUED"))
				.andExpect(jsonPath("$.request.issuedAt").isNotEmpty())
				.andExpect(jsonPath("$.lines[0].issuedQuantity").value(12))
				.andExpect(jsonPath("$.lines[0].issuedUnit").value("KG"))
				.andExpect(jsonPath("$.events[3].eventType").value("ISSUED"));
		assertThat(auditCount("INGREDIENT_REQUEST_ISSUED")).isEqualTo(1);
	}

	@Test
	@DisplayName("an empty issue hands over exactly what was approved")
	void anEmptyIssueTakesTheApprovedQuantities() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));
		seedBatch(dal, "20", LocalDate.of(2026, 12, 31));
		String id = approvedRequest(line(rice, "12", "KG") + "," + line(dal, "4", "KG"));

		issue(id, "{}").andExpect(status().isNoContent());

		assertThat(onHandBase(rice)).isEqualByComparingTo("38000");
		assertThat(onHandBase(dal)).isEqualByComparingTo("16000");
	}

	@Test
	@DisplayName("the storekeeper may hand over less than was approved, and the lower figure is what is recorded")
	void issuesLessThanApproved() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));
		String id = approvedRequest(line(rice, "12", "KG"));
		UUID lineId = firstLineId(id);

		issue(id, issuedLines(issued(lineId, "9", "KG"))).andExpect(status().isNoContent());

		assertThat(onHandBase(rice)).isEqualByComparingTo("41000");
		mvc.perform(authed(get("/api/v1/ingredient-requests/{id}", id)))
				.andExpect(jsonPath("$.lines[0].issuedQuantity").value(9));
	}

	@Test
	@DisplayName("a line issued as zero writes no stock movement at all, and the zero is kept")
	void aZeroLineWritesNoMovement() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));
		seedBatch(dal, "20", LocalDate.of(2026, 12, 31));
		String id = approvedRequest(line(rice, "12", "KG") + "," + line(dal, "4", "KG"));
		UUID dalLine = lineIdFor(id, dal);

		issue(id, issuedLines(issued(dalLine, "0", "KG"))).andExpect(status().isNoContent());

		assertThat(issueMovements(id)).hasSize(1);
		assertThat(onHandBase(dal)).isEqualByComparingTo("20000");
		mvc.perform(authed(get("/api/v1/ingredient-requests/{id}", id)))
				.andExpect(jsonPath("$.lines[1].issuedQuantity").value(0));
	}

	@Test
	@DisplayName("a shortfall on the fourth line writes nothing at all for the first three")
	void aShortfallWritesNothing() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));
		seedBatch(dal, "20", LocalDate.of(2026, 12, 31));
		seedBatch(oil, "10", LocalDate.of(2026, 12, 31));
		seedBatch(jaggery, "1", LocalDate.of(2026, 12, 31));

		String id = approvedRequest(line(rice, "12", "KG") + "," + line(dal, "4", "KG")
				+ "," + line(oil, "2", "L") + "," + line(jaggery, "5", "KG"));

		issue(id, "{}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4987"))
				// And it says which one. A storekeeper holding a request for four things cannot act
				// on "there is not enough stock" — they would check all four by hand. Jaggery is the
				// short one, and the refusal names it and says how short.
				.andExpect(jsonPath("$.fieldErrors[0].field").value("Jaggery"))
				.andExpect(jsonPath("$.fieldErrors[0].message")
						.value(org.hamcrest.Matchers.containsString("the store holds")))
				.andExpect(jsonPath("$.fieldErrors.length()").value(1));

		// Not "some" — none. A half-written issue would leave four stock figures and no way to say
		// which of them was true.
		assertThat(movementCount("ISSUE")).isZero();
		assertThat(onHandBase(rice)).isEqualByComparingTo("50000");
		assertThat(onHandBase(dal)).isEqualByComparingTo("20000");
		assertThat(onHandBase(oil)).isEqualByComparingTo("10000");
		mvc.perform(authed(get("/api/v1/ingredient-requests/{id}", id)))
				.andExpect(jsonPath("$.request.status").value("APPROVED"));
	}

	@Test
	@DisplayName("an ingredient with no stock at all is a shortfall, not a silent zero")
	void nothingOnTheShelfIsAShortfall() throws Exception {
		String id = approvedRequest(line(rice, "1", "KG"));

		issue(id, "{}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4987"));
		assertThat(movementCount("ISSUE")).isZero();
	}

	@Test
	@DisplayName("two lines naming the same ingredient share one shelf rather than each seeing it whole")
	void twoLinesShareTheSameShelf() throws Exception {
		seedBatch(rice, "10", LocalDate.of(2026, 12, 31));

		// Six and six is twelve, and there are only ten. Each line on its own would fit.
		String id = approvedRequest(line(rice, "6", "KG") + "," + line(rice, "6", "KG"));

		issue(id, "{}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4987"));
		assertThat(movementCount("ISSUE")).isZero();
	}

	@Test
	@DisplayName("a pinned batch is drawn before the one that expires first")
	void aPinnedBatchGoesFirst() throws Exception {
		UUID september = seedBatch(rice, "5", LocalDate.of(2026, 9, 30));
		UUID opened = seedBatch(rice, "10", LocalDate.of(2026, 12, 31));

		String id = approvedRequest(line(rice, "3", "KG"));
		issue(id, ("{\"batchOverrides\":[{\"ingredientId\":\"%s\",\"batchId\":\"%s\"}]}")
				.formatted(rice, opened))
				.andExpect(status().isNoContent());

		List<Map<String, Object>> movements = issueMovements(id);
		assertThat(movements).hasSize(1);
		assertThat(movements.get(0).get("batch_id")).isEqualTo(opened);
		assertThat(september).isNotEqualTo(opened);
	}

	@Test
	@DisplayName("a quantity may come back in another unit of the same family, and never in a different one")
	void issuedUnitStaysInTheFamily() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));
		String id = approvedRequest(line(rice, "12", "KG"));
		UUID lineId = firstLineId(id);

		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/issue", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content(issuedLines(issued(lineId, "3", "L"))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4001"));

		issue(id, issuedLines(issued(lineId, "500", "GM"))).andExpect(status().isNoContent());
		assertThat(onHandBase(rice)).isEqualByComparingTo("49500");
	}

	@Test
	@DisplayName("an approved request cannot be issued twice")
	void cannotIssueTwice() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));
		String id = approvedRequest(line(rice, "12", "KG"));

		issue(id, "{}").andExpect(status().isNoContent());
		issue(id, "{}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4982"));

		assertThat(issueMovements(id)).hasSize(1);
	}

	@Test
	@DisplayName("a request nobody has approved cannot be issued")
	void cannotIssueWhatIsNotApproved() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));
		String id = draftRequest(line(rice, "12", "KG"));

		issue(id, "{}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4981"));

		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/submit", id)))
				.andExpect(status().isNoContent());
		issue(id, "{}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4981"));
		assertThat(movementCount("ISSUE")).isZero();
	}

	@Test
	@DisplayName("a denied request cannot be issued")
	void cannotIssueADeniedRequest() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));
		String id = draftRequest(line(rice, "12", "KG"));
		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/submit", id)))
				.andExpect(status().isNoContent());
		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/deny", id))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNoContent());

		issue(id, "{}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4981"));
	}

	@Test
	@DisplayName("an issued request can no longer be edited, deleted or withdrawn")
	void anIssuedRequestIsClosedForGood() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));
		String id = approvedRequest(line(rice, "12", "KG"));
		issue(id, "{}").andExpect(status().isNoContent());

		mvc.perform(authed(put("/api/v1/ingredient-requests/{id}", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content(IngredientRequestIT.body(kitchenA, line(rice, "1", "KG"),
						IngredientRequestIT.dish("Khichdi", "10", "KG"))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4982"));

		mvc.perform(authed(delete("/api/v1/ingredient-requests/{id}", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4982"));

		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/withdraw", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4982"));
	}

	@Test
	@DisplayName("kitchen staff may not issue against a request, however it was approved")
	void kitchenStaffCannotIssue() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));
		String id = approvedRequest(line(rice, "12", "KG"));

		signIn("uid-cook-a");
		issue(id, "{}")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-4301"));
		assertThat(movementCount("ISSUE")).isZero();
	}

	@Test
	@DisplayName("a kitchen manager may issue — that is the storekeeper this temple actually has")
	void aKitchenManagerMayIssue() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));
		String id = approvedRequest(line(rice, "12", "KG"));

		signIn("uid-manager-a");
		issue(id, "{}").andExpect(status().isNoContent());
		assertThat(issueMovements(id)).hasSize(1);
	}

	// ---------------------------------------------------------------------

	private String draftRequest(String lines) throws Exception {
		String json = IngredientRequestIT.body(kitchenA, lines,
				IngredientRequestIT.dish("Khichdi", "200", "KG"));
		String response = mvc.perform(authed(post("/api/v1/ingredient-requests"))
				.contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return response.replaceAll(".*\"id\"\\s*:\\s*\"([0-9a-f-]+)\".*", "$1");
	}

	private String approvedRequest(String lines) throws Exception {
		String id = draftRequest(lines);
		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/submit", id)))
				.andExpect(status().isNoContent());
		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/approve", id))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNoContent());
		return id;
	}

	private org.springframework.test.web.servlet.ResultActions issue(String id, String json)
			throws Exception {
		return mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/issue", id))
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static String line(UUID ingredientId, String quantity, String unit) {
		return IngredientRequestIT.line(ingredientId, quantity, unit);
	}

	private static String issued(UUID lineId, String quantity, String unit) {
		return "{\"lineId\":\"%s\",\"quantity\":%s,\"unit\":\"%s\"}".formatted(lineId, quantity, unit);
	}

	private static String issuedLines(String... lines) {
		return "{\"lines\":[" + String.join(",", lines) + "]}";
	}

	private UUID firstLineId(String requestId) {
		return admin.queryForObject(
				"SELECT id FROM ingredient_request_lines WHERE request_id = ?::uuid ORDER BY line_no LIMIT 1",
				UUID.class, requestId);
	}

	private UUID lineIdFor(String requestId, UUID ingredientId) {
		return admin.queryForObject("""
				SELECT id FROM ingredient_request_lines
				WHERE request_id = ?::uuid AND ingredient_id = ? ORDER BY line_no LIMIT 1
				""", UUID.class, requestId, ingredientId);
	}

	/** The issue movements this request wrote, oldest first — which is the order they were drawn in. */
	private List<Map<String, Object>> issueMovements(String requestId) {
		return admin.queryForList("""
				SELECT batch_id, quantity, unit, movement_type, reference_type, reference_id,
					   storage_location
				FROM stock_movements
				WHERE reference_type = 'INGREDIENT_REQUEST' AND reference_id = ?::uuid
				ORDER BY created_at, id
				""", requestId);
	}

	/** How much this issue took out of each batch. Keyed by batch, because order is not meaningful. */
	private Map<UUID, BigDecimal> drawnByBatch(String requestId) {
		Map<UUID, BigDecimal> drawn = new java.util.LinkedHashMap<>();
		for (Map<String, Object> row : issueMovements(requestId)) {
			drawn.merge((UUID) row.get("batch_id"), (BigDecimal) row.get("quantity"), BigDecimal::add);
		}
		return drawn;
	}

	private int movementCount(String type) {
		Integer c = admin.queryForObject(
				"SELECT count(*) FROM stock_movements WHERE movement_type = ?", Integer.class, type);
		return c == null ? 0 : c;
	}

	/** What the shelf holds, in the family's base unit — the only figure the ledger can be asked for. */
	private BigDecimal onHandBase(UUID ingredientId) {
		BigDecimal sum = admin.queryForObject("""
				SELECT COALESCE(SUM(to_base_qty(quantity, unit)), 0)
				FROM stock_movements WHERE ingredient_id = ?
				""", BigDecimal.class, ingredientId);
		return sum == null ? BigDecimal.ZERO : sum;
	}

	private UUID seedBatch(UUID ingredientId, String quantityInCanonicalUnit, LocalDate expiry) {
		UUID batch = UUID.randomUUID();
		String unit = admin.queryForObject(
				"SELECT canonical_unit FROM ingredients WHERE id = ?", String.class, ingredientId);
		admin.update("""
				INSERT INTO stock_movements (
					tenant_id, ingredient_id, batch_id, quantity, unit, movement_type,
					expiry_date, received_date, actor_user_id)
				VALUES (?, ?, ?, ?::numeric, ?, 'PO_RECEIPT', ?, ?, ?)
				""", templeA, ingredientId, batch, quantityInCanonicalUnit, unit, expiry,
				LocalDate.of(2026, 1, 1), adminA);
		return batch;
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
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug, name);
	}

	private UUID insertUser(UUID tenantId, String uid, String fullName, String role) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, '+919876500081', ?, 'ACTIVE') RETURNING id
				""", UUID.class, tenantId, uid, fullName, uid + "@example.com", role);
	}

	private UUID insertKitchen(UUID tenantId, String name) {
		return admin.queryForObject("""
				INSERT INTO kitchens (tenant_id, name, created_by)
				VALUES (?, ?, (SELECT id FROM users WHERE tenant_id = ? LIMIT 1)) RETURNING id
				""", UUID.class, tenantId, name, tenantId);
	}

	private UUID insertIngredient(UUID tenantId, String name, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', ?) RETURNING id
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
