package org.iskcon.kms.purchaseorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A purchase-order line ordered in the vendor's pack (R-SL-3, T-260), through the full stack as
 * Kitchen Staff, against the real schema under row-level security.
 *
 * <p>"100 Kg short, sold as Bag = 25 Kg → 4 bags, and the PO line, the PDF and the WhatsApp text read
 * '4 × Bag (25 Kg)'. The line also keeps its stock-unit quantity (100 Kg) for stock and costing." Each
 * half of that sentence is a test below: the stored amount, the words on the sheet, and the refusals
 * that keep a line from naming a pack that is not its ingredient's. The WhatsApp half is in
 * {@link PurchaseOrderWhatsAppIT}, because the message carries no amounts — the sheet does.
 *
 * <p>The sheet's price wording follows the conductor's ruling of 2026-09-19 (the document is silent
 * on it): a pack line shows the price per pack and per unit, "₹1,500 / bag · ₹60 / Kg"; a
 * plain-size pack reads "2 × 500 gm"; and a rate is said per the readable unit, Kg or L, so a
 * 2792 gm line printed as "3 Kg" is priced "/ Kg" and so is a lone 500 gm pack (T-288, which
 * replaced T-260's "per the unit the quantity is shown in"). Rupees are written the screen's way (T-268):
 * Indian grouping, paise only where there are any.
 */
@AutoConfigureMockMvc
class PurchaseOrderPackLineIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID otherTenant;
	private UUID rice;
	private UUID tea;
	private UUID ghee;
	private UUID curryLeaves;
	private UUID vendor;
	private UUID bag;
	private UUID teaPack;
	private UUID gheeTin;
	private UUID otherTemplesBag;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		tenant = tenant("radha-govinda");
		otherTenant = tenant("radha-krishna");
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-staff-a', 'Staff A', 'staff-a@example.com', '+919876500081', 'KITCHEN_STAFF', 'ACTIVE')
				""", tenant);
		rice = ingredient(tenant, "Rice", "KG");
		tea = ingredient(tenant, "Tea", "KG");
		ghee = ingredient(tenant, "Ghee", "L");
		curryLeaves = ingredient(tenant, "Curry leaves", "GM");
		vendor = admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Kalasipalya Traders', '+919812345678')
				RETURNING id
				""", UUID.class, tenant);
		bag = pack(tenant, rice, "Bag", "25", "KG");
		teaPack = pack(tenant, tea, null, "500", "GM");
		gheeTin = pack(tenant, ghee, "Tin", "15", "L");
		otherTemplesBag = pack(otherTenant, ingredient(otherTenant, "Rice", "KG"), "Bag", "25", "KG");
		stubVerifier.accept("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM documents");
		admin.execute("DELETE FROM po_label_translations");
		admin.execute("DELETE FROM po_events");
		admin.execute("DELETE FROM purchase_order_lines");
		admin.execute("DELETE FROM purchase_orders");
		admin.execute("DELETE FROM po_sequence");
		admin.execute("DELETE FROM vendor_supplies");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM ingredient_pack_sizes");
		admin.execute("DELETE FROM ingredient_aliases");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- The amount ---------------------------------------------------------

	@Test
	@DisplayName("4 × Bag (25 Kg) is stored as 100 Kg with its pack, and reads back as the pack")
	void aPackLineStoresTheStockUnitAmountAndReadsBackAsThePack() throws Exception {
		String id = create(line(rice, "100", "KG", "60", bag, "4"));

		Map<String, Object> row = admin.queryForMap(
				"SELECT quantity, unit, pack_size_id, pack_count, expected_price FROM purchase_order_lines");
		assertThat((BigDecimal) row.get("quantity")).isEqualByComparingTo("100");
		assertThat(row.get("unit")).isEqualTo("KG");
		assertThat(row.get("pack_size_id")).isEqualTo(bag);
		assertThat((BigDecimal) row.get("pack_count")).isEqualByComparingTo("4");
		assertThat((BigDecimal) row.get("expected_price")).isEqualByComparingTo("60");

		JsonNode l = detail(id).get("lines").get(0);
		assertThat(l.get("packSizeId").asText()).isEqualTo(bag.toString());
		assertThat(l.get("packLabel").asText()).isEqualTo("Bag (25 Kg)");
		assertThat(l.get("packCount").decimalValue()).isEqualByComparingTo("4");
		assertThat(l.get("packCount").asText()).as("a count, not 4.000").isEqualTo("4");
		assertThat(l.get("packQuantity").decimalValue()).as("one bag in the line's unit, Kg").isEqualByComparingTo("25");
		assertThat(l.get("quantity").decimalValue()).isEqualByComparingTo("100");
		assertThat(l.get("unit").asText()).isEqualTo("KG");
	}

	@Test
	@DisplayName("a line without a pack reads back with all four pack fields null")
	void aPlainLineHasNoPack() throws Exception {
		String id = create(line(rice, "30", "KG", "60", null, null));
		JsonNode l = detail(id).get("lines").get(0);
		for (String field : new String[] {"packSizeId", "packLabel", "packQuantity", "packCount"}) {
			assertThat(l.has(field)).as("%s is on the wire", field).isTrue();
			assertThat(l.get(field).isNull()).as("%s is null", field).isTrue();
		}
	}

	@Test
	@DisplayName("the pack decides the amount: a quantity sent beside it that disagrees is not used")
	void thePackDecidesTheAmount() throws Exception {
		// A stale box on the screen said 90. The vendor is asked for four bags, so 100 Kg is what is
		// written: stock and costing must agree with the order the vendor received.
		create(line(rice, "90", "KG", "60", bag, "4"));
		assertThat(admin.queryForObject("SELECT quantity FROM purchase_order_lines", BigDecimal.class))
				.isEqualByComparingTo("100");
	}

	@Test
	@DisplayName("a price sent per gm on a pack line kept in Kg is restated per Kg, not stored beside the wrong unit")
	void thePriceMovesWithTheUnit() throws Exception {
		create(line(rice, "100000", "GM", "0.06", bag, "4"));
		Map<String, Object> row = admin.queryForMap("SELECT quantity, unit, expected_price FROM purchase_order_lines");
		assertThat(row.get("unit")).isEqualTo("KG");
		assertThat((BigDecimal) row.get("quantity")).isEqualByComparingTo("100");
		assertThat((BigDecimal) row.get("expected_price")).isEqualByComparingTo("60");
	}

	@Test
	@DisplayName("a pack line with no price takes the vendor's list price, converted into the pack's unit")
	void aBlankPriceIsFilledInThePacksUnit() throws Exception {
		// Tea is kept in Kg at ₹400 / Kg; its pack is a plain 500 gm, so the line is in gm.
		admin.update("""
				INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price, preferred)
				VALUES (?, ?, ?, 400, true)
				""", tenant, vendor, tea);
		String id = create(line(tea, "1", "KG", null, teaPack, "2"));

		Map<String, Object> row = admin.queryForMap("SELECT quantity, unit, expected_price FROM purchase_order_lines");
		assertThat(row.get("unit")).isEqualTo("GM");
		assertThat((BigDecimal) row.get("quantity")).isEqualByComparingTo("1000");
		assertThat((BigDecimal) row.get("expected_price")).as("₹400 / Kg is ₹0.40 / gm").isEqualByComparingTo("0.4");

		JsonNode l = detail(id).get("lines").get(0);
		assertThat(l.get("packLabel").asText()).as("a plain size has no name to put first").isEqualTo("500 gm");
		assertThat(l.get("packQuantity").decimalValue()).isEqualByComparingTo("500");
	}

	@Test
	@DisplayName("editing a draft takes packs the same way")
	void anEditTakesPacks() throws Exception {
		String id = create(line(rice, "30", "KG", "60", null, null));
		mvc.perform(authed(put("/api/v1/purchase-orders/{id}", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"lines\":[" + line(rice, "1", "KG", "60", bag, "2") + "]}"))
				.andExpect(status().isNoContent());
		JsonNode l = detail(id).get("lines").get(0);
		assertThat(l.get("quantity").decimalValue()).isEqualByComparingTo("50");
		assertThat(l.get("packLabel").asText()).isEqualTo("Bag (25 Kg)");
		assertThat(l.get("packCount").decimalValue()).isEqualByComparingTo("2");
	}

	// ---- 4 places -------------------------------------------------------------

	@Test
	@DisplayName("a price of ₹0.0712 per gm round-trips with 4 places, not rounded to ₹0.07")
	void aPerGramPriceKeepsFourPlaces() throws Exception {
		String id = create(line(curryLeaves, "2792", "GM", "0.0712", null, null));
		assertThat(admin.queryForObject("SELECT expected_price::text FROM purchase_order_lines", String.class))
				.isEqualTo("0.0712");
		assertThat(detail(id).get("lines").get(0).get("expectedPrice").decimalValue())
				.isEqualByComparingTo("0.0712");
	}

	// ---- Refusals -------------------------------------------------------------

	@Test
	@DisplayName("another ingredient's pack is refused with KMS-400162, and nothing is written")
	void anotherIngredientsPackIsRefused() throws Exception {
		refused(line(ghee, "30", "L", null, bag, "1"), "KMS-400162");
	}

	@Test
	@DisplayName("another temple's pack is not this ingredient's either: KMS-400162")
	void anotherTemplesPackIsRefused() throws Exception {
		refused(line(rice, "25", "KG", null, otherTemplesBag, "1"), "KMS-400162");
	}

	@Test
	@DisplayName("a pack on a one-off line is refused with KMS-400162")
	void aPackOnAOneOffLineIsRefused() throws Exception {
		refused("{\"ingredientId\":null,\"description\":\"Plastic stool\",\"quantity\":4,\"unit\":\"PIECES\","
				+ "\"packSizeId\":\"" + gheeTin + "\",\"packCount\":1}", "KMS-400162");
	}

	@Test
	@DisplayName("a pack with no count, or a count with no pack, is refused by line, and nothing is written")
	void aHalfPairIsRefused() throws Exception {
		mvc.perform(authed(post("/api/v1/purchase-orders")).contentType(MediaType.APPLICATION_JSON)
						.content(order(line(rice, "25", "KG", null, bag, null))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors[0].field").value("Line 1"));
		mvc.perform(authed(post("/api/v1/purchase-orders")).contentType(MediaType.APPLICATION_JSON)
						.content(order(line(rice, "25", "KG", null, null, "2"))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors[0].field").value("Line 1"));
		assertThat(admin.queryForObject("SELECT count(*) FROM purchase_orders", Integer.class)).isZero();
	}

	// ---- The sheet ------------------------------------------------------------

	@Test
	@DisplayName("the sheet reads 4 × Bag (25 Kg), 2 × 500 gm and 3 Kg, never 2792 gm, with rates per the unit shown")
	void theSheetWordsPacksAndReadableAmounts() throws Exception {
		String id = create(line(rice, "100", "KG", "60", bag, "4") + ","
				+ line(tea, "1000", "GM", "0.4", teaPack, "2") + ","
				+ line(curryLeaves, "2792", "GM", "0.0712", null, null));

		String html = mvc.perform(authed(get("/api/v1/purchase-orders/{id}/print", id)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		// R-SL-3: the vendor is asked in the pack.
		assertThat(html).contains(">4 × Bag (25 Kg)<");
		assertThat(html).contains(">2 × 500 gm<");
		// The pack line's price, per pack and per unit (conductor's ruling 2026-09-19).
		// Rupees as the screen writes them (T-268): "₹1,500", never "₹1500.00".
		assertThat(html).contains(">₹1,500 / bag · ₹60 / Kg<");
		assertThat(html).doesNotContain("₹1500").doesNotContain(".00 /").doesNotContain(".00<");
		// A plain-size pack is priced per that size; 2 × 500 gm is 1 Kg, so the unit rate is per Kg.
		assertThat(html).contains(">₹200 / 500 gm · ₹400 / Kg<");
		// R-SL-1: 2792 gm is said in Kg (the cook's form), and its rate follows it.
		assertThat(html).contains(">3 Kg<");
		assertThat(html).contains(">₹71.20 / Kg<");
		assertThat(html).doesNotContain("2792").doesNotContain("2,792").doesNotContain("/ gm<")
				.doesNotContain("₹0.07");
		// The total is the stored amounts at their stored prices: 6000 + 400 + 198.7904.
		assertThat(html).contains(">₹6,598.79<");
	}

	@Test
	@DisplayName("a rate is said per Kg or L, never per gm or ml: a lone 500 gm pack reads ₹250 / 500 gm · ₹500 / Kg")
	void aRateIsSaidPerTheReadableUnitEvenBelowAThousand() throws Exception {
		// T-288, VERIFY-B D-8. Each line here prints its quantity in gm or ml — below 1,000, so
		// correctly — and before the fix each rate followed it: "₹0.50 / gm", "₹0.06 / gm",
		// "₹0.30 / ml". The conductor's rule is that a rate follows the readable unit, which for a
		// weight is Kg and for a volume L, as the vendor page and the create form already say it.
		// The last line is what the create form now sends for "3" beside Kg (T-288): 3 KG at ₹60.
		String id = create(line(tea, "500", "GM", "0.5", teaPack, "1") + ","
				+ line(curryLeaves, "450", "GM", "0.06", null, null) + ","
				+ line(ghee, "500", "ML", "0.3", null, null) + ","
				+ line(curryLeaves, "3", "KG", "60", null, null));

		String html = mvc.perform(authed(get("/api/v1/purchase-orders/{id}/print", id)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		assertThat(html).contains(">1 × 500 gm<");
		assertThat(html).contains(">₹250 / 500 gm · ₹500 / Kg<");
		assertThat(html).contains(">450 gm<");
		assertThat(html).contains(">500 ml<");
		assertThat(html).contains(">₹300 / L<");
		assertThat(html).contains(">3 Kg<");
		assertThat(html).contains(">₹60 / Kg<");
		assertThat(html).doesNotContain("/ gm<").doesNotContain("/ ml<")
				.doesNotContain("₹0.50").doesNotContain("₹0.06").doesNotContain("₹0.30");
		// The total is untouched by how a rate is worded: 250 + 27 + 150 + 180.
		assertThat(html).contains(">₹607<");
	}

	// ---------------------------------------------------------------------

	private void refused(String lineJson, String code) throws Exception {
		mvc.perform(authed(post("/api/v1/purchase-orders")).contentType(MediaType.APPLICATION_JSON)
						.content(order(lineJson)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(code));
		assertThat(admin.queryForObject("SELECT count(*) FROM purchase_orders", Integer.class))
				.as("the order is refused whole").isZero();
		assertThat(admin.queryForObject("SELECT count(*) FROM purchase_order_lines", Integer.class)).isZero();
	}

	private String create(String linesJson) throws Exception {
		String response = mvc.perform(authed(post("/api/v1/purchase-orders"))
						.contentType(MediaType.APPLICATION_JSON).content(order(linesJson)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(response).get("id").asText();
	}

	private String order(String linesJson) {
		return "{\"vendorId\":\"" + vendor + "\",\"lines\":[" + linesJson + "]}";
	}

	private static String line(UUID ingredient, String quantity, String unit, String price, UUID pack, String count) {
		return "{\"ingredientId\":\"" + ingredient + "\",\"quantity\":" + quantity + ",\"unit\":\"" + unit + "\""
				+ ",\"expectedPrice\":" + (price == null ? "null" : price)
				+ ",\"packSizeId\":" + (pack == null ? "null" : "\"" + pack + "\"")
				+ ",\"packCount\":" + (count == null ? "null" : count) + "}";
	}

	private JsonNode detail(String id) throws Exception {
		return JSON.readTree(mvc.perform(authed(get("/api/v1/purchase-orders/{id}", id)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private UUID tenant(String slug) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug, slug);
	}

	private UUID ingredient(UUID tenantId, String name, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit) VALUES (?, ?, 'Grains', ?)
				RETURNING id
				""", UUID.class, tenantId, name, unit);
	}

	private UUID pack(UUID tenantId, UUID ingredientId, String name, String quantity, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredient_pack_sizes (tenant_id, ingredient_id, name, quantity, unit)
				VALUES (?, ?, ?, ?::numeric, ?) RETURNING id
				""", UUID.class, tenantId, ingredientId, name, quantity, unit);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}
}
