package org.iskcon.kms.donation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The public donation page's own figures (E7-S1): the plates being cooked today, what one costs, and
 * where last month's money went.
 *
 * <p>Every one of them is a raw SQL sum over another epic's tables, which is exactly the code that a
 * unit test cannot vouch for — a column renamed two migrations away compiles perfectly and fails only
 * when a donor opens the page. So this runs against the real schema, and it checks the arithmetic as
 * well as the shape: the figures a donor is quoted must be this temple's own, and no other's.
 */
@AutoConfigureMockMvc
class PublicDonationPageIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID staff;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		tenant = tenant("radha-govinda", "Bengaluru Temple");
		staff = user(tenant, "uid-page-staff", "staff-page@example.com", "+919876500091");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM vendor_invoices");
		admin.execute("DELETE FROM purchase_order_lines");
		admin.execute("DELETE FROM purchase_orders");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM meal_plans");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a temple that has cooked and bought is quoted its own plates, cost per plate and shares")
	void figuresComeFromTheTemplesOwnWork() throws Exception {
		UUID khichdi = recipe(tenant, "Khichdi");

		// Today: two meals on the plan, one cancelled — a donor is told about the 1,240 being cooked.
		plan(tenant, khichdi, LocalDate.now(), 820, "PLANNED");
		plan(tenant, khichdi, LocalDate.now(), 420, "PLANNED");
		plan(tenant, khichdi, LocalDate.now(), 500, "CANCELLED");

		// Last month: 2,000 plates cooked against ₹64,000 of invoices — ₹32 a plate.
		plan(tenant, khichdi, LocalDate.now().minusDays(10), 2000, "COOKED");
		UUID vendor = vendor(tenant, "Govind Wholesale", "+919812345678");
		invoice(tenant, vendor, "INV-1", LocalDate.now().minusDays(10), "64000");

		// And what it bought, by the temple's own categories: ₹6,000 grains, ₹4,000 vegetables.
		UUID po = purchaseOrder(tenant, vendor, "PO-1");
		poLine(tenant, po, ingredient(tenant, "Rice", "Grains and dal"), "100", "60");
		poLine(tenant, po, ingredient(tenant, "Beans", "Vegetables"), "80", "50");

		mvc.perform(get("/api/v1/public/t/radha-govinda/donation-page"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.templeName").value("Bengaluru Temple"))
				.andExpect(jsonPath("$.platesToday").value(1240))
				.andExpect(jsonPath("$.costPerPlateInr").value(32))
				.andExpect(jsonPath("$.spendShares.length()").value(2))
				.andExpect(jsonPath("$.spendShares[0].label").value("Grains and dal"))
				.andExpect(jsonPath("$.spendShares[0].percent").value(60))
				.andExpect(jsonPath("$.spendShares[1].label").value("Vegetables"))
				.andExpect(jsonPath("$.spendShares[1].percent").value(40));
	}

	@Test
	@DisplayName("a temple that has not cooked or bought yet is quoted nothing rather than a made-up number")
	void figuresAreLeftOutRatherThanInvented() throws Exception {
		mvc.perform(get("/api/v1/public/t/radha-govinda/donation-page"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.templeName").value("Bengaluru Temple"))
				.andExpect(jsonPath("$.platesToday").doesNotExist())
				.andExpect(jsonPath("$.costPerPlateInr").doesNotExist())
				.andExpect(jsonPath("$.spendShares.length()").value(0));
	}

	@Test
	@DisplayName("no figure on one temple's page is ever another temple's work")
	void figuresStopAtTheTenantBoundary() throws Exception {
		UUID other = tenant("iskcon-mysore", "Mysore Temple");
		UUID otherStaff = user(other, "uid-other-staff", "staff-other@example.com", "+919876500092");
		UUID otherRecipe = recipe(other, "Payasam");
		plan(other, otherRecipe, LocalDate.now(), 3000, "PLANNED", otherStaff);
		plan(other, otherRecipe, LocalDate.now().minusDays(5), 3000, "COOKED", otherStaff);
		UUID otherVendor = vendor(other, "Mysore Traders", "+919812345679");
		invoice(other, otherVendor, "INV-M1", LocalDate.now().minusDays(5), "99000", otherStaff);

		// Bengaluru cooked today but has neither invoices nor cooked meals of its own.
		plan(tenant, recipe(tenant, "Khichdi"), LocalDate.now(), 100, "PLANNED");

		mvc.perform(get("/api/v1/public/t/radha-govinda/donation-page"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.platesToday").value(100))
				.andExpect(jsonPath("$.costPerPlateInr").doesNotExist())
				.andExpect(jsonPath("$.spendShares.length()").value(0));
	}

	// ---- seeding ----------------------------------------------------------

	private UUID tenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug, name);
	}

	private UUID user(UUID tenantId, String uid, String email, String phone) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Staff', ?, ?, 'KITCHEN_STAFF', 'ACTIVE') RETURNING id
				""", UUID.class, tenantId, uid, email, phone);
	}

	private UUID recipe(UUID tenantId, String name) {
		UUID category = admin.queryForObject(
				"INSERT INTO recipe_categories (tenant_id, name) VALUES (?, ?) RETURNING id",
				UUID.class, tenantId, name + " category");
		return admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, ?, ?, 100, 'SERVINGS') RETURNING id
				""", UUID.class, tenantId, name, category);
	}

	private void plan(UUID tenantId, UUID recipe, LocalDate date, int servings, String status) {
		plan(tenantId, recipe, date, servings, status, staff);
	}

	private void plan(UUID tenantId, UUID recipe, LocalDate date, int servings, String status, UUID by) {
		admin.update("""
				INSERT INTO meal_plans (tenant_id, plan_date, meal_kind, ready_by, recipe_id,
					target_servings, day_type, status, created_by)
				VALUES (?, ?, 'Lunch', TIME '12:00', ?, ?, 'REGULAR', ?, ?)
				""", tenantId, date, recipe, servings, status, by);
	}

	private UUID vendor(UUID tenantId, String name, String phone) {
		return admin.queryForObject(
				"INSERT INTO vendors (tenant_id, name, phone) VALUES (?, ?, ?) RETURNING id",
				UUID.class, tenantId, name, phone);
	}

	private void invoice(UUID tenantId, UUID vendor, String number, LocalDate date, String amount) {
		invoice(tenantId, vendor, number, date, amount, staff);
	}

	private void invoice(UUID tenantId, UUID vendor, String number, LocalDate date, String amount, UUID by) {
		admin.update("""
				INSERT INTO vendor_invoices (tenant_id, vendor_id, direct, description, invoice_number,
					invoice_date, amount, created_by)
				VALUES (?, ?, true, 'Vegetables and grains', ?, ?, ?::numeric, ?)
				""", tenantId, vendor, number, date, amount, by);
	}

	private UUID purchaseOrder(UUID tenantId, UUID vendor, String number) {
		return admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, created_by)
				VALUES (?, ?, ?, ?) RETURNING id
				""", UUID.class, tenantId, number, vendor, staff);
	}

	private UUID ingredient(UUID tenantId, String name, String category) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, ?, 'KG') RETURNING id
				""", UUID.class, tenantId, name, category);
	}

	private void poLine(UUID tenantId, UUID po, UUID ingredient, String quantity, String price) {
		admin.update("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit, expected_price)
				VALUES (?, ?, ?, ?::numeric, 'KG', ?::numeric)
				""", tenantId, po, ingredient, quantity, price);
	}
}
