package org.iskcon.kms.costing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * What the store issued to each kitchen, costed (E10-S13).
 *
 * <p>The report exists because an issue already says which kitchen the food went to, and that is a
 * cost attribution nobody was reading. What these tests pin is that it reads the ledger and only the
 * ledger: the kitchen comes off the request rather than off the movement, a reversed issue is not an
 * issue, and a movement that is not an issue is not one either.
 *
 * <p>Prices are the same fixture {@code MaterialsCostIT} uses, so the two reports can be read against
 * each other: rice at the preferred vendor's ₹45 a Kg, toor dal at ₹120 (the dearer of two vendors
 * nobody prefers), and rock salt that no vendor supplies at all — the hole the figure has to admit
 * to rather than cost at zero.
 */
@AutoConfigureMockMvc
@Import(IssuedFromStoreIT.StubVerifierConfiguration.class)
class IssuedFromStoreIT extends AbstractIntegrationTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID rice;
	private UUID dal;
	private UUID salt;
	private UUID deityKitchen;
	private UUID prasadamKitchen;
	private final LocalDate day = LocalDate.now(IST);

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		insertUser("uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		insertUser("uid-vol-a", "vol-a@example.com", "VOLUNTEER");

		rice = ingredient("Rice");
		dal = ingredient("Toor Dal");
		salt = ingredient("Rock Salt");

		UUID preferredVendor = vendor("Govind Wholesale");
		UUID otherVendor = vendor("Sri Traders");
		supply(preferredVendor, rice, "45.00", true);
		supply(otherVendor, rice, "90.00", false);
		supply(preferredVendor, dal, "100.00", false);
		supply(otherVendor, dal, "120.00", false);
		// Rock salt is supplied by nobody, on purpose.

		deityKitchen = kitchen("Deity kitchen", false);
		prasadamKitchen = kitchen("Prasadam kitchen", false);

		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM ingredient_request_events");
		admin.execute("DELETE FROM ingredient_request_lines");
		admin.execute("DELETE FROM ingredient_request_dishes");
		admin.execute("DELETE FROM ingredient_requests");
		admin.execute("DELETE FROM kitchens");
		admin.execute("DELETE FROM vendor_supplies");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	/**
	 * The whole report in one fixture. The Deity kitchen took 10 Kg of rice and 2 Kg of dal — ₹450
	 * and ₹240 — and the prasadam kitchen took 4 Kg of rice, ₹180.
	 */
	@Test
	@DisplayName("each kitchen is costed on what the store issued to it, dearest first")
	void costsWhatWasIssuedToEachKitchen() throws Exception {
		UUID toDeity = request(deityKitchen, "IR-2026-0001");
		issued(toDeity, rice, "10000", "GM", noon(day));
		issued(toDeity, dal, "2000", "GM", noon(day));
		UUID toPrasadam = request(prasadamKitchen, "IR-2026-0002");
		issued(toPrasadam, rice, "4000", "GM", noon(day));

		report(day, day).andExpect(status().isOk())
				.andExpect(jsonPath("$.from").value(day.toString()))
				.andExpect(jsonPath("$.requests").value(2))
				.andExpect(jsonPath("$.estimatedTotal").value(870.00))
				.andExpect(jsonPath("$.kitchens.length()").value(2))
				.andExpect(jsonPath("$.kitchens[0].kitchen").value("Deity kitchen"))
				.andExpect(jsonPath("$.kitchens[0].estimatedTotal").value(690.00))
				.andExpect(jsonPath("$.kitchens[0].requests").value(1))
				.andExpect(jsonPath("$.kitchens[0].ingredients").value(2))
				.andExpect(jsonPath("$.kitchens[0].usesMealPlanner").value(false))
				.andExpect(jsonPath("$.kitchens[1].kitchen").value("Prasadam kitchen"))
				.andExpect(jsonPath("$.kitchens[1].estimatedTotal").value(180.00));
	}

	@Test
	@DisplayName("an ingredient no vendor supplies is counted and named, not costed at zero")
	void unpricedIngredientIsNamed() throws Exception {
		UUID toDeity = request(deityKitchen, "IR-2026-0001");
		issued(toDeity, rice, "10000", "GM", noon(day));
		issued(toDeity, salt, "400", "GM", noon(day));

		report(day, day)
				.andExpect(jsonPath("$.estimatedTotal").value(450.00))
				.andExpect(jsonPath("$.ingredientsWithoutPrice").value(1))
				.andExpect(jsonPath("$.unpriced[0].name").value("Rock Salt"))
				.andExpect(jsonPath("$.unpriced[0].quantity").value(0.4))
				.andExpect(jsonPath("$.unpriced[0].unit").value("KG"))
				// And declared against the kitchen too, not only in the footer — a reader comparing
				// two kitchens has to know which of them the figure under-reports.
				.andExpect(jsonPath("$.kitchens[0].ingredientsWithoutPrice").value(1))
				.andExpect(jsonPath("$.kitchens[0].unpriced[0].name").value("Rock Salt"));
	}

	@Test
	@DisplayName("two requests to one kitchen are one row, and the ingredient is added up once")
	void severalRequestsToOneKitchenAreOneRow() throws Exception {
		issued(request(deityKitchen, "IR-2026-0001"), rice, "10000", "GM", noon(day));
		issued(request(deityKitchen, "IR-2026-0002"), rice, "6000", "GM", noon(day));

		report(day, day)
				.andExpect(jsonPath("$.kitchens.length()").value(1))
				.andExpect(jsonPath("$.kitchens[0].requests").value(2))
				.andExpect(jsonPath("$.kitchens[0].ingredients").value(1))
				.andExpect(jsonPath("$.kitchens[0].estimatedTotal").value(720.00));
	}

	/**
	 * The ledger is append-only, so an issue recorded wrongly is undone by a compensating movement
	 * pointing back at it. Charging the kitchen for food it never received would be the plainest way
	 * this report could lie.
	 */
	@Test
	@DisplayName("an issue somebody has reversed is not charged to the kitchen")
	void correctedIssueLeavesTheFigure() throws Exception {
		UUID toDeity = request(deityKitchen, "IR-2026-0001");
		UUID mistake = issued(toDeity, rice, "10000", "GM", noon(day));
		issued(toDeity, dal, "2000", "GM", noon(day));
		correct(mistake, rice, "10000", "GM", noon(day));

		report(day, day)
				.andExpect(jsonPath("$.kitchens[0].estimatedTotal").value(240.00))
				.andExpect(jsonPath("$.kitchens[0].ingredients").value(1));
	}

	@Test
	@DisplayName("cooking a meal is not an issue, and does not land on any kitchen")
	void consumptionIsNotAnIssue() throws Exception {
		UUID toDeity = request(deityKitchen, "IR-2026-0001");
		issued(toDeity, rice, "10000", "GM", noon(day));
		// The other door out of the same store. It belongs to the cost-per-serving report, not here.
		movement("CONSUMPTION", null, null, rice, "-4000", "GM", noon(day));

		report(day, day).andExpect(jsonPath("$.estimatedTotal").value(450.00));
	}

	/**
	 * The store's day is the temple's day. An issue handed over at half past eleven at night is that
	 * evening's, and one at half past midnight is the next morning's, whatever the server thinks.
	 */
	@Test
	@DisplayName("the period is the temple's own days, to the last minute of the last one")
	void periodRunsOnTempleDays() throws Exception {
		UUID toDeity = request(deityKitchen, "IR-2026-0001");
		issued(toDeity, rice, "10000", "GM", at(day, LocalTime.of(23, 30)));
		issued(toDeity, dal, "2000", "GM", at(day.plusDays(1), LocalTime.of(0, 30)));

		report(day, day).andExpect(jsonPath("$.estimatedTotal").value(450.00));
		report(day, day.plusDays(1)).andExpect(jsonPath("$.estimatedTotal").value(690.00));
	}

	/**
	 * A kitchen that plans its meals here draws consumption instead, so anything on its row is from
	 * before it opted in. The flag travels with the row so the screen can say so rather than leaving
	 * a reader to assume the figure is current.
	 */
	@Test
	@DisplayName("a kitchen that now plans its own meals still shows what it was issued before")
	void kitchenOnTheMealPlannerCarriesItsFlag() throws Exception {
		UUID guestHouse = kitchen("Guest house kitchen", true);
		issued(request(guestHouse, "IR-2026-0001"), rice, "10000", "GM", noon(day));

		report(day, day)
				.andExpect(jsonPath("$.kitchens[0].kitchen").value("Guest house kitchen"))
				.andExpect(jsonPath("$.kitchens[0].usesMealPlanner").value(true));
	}

	@Test
	@DisplayName("a kitchen the store issued nothing to is absent, not a row of zeroes")
	void kitchenWithNothingIssuedIsAbsent() throws Exception {
		issued(request(deityKitchen, "IR-2026-0001"), rice, "10000", "GM", noon(day));

		report(day, day)
				.andExpect(jsonPath("$.kitchens.length()").value(1))
				.andExpect(jsonPath("$.kitchens[0].kitchen").value("Deity kitchen"));
	}

	@Test
	@DisplayName("a period the store issued nothing in reports no kitchens rather than zeroes")
	void emptyPeriod() throws Exception {
		report(day, day)
				.andExpect(jsonPath("$.kitchens.length()").value(0))
				.andExpect(jsonPath("$.requests").value(0))
				.andExpect(jsonPath("$.estimatedTotal").value(0.00))
				.andExpect(jsonPath("$.ingredientsWithoutPrice").value(0));
	}

	@Test
	@DisplayName("a period that runs backwards is refused with KMS-4988")
	void backwardsPeriodIsRefused() throws Exception {
		report(day.plusDays(1), day)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4988"));
	}

	@Test
	@DisplayName("a period longer than a year is refused rather than walked")
	void tooLongAPeriodIsRefused() throws Exception {
		report(day, day.plusYears(2))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4988"));
	}

	@Test
	@DisplayName("a volunteer cannot read what the store issued")
	void volunteerForbidden() throws Exception {
		signIn("uid-vol-a");
		report(day, day).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private ResultActions report(LocalDate from, LocalDate to) throws Exception {
		return mvc.perform(get("/api/v1/issued-from-store")
				.param("from", from.toString())
				.param("to", to.toString())
				.header("Authorization", "Bearer valid-token"));
	}

	private OffsetDateTime noon(LocalDate date) {
		return at(date, LocalTime.NOON);
	}

	private OffsetDateTime at(LocalDate date, LocalTime time) {
		return date.atTime(time).atZone(IST).toOffsetDateTime();
	}

	/** One issue, written the way {@code IngredientIssueService} writes it: negative, in base units. */
	private UUID issued(UUID requestId, UUID ingredientId, String baseQuantity, String unit,
			OffsetDateTime at) {
		return movement("ISSUE", "INGREDIENT_REQUEST", requestId, ingredientId,
				"-" + baseQuantity, unit, at);
	}

	/** The compensating movement {@code StockMovementService.compensate} writes: the reverse of one row. */
	private void correct(UUID originalId, UUID ingredientId, String baseQuantity, String unit,
			OffsetDateTime at) {
		movement("ADJUSTMENT", "CORRECTION", originalId, ingredientId, baseQuantity, unit, at);
	}

	private UUID movement(String type, String referenceType, UUID referenceId, UUID ingredientId,
			String quantity, String unit, OffsetDateTime at) {
		return admin.queryForObject("""
				INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit,
						movement_type, reference_type, reference_id, actor_user_id, created_at)
				VALUES (?, ?, gen_random_uuid(), ?::numeric, ?, ?, ?, ?,
						(SELECT id FROM users WHERE firebase_uid = 'uid-staff-a'), ?)
				RETURNING id
				""", UUID.class, tenant, ingredientId, quantity, unit, type, referenceType, referenceId, at);
	}

	private UUID request(UUID kitchenId, String reference) {
		return admin.queryForObject("""
				INSERT INTO ingredient_requests (tenant_id, reference, kitchen_id, needed_on, status,
						requested_by, issued_at)
				VALUES (?, ?, ?, ?, 'ISSUED',
						(SELECT id FROM users WHERE firebase_uid = 'uid-staff-a'), now())
				RETURNING id
				""", UUID.class, tenant, reference, kitchenId, day);
	}

	private UUID kitchen(String name, boolean usesMealPlanner) {
		return admin.queryForObject("""
				INSERT INTO kitchens (tenant_id, name, uses_meal_planner, created_by)
				VALUES (?, ?, ?, (SELECT id FROM users WHERE firebase_uid = 'uid-staff-a'))
				RETURNING id
				""", UUID.class, tenant, name, usesMealPlanner);
	}

	private UUID ingredient(String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', 'KG') RETURNING id
				""", UUID.class, tenant, name);
	}

	private UUID vendor(String name) {
		return admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, ?, '+919812345678') RETURNING id
				""", UUID.class, tenant, name);
	}

	private void supply(UUID vendorId, UUID ingredientId, String lastPrice, boolean preferred) {
		admin.update("""
				INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price, preferred)
				VALUES (?, ?, ?, ?::numeric, ?)
				""", tenant, vendorId, ingredientId, lastPrice, preferred);
	}

	private void insertUser(String uid, String email, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE')
				""", tenant, uid, email, role);
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
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
