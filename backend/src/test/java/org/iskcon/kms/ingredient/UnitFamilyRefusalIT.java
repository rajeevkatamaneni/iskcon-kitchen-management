package org.iskcon.kms.ingredient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.notification.NotificationService;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The three doors that used to refuse a unit from the wrong family with the generic
 * {@code KMS-400001} (T-150), each now held to the one rule every quantity obeys (BL-9,
 * {@link IngredientUnits#requireSameFamily}): a stock adjustment, a gift of goods, and a kitchen's
 * ingredient request.
 *
 * <p>Each door is asked the same three things. It refuses with {@code KMS-400013} and a field error
 * naming the ingredient — the whole point of the specific code is that somebody looking at a
 * many-line gift is told <em>which</em> line. It writes nothing, and on a two-line form where one
 * line is fine and one is not, it writes neither. And it still accepts a different unit of the same
 * family, grams against a kilo-held rice, because refusing that would be refusing the ordinary case.
 *
 * <p><strong>Read the "nothing written" assertions with care.</strong> The old inline checks also
 * refused, so they wrote nothing too; those assertions pass against the old code and prove the new
 * code did not regress, not that it changed. What distinguishes the two is the code and the named
 * field, which is why every refusal here asserts both.
 *
 * <p>Everything runs as Kitchen Staff, the role UAT-081 is written for, and every figure is small
 * enough that the large-adjustment approval gate is never what answers.
 */
@AutoConfigureMockMvc
@Import(UnitFamilyRefusalIT.StubVerifierConfiguration.class)
class UnitFamilyRefusalIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	// A gift from a named donor with contact details queues a thank-you. None of these gifts has
	// one, but the base test keeps Quartz out of the context and this keeps the donation path off it.
	@MockBean
	private NotificationService notificationService;

	private JdbcTemplate admin;
	private UUID temple;
	private UUID cook;
	private UUID rice;
	private UUID ghee;
	private UUID riceItem;
	private UUID riceBatch;
	private UUID kitchen;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		temple = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		cook = insertUser(temple, "uid-cook", "KITCHEN_STAFF");
		rice = insertIngredient(temple, "Rice", "KG");
		ghee = insertIngredient(temple, "Ghee", "L");

		riceItem = admin.queryForObject("""
				INSERT INTO inventory_items (tenant_id, ingredient_id, storage_location)
				VALUES (?, ?, 'Main store') RETURNING id
				""", UUID.class, temple, rice);
		riceBatch = UUID.randomUUID();
		admin.update("""
				INSERT INTO stock_movements (
					tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id)
				VALUES (?, ?, ?, 100, 'KG', 'PO_RECEIPT', ?)
				""", temple, rice, riceBatch, cook);

		kitchen = admin.queryForObject("""
				INSERT INTO kitchens (tenant_id, name, uses_meal_planner, created_by)
				VALUES (?, 'Deity kitchen', false, ?) RETURNING id
				""", UUID.class, temple, cook);

		stubVerifier.accept("uid-cook");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM donations");
		admin.execute("DELETE FROM audit_events");
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

	// ---- A stock adjustment ---------------------------------------------

	@Test
	@DisplayName("adjusting rice in litres or pieces is KMS-400013 naming Rice, and the ledger is untouched")
	void adjustmentRefusesAnotherFamily() throws Exception {
		refusedNaming(mvc.perform(adjust("-2", "L")),
				"Rice", "Rice is measured in Kg, and there is no way to turn L into Kg.");
		refusedNaming(mvc.perform(adjust("-2", "PIECES")),
				"Rice", "Rice is measured in Kg, and there is no way to turn pieces into Kg.");

		assertThat(count("stock_movements")).as("only the seeded receipt").isEqualTo(1);
		assertThat(riceOnHandBase()).isEqualByComparingTo("100000");
	}

	@Test
	@DisplayName("adjusting rice by 500 gm is accepted, because grams and kilograms are one family")
	void adjustmentAcceptsTheSameFamily() throws Exception {
		mvc.perform(adjust("-500", "GM")).andExpect(status().isCreated());

		assertThat(count("stock_movements")).isEqualTo(2);
		assertThat(riceOnHandBase()).as("half a kilo out of a hundred").isEqualByComparingTo("99500");
	}

	// ---- A gift of goods ------------------------------------------------

	@Test
	@DisplayName("a gift of rice in litres is KMS-400013 naming Rice, and no donation or movement is written")
	void giftRefusesAnotherFamily() throws Exception {
		refusedNaming(mvc.perform(gift(giftLine(rice, "5", "L"))),
				"Rice", "Rice is measured in Kg, and there is no way to turn L into Kg.");

		assertThat(count("donations")).isZero();
		assertThat(count("stock_movements")).as("only the seeded receipt").isEqualTo(1);
	}

	@Test
	@DisplayName("a two-line gift with one good line and one nonsense line is refused whole, naming Ghee")
	void twoLineGiftIsRefusedWhole() throws Exception {
		refusedNaming(mvc.perform(gift(giftLine(rice, "5", "KG") + "," + giftLine(ghee, "2", "KG"))),
				"Ghee", "Ghee is measured in L, and there is no way to turn Kg into L.");

		assertThat(count("donations")).isZero();
		assertThat(count("stock_movements")).as("the rice line was not written either").isEqualTo(1);
		assertThat(riceOnHandBase()).isEqualByComparingTo("100000");
		assertThat(count("inventory_items")).as("ghee was not started being tracked").isEqualTo(1);
	}

	@Test
	@DisplayName("a gift of 5000 gm of rice is accepted into stock")
	void giftAcceptsTheSameFamily() throws Exception {
		mvc.perform(gift(giftLine(rice, "5000", "GM"))).andExpect(status().isCreated());

		assertThat(count("donations")).isEqualTo(1);
		assertThat(riceOnHandBase()).isEqualByComparingTo("105000");
	}

	// ---- An ingredient request ------------------------------------------

	@Test
	@DisplayName("a request for rice in litres is KMS-400013 naming Rice, and no request or line is written")
	void requestRefusesAnotherFamily() throws Exception {
		refusedNaming(mvc.perform(createRequest(requestLine(rice, "3", "L"))),
				"Rice", "Rice is measured in Kg, and there is no way to turn L into Kg.");

		assertThat(count("ingredient_requests")).isZero();
		assertThat(count("ingredient_request_lines")).isZero();
		assertThat(count("ingredient_request_dishes")).isZero();
	}

	@Test
	@DisplayName("a two-line request with one nonsense line is refused whole, naming Ghee")
	void twoLineRequestIsRefusedWhole() throws Exception {
		refusedNaming(mvc.perform(createRequest(requestLine(rice, "40", "KG") + "," + requestLine(ghee, "2", "KG"))),
				"Ghee", "Ghee is measured in L, and there is no way to turn Kg into L.");

		assertThat(count("ingredient_requests")).isZero();
		assertThat(count("ingredient_request_lines")).isZero();
	}

	@Test
	@DisplayName("editing a draft to add a nonsense line is refused, and the draft's lines are as they were")
	void editIsRefusedAndTheDraftIsUnchanged() throws Exception {
		String id = created(mvc.perform(createRequest(requestLine(rice, "40", "KG"))));

		refusedNaming(mvc.perform(authed(put("/api/v1/ingredient-requests/{id}", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content(requestBody(requestLine(rice, "60", "KG") + "," + requestLine(ghee, "2", "KG")))),
				"Ghee", "Ghee is measured in L, and there is no way to turn Kg into L.");

		assertThat(count("ingredient_request_lines")).isEqualTo(1);
		assertThat(admin.queryForObject(
				"SELECT quantity FROM ingredient_request_lines WHERE ingredient_id = ?", BigDecimal.class, rice))
				.as("the rice line still says 40, not the 60 the refused edit carried")
				.isEqualByComparingTo("40");
	}

	@Test
	@DisplayName("a request for 500 gm of rice is accepted")
	void requestAcceptsTheSameFamily() throws Exception {
		created(mvc.perform(createRequest(requestLine(rice, "500", "GM"))));

		assertThat(admin.queryForObject("SELECT unit FROM ingredient_request_lines", String.class)).isEqualTo("GM");
	}

	// ---------------------------------------------------------------------

	/** KMS-400013, with exactly one field error, and that one names the ingredient at fault. */
	private static void refusedNaming(ResultActions result, String ingredient, String message) throws Exception {
		result.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400013"))
				.andExpect(jsonPath("$.fieldErrors.length()").value(1))
				.andExpect(jsonPath("$.fieldErrors[0].field").value(ingredient))
				.andExpect(jsonPath("$.fieldErrors[0].message").value(message));
	}

	private MockHttpServletRequestBuilder adjust(String quantity, String unit) {
		return authed(post("/api/v1/inventory/items/{id}/adjustments", riceItem))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"batchId\":\"%s\",\"quantity\":%s,\"unit\":\"%s\",\"reason\":\"SPOILAGE\"}"
						.formatted(riceBatch, quantity, unit));
	}

	private MockHttpServletRequestBuilder gift(String lines) {
		return authed(post("/api/v1/donations"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"anonymous\":true,\"donatedOn\":\"2026-08-10\",\"ingredients\":[%s]}".formatted(lines));
	}

	private static String giftLine(UUID ingredientId, String quantity, String unit) {
		return "{\"ingredientId\":\"%s\",\"quantity\":%s,\"unit\":\"%s\"}".formatted(ingredientId, quantity, unit);
	}

	private MockHttpServletRequestBuilder createRequest(String lines) {
		return authed(post("/api/v1/ingredient-requests"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody(lines));
	}

	private String requestBody(String lines) {
		return ("{\"kitchenId\":\"%s\",\"neededOn\":\"%s\",\"purpose\":\"Janmashtami feast\","
				+ "\"lines\":[%s],\"dishes\":[{\"dishName\":\"Khichdi\",\"quantity\":200,\"unit\":\"KG\"}]}")
				.formatted(kitchen, LocalDate.now().plusDays(2), lines);
	}

	private static String requestLine(UUID ingredientId, String quantity, String unit) {
		return "{\"ingredientId\":\"%s\",\"quantity\":%s,\"unit\":\"%s\"}".formatted(ingredientId, quantity, unit);
	}

	private static String created(ResultActions result) throws Exception {
		String response = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return response.replaceAll(".*\"id\"\\s*:\\s*\"([0-9a-f-]+)\".*", "$1");
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private int count(String table) {
		Integer c = admin.queryForObject("SELECT count(*) FROM " + table, Integer.class);
		return c == null ? 0 : c;
	}

	private BigDecimal riceOnHandBase() {
		return admin.queryForObject(
				"SELECT COALESCE(SUM(to_base_qty(quantity, unit)), 0) FROM stock_movements WHERE ingredient_id = ?",
				BigDecimal.class, rice);
	}

	private UUID insertTenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug, name);
	}

	private UUID insertUser(UUID tenantId, String uid, String role) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Bhakta Shyam', ?, '+919876500081', ?, 'ACTIVE') RETURNING id
				""", UUID.class, tenantId, uid, uid + "@example.com", role);
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
