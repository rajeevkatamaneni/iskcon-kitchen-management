package org.iskcon.kms.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.tenancy.TenantAwareDataSource;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The list price's history (R-VEN-3, R-VEN-4; T-252): every change leaves one row, no change leaves
 * none, a price sold by the bag is worked out per Kg, and the supply view carries the previous price
 * and its date for the arrow.
 *
 * <p>The last test is the V145 backfill, run against its own throwaway database migrated to V144
 * with a temple already holding prices, the way {@code ProcurementDataModelMigrationIT} checks V144.
 */
@AutoConfigureMockMvc
class VendorPriceHistoryIT extends AbstractIntegrationTest {

	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID otherTenant;
	private UUID staff;
	private UUID rice;
	private UUID ghee;
	private UUID vendor;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		tenant = temple("price-history-a");
		otherTenant = temple("price-history-b");
		staff = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-price-staff', 'Karuna Das', 'price-staff@example.com', '+919876500091',
						'KITCHEN_STAFF', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant);
		rice = ingredient(tenant, "Rice", "KG");
		ghee = ingredient(tenant, "Ghee", "L");
		vendor = admin.queryForObject(
				"INSERT INTO vendors (tenant_id, name) VALUES (?, 'Kalasipalya Traders') RETURNING id",
				UUID.class, tenant);
		stubVerifier.accept("uid-price-staff");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		for (UUID id : List.of(tenant, otherTenant)) {
			admin.execute("SELECT delete_tenant_cascade('" + id + "')");
		}
	}

	@Test
	@DisplayName("a price changed from 60 to 64 leaves two history rows, and the view shows 64 with 60 and its date before it")
	void aChangedPriceLeavesTwoRowsAndThePreviousPrice() throws Exception {
		mvc.perform(setSupply(vendor, "{\"ingredientId\":\"" + rice + "\",\"lastPrice\":60,\"preferred\":false}"))
				.andExpect(status().isNoContent());
		mvc.perform(setSupply(vendor, "{\"ingredientId\":\"" + rice + "\",\"lastPrice\":64,\"preferred\":false}"))
				.andExpect(status().isNoContent());

		List<Map<String, Object>> rows = history(rice);
		assertThat(rows).hasSize(2);
		assertThat((BigDecimal) rows.get(0).get("price_per_unit")).isEqualByComparingTo("60");
		assertThat((BigDecimal) rows.get(1).get("price_per_unit")).isEqualByComparingTo("64");
		for (Map<String, Object> row : rows) {
			assertThat(row.get("source")).isEqualTo("MANUAL");
			assertThat(row.get("set_by")).isEqualTo(staff);
			assertThat(row.get("backfilled")).isEqualTo(false);
			assertThat(row.get("price_per_pack")).isNull();
		}
		String today = LocalDate.now(TEMPLE_ZONE).toString();
		assertThat(rows.get(0).get("effective_on").toString()).isEqualTo(today);

		mvc.perform(authed(get("/api/v1/vendors/{id}", vendor)))
				.andExpect(jsonPath("$.supplies[0].lastPrice").value(64))
				.andExpect(jsonPath("$.supplies[0].unit").value("KG"))
				.andExpect(jsonPath("$.supplies[0].previousPrice").value(60))
				.andExpect(jsonPath("$.supplies[0].previousPriceOn").value(today));
	}

	@Test
	@DisplayName("no change, no row: a re-save at the same price, or a cleared price, writes nothing")
	void noChangeNoRow() throws Exception {
		mvc.perform(setSupply(vendor, "{\"ingredientId\":\"" + rice + "\",\"lastPrice\":60,\"preferred\":false}"))
				.andExpect(status().isNoContent());

		// The first price has nothing before it: no arrow.
		mvc.perform(authed(get("/api/v1/vendors/{id}", vendor)))
				.andExpect(jsonPath("$.supplies[0].previousPrice").doesNotExist())
				.andExpect(jsonPath("$.supplies[0].previousPriceOn").doesNotExist());

		// The same price again, with the lead time changed — not a price change.
		mvc.perform(setSupply(vendor, "{\"ingredientId\":\"" + rice
						+ "\",\"lastPrice\":60.00,\"leadTimeDays\":2,\"preferred\":false}"))
				.andExpect(status().isNoContent());
		assertThat(history(rice)).hasSize(1);

		// Cleared: nothing to record, and the history is untouched.
		mvc.perform(setSupply(vendor, "{\"ingredientId\":\"" + rice + "\",\"lastPrice\":null,\"preferred\":false}"))
				.andExpect(status().isNoContent());
		assertThat(history(rice)).hasSize(1);

		// Typed back in at 60: compared with the last RECORDED price, so still no change.
		mvc.perform(setSupply(vendor, "{\"ingredientId\":\"" + rice + "\",\"lastPrice\":60,\"preferred\":false}"))
				.andExpect(status().isNoContent());
		assertThat(history(rice)).hasSize(1);
	}

	@Test
	@DisplayName("a bag of 25 Kg at ₹1,500 is a list price of ₹60 / Kg, and the history keeps the bag beside it")
	void aPackPriceIsWorkedOutPerUnit() throws Exception {
		UUID bag = packSize(rice, "Bag", "25", "KG");

		mvc.perform(setSupply(vendor, "{\"ingredientId\":\"" + rice + "\",\"packSizeId\":\"" + bag
						+ "\",\"pricePerPack\":1500,\"preferred\":false}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/vendors/{id}", vendor)))
				.andExpect(jsonPath("$.supplies[0].lastPrice").value(60))
				.andExpect(jsonPath("$.supplies[0].pricePerPack").value(1500))
				.andExpect(jsonPath("$.supplies[0].packSizeId").value(bag.toString()))
				.andExpect(jsonPath("$.supplies[0].packLabel").value("Bag = 25 Kg"))
				.andExpect(jsonPath("$.supplies[0].unit").value("KG"));

		List<Map<String, Object>> rows = history(rice);
		assertThat(rows).hasSize(1);
		assertThat((BigDecimal) rows.get(0).get("price_per_unit")).isEqualByComparingTo("60");
		assertThat((BigDecimal) rows.get(0).get("price_per_pack")).isEqualByComparingTo("1500");
		assertThat(rows.get(0).get("pack_description")).isEqualTo("Bag = 25 Kg");

		// A per-unit price sent alongside a pack price is ignored, never stored as a second figure.
		mvc.perform(setSupply(vendor, "{\"ingredientId\":\"" + rice + "\",\"packSizeId\":\"" + bag
						+ "\",\"pricePerPack\":1600,\"lastPrice\":5,\"preferred\":false}"))
				.andExpect(status().isNoContent());
		mvc.perform(authed(get("/api/v1/vendors/{id}", vendor)))
				.andExpect(jsonPath("$.supplies[0].lastPrice").value(64))
				.andExpect(jsonPath("$.supplies[0].previousPrice").value(60));

		// A plain size with no name reads as the size alone, as entered: "500 ml", not "0.5 L".
		UUID halfLitre = packSize(ghee, null, "500", "ML");
		mvc.perform(setSupply(vendor, "{\"ingredientId\":\"" + ghee + "\",\"packSizeId\":\"" + halfLitre
						+ "\",\"pricePerPack\":325,\"preferred\":false}"))
				.andExpect(status().isNoContent());
		mvc.perform(authed(get("/api/v1/vendors/{id}", vendor)))
				.andExpect(jsonPath("$.supplies[0].ingredientName").value("Ghee"))
				.andExpect(jsonPath("$.supplies[0].packLabel").value("500 ml"))
				.andExpect(jsonPath("$.supplies[0].lastPrice").value(650));
	}

	/**
	 * The reason V145 widened last_price: an ingredient counted in grams is priced in fractions of a
	 * paisa. At two places ₹0.0650 and ₹0.0680 would both have been stored as ₹0.07 and the arrow
	 * would have shown no change at all; at four they are what the history holds.
	 */
	@Test
	@DisplayName("a gram-counted item priced ₹0.0650 / gm then ₹0.0680 / gm keeps both figures, so the arrow can show the rise")
	void aGramPriceKeepsFourPlaces() throws Exception {
		UUID saffron = ingredient(tenant, "Cardamom", "GM");
		UUID kilo = packSize(saffron, "Pack", "1", "KG");

		mvc.perform(setSupply(vendor, "{\"ingredientId\":\"" + saffron + "\",\"packSizeId\":\"" + kilo
						+ "\",\"pricePerPack\":65,\"preferred\":false}"))
				.andExpect(status().isNoContent());
		mvc.perform(setSupply(vendor, "{\"ingredientId\":\"" + saffron + "\",\"packSizeId\":\"" + kilo
						+ "\",\"pricePerPack\":68,\"preferred\":false}"))
				.andExpect(status().isNoContent());

		assertThat(admin.queryForObject("SELECT last_price FROM vendor_supplies WHERE ingredient_id = ?",
				BigDecimal.class, saffron)).isEqualByComparingTo("0.0680");
		List<Map<String, Object>> rows = history(saffron);
		assertThat(rows).hasSize(2);
		assertThat((BigDecimal) rows.get(0).get("price_per_unit")).isEqualByComparingTo("0.0650");
		assertThat((BigDecimal) rows.get(1).get("price_per_unit")).isEqualByComparingTo("0.0680");

		mvc.perform(authed(get("/api/v1/vendors/{id}", vendor)))
				.andExpect(jsonPath("$.supplies[0].ingredientName").value("Cardamom"))
				.andExpect(jsonPath("$.supplies[0].unit").value("GM"))
				.andExpect(jsonPath("$.supplies[0].packLabel").value("Pack = 1 Kg"))
				.andExpect(jsonPath("$.supplies[0].lastPrice").value(0.068))
				.andExpect(jsonPath("$.supplies[0].previousPrice").value(0.065));
	}

	@Test
	@DisplayName("a pack of another ingredient, or a per-unit price beside a pack, is refused and nothing is written")
	void packRefusals() throws Exception {
		UUID tin = packSize(ghee, "Tin", "15", "L");

		mvc.perform(setSupply(vendor, "{\"ingredientId\":\"" + rice + "\",\"packSizeId\":\"" + tin
						+ "\",\"pricePerPack\":9000,\"preferred\":false}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400162"));

		UUID bag = packSize(rice, "Bag", "25", "KG");
		mvc.perform(setSupply(vendor, "{\"ingredientId\":\"" + rice + "\",\"packSizeId\":\"" + bag
						+ "\",\"lastPrice\":60,\"preferred\":false}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400163"));

		// A pack price with no pack to be per: a field error in words, not a constraint violation.
		mvc.perform(setSupply(vendor, "{\"ingredientId\":\"" + rice + "\",\"pricePerPack\":1500,\"preferred\":false}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));

		assertThat(admin.queryForObject("SELECT count(*) FROM vendor_supplies WHERE tenant_id = ?",
				Integer.class, tenant)).isZero();
		assertThat(history(rice)).isEmpty();

		// A pack chosen with no price at all is fine: the list price is optional.
		mvc.perform(setSupply(vendor, "{\"ingredientId\":\"" + rice + "\",\"packSizeId\":\"" + bag
						+ "\",\"preferred\":false}"))
				.andExpect(status().isNoContent());
		mvc.perform(authed(get("/api/v1/vendors/{id}", vendor)))
				.andExpect(jsonPath("$.supplies[0].packLabel").value("Bag = 25 Kg"))
				.andExpect(jsonPath("$.supplies[0].lastPrice").doesNotExist());
		assertThat(history(rice)).isEmpty();
	}

	@Test
	@DisplayName("another temple's prices and packs are invisible: its history is not read, and its pack is refused")
	void anotherTemplesRowsAreInvisible() throws Exception {
		UUID theirRice = ingredient(otherTenant, "Rice", "KG");
		UUID theirVendor = admin.queryForObject(
				"INSERT INTO vendors (tenant_id, name) VALUES (?, 'Their Vendor') RETURNING id",
				UUID.class, otherTenant);
		UUID theirUser = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-price-other', 'Other', 'price-other@example.com', '+919876500092',
						'TEMPLE_ADMIN', 'ACTIVE') RETURNING id
				""", UUID.class, otherTenant);
		UUID theirBag = packSize(theirRice, "Bag", "25", "KG");
		admin.update("""
				INSERT INTO vendor_price_history (tenant_id, vendor_id, ingredient_id, price_per_unit,
					effective_on, source, set_by)
				VALUES (?, ?, ?, 99, current_date, 'MANUAL', ?)
				""", otherTenant, theirVendor, theirRice, theirUser);

		mvc.perform(setSupply(vendor, "{\"ingredientId\":\"" + rice + "\",\"lastPrice\":60,\"preferred\":false}"))
				.andExpect(status().isNoContent());

		// As the application role, adopted as this temple: its own row, never the other's.
		asApplication(tenant, app -> {
			assertThat(app.queryForObject("SELECT count(*) FROM vendor_price_history", Integer.class)).isEqualTo(1);
			assertThat(app.queryForObject("SELECT count(*) FROM vendor_price_history WHERE tenant_id = ?",
					Integer.class, otherTenant)).isZero();
		});

		// Their pack, named by id from this temple, finds no row and is refused as not this ingredient's.
		mvc.perform(setSupply(vendor, "{\"ingredientId\":\"" + rice + "\",\"packSizeId\":\"" + theirBag
						+ "\",\"pricePerPack\":1500,\"preferred\":false}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400162"));

		// And the ingredient-side list shows this temple's vendor only.
		mvc.perform(authed(get("/api/v1/vendors/supplies")).param("ingredientId", theirRice.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	/**
	 * V145: every price held before history existed becomes its first row, attributed to nobody
	 * because nobody recorded who typed it, and marked backfilled. Run per temple under row-level
	 * security, against a database migrated to V144 with two temples' prices already in it.
	 */
	@Test
	@DisplayName("V145 copies each existing list price into the history, per temple, as a backfilled MANUAL row with no author")
	void v145BackfillsExistingPrices() throws SQLException {
		String database = "kms_price_history_backfill_check";
		try (Connection c = adminConnection(); Statement s = c.createStatement()) {
			s.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
			s.execute("CREATE DATABASE " + database);
		}
		try (Connection c = superuser(database); Statement s = c.createStatement()) {
			s.execute("ALTER SCHEMA public OWNER TO " + MIGRATION_ROLE);
		}
		migrate(database, "144");

		try (Connection c = superuser(database); Statement s = c.createStatement()) {
			for (String slug : List.of("backfill-a", "backfill-b")) {
				s.execute("INSERT INTO tenants (slug, name, latitude, longitude, timezone) VALUES ('" + slug
						+ "', 'Temple " + slug + "', 12.97, 77.59, 'Asia/Kolkata')");
				s.execute("INSERT INTO ingredients (tenant_id, name, category, canonical_unit) SELECT id, 'Rice',"
						+ " 'Grains', 'KG' FROM tenants WHERE slug = '" + slug + "'");
				s.execute("INSERT INTO ingredients (tenant_id, name, category, canonical_unit) SELECT id, 'Dal',"
						+ " 'Pulses', 'KG' FROM tenants WHERE slug = '" + slug + "'");
				s.execute("INSERT INTO vendors (tenant_id, name) SELECT id, 'Vendor' FROM tenants WHERE slug = '"
						+ slug + "'");
			}
			// Temple A: rice priced, set late in the evening UTC (already the next day in Bengaluru);
			// dal unpriced. Temple B: rice priced.
			s.execute("""
					INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price, updated_at)
					SELECT t.id, v.id, i.id,
						   CASE WHEN t.slug = 'backfill-a' THEN 58 ELSE 61 END,
						   '2026-08-14 20:00:00+00'
					FROM tenants t JOIN vendors v ON v.tenant_id = t.id
					JOIN ingredients i ON i.tenant_id = t.id AND i.name = 'Rice'
					""");
			s.execute("""
					INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price)
					SELECT t.id, v.id, i.id, NULL
					FROM tenants t JOIN vendors v ON v.tenant_id = t.id
					JOIN ingredients i ON i.tenant_id = t.id AND i.name = 'Dal'
					WHERE t.slug = 'backfill-a'
					""");
		}

		migrate(database, "145");

		List<String> rows = strings(database, """
				SELECT t.slug || '|' || i.name || '|' || h.price_per_unit || '|' || h.effective_on || '|'
					   || h.source || '|' || h.backfilled || '|' || coalesce(h.set_by::text, 'nobody')
				FROM vendor_price_history h
				JOIN tenants t ON t.id = h.tenant_id
				JOIN ingredients i ON i.id = h.ingredient_id
				ORDER BY t.slug
				""");
		assertThat(rows).containsExactly(
				"backfill-a|Rice|58.0000|2026-08-15|MANUAL|true|nobody",
				"backfill-b|Rice|61.0000|2026-08-15|MANUAL|true|nobody");

		// The relaxed column is relaxed for backfilled rows only: the application still has to say
		// who set a price.
		String tenantA = strings(database, "SELECT id FROM tenants WHERE slug = 'backfill-a'").get(0);
		DriverManagerDataSource plain = new DriverManagerDataSource();
		plain.setUrl(urlFor(database));
		plain.setUsername(APP_ROLE);
		plain.setPassword(APP_PASSWORD);
		TenantContext.set(UUID.fromString(tenantA));
		try {
			JdbcTemplate app = new JdbcTemplate(new TenantAwareDataSource(plain));
			assertThatThrownBy(() -> app.update("""
					INSERT INTO vendor_price_history (tenant_id, vendor_id, ingredient_id, price_per_unit,
						effective_on, source)
					SELECT tenant_id, vendor_id, ingredient_id, 70, current_date, 'MANUAL'
					FROM vendor_supplies WHERE last_price IS NOT NULL
					"""))
					.hasStackTraceContaining("vendor_price_history_set_by_known");
		} finally {
			TenantContext.clear();
		}
	}

	// ---------------------------------------------------------------------

	private List<Map<String, Object>> history(UUID ingredientId) {
		return admin.queryForList("""
				SELECT price_per_unit, price_per_pack, pack_description, effective_on, source, set_by, backfilled
				FROM vendor_price_history WHERE ingredient_id = ? ORDER BY created_at
				""", ingredientId);
	}

	private UUID temple(String slug) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, 'Temple', 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug);
	}

	private UUID ingredient(UUID tenantId, String name, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', ?) RETURNING id
				""", UUID.class, tenantId, name, unit);
	}

	private UUID packSize(UUID ingredientId, String name, String quantity, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredient_pack_sizes (tenant_id, ingredient_id, name, quantity, unit)
				SELECT tenant_id, id, ?, ?::numeric, ? FROM ingredients WHERE id = ?
				RETURNING id
				""", UUID.class, name, quantity, unit, ingredientId);
	}

	private void asApplication(UUID tenantId, java.util.function.Consumer<JdbcTemplate> work) {
		DriverManagerDataSource plain = new DriverManagerDataSource();
		plain.setUrl(POSTGRES.getJdbcUrl());
		plain.setUsername(APP_ROLE);
		plain.setPassword(APP_PASSWORD);
		TenantContext.set(tenantId);
		try {
			work.accept(new JdbcTemplate(new TenantAwareDataSource(plain)));
		} finally {
			TenantContext.clear();
		}
	}

	private MockHttpServletRequestBuilder setSupply(UUID vendorId, String json) {
		return authed(put("/api/v1/vendors/{id}/supplies", vendorId))
				.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private static void migrate(String database, String version) {
		Flyway.configure()
				.dataSource(urlFor(database), MIGRATION_ROLE, MIGRATION_PASSWORD)
				.locations("classpath:db/migration")
				.target(version)
				.load()
				.migrate();
	}

	private static List<String> strings(String database, String sql) throws SQLException {
		List<String> out = new ArrayList<>();
		try (Connection c = superuser(database); Statement s = c.createStatement(); var rs = s.executeQuery(sql)) {
			while (rs.next()) {
				out.add(rs.getString(1));
			}
		}
		return out;
	}

	private static Connection superuser(String database) throws SQLException {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setUrl(urlFor(database));
		dataSource.setUsername(POSTGRES.getUsername());
		dataSource.setPassword(POSTGRES.getPassword());
		return dataSource.getConnection();
	}

	private static String urlFor(String database) {
		return "jdbc:postgresql://%s:%d/%s".formatted(POSTGRES.getHost(), POSTGRES.getFirstMappedPort(), database);
	}
}
