package org.iskcon.kms.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
 * Onboarding a vendor's list in one go (R-VEN-1), and the same supplies seen from the ingredient
 * (R-ING-2). T-252.
 */
@AutoConfigureMockMvc
class VendorOnboardingIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID rice;
	private UUID dal;
	private UUID jaggery;
	private UUID vendor;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('onboarding-a', 'Temple', 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class);
		user("uid-onboard-admin", "onboard-admin@example.com", "+919876500093", "TEMPLE_ADMIN");
		user("uid-onboard-vol", "onboard-vol@example.com", "+919876500094", "VOLUNTEER");
		rice = ingredient("Rice");
		dal = ingredient("Toor dal");
		jaggery = ingredient("Jaggery");
		vendor = vendor("Kalasipalya Traders");
		stubVerifier.accept("uid-onboard-admin");
	}

	@AfterEach
	void tearDown() {
		admin.execute("SELECT delete_tenant_cascade('" + tenant + "')");
	}

	@Test
	@DisplayName("three ingredients ticked, two priced: three supplies and two ONBOARDING price rows")
	void bulkAddCreatesSuppliesAndOnboardingHistory() throws Exception {
		UUID bag = admin.queryForObject("""
				INSERT INTO ingredient_pack_sizes (tenant_id, ingredient_id, name, quantity, unit)
				VALUES (?, ?, 'Bag', 25, 'KG') RETURNING id
				""", UUID.class, tenant, rice);

		mvc.perform(bulk(vendor, "{\"rows\":["
						+ "{\"ingredientId\":\"" + rice + "\",\"packSizeId\":\"" + bag
						+ "\",\"pricePerPack\":1500,\"lastPrice\":null,\"leadTimeDays\":2,\"preferred\":true},"
						+ "{\"ingredientId\":\"" + dal + "\",\"lastPrice\":112,\"leadTimeDays\":null,\"preferred\":false},"
						+ "{\"ingredientId\":\"" + jaggery + "\",\"lastPrice\":null,\"leadTimeDays\":null,\"preferred\":false}"
						+ "]}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/vendors/{id}", vendor)))
				.andExpect(jsonPath("$.supplies.length()").value(3))
				// Ordered by name: Jaggery, Rice, Toor dal.
				.andExpect(jsonPath("$.supplies[0].ingredientName").value("Jaggery"))
				.andExpect(jsonPath("$.supplies[0].lastPrice").doesNotExist())
				.andExpect(jsonPath("$.supplies[1].ingredientName").value("Rice"))
				.andExpect(jsonPath("$.supplies[1].lastPrice").value(60))
				.andExpect(jsonPath("$.supplies[1].packLabel").value("Bag = 25 Kg"))
				.andExpect(jsonPath("$.supplies[1].leadTimeDays").value(2))
				.andExpect(jsonPath("$.supplies[1].preferred").value(true))
				.andExpect(jsonPath("$.supplies[2].ingredientName").value("Toor dal"))
				.andExpect(jsonPath("$.supplies[2].lastPrice").value(112));

		List<String> sources = admin.queryForList(
				"SELECT source FROM vendor_price_history WHERE vendor_id = ?", String.class, vendor);
		assertThat(sources).containsExactly("ONBOARDING", "ONBOARDING");
	}

	@Test
	@DisplayName("one bad row refuses the whole list: nothing is half-saved")
	void oneBadRowRefusesTheLot() throws Exception {
		UUID othersPack = admin.queryForObject("""
				INSERT INTO ingredient_pack_sizes (tenant_id, ingredient_id, name, quantity, unit)
				VALUES (?, ?, 'Bag', 30, 'KG') RETURNING id
				""", UUID.class, tenant, dal);

		mvc.perform(bulk(vendor, "{\"rows\":["
						+ "{\"ingredientId\":\"" + jaggery + "\",\"lastPrice\":48,\"preferred\":false},"
						+ "{\"ingredientId\":\"" + rice + "\",\"packSizeId\":\"" + othersPack
						+ "\",\"pricePerPack\":1500,\"preferred\":false}"
						+ "]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400162"));

		assertThat(admin.queryForObject("SELECT count(*) FROM vendor_supplies WHERE vendor_id = ?",
				Integer.class, vendor)).isZero();
		assertThat(admin.queryForObject("SELECT count(*) FROM vendor_price_history WHERE vendor_id = ?",
				Integer.class, vendor)).isZero();

		// And an empty list is a sentence, not a silent success.
		mvc.perform(bulk(vendor, "{\"rows\":[]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));
	}

	@Test
	@DisplayName("an ingredient's vendors come back flat with the vendor's id and name, and linking from there is the same write")
	void suppliesSeenFromTheIngredient() throws Exception {
		UUID second = vendor("Anand Stores");

		// Linked from the vendor page…
		mvc.perform(setSupply(vendor, "{\"ingredientId\":\"" + rice + "\",\"lastPrice\":58,\"preferred\":true}"))
				.andExpect(status().isNoContent());
		// …and from the ingredient page, which sends the same PUT for the vendor it picked.
		mvc.perform(setSupply(second, "{\"ingredientId\":\"" + rice + "\",\"lastPrice\":61,\"leadTimeDays\":1,\"preferred\":false}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/vendors/supplies")).param("ingredientId", rice.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				// Vendor name order: Anand Stores, Kalasipalya Traders.
				.andExpect(jsonPath("$[0].vendorId").value(second.toString()))
				.andExpect(jsonPath("$[0].vendorName").value("Anand Stores"))
				.andExpect(jsonPath("$[0].ingredientId").value(rice.toString()))
				.andExpect(jsonPath("$[0].ingredientName").value("Rice"))
				.andExpect(jsonPath("$[0].lastPrice").value(61))
				.andExpect(jsonPath("$[0].unit").value("KG"))
				.andExpect(jsonPath("$[0].leadTimeDays").value(1))
				.andExpect(jsonPath("$[0].preferred").value(false))
				.andExpect(jsonPath("$[0].previousPrice").doesNotExist())
				.andExpect(jsonPath("$[1].vendorName").value("Kalasipalya Traders"))
				.andExpect(jsonPath("$[1].preferred").value(true));

		// Only these two write paths exist, and both land in the one table.
		assertThat(admin.queryForObject("SELECT count(*) FROM vendor_supplies WHERE ingredient_id = ?",
				Integer.class, rice)).isEqualTo(2);
	}

	@Test
	@DisplayName("a volunteer can neither add a vendor's list nor read an ingredient's vendors")
	void volunteerForbidden() throws Exception {
		stubVerifier.accept("uid-onboard-vol");
		mvc.perform(bulk(vendor, "{\"rows\":[{\"ingredientId\":\"" + rice + "\",\"preferred\":false}]}"))
				.andExpect(status().isForbidden());
		mvc.perform(authed(get("/api/v1/vendors/supplies")).param("ingredientId", rice.toString()))
				.andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private void user(String uid, String email, String phone, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, ?, ?, 'ACTIVE')
				""", tenant, uid, email, phone, role);
	}

	private UUID ingredient(String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', 'KG') RETURNING id
				""", UUID.class, tenant, name);
	}

	private UUID vendor(String name) {
		return admin.queryForObject("INSERT INTO vendors (tenant_id, name) VALUES (?, ?) RETURNING id",
				UUID.class, tenant, name);
	}

	private MockHttpServletRequestBuilder bulk(UUID vendorId, String json) {
		return authed(post("/api/v1/vendors/{id}/supplies/bulk", vendorId))
				.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder setSupply(UUID vendorId, String json) {
		return authed(put("/api/v1/vendors/{id}/supplies", vendorId))
				.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}
}
