package org.iskcon.kms.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * One preferred vendor per ingredient (R-VEN-2, T-258).
 *
 * <p>Ticking Preferred on vendor B for an ingredient preferred at vendor A makes B preferred and A
 * not, through either write path (the supply row's Save and the Other ingredients bulk Save). Only
 * the flag moves: A's list price is unchanged and A gets no price-history row, because its price did
 * not change. The rule is the database's as well as the service's: V24's partial unique index
 * {@code vendor_supplies_one_preferred} refuses a second preferred row outright, which is why no
 * temple can already hold two and no de-duplicating migration is needed (see the proof).
 *
 * <p>The negative control for this class removes the un-prefer UPDATE from
 * {@code VendorService.writeSupply}: the index then refuses B's row and both move tests fail.
 */
@AutoConfigureMockMvc
class OnePreferredVendorIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID otherTenant;
	private UUID rice;
	private UUID vendorA;
	private UUID vendorB;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		tenant = tenant("one-preferred-a");
		otherTenant = tenant("one-preferred-b");
		user(tenant, "uid-onepref-admin", "onepref-admin@example.com", "+919876500181", "TEMPLE_ADMIN");
		user(tenant, "uid-onepref-vol", "onepref-vol@example.com", "+919876500182", "VOLUNTEER");
		rice = ingredient(tenant, "Rice");
		vendorA = vendor(tenant, "Anand Stores");
		vendorB = vendor(tenant, "Bhavani Traders");
		stubVerifier.accept("uid-onepref-admin");
	}

	@AfterEach
	void tearDown() {
		admin.execute("SELECT delete_tenant_cascade('" + tenant + "')");
		admin.execute("SELECT delete_tenant_cascade('" + otherTenant + "')");
	}

	@Test
	@DisplayName("ticking Preferred at B for rice preferred at A leaves B preferred and A not, and writes A no price row")
	void tickingPreferredAtBReplacesA() throws Exception {
		mvc.perform(setSupply(vendorA, "{\"ingredientId\":\"" + rice + "\",\"lastPrice\":58,\"preferred\":true}"))
				.andExpect(status().isNoContent());
		mvc.perform(authed(get("/api/v1/vendors/preferred")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].vendorName").value("Anand Stores"));

		mvc.perform(setSupply(vendorB, "{\"ingredientId\":\"" + rice + "\",\"lastPrice\":61,\"preferred\":true}"))
				.andExpect(status().isNoContent());

		assertThat(preferredAt(vendorA)).isFalse();
		assertThat(preferredAt(vendorB)).isTrue();
		// A's price is exactly what it was, and its history is the one row its own save wrote.
		assertThat(admin.queryForObject(
				"SELECT last_price FROM vendor_supplies WHERE vendor_id = ? AND ingredient_id = ?",
				BigDecimal.class, vendorA, rice)).isEqualByComparingTo("58");
		assertThat(historyRows(vendorA)).isEqualTo(1);
		assertThat(historyRows(vendorB)).isEqualTo(1);

		mvc.perform(authed(get("/api/v1/vendors/preferred")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].ingredientId").value(rice.toString()))
				.andExpect(jsonPath("$[0].vendorId").value(vendorB.toString()))
				.andExpect(jsonPath("$[0].vendorName").value("Bhavani Traders"));
	}

	@Test
	@DisplayName("the Other ingredients bulk Save moves the preference the same way")
	void bulkAddReplacesA() throws Exception {
		mvc.perform(setSupply(vendorA, "{\"ingredientId\":\"" + rice + "\",\"lastPrice\":58,\"preferred\":true}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(post("/api/v1/vendors/{id}/supplies/bulk", vendorB))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"rows\":[{\"ingredientId\":\"" + rice
								+ "\",\"lastPrice\":null,\"leadTimeDays\":null,\"preferred\":true}]}"))
				.andExpect(status().isNoContent());

		assertThat(preferredAt(vendorA)).isFalse();
		assertThat(preferredAt(vendorB)).isTrue();
		assertThat(historyRows(vendorA)).isEqualTo(1);
	}

	@Test
	@DisplayName("the database itself refuses a second preferred vendor for one ingredient")
	void theDatabaseRefusesTwo() {
		admin.update("INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, preferred) VALUES (?, ?, ?, true)",
				tenant, vendorA, rice);
		assertThatThrownBy(() -> admin.update(
				"INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, preferred) VALUES (?, ?, ?, true)",
				tenant, vendorB, rice))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("vendor_supplies_one_preferred");
		// Not preferred is not limited: any number of vendors may simply supply it.
		admin.update("INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, preferred) VALUES (?, ?, ?, false)",
				tenant, vendorB, rice);
	}

	@Test
	@DisplayName("another temple's preferred vendor is never un-set and never listed")
	void anotherTemplesPreferenceIsUntouched() throws Exception {
		UUID theirRice = ingredient(otherTenant, "Rice");
		UUID theirVendor = vendor(otherTenant, "Their Vendor");
		admin.update("INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, preferred) VALUES (?, ?, ?, true)",
				otherTenant, theirVendor, theirRice);

		mvc.perform(setSupply(vendorA, "{\"ingredientId\":\"" + rice + "\",\"preferred\":true}"))
				.andExpect(status().isNoContent());
		mvc.perform(setSupply(vendorB, "{\"ingredientId\":\"" + rice + "\",\"preferred\":true}"))
				.andExpect(status().isNoContent());

		assertThat(preferredAt(theirVendor)).isTrue();
		mvc.perform(authed(get("/api/v1/vendors/preferred")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].vendorId").value(vendorB.toString()));

		// The un-prefer statement itself, run as the application role adopted as this temple and
		// aimed straight at their ingredient, finds nothing to clear.
		asApplication(tenant, app -> assertThat(app.update(
				"UPDATE vendor_supplies SET preferred = false, updated_at = now() WHERE ingredient_id = ? AND preferred",
				theirRice)).isZero());
		assertThat(preferredAt(theirVendor)).isTrue();
	}

	@Test
	@DisplayName("a volunteer cannot read the preferred vendors")
	void volunteerForbidden() throws Exception {
		stubVerifier.accept("uid-onepref-vol");
		mvc.perform(authed(get("/api/v1/vendors/preferred"))).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private boolean preferredAt(UUID vendorId) {
		return Boolean.TRUE.equals(admin.queryForObject(
				"SELECT preferred FROM vendor_supplies WHERE vendor_id = ?", Boolean.class, vendorId));
	}

	private int historyRows(UUID vendorId) {
		return admin.queryForObject(
				"SELECT count(*) FROM vendor_price_history WHERE vendor_id = ?", Integer.class, vendorId);
	}

	private UUID tenant(String slug) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, 'Temple', 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug);
	}

	private void user(UUID tenantId, String uid, String email, String phone, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, ?, ?, 'ACTIVE')
				""", tenantId, uid, email, phone, role);
	}

	private UUID ingredient(UUID tenantId, String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', 'KG') RETURNING id
				""", UUID.class, tenantId, name);
	}

	private UUID vendor(UUID tenantId, String name) {
		return admin.queryForObject("INSERT INTO vendors (tenant_id, name) VALUES (?, ?) RETURNING id",
				UUID.class, tenantId, name);
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
}
