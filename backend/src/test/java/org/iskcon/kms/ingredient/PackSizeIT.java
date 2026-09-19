package org.iskcon.kms.ingredient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * An ingredient's pack sizes (R-ING-1, T-253) through the full stack, as Kitchen Staff — the least
 * senior role that edits ingredients — against the real V144 schema under row-level security.
 *
 * <p>Each refusal asserts its permanent code <em>and</em> that nothing was written, because a
 * refusal that still wrote the row is worse than no refusal. The duplicate and limit cases are the
 * ones the negative control in the proof removes the translation from: without it the unique index
 * still refuses, but as an internal error instead of KMS-400157.
 */
@AutoConfigureMockMvc
class PackSizeIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private ObjectMapper json;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID templeB;
	private UUID rice;
	private UUID tea;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");
		insertUser(templeA, "uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		insertUser(templeA, "uid-volunteer-a", "vol-a@example.com", "VOLUNTEER");
		insertUser(templeB, "uid-staff-b", "staff-b@example.com", "KITCHEN_STAFF");
		rice = ingredient(templeA, "Rice", "KG");
		tea = ingredient(templeA, "Tea", "GM");
		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		// Order lines point at packs since V146 (T-260), and at the vendor through their order.
		admin.execute("DELETE FROM purchase_order_lines");
		admin.execute("DELETE FROM purchase_orders");
		admin.execute("DELETE FROM vendor_supplies");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM ingredient_pack_sizes");
		admin.execute("DELETE FROM ingredient_aliases");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- Sizes persist, and read back smallest first --------------------

	@Test
	@DisplayName("sizes persist, come back smallest first with readable chip labels, and are audited")
	void sizesPersistAndReadBack() throws Exception {
		String bag = addedId(add(rice, "Bag", "25", "KG"));
		addedId(add(rice, null, "1000", "GM"));
		addedId(add(rice, "  ", "250", "GM"));
		addedId(add(rice, "Pack", "0.5", "KG"));

		mvc.perform(authed(get("/api/v1/ingredients/{id}", rice)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.packSizes.length()").value(4))
				.andExpect(jsonPath("$.packSizes[0].label").value("250 gm"))
				.andExpect(jsonPath("$.packSizes[0].name").isEmpty())
				.andExpect(jsonPath("$.packSizes[0].baseQuantity").value(0.25))
				// As typed: 0.5 and KG. Said readably: 500 gm (PROCUREMENT-REQUIREMENTS §1).
				.andExpect(jsonPath("$.packSizes[1].label").value("Pack = 500 gm"))
				.andExpect(jsonPath("$.packSizes[1].quantity").value(0.5))
				.andExpect(jsonPath("$.packSizes[1].unit").value("KG"))
				.andExpect(jsonPath("$.packSizes[2].label").value("1 Kg"))
				.andExpect(jsonPath("$.packSizes[2].baseQuantity").value(1))
				.andExpect(jsonPath("$.packSizes[3].id").value(bag))
				.andExpect(jsonPath("$.packSizes[3].label").value("Bag = 25 Kg"))
				.andExpect(jsonPath("$.packSizes[3].quantity").value(25))
				.andExpect(jsonPath("$.packSizes[3].baseQuantity").value(25));

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM ingredient_pack_sizes WHERE ingredient_id = ? AND tenant_id = ?",
				Integer.class, rice, templeA)).isEqualTo(4);
		assertThat(admin.queryForObject("SELECT name FROM ingredient_pack_sizes WHERE quantity = 250",
				String.class)).as("a blank name is stored as no name").isNull();

		assertThat(admin.queryForObject("""
				SELECT count(*) FROM audit_events
				WHERE action = 'INGREDIENT_UPDATED' AND entity_type = 'INGREDIENT' AND entity_id = ?
				""", Integer.class, rice)).isEqualTo(4);
		assertThat(admin.queryForObject("""
				SELECT reason FROM audit_events WHERE entity_id = ? ORDER BY created_at DESC LIMIT 1
				""", String.class, rice)).isEqualTo("Added pack size Pack = 500 gm.");
	}

	@Test
	@DisplayName("a pack in pieces reads with the singular where it should")
	void piecesReadCorrectly() throws Exception {
		UUID banana = ingredient(templeA, "Banana", "PIECES");
		addedId(add(banana, "Bunch", "12", "PIECES"));
		addedId(add(banana, null, "1", "PIECES"));

		mvc.perform(authed(get("/api/v1/ingredients/{id}", banana)))
				.andExpect(jsonPath("$.packSizes[0].label").value("1 piece"))
				.andExpect(jsonPath("$.packSizes[1].label").value("Bunch = 12 pieces"));
	}

	// ---- Validation ------------------------------------------------------

	@Test
	@DisplayName("zero, a negative and a missing quantity are field errors, and nothing is written")
	void quantityMustBePositive() throws Exception {
		add(rice, null, "0", "KG").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("Enter an amount greater than zero."));
		add(rice, null, "-5", "KG").andExpect(status().isBadRequest());
		mvc.perform(authed(post("/api/v1/ingredients/{id}/pack-sizes", rice))
						.contentType(MediaType.APPLICATION_JSON).content("{\"unit\":\"KG\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors[0].message").value("Enter how much one pack holds."));
		add(rice, null, "5", "BUCKETS").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));

		assertThat(packCount(rice)).isZero();
	}

	@Test
	@DisplayName("a pack from another family is KMS-400013 naming the ingredient; the same family is fine")
	void sameFamilyOnly() throws Exception {
		add(rice, "Tin", "15", "L").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400013"))
				.andExpect(jsonPath("$.fieldErrors[0].message")
						.value("Rice is measured in Kg, and there is no way to turn L into Kg."));
		add(rice, "Bundle", "10", "PIECES").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400013"));
		assertThat(packCount(rice)).isZero();

		// Tea is kept in grams; a kilo pack of it is the ordinary case.
		addedId(add(tea, null, "1", "KG"));
		assertThat(packCount(tea)).isEqualTo(1);
	}

	@Test
	@DisplayName("500 gm and \"Pack = 0.5 Kg\" are the same size, so the second is KMS-400157")
	void duplicateSizeIsRefused() throws Exception {
		addedId(add(rice, null, "500", "GM"));

		add(rice, "Pack", "0.5", "KG").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400157"));
		add(rice, null, "500", "GM").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400157"));

		assertThat(packCount(rice)).isEqualTo(1);
		assertThat(admin.queryForObject("SELECT count(*) FROM audit_events", Integer.class))
				.as("only the one add that worked is on the trail").isEqualTo(1);
	}

	@Test
	@DisplayName("the same size on a different ingredient is not a duplicate")
	void duplicateIsPerIngredient() throws Exception {
		addedId(add(rice, null, "1", "KG"));
		addedId(add(tea, null, "1", "KG"));
		assertThat(packCount(rice)).isEqualTo(1);
		assertThat(packCount(tea)).isEqualTo(1);
	}

	@Test
	@DisplayName("eight sizes are allowed and a ninth is KMS-400158")
	void atMostEight() throws Exception {
		for (int grams = 100; grams <= 800; grams += 100) {
			addedId(add(tea, null, String.valueOf(grams), "GM"));
		}
		assertThat(packCount(tea)).isEqualTo(8);

		add(tea, "Bag", "5", "KG").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400158"));
		assertThat(packCount(tea)).isEqualTo(8);

		// Removing one makes room again.
		String first = admin.queryForObject(
				"SELECT id::text FROM ingredient_pack_sizes WHERE ingredient_id = ? AND quantity = 100",
				String.class, tea);
		mvc.perform(authed(delete("/api/v1/ingredients/{id}/pack-sizes/{p}", tea, first)))
				.andExpect(status().isNoContent());
		addedId(add(tea, "Bag", "5", "KG"));
		assertThat(packCount(tea)).isEqualTo(8);
	}

	// ---- Removal ---------------------------------------------------------

	@Test
	@DisplayName("a pack is removed and audited; while a vendor sells in it, removal is KMS-400159")
	void removalRefusedWhileInUse() throws Exception {
		String bag = addedId(add(rice, "Bag", "25", "KG"));
		String loose = addedId(add(rice, null, "1", "KG"));

		UUID vendor = admin.queryForObject(
				"INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Kalasipalya Traders', '+919812345678') RETURNING id",
				UUID.class, templeA);
		admin.update("""
				INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price, preferred,
						pack_size_id, price_per_pack)
				VALUES (?, ?, ?, 60, true, ?::uuid, 1500)
				""", templeA, vendor, rice, bag);

		mvc.perform(authed(delete("/api/v1/ingredients/{id}/pack-sizes/{p}", rice, bag)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400159"));
		assertThat(packCount(rice)).isEqualTo(2);

		mvc.perform(authed(delete("/api/v1/ingredients/{id}/pack-sizes/{p}", rice, loose)))
				.andExpect(status().isNoContent());
		assertThat(packCount(rice)).isEqualTo(1);
		assertThat(admin.queryForObject("""
				SELECT reason FROM audit_events WHERE entity_id = ? AND reason LIKE 'Removed%'
				""", String.class, rice)).isEqualTo("Removed pack size 1 Kg.");

		// Once the vendor stops selling in bags, the bag can go.
		admin.update("UPDATE vendor_supplies SET pack_size_id = NULL, price_per_pack = NULL");
		mvc.perform(authed(delete("/api/v1/ingredients/{id}/pack-sizes/{p}", rice, bag)))
				.andExpect(status().isNoContent());
		assertThat(packCount(rice)).isZero();
	}

	@Test
	@DisplayName("a pack an order line was placed in cannot be removed: KMS-400159, not a foreign-key 500 (T-260)")
	void removalRefusedWhileAnOrderUsesIt() throws Exception {
		String bag = addedId(add(rice, "Bag", "25", "KG"));

		UUID vendor = admin.queryForObject(
				"INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Kalasipalya Traders', '+919812345678') RETURNING id",
				UUID.class, templeA);
		// Only an order uses the bag: no vendor sells in it here, so the refusal can only have come from
		// the order line's foreign key (V146).
		UUID po = admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, created_by)
				VALUES (?, 'PO-2026-0001', ?, 'DRAFT', (SELECT id FROM users WHERE firebase_uid = 'uid-staff-a'))
				RETURNING id
				""", UUID.class, templeA, vendor);
		admin.update("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit, pack_size_id, pack_count)
				VALUES (?, ?, ?, 100, 'KG', ?::uuid, 4)
				""", templeA, po, rice, bag);

		mvc.perform(authed(delete("/api/v1/ingredients/{id}/pack-sizes/{p}", rice, bag)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400159"));
		assertThat(packCount(rice)).isEqualTo(1);
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM purchase_order_lines WHERE pack_size_id = ?::uuid", Integer.class, bag))
				.as("the order line still names its pack").isEqualTo(1);
	}

	@Test
	@DisplayName("a pack id that belongs to a different ingredient is not found under this one")
	void removalIsPerIngredient() throws Exception {
		String teaPack = addedId(add(tea, null, "250", "GM"));
		mvc.perform(authed(delete("/api/v1/ingredients/{id}/pack-sizes/{p}", rice, teaPack)))
				.andExpect(status().isNotFound());
		assertThat(packCount(tea)).isEqualTo(1);
	}

	// ---- The canonical unit's family, while packs exist -------------------

	@Test
	@DisplayName("moving the unit to another family is KMS-400160 while packs exist; Kg to gm is allowed")
	void familyChangeRefusedWhilePacksExist() throws Exception {
		String bag = addedId(add(rice, "Bag", "25", "KG"));

		mvc.perform(update(rice, "L")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400160"));
		mvc.perform(update(rice, "PIECES")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400160"));
		assertThat(admin.queryForObject("SELECT canonical_unit FROM ingredients WHERE id = ?", String.class, rice))
				.isEqualTo("KG");

		// Same family: the bag is exactly as true in grams, and its canonical figure follows.
		mvc.perform(update(rice, "GM")).andExpect(status().isNoContent());
		mvc.perform(authed(get("/api/v1/ingredients/{id}", rice)))
				.andExpect(jsonPath("$.unit").value("GM"))
				.andExpect(jsonPath("$.packSizes[0].label").value("Bag = 25 Kg"))
				.andExpect(jsonPath("$.packSizes[0].baseQuantity").value(25000));

		// With the packs gone, the family can change.
		mvc.perform(authed(delete("/api/v1/ingredients/{id}/pack-sizes/{p}", rice, bag)))
				.andExpect(status().isNoContent());
		mvc.perform(update(rice, "L")).andExpect(status().isNoContent());
	}

	// ---- Another temple, and who may ------------------------------------

	@Test
	@DisplayName("another temple can neither see nor add to or remove this temple's pack sizes")
	void anotherTempleCannotReachThem() throws Exception {
		String bag = addedId(add(rice, "Bag", "25", "KG"));
		UUID theirRice = ingredient(templeB, "Rice", "KG");
		admin.update("""
				INSERT INTO ingredient_pack_sizes (tenant_id, ingredient_id, name, quantity, unit)
				VALUES (?, ?, 'Sack', 50, 'KG')
				""", templeB, theirRice);

		signIn("uid-staff-b");
		mvc.perform(authed(get("/api/v1/ingredients/{id}", rice))).andExpect(status().isNotFound());
		add(rice, "Tin", "5", "KG").andExpect(status().isNotFound());
		mvc.perform(authed(delete("/api/v1/ingredients/{id}/pack-sizes/{p}", rice, bag)))
				.andExpect(status().isNotFound());
		mvc.perform(authed(get("/api/v1/ingredients")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].packSizes.length()").value(1))
				.andExpect(jsonPath("$[0].packSizes[0].label").value("Sack = 50 Kg"));

		assertThat(packCount(rice)).as("temple A's rice still has only its bag").isEqualTo(1);

		signIn("uid-staff-a");
		mvc.perform(authed(get("/api/v1/ingredients")))
				.andExpect(jsonPath("$[?(@.name=='Rice')].packSizes[0].label").value("Bag = 25 Kg"))
				.andExpect(jsonPath("$..packSizes[?(@.label=='Sack = 50 Kg')]").isEmpty());
	}

	@Test
	@DisplayName("a volunteer, who may not edit ingredients, may not add or remove a pack")
	void volunteerIsRefused() throws Exception {
		String bag = addedId(add(rice, "Bag", "25", "KG"));
		signIn("uid-volunteer-a");
		add(rice, null, "1", "KG").andExpect(status().isForbidden());
		mvc.perform(authed(delete("/api/v1/ingredients/{id}/pack-sizes/{p}", rice, bag)))
				.andExpect(status().isForbidden());
		assertThat(packCount(rice)).isEqualTo(1);
	}

	// ---------------------------------------------------------------------

	private ResultActions add(UUID ingredientId, String name, String quantity, String unit) throws Exception {
		String body = name == null
				? "{\"quantity\":%s,\"unit\":\"%s\"}".formatted(quantity, unit)
				: "{\"name\":%s,\"quantity\":%s,\"unit\":\"%s\"}".formatted(json.writeValueAsString(name), quantity, unit);
		return mvc.perform(authed(post("/api/v1/ingredients/{id}/pack-sizes", ingredientId))
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private String addedId(ResultActions result) throws Exception {
		String body = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return json.readTree(body).get("id").asText();
	}

	private MockHttpServletRequestBuilder update(UUID id, String unit) {
		return authed(put("/api/v1/ingredients/{id}", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Rice\",\"category\":\"Grains\",\"unit\":\"%s\"}".formatted(unit));
	}

	private int packCount(UUID ingredientId) {
		Integer c = admin.queryForObject(
				"SELECT count(*) FROM ingredient_pack_sizes WHERE ingredient_id = ?", Integer.class, ingredientId);
		return c == null ? 0 : c;
	}

	private UUID ingredient(UUID tenant, String name, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', ?) RETURNING id
				""", UUID.class, tenant, name, unit);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
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
}
