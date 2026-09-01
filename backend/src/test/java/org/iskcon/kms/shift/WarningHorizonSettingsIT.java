package org.iskcon.kms.shift;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The two horizons that warn a temple in advance, now that they are the temple's own (V85).
 *
 * <p>They used to be two constants, both seven days, one of them borrowed from the other because
 * there was no evidence a supplier agreement wanted a different number from a sack of flour. There
 * is: seven days is not enough notice to renegotiate a contract. So both moved here together, which
 * is what E5-S1 D2 said should happen if either of them ever moved — a temple should not be able to
 * find one of them configurable and the other nailed down.
 *
 * <p>What is worth asserting is not that a number is stored. It is that changing the number changes
 * which vendors and which batches carry a badge, that the two are independent of each other and of
 * the temple next door, and that a horizon no warning could survive is refused twice — at the
 * boundary and by the database, which is the one that cannot be got round.
 */
@AutoConfigureMockMvc
@Import(WarningHorizonSettingsIT.StubVerifierConfiguration.class)
class WarningHorizonSettingsIT extends AbstractIntegrationTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID templeB;
	private UUID actorA;
	private UUID toorDal;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");
		actorA = insertUser(templeA, "uid-admin-a", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-cook-a", "KITCHEN_STAFF");
		insertUser(templeB, "uid-admin-b", "TEMPLE_ADMIN");
		toorDal = insertIngredient(templeA, "Toor Dal");
		signIn("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM tenant_settings");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- What a temple gets without doing anything --------------------------

	@Test
	@DisplayName("a temple that has never chosen gets seven days for stock and thirty for contracts")
	void defaultsForATempleThatHasNeverChosen() throws Exception {
		mvc.perform(authed(get("/api/v1/settings")))
				.andExpect(status().isOk())
				// Unchanged from the constant it replaced. Every temple's stock screens behave today
				// exactly as they did yesterday.
				.andExpect(jsonPath("$.stockExpiryWarningDays").value(7))
				// The one thing this change actually moves: it was seven, and seven days is not
				// enough to renegotiate an agreement.
				.andExpect(jsonPath("$.contractEndWarningDays").value(30));
	}

	@Test
	@DisplayName("a settings row made for some other reason still carries both horizons")
	void aRowMadeForSomeOtherReasonCarriesBoth() throws Exception {
		// This is the shape the migration's backfill guarantees, and the one worth testing here.
		// tenant_settings has been sparse since V36 — a temple that never opened the screen has no
		// row at all — so V85 writes 7 and 30 into every temple's row explicitly and creates one
		// where there was none. A temple that acquires a row later, for an unrelated preference,
		// has to end up in the same place, or the two paths disagree.
		choose("harbour-blue");

		mvc.perform(authed(get("/api/v1/settings")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.themeId").value("harbour-blue"))
				.andExpect(jsonPath("$.stockExpiryWarningDays").value(7))
				.andExpect(jsonPath("$.contractEndWarningDays").value(30));
	}

	// ---- The number actually decides which rows warn ------------------------

	@Test
	@DisplayName("the contract horizon decides which vendors warn, and thirty days is the new floor")
	void contractHorizonDecidesWhichVendorsWarn() throws Exception {
		LocalDate today = LocalDate.now(IST);
		UUID vendor = insertVendor(templeA, "Sri Ganesh Traders", "+919812345601", today.plusDays(20));

		// Out of the box, twenty days out warns — which it did not before this change, because the
		// horizon was the seven days stock expiry uses. That is the whole point of the change.
		assertVendorWarns(vendor, true);

		// A temple that wants the old behaviour can have it, and then the same vendor goes quiet.
		setHorizons(7, 7);
		assertVendorWarns(vendor, false);

		// And back. Nothing about the vendor changed in between.
		setHorizons(7, 30);
		assertVendorWarns(vendor, true);
	}

	@Test
	@DisplayName("the stock horizon decides which batches warn, independently of the contract one")
	void stockHorizonDecidesWhichBatchesWarn() throws Exception {
		LocalDate today = LocalDate.now(IST);
		UUID item = insertItem(templeA, toorDal, "Main store");
		seedMovement(templeA, toorDal, UUID.randomUUID(), "10", today.plusDays(20));

		// Twenty days out, against a seven-day stock horizon: not yet worth saying.
		assertBatchWarns(item, false);

		// Moving the contract horizon alone must not touch it — they were one number, and the
		// failure this change exists to prevent is them silently sharing one again.
		setHorizons(7, 90);
		assertBatchWarns(item, false);

		setHorizons(30, 90);
		assertBatchWarns(item, true);
	}

	@Test
	@DisplayName("an explicit window on the request still wins over the temple's own")
	void anExplicitWindowStillWins() throws Exception {
		LocalDate today = LocalDate.now(IST);
		UUID item = insertItem(templeA, toorDal, "Main store");
		seedMovement(templeA, toorDal, UUID.randomUUID(), "10", today.plusDays(20));

		// The stock screen has always been able to ask its own question. The setting is what
		// answers when nobody asks, not a cap on what may be asked.
		mvc.perform(authed(get("/api/v1/inventory/items/{id}", item)).param("expiringWithinDays", "60"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.item.expiringSoon").value(true));
	}

	@Test
	@DisplayName("one temple's notice is not another's")
	void horizonsArePerTemple() throws Exception {
		setHorizons(21, 90);

		signIn("uid-admin-b");
		mvc.perform(authed(get("/api/v1/settings")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.stockExpiryWarningDays").value(7))
				.andExpect(jsonPath("$.contractEndWarningDays").value(30));
	}

	@Test
	@DisplayName("changing the horizons replaces them rather than accumulating rows")
	void changingReplaces() throws Exception {
		setHorizons(14, 60);
		setHorizons(21, 45);

		mvc.perform(authed(get("/api/v1/settings")))
				.andExpect(jsonPath("$.stockExpiryWarningDays").value(21))
				.andExpect(jsonPath("$.contractEndWarningDays").value(45));
		Integer rows = admin.queryForObject("SELECT count(*) FROM tenant_settings", Integer.class);
		Assertions.assertThat(rows).isEqualTo(1);
	}

	// ---- Bounds, at both layers ---------------------------------------------

	@Test
	@DisplayName("a horizon no warning could survive is refused at the boundary")
	void boundsAreRefusedAtTheBoundary() throws Exception {
		// Zero warns on the morning the thing has already expired, which is not advance notice.
		// Negative points at the past. Beyond a year every batch and every contract is badged from
		// the day it is entered, and a badge that is always on is not read.
		for (String bad : new String[] {"0", "-1", "366", "10000"}) {
			mvc.perform(authed(put("/api/v1/settings/warning-horizons"))
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"stockExpiryWarningDays\":" + bad + ",\"contractEndWarningDays\":30}"))
					.andExpect(status().isBadRequest());
			mvc.perform(authed(put("/api/v1/settings/warning-horizons"))
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"stockExpiryWarningDays\":7,\"contractEndWarningDays\":" + bad + "}"))
					.andExpect(status().isBadRequest());
		}

		// The ends of the range are inside it, not outside.
		setHorizons(1, 365);
	}

	@Test
	@DisplayName("and by the database, which is the layer that cannot be got round")
	void boundsAreRefusedByTheDatabase() {
		setHorizonsDirectly(7, 30);

		assertThatThrownBy(() -> admin.update("UPDATE tenant_settings SET stock_expiry_warning_days = 0"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> admin.update("UPDATE tenant_settings SET contract_end_warning_days = 366"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// ---- Who may change them ------------------------------------------------

	@Test
	@DisplayName("a cook cannot change how much notice the temple gets")
	void cooksCannotChange() throws Exception {
		signIn("uid-cook-a");

		mvc.perform(authed(put("/api/v1/settings/warning-horizons"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"stockExpiryWarningDays\":30,\"contractEndWarningDays\":90}"))
				.andExpect(status().isForbidden());
		mvc.perform(authed(get("/api/v1/settings"))).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------- helpers

	private void assertVendorWarns(UUID vendorId, boolean expected) throws Exception {
		mvc.perform(authed(get("/api/v1/vendors/{id}", vendorId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.vendor.contractEndingSoon").value(expected));
		mvc.perform(authed(get("/api/v1/vendors")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].contractEndingSoon").value(expected));
	}

	private void assertBatchWarns(UUID itemId, boolean expected) throws Exception {
		mvc.perform(authed(get("/api/v1/inventory/items/{id}", itemId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.item.expiringSoon").value(expected))
				.andExpect(jsonPath("$.batches[0].expiringSoon").value(expected));
		mvc.perform(authed(get("/api/v1/inventory/items")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].expiringSoon").value(expected));
	}

	private void setHorizons(int stockDays, int contractDays) throws Exception {
		mvc.perform(authed(put("/api/v1/settings/warning-horizons"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"stockExpiryWarningDays\":" + stockDays
								+ ",\"contractEndWarningDays\":" + contractDays + "}"))
				.andExpect(status().isNoContent());
	}

	/** A row to aim the CHECK at, without going through the layer that would refuse it first. */
	private void setHorizonsDirectly(int stockDays, int contractDays) {
		admin.update("""
				INSERT INTO tenant_settings (tenant_id, stock_expiry_warning_days, contract_end_warning_days)
				VALUES (?, ?, ?)
				""", templeA, stockDays, contractDays);
	}

	private void choose(String themeId) throws Exception {
		mvc.perform(authed(put("/api/v1/settings/theme"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"themeId\":\"" + themeId + "\"}"))
				.andExpect(status().isNoContent());
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
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug, name);
	}

	private UUID insertUser(UUID tenantId, String uid, String role) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE') RETURNING id
				""", UUID.class, tenantId, uid, uid + "@example.com", role);
	}

	private UUID insertIngredient(UUID tenantId, String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Pulses', 'KG') RETURNING id
				""", UUID.class, tenantId, name);
	}

	private UUID insertItem(UUID tenantId, UUID ingredientId, String location) {
		return admin.queryForObject("""
				INSERT INTO inventory_items (tenant_id, ingredient_id, storage_location)
				VALUES (?, ?, ?) RETURNING id
				""", UUID.class, tenantId, ingredientId, location);
	}

	private UUID insertVendor(UUID tenantId, String name, String phone, LocalDate contractEnd) {
		return admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone, contract_end_date)
				VALUES (?, ?, ?, ?) RETURNING id
				""", UUID.class, tenantId, name, phone, contractEnd);
	}

	private void seedMovement(UUID tenantId, UUID ingredientId, UUID batch, String qty, LocalDate expiry) {
		admin.update("""
				INSERT INTO stock_movements (
					tenant_id, ingredient_id, batch_id, quantity, unit, movement_type,
					expiry_date, received_date, actor_user_id)
				VALUES (?, ?, ?, ?::numeric, 'KG', 'PO_RECEIPT', ?, ?, ?)
				""", tenantId, ingredientId, batch, qty, expiry, expiry, actorA);
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
