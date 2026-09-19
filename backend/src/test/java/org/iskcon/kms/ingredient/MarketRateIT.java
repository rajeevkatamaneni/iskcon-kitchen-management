package org.iskcon.kms.ingredient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * An ingredient's market rate (R-ING-3, T-254): typing it on the ingredient's page, and the pre-fill
 * the stock-take box starts from.
 *
 * <p>The stock-take path that also sets it is tested where it lives, in {@code StockAdjustmentIT};
 * costing's use of it in {@code MaterialsCostIT} and {@code IssuedFromStoreIT}. What is pinned here is
 * the writer itself: all three columns and one history row, together, for every change; a rate that
 * is blank, zero or negative refused with the same code the stock-take box gives; another temple's
 * ingredient not found; and the two-source pre-fill, including a ₹0 list price not being offered.
 *
 * <p>RLS and append-only on {@code ingredient_market_rate_history} itself are the schema's, and are
 * covered with the migration in {@code ProcurementDataModelMigrationIT} (T-248). Every write here goes
 * through the application role, so the history row passing RLS's check on insert is exercised by
 * every test that sets a rate.
 */
@AutoConfigureMockMvc
class MarketRateIT extends AbstractIntegrationTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID templeB;
	private UUID rice;
	private UUID riceB;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("jagannath", "Sri Jagannath Temple");
		insertUser(templeA, "uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		insertUser(templeA, "uid-vol-a", "vol-a@example.com", "VOLUNTEER");
		insertUser(templeB, "uid-staff-b", "staff-b@example.com", "KITCHEN_STAFF");
		rice = insertIngredient(templeA, "Rice");
		riceB = insertIngredient(templeB, "Rice");
		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM ingredient_market_rate_history");
		admin.execute("DELETE FROM vendor_supplies");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- Setting it by hand ---------------------------------------------------------------------

	@Test
	@DisplayName("a market rate typed on the ingredient page sets all three columns and one history row")
	void manualRateSetsColumnsAndHistory() throws Exception {
		setRate(rice, "{\"marketRate\":60}").andExpect(status().isNoContent());

		Map<String, Object> row = admin.queryForMap(
				"SELECT market_rate, market_rate_on, market_rate_source FROM ingredients WHERE id = ?", rice);
		assertThat((BigDecimal) row.get("market_rate")).isEqualByComparingTo("60");
		// The temple's day, not the server's.
		assertThat(row.get("market_rate_on").toString()).isEqualTo(LocalDate.now(IST).toString());
		assertThat(row.get("market_rate_source")).isEqualTo("MANUAL");

		List<Map<String, Object>> history = history(rice);
		assertThat(history).hasSize(1);
		assertThat((BigDecimal) history.get(0).get("rate")).isEqualByComparingTo("60");
		assertThat(history.get(0))
				.containsEntry("source", "MANUAL")
				.containsEntry("tenant_id", templeA)
				.containsEntry("firebase_uid", "uid-staff-a")
				.containsEntry("vendor_invoice_line_id", null);
	}

	/**
	 * The history is how it got there: a second rate replaces the first on the ingredient and is added
	 * beside it in the history, which never loses the first. Four places kept, for a rate per gram.
	 */
	@Test
	@DisplayName("a second rate replaces the first on the ingredient and is added to the history beside it")
	void secondRateIsAppended() throws Exception {
		setRate(rice, "{\"marketRate\":60}").andExpect(status().isNoContent());
		setRate(rice, "{\"marketRate\":64.12345}").andExpect(status().isNoContent());

		assertThat(admin.queryForObject("SELECT market_rate FROM ingredients WHERE id = ?",
				BigDecimal.class, rice)).isEqualByComparingTo("64.1235");
		assertThat(history(rice)).extracting(h -> ((BigDecimal) h.get("rate")).stripTrailingZeros().toPlainString())
				.containsExactly("60", "64.1235");
	}

	@Test
	@DisplayName("a blank, zero or negative market rate is refused with KMS-400161, and nothing is stored")
	void blankZeroOrNegativeIsRefused() throws Exception {
		for (String body : new String[] {"{}", "{\"marketRate\":null}", "{\"marketRate\":0}",
				"{\"marketRate\":-5}", "{\"marketRate\":0.00001}"}) {
			setRate(rice, body)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("KMS-400161"));
		}
		assertThat(admin.queryForObject("SELECT market_rate FROM ingredients WHERE id = ?",
				BigDecimal.class, rice)).isNull();
		assertThat(history(rice)).isEmpty();
	}

	@Test
	@DisplayName("another temple's ingredient is not found, and is left untouched")
	void anotherTemplesIngredientIsNotFound() throws Exception {
		setRate(riceB, "{\"marketRate\":60}")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-400030"));
		suggestion(riceB).andExpect(status().isNotFound());

		assertThat(admin.queryForObject("SELECT market_rate FROM ingredients WHERE id = ?",
				BigDecimal.class, riceB)).isNull();
		assertThat(history(riceB)).isEmpty();
	}

	@Test
	@DisplayName("without MANAGE_INVENTORY, neither the rate nor the suggestion can be reached")
	void volunteerIsForbidden() throws Exception {
		signIn("uid-vol-a");
		setRate(rice, "{\"marketRate\":60}").andExpect(status().isForbidden());
		suggestion(rice).andExpect(status().isForbidden());
		assertThat(history(rice)).isEmpty();
	}

	// ---- The stock-take pre-fill -----------------------------------------------------------------

	@Test
	@DisplayName("with no vendor price and no market rate, the suggestion is empty rather than ₹0")
	void noSuggestion() throws Exception {
		suggestion(rice).andExpect(status().isOk())
				.andExpect(jsonPath("$.pricePerUnit").doesNotExist())
				.andExpect(jsonPath("$.source").doesNotExist());
	}

	@Test
	@DisplayName("with only a market rate, the suggestion is the market rate")
	void suggestsTheMarketRate() throws Exception {
		setRate(rice, "{\"marketRate\":58.5}").andExpect(status().isNoContent());

		suggestion(rice)
				.andExpect(jsonPath("$.pricePerUnit").value(58.5))
				.andExpect(jsonPath("$.source").value("MARKET_RATE"));
	}

	/** The preferred vendor's list price comes first, ahead of a market rate. */
	@Test
	@DisplayName("the preferred vendor's list price is suggested ahead of the market rate")
	void suggestsThePreferredVendorFirst() throws Exception {
		setRate(rice, "{\"marketRate\":58.5}").andExpect(status().isNoContent());
		supply(vendor("Govind Wholesale"), rice, "45.00", true);
		supply(vendor("Sri Traders"), rice, "90.00", false);

		suggestion(rice)
				.andExpect(jsonPath("$.pricePerUnit").value(45.0))
				.andExpect(jsonPath("$.source").value("PREFERRED_VENDOR"));
	}

	/**
	 * Two sources only (R-ING-3: "pre-filled from the preferred vendor's list price, else the market
	 * rate", confirmed by the clarifier). A vendor nobody prefers is not a suggestion.
	 */
	@Test
	@DisplayName("a vendor nobody prefers is not suggested")
	void anUnpreferredVendorIsNotSuggested() throws Exception {
		supply(vendor("Sri Traders"), rice, "90.00", false);

		suggestion(rice)
				.andExpect(jsonPath("$.pricePerUnit").doesNotExist())
				.andExpect(jsonPath("$.source").doesNotExist());
	}

	/** A ₹0 list price is not offered: the box refuses 0, so it would be a value nobody can save. */
	@Test
	@DisplayName("a preferred vendor at ₹0 is skipped for the market rate")
	void aZeroListPriceIsNotSuggested() throws Exception {
		supply(vendor("Govind Wholesale"), rice, "0.00", true);
		setRate(rice, "{\"marketRate\":58.5}").andExpect(status().isNoContent());

		suggestion(rice)
				.andExpect(jsonPath("$.pricePerUnit").value(58.5))
				.andExpect(jsonPath("$.source").value("MARKET_RATE"));
	}

	// ---------------------------------------------------------------------

	private ResultActions setRate(UUID ingredientId, String body) throws Exception {
		return mvc.perform(put("/api/v1/ingredients/{id}/market-rate", ingredientId)
				.header("Authorization", "Bearer valid-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private ResultActions suggestion(UUID ingredientId) throws Exception {
		return mvc.perform(get("/api/v1/ingredients/{id}/stock-value-suggestion", ingredientId)
				.header("Authorization", "Bearer valid-token"));
	}

	private List<Map<String, Object>> history(UUID ingredientId) {
		return admin.queryForList("""
				SELECT h.rate, h.source, h.tenant_id, h.vendor_invoice_line_id, u.firebase_uid
				FROM ingredient_market_rate_history h JOIN users u ON u.id = h.set_by
				WHERE h.ingredient_id = ?
				ORDER BY h.created_at, h.rate
				""", ingredientId);
	}

	private UUID vendor(String name) {
		return admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, ?, '+919812345678') RETURNING id
				""", UUID.class, templeA, name);
	}

	private void supply(UUID vendorId, UUID ingredientId, String lastPrice, boolean preferred) {
		admin.update("""
				INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price, preferred)
				VALUES (?, ?, ?, ?::numeric, ?)
				""", templeA, vendorId, ingredientId, lastPrice, preferred);
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

	private UUID insertIngredient(UUID tenantId, String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', 'KG')
				RETURNING id
				""", UUID.class, tenantId, name);
	}
}
