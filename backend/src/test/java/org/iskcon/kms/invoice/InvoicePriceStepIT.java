package org.iskcon.kms.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.iskcon.kms.invoice.InvoiceLinesIT.body;
import static org.iskcon.kms.invoice.InvoiceLinesIT.directLine;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The price step (R-INV-6, R-VEN-3, R-VEN-4, R-ING-3; T-271): each saved invoice line sets the vendor's
 * list price, writes its history per stock unit with source INVOICE, and refreshes the ingredient's
 * market rate — dated by the bill, never to ₹0, and creating the vendor's supply link (not preferred)
 * when the bill is the first news that they sell it.
 *
 * <p>Requests run as the unprivileged application role, as everywhere in {@link AbstractIntegrationTest}.
 */
@AutoConfigureMockMvc
class InvoicePriceStepIT extends AbstractIntegrationTest {

	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private ObjectMapper json;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID staff;
	private UUID rice;
	private UUID ghee;
	private UUID bag;
	private UUID vendor;
	private UUID otherVendor;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('price-step-a', 'Price step temple', 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class);
		staff = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-price-step', 'Govinda Das', 'price-step@example.com', '+919876500501',
						'TEMPLE_ADMIN', 'ACTIVE') RETURNING id
				""", UUID.class, tenant);
		rice = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit) VALUES (?, 'Rice', 'Grains', 'KG')
				RETURNING id
				""", UUID.class, tenant);
		ghee = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit) VALUES (?, 'Ghee', 'Dairy', 'L')
				RETURNING id
				""", UUID.class, tenant);
		bag = admin.queryForObject("""
				INSERT INTO ingredient_pack_sizes (tenant_id, ingredient_id, name, quantity, unit)
				VALUES (?, ?, 'Bag', 25, 'KG') RETURNING id
				""", UUID.class, tenant, rice);
		vendor = admin.queryForObject("INSERT INTO vendors (tenant_id, name) VALUES (?, 'Kalasipalya Traders') RETURNING id",
				UUID.class, tenant);
		otherVendor = admin.queryForObject("INSERT INTO vendors (tenant_id, name) VALUES (?, 'Anand Stores') RETURNING id",
				UUID.class, tenant);
		stubVerifier.accept("uid-price-step");
	}

	@AfterEach
	void tearDown() {
		admin.execute("SELECT delete_tenant_cascade('" + tenant + "')");
	}

	@Test
	@DisplayName("R-VEN-3: bills at ₹60 then ₹64 a Kg leave a list price of ₹64, with ₹60 and its date before it")
	void sixtyThenSixtyFour() throws Exception {
		LocalDate first = today().minusDays(3);
		LocalDate second = today().minusDays(1);
		record(first, directLine(rice, null, "10", "KG", "600"));
		record(second, directLine(rice, null, "10", "KG", "640"));

		mvc.perform(authed(get("/api/v1/vendors/{id}", vendor)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.supplies.length()").value(1))
				.andExpect(jsonPath("$.supplies[0].ingredientName").value("Rice"))
				.andExpect(jsonPath("$.supplies[0].lastPrice").value(64.0))
				.andExpect(jsonPath("$.supplies[0].previousPrice").value(60.0))
				.andExpect(jsonPath("$.supplies[0].previousPriceOn").value(first.toString()))
				// The bill created the link, and it is nobody's preference until a person says so.
				.andExpect(jsonPath("$.supplies[0].preferred").value(false))
				.andExpect(jsonPath("$.supplies[0].pricePerPack").value(nullValue()));

		// The history: one INVOICE row per bill, per Kg, each naming its own line, dated by the bill.
		List<Map<String, Object>> history = admin.queryForList("""
				SELECT h.price_per_unit, h.effective_on, h.source, h.set_by, l.line_amount
				FROM vendor_price_history h
				JOIN vendor_invoice_lines l ON l.id = h.vendor_invoice_line_id
				WHERE h.vendor_id = ? AND h.ingredient_id = ?
				ORDER BY h.effective_on
				""", vendor, rice);
		assertThat(history).hasSize(2);
		assertThat((BigDecimal) history.get(0).get("price_per_unit")).isEqualByComparingTo("60");
		assertThat((BigDecimal) history.get(0).get("line_amount")).isEqualByComparingTo("600");
		assertThat(history.get(0).get("effective_on").toString()).isEqualTo(first.toString());
		assertThat((BigDecimal) history.get(1).get("price_per_unit")).isEqualByComparingTo("64");
		assertThat(history).allSatisfy(h -> {
			assertThat(h.get("source")).isEqualTo("INVOICE");
			assertThat(h.get("set_by")).isEqualTo(staff);
		});

		// The market rate follows the latest bill, and its history names each line.
		Map<String, Object> market = admin.queryForMap(
				"SELECT market_rate, market_rate_on, market_rate_source FROM ingredients WHERE id = ?", rice);
		assertThat((BigDecimal) market.get("market_rate")).isEqualByComparingTo("64");
		assertThat(market.get("market_rate_on").toString()).isEqualTo(second.toString());
		assertThat(market.get("market_rate_source")).isEqualTo("INVOICE");
		assertThat(count("SELECT count(*) FROM ingredient_market_rate_history WHERE ingredient_id = ?"
				+ " AND source = 'INVOICE' AND vendor_invoice_line_id IS NOT NULL", rice)).isEqualTo(2);
	}

	@Test
	@DisplayName("4 × Bag (25 Kg) for ₹6,000 is ₹1,500 a bag and ₹60 a Kg, and the history keeps the bag beside it")
	void bagsAreFifteenHundredABagAndSixtyAKg() throws Exception {
		// The vendor sells rice by the bag, at an older price, and is the preferred source.
		admin.update("""
				INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price, preferred, pack_size_id,
					price_per_pack)
				VALUES (?, ?, ?, 56, true, ?, 1400)
				""", tenant, vendor, rice, bag);

		record(today(), directLine(rice, null, "100", "KG", bag, "4", "6000"));

		Map<String, Object> supply = admin.queryForMap(
				"SELECT last_price, price_per_pack, pack_size_id, preferred FROM vendor_supplies WHERE vendor_id = ? AND ingredient_id = ?",
				vendor, rice);
		assertThat((BigDecimal) supply.get("last_price")).isEqualByComparingTo("60");
		assertThat((BigDecimal) supply.get("price_per_pack")).isEqualByComparingTo("1500");
		assertThat(supply.get("pack_size_id")).as("the pack it sells in is the vendor page's setting").isEqualTo(bag);
		assertThat(supply.get("preferred")).as("a bill does not touch the preference").isEqualTo(true);

		Map<String, Object> row = admin.queryForMap("""
				SELECT price_per_unit, price_per_pack, pack_description, source FROM vendor_price_history
				WHERE vendor_id = ? AND ingredient_id = ?
				""", vendor, rice);
		assertThat((BigDecimal) row.get("price_per_unit")).as("per stock unit, always").isEqualByComparingTo("60");
		assertThat((BigDecimal) row.get("price_per_pack")).isEqualByComparingTo("1500");
		assertThat(row.get("pack_description")).isEqualTo("Bag = 25 Kg");
		assertThat(row.get("source")).isEqualTo("INVOICE");

		mvc.perform(authed(get("/api/v1/vendors/{id}", vendor)))
				.andExpect(jsonPath("$.supplies[0].lastPrice").value(60.0))
				.andExpect(jsonPath("$.supplies[0].pricePerPack").value(1500.0))
				.andExpect(jsonPath("$.supplies[0].packLabel").value("Bag = 25 Kg"));

		// A later bill in plain Kg for a vendor who sells by the bag still restates the bag's price:
		// 50 Kg for ₹3,100 is ₹62 a Kg, so ₹1,550 a bag.
		record(today(), directLine(rice, null, "50", "KG", "3100"));
		supply = admin.queryForMap(
				"SELECT last_price, price_per_pack FROM vendor_supplies WHERE vendor_id = ? AND ingredient_id = ?",
				vendor, rice);
		assertThat((BigDecimal) supply.get("last_price")).isEqualByComparingTo("62");
		assertThat((BigDecimal) supply.get("price_per_pack")).isEqualByComparingTo("1550");
	}

	@Test
	@DisplayName("a missing supply link is created, not preferred, and another vendor's preference is left alone")
	void aMissingLinkIsCreatedNotPreferred() throws Exception {
		admin.update("""
				INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price, preferred)
				VALUES (?, ?, ?, 58, true)
				""", tenant, otherVendor, rice);

		record(today(), directLine(rice, null, "10", "KG", "600"));

		assertThat(admin.queryForObject(
				"SELECT preferred FROM vendor_supplies WHERE vendor_id = ? AND ingredient_id = ?", Boolean.class,
				vendor, rice)).isFalse();
		assertThat(admin.queryForObject(
				"SELECT preferred FROM vendor_supplies WHERE vendor_id = ? AND ingredient_id = ?", Boolean.class,
				otherVendor, rice)).isTrue();
		assertThat(admin.queryForObject(
				"SELECT lead_time_days FROM vendor_supplies WHERE vendor_id = ? AND ingredient_id = ?", Integer.class,
				vendor, rice)).as("nobody has said").isNull();
	}

	@Test
	@DisplayName("a zero rate sets nothing: no list price, no history, no market rate, no link (§13: never ₹0)")
	void aZeroRateSetsNothing() throws Exception {
		record(today(),
				directLine(rice, null, "0", "KG", "0"),       // an item not billed
				directLine(ghee, null, "5", "L", "0"),        // given free
				directLine(null, "Transport", "1", "PIECES", "100")); // a one-off, never a price

		assertThat(count("SELECT count(*) FROM vendor_supplies")).isZero();
		assertThat(count("SELECT count(*) FROM vendor_price_history")).isZero();
		assertThat(count("SELECT count(*) FROM ingredient_market_rate_history")).isZero();
		assertThat(count("SELECT count(*) FROM ingredients WHERE market_rate IS NOT NULL")).isZero();
		assertThat(count("SELECT count(*) FROM vendor_invoice_lines")).as("the lines themselves are saved").isEqualTo(3);
	}

	@Test
	@DisplayName("a bill keyed late is history in its date's place, and leaves the current price as the later bill set it")
	void anOlderBillEnteredLateDoesNotMoveTheCurrentPrice() throws Exception {
		LocalDate tenth = today().minusDays(1);
		LocalDate first = today().minusDays(9);
		record(tenth, directLine(rice, null, "10", "KG", "640"));
		record(first, directLine(rice, null, "10", "KG", "600")); // the older bill, keyed afterwards

		assertThat(admin.queryForObject(
				"SELECT last_price FROM vendor_supplies WHERE vendor_id = ? AND ingredient_id = ?", BigDecimal.class,
				vendor, rice)).isEqualByComparingTo("64");
		assertThat(count("SELECT count(*) FROM vendor_price_history WHERE vendor_id = ? AND source = 'INVOICE'",
				vendor)).isEqualTo(2);
		// The arrow compares the latest two by date: ₹64 now, ₹60 before it on the 1st.
		mvc.perform(authed(get("/api/v1/vendors/{id}", vendor)))
				.andExpect(jsonPath("$.supplies[0].lastPrice").value(64.0))
				.andExpect(jsonPath("$.supplies[0].previousPrice").value(60.0))
				.andExpect(jsonPath("$.supplies[0].previousPriceOn").value(first.toString()));

		Map<String, Object> market = admin.queryForMap(
				"SELECT market_rate, market_rate_on FROM ingredients WHERE id = ?", rice);
		assertThat((BigDecimal) market.get("market_rate")).isEqualByComparingTo("64");
		assertThat(market.get("market_rate_on").toString()).isEqualTo(tenth.toString());
		assertThat(count("SELECT count(*) FROM ingredient_market_rate_history WHERE ingredient_id = ?", rice))
				.as("both bills are in the market rate's history").isEqualTo(2);
	}

	@Test
	@DisplayName("a line billed in grams of an ingredient kept in Kg is priced per Kg: 500 gm for ₹35 is ₹70 a Kg")
	void theRateIsPerStockUnit() throws Exception {
		record(today(), directLine(rice, null, "500", "GM", "35"));

		assertThat(admin.queryForObject(
				"SELECT last_price FROM vendor_supplies WHERE vendor_id = ? AND ingredient_id = ?", BigDecimal.class,
				vendor, rice)).isEqualByComparingTo("70");
		assertThat(admin.queryForObject("SELECT market_rate FROM ingredients WHERE id = ?", BigDecimal.class, rice))
				.isEqualByComparingTo("70");
		// The line's own rate stays per its own unit, as R-INV-4 shows it: ₹0.07 / gm.
		assertThat(admin.queryForObject("SELECT rate FROM vendor_invoice_lines", BigDecimal.class))
				.isEqualByComparingTo("0.07");
	}

	// ---------------------------------------------------------------------

	@SafeVarargs
	private void record(LocalDate invoiceDate, Map<String, Object>... lines) throws Exception {
		BigDecimal total = BigDecimal.ZERO;
		for (Map<String, Object> line : lines) {
			total = total.add((BigDecimal) line.get("amount"));
		}
		UUID billFile = admin.queryForObject("""
				INSERT INTO attachments (tenant_id, kind, storage_key, content_type, size_bytes, uploaded_by)
				VALUES (?, 'INVOICE_BILL', ?, 'image/png', 2048, ?) RETURNING id
				""", UUID.class, tenant, "tenants/" + tenant + "/attachments/" + UUID.randomUUID(), staff);
		Map<String, Object> body = body(vendor, List.of(), List.of(lines), "0", "0", "0", total.toPlainString(),
				billFile, null);
		body.put("invoiceDate", invoiceDate.toString());
		mvc.perform(authed(post("/api/v1/vendor-invoices"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(body)))
				.andExpect(status().isCreated());
	}

	private long count(String sql, Object... args) {
		Long n = admin.queryForObject(sql, Long.class, args);
		return n == null ? 0 : n;
	}

	private static LocalDate today() {
		return LocalDate.now(TEMPLE_ZONE);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}
}
