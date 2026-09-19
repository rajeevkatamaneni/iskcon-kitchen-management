package org.iskcon.kms.shoppinglist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.ingredient.Quantities;
import org.iskcon.kms.ingredient.Unit;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The buying amount through {@code GET /api/v1/shopping-list} (R-SL-2, R-SL-3; T-259).
 *
 * <p>{@code BuyingAmountTest} proves the rules; this proves the list uses them, with the pack sizes
 * and the vendor's "Sells it as" pack read from the tables V144 created, and a typed quantity left
 * exactly as typed. Every figure comes from the threshold stream, whose arithmetic is the plainest
 * of the three — reorder level × 1.2, less what is on the shelf — so each need below is exact:
 *
 * <ul>
 *   <li>Curry leaves, kept in gm, no pack sizes: 2,500 × 1.2 − 208 = <strong>2,792 gm</strong>, the
 *       figure Rajeev found on the Kalasipalya tile. It must come back 3,000 gm — "3 Kg".</li>
 *   <li>Rice, kept in Kg, sold by its preferred vendor as Bag = 25 Kg: 100 × 1.2 − 20 =
 *       <strong>100 Kg</strong>. It must come back as 4 × Bag (25 Kg), from the vendor.</li>
 *   <li>Tea, kept in gm, pack sizes 250 gm, 500 gm and 1 Kg, no vendor pack: 500 × 1.2 − 184 =
 *       <strong>416 gm</strong>, the document's own example. It must come back 1 × 500 gm.</li>
 * </ul>
 */
@AutoConfigureMockMvc
class ShoppingListPacksIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID staffId;
	private UUID curryLeaves;
	private UUID rice;
	private UUID tea;
	private UUID bag;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		staffId = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-staff-a', 'Staff A', 'staff-a@example.com', '+919876500081', 'KITCHEN_STAFF', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant);

		curryLeaves = ingredient("Curry leaves", "GM");
		item(curryLeaves, "2500");
		receipt(curryLeaves, "208", "GM");

		rice = ingredient("Rice", "KG");
		item(rice, "100");
		receipt(rice, "20", "KG");
		pack(rice, null, "5", "KG");
		bag = pack(rice, "Bag", "25", "KG");
		UUID vendor = admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone)
				VALUES (?, 'Kalasipalya Vegetable Mandi', '+919812345678') RETURNING id
				""", UUID.class, tenant);
		admin.update("""
				INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, preferred, pack_size_id)
				VALUES (?, ?, ?, true, ?)
				""", tenant, vendor, rice, bag);

		tea = ingredient("Tea", "GM");
		item(tea, "500");
		receipt(tea, "184", "GM");
		pack(tea, null, "250", "GM");
		pack(tea, null, "500", "GM");
		pack(tea, null, "1", "KG");

		stubVerifier.accept("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM shopping_list_lines");
		admin.execute("DELETE FROM vendor_supplies");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredient_pack_sizes");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("2792 gm of curry leaves is suggested as 3000 gm, which reads 3 Kg, with no packs")
	void curryLeavesRoundUpToAStep() throws Exception {
		JsonNode line = line("Curry leaves");
		assertThat(line.get("shortfall").decimalValue().max(line.get("thresholdTopUp").decimalValue()))
				.as("the need really is the 2792 gm Rajeev saw").isEqualByComparingTo("2792");
		assertThat(line.get("unit").asText()).isEqualTo("GM");
		assertThat(line.get("suggestedQty").decimalValue()).isEqualByComparingTo("3000");
		assertThat(Quantities.cooks(line.get("suggestedQty").decimalValue(), Unit.GM)).isEqualTo("3 Kg");
		assertThat(line.get("buyPacks")).isEmpty();
		assertThat(line.get("packFromVendor").asBoolean()).isFalse();
	}

	@Test
	@DisplayName("100 Kg of rice sold as Bag = 25 Kg is 4 × Bag (25 Kg), from the vendor")
	void riceInTheVendorsBags() throws Exception {
		JsonNode line = line("Rice");
		assertThat(line.get("suggestedQty").decimalValue()).isEqualByComparingTo("100");
		assertThat(line.get("packFromVendor").asBoolean()).isTrue();
		assertThat(line.get("buyPacks")).hasSize(1);
		JsonNode p = line.get("buyPacks").get(0);
		assertThat(p.get("packSizeId").asText()).isEqualTo(bag.toString());
		assertThat(p.get("label").asText()).isEqualTo("Bag (25 Kg)");
		assertThat(p.get("perPackQty").decimalValue()).isEqualByComparingTo("25");
		assertThat(p.get("count").asInt()).isEqualTo(4);
	}

	@Test
	@DisplayName("416 gm of tea with packs of 250 gm, 500 gm and 1 Kg is 1 × 500 gm")
	void teaInTheIngredientsPacks() throws Exception {
		JsonNode line = line("Tea");
		assertThat(line.get("suggestedQty").decimalValue()).isEqualByComparingTo("500");
		assertThat(line.get("packFromVendor").asBoolean()).isFalse();
		assertThat(line.get("buyPacks")).hasSize(1);
		JsonNode p = line.get("buyPacks").get(0);
		assertThat(p.get("label").asText()).isEqualTo("500 gm");
		assertThat(p.get("perPackQty").decimalValue()).isEqualByComparingTo("500");
		assertThat(p.get("count").asInt()).isEqualTo(1);
	}

	@Test
	@DisplayName("a hand-edited quantity comes back exactly as typed, and is not re-rounded")
	void typedQuantityIsNeverReRounded() throws Exception {
		edit(curryLeaves, "2792");
		JsonNode curry = line("Curry leaves");
		assertThat(curry.get("suggestedQty").decimalValue()).isEqualByComparingTo("2792");
		assertThat(curry.get("edited").asBoolean()).isTrue();
		assertThat(curry.get("buyPacks")).isEmpty();

		// 90 Kg is not a whole number of 25 Kg bags: kept as typed, and no bags claimed beside it.
		edit(rice, "90");
		JsonNode ninety = line("Rice");
		assertThat(ninety.get("suggestedQty").decimalValue()).isEqualByComparingTo("90");
		assertThat(ninety.get("buyPacks")).isEmpty();
		assertThat(ninety.get("packFromVendor").asBoolean()).isFalse();

		// 75 Kg is: still as typed, and described as the vendor's bags.
		edit(rice, "75");
		JsonNode seventyFive = line("Rice");
		assertThat(seventyFive.get("suggestedQty").decimalValue()).isEqualByComparingTo("75");
		assertThat(seventyFive.get("packFromVendor").asBoolean()).isTrue();
		assertThat(seventyFive.get("buyPacks").get(0).get("count").asInt()).isEqualTo(3);
		assertThat(seventyFive.get("buyPacks").get(0).get("label").asText()).isEqualTo("Bag (25 Kg)");
	}

	// ---------------------------------------------------------------------

	private JsonNode line(String name) throws Exception {
		String body = mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		for (JsonNode l : JSON.readTree(body)) {
			if (name.equals(l.get("ingredientName").asText())) {
				return l;
			}
		}
		throw new AssertionError(name + " is not on the shopping list: " + body);
	}

	private void edit(UUID ingredientId, String qty) throws Exception {
		mvc.perform(authed(patch("/api/v1/shopping-list/" + ingredientId))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"suggestedQty\":" + qty + ",\"included\":true}"))
				.andExpect(status().is2xxSuccessful());
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private UUID ingredient(String name, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', ?) RETURNING id
				""", UUID.class, tenant, name, unit);
	}

	private UUID pack(UUID ingredient, String name, String qty, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredient_pack_sizes (tenant_id, ingredient_id, name, quantity, unit)
				VALUES (?, ?, ?, ?::numeric, ?) RETURNING id
				""", UUID.class, tenant, ingredient, name, qty, unit);
	}

	private void item(UUID ingredient, String threshold) {
		admin.update("""
				INSERT INTO inventory_items (tenant_id, ingredient_id, reorder_threshold)
				VALUES (?, ?, ?::numeric)
				""", tenant, ingredient, threshold);
	}

	private void receipt(UUID ingredient, String qty, String unit) {
		admin.update("""
				INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id)
				VALUES (?, ?, ?, ?::numeric, ?, 'PO_RECEIPT', ?)
				""", tenant, ingredient, UUID.randomUUID(), qty, unit, staffId);
	}
}
