package org.iskcon.kms.donation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.inventory.StockMovementService;
import org.iskcon.kms.notification.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Striking a gift that was recorded wrongly (T-012), through the full stack against a real database
 * under RLS.
 *
 * <p>Recording was a one-way door: a gift entered twice, or against the wrong donor, permanently
 * inflated the figures the temple reports under 80G, and where it was in kind it had put real
 * kilograms into the store-room in the same transaction. Both are undone here, and the test that
 * matters most is {@link #bothHalvesRollBackTogether} — the two records are corrected in different
 * ways (the donation is marked, the append-only stock ledger is compensated) and the only thing
 * keeping them honest is that they commit together.
 *
 * <p>The notification service is mocked, as it is in {@code DonationIntakeIT}: the thank-you that
 * intake sends is not this story's concern, and mocking it keeps this context off Quartz.
 */
@AutoConfigureMockMvc
@Import(DonationVoidIT.StubVerifierConfiguration.class)
class DonationVoidIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private NotificationService notificationService;

	/**
	 * A spy rather than a mock, and only one method is ever stubbed. Recording an in-kind gift has to
	 * go on writing real movements through the real service — a mock would give this test a donation
	 * with nothing behind it, and every assertion about reversing stock would pass against an empty
	 * ledger.
	 */
	@SpyBean
	private StockMovementService stockMovementService;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID rice;
	private LocalDate today;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = insertTenant();
		insertUser(tenant, "uid-admin", "admin@example.com", "TEMPLE_ADMIN");
		insertUser(tenant, "uid-manager", "manager@example.com", "KITCHEN_MANAGER");
		insertUser(tenant, "uid-staff", "staff@example.com", "KITCHEN_STAFF");
		rice = insertIngredient("Rice");
		// The gift is dated today in the temple's own zone, so it lands inside the MONTH window the
		// period summary resolves for itself. A fixed date would drift out of that window and the
		// 80G assertion would start passing for the wrong reason.
		today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
		signIn("uid-admin");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM equipment_state_changes");
		admin.execute("DELETE FROM equipment_items");
		admin.execute("DELETE FROM donations");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	/**
	 * Acceptance 1 and 5: the mark and the compensating movement land together, and each files its own
	 * audit entry.
	 */
	@Test
	@DisplayName("voiding a gift of food marks the donation and reverses the stock it brought")
	void voidsTheGiftAndTheStockTogether() throws Exception {
		UUID donation = recordFood(new BigDecimal("5"));
		assertThat(stockOnHand()).isEqualByComparingTo("5000"); // 5 Kg, in the base unit

		mvc.perform(voidRequest(donation, "Entered twice at the gate."))
				.andExpect(status().isNoContent());

		Map<String, Object> row = admin.queryForMap("""
				SELECT voided_at, voided_by, void_reason, status FROM donations WHERE id = ?
				""", donation);
		assertThat(row.get("voided_at")).isNotNull();
		assertThat(row.get("voided_by")).isEqualTo(userId("uid-admin"));
		assertThat(row.get("void_reason")).isEqualTo("Entered twice at the gate.");
		// `status` says how the payment went and is deliberately untouched: a gift can complete and
		// then be voided, and folding the two would make one column answer two questions.
		assertThat(row.get("status")).isEqualTo("COMPLETED");

		// The shelf is back where it was, by a fresh row rather than an edited one — the ledger is
		// append-only, so a correction is the only honest way to undo a movement.
		assertThat(stockOnHand()).isEqualByComparingTo("0");
		assertThat(admin.queryForObject("""
				SELECT count(*) FROM stock_movements m
				WHERE m.reference_type = 'CORRECTION' AND m.reference_id = (
					SELECT id FROM stock_movements
					WHERE reference_type = 'DONATION' AND reference_id = ?)
				""", Integer.class, donation)).isEqualTo(1);

		// Two entries for one act, and both are wanted: the store-room's ledger has its own readers,
		// and an entry about a donation is not one they would ever find.
		assertThat(auditCount("DONATION_VOIDED")).isEqualTo(1);
		assertThat(auditCount("STOCK_MOVEMENT_CORRECTED")).isEqualTo(1);
	}

	/**
	 * Acceptance 1, and the reason this feature is one transaction rather than two calls.
	 *
	 * <p>The service marks the donation <em>first</em> and reverses the stock after, so that a
	 * failure in the stock half has something to roll back. Written the other way round this test
	 * would pass without proving anything: the mark would simply never have been attempted.
	 */
	@Test
	@DisplayName("a failure reversing the stock leaves the donation unmarked — neither half survives alone")
	void bothHalvesRollBackTogether() throws Exception {
		UUID donation = recordFood(new BigDecimal("5"));

		doThrow(new IllegalStateException("the stock ledger is unavailable"))
				.when(stockMovementService).compensate(any(), any(UUID.class), anyString());

		mvc.perform(voidRequest(donation, "Entered twice at the gate."))
				.andExpect(status().is5xxServerError());

		// The mark is gone with it. Without one transaction this row would read "voided" over a
		// store-room still holding 5 Kg the temple was never given.
		assertThat(admin.queryForObject(
				"SELECT voided_at FROM donations WHERE id = ?", java.sql.Timestamp.class, donation))
				.isNull();
		assertThat(admin.queryForObject(
				"SELECT void_reason FROM donations WHERE id = ?", String.class, donation)).isNull();
		assertThat(stockOnHand()).isEqualByComparingTo("5000");
		assertThat(correctionCount()).isZero();
		assertThat(auditCount("DONATION_VOIDED")).isZero();
	}

	/**
	 * Acceptance 2: the money leaves the 80G tiles and the gift stays on the list, marked. Both are
	 * asserted before and after, because an empty tile proves nothing on its own — it is the same
	 * answer a temple that received nothing would get.
	 */
	@Test
	@DisplayName("a voided gift leaves the 80G figures and stays in the ledger, marked")
	void leavesTheFiguresAndStaysInTheLedger() throws Exception {
		UUID donation = recordFood(new BigDecimal("5"));

		mvc.perform(authed(get("/api/v1/donations/ledger/period-summary?period=MONTH")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.byCategory.IN_KIND.total").value(2500));

		mvc.perform(voidRequest(donation, "Recorded against the wrong donor."))
				.andExpect(status().isNoContent());

		// Out of the figures the temple reports.
		mvc.perform(authed(get("/api/v1/donations/ledger/period-summary?period=MONTH")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.byCategory.IN_KIND").doesNotExist());

		// And still on the list, marked, with the reason it was struck. An accountant reconciling
		// against a receipt book has to be able to find the gift that was struck, and read why.
		mvc.perform(authed(get("/api/v1/donations/ledger")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(donation.toString()))
				.andExpect(jsonPath("$[0].voided").value(true))
				.andExpect(jsonPath("$[0].voidReason").value("Recorded against the wrong donor."))
				.andExpect(jsonPath("$[0].status").value("COMPLETED"));

		// The export is where the figures leave the building, so it carries the mark too.
		String csv = mvc.perform(authed(get("/api/v1/donations/ledger/export")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(csv).contains("Voided,Void reason");
		assertThat(csv).contains("Yes,Recorded against the wrong donor.");
	}

	/** Acceptance 3. */
	@Test
	@DisplayName("striking the same gift twice is refused with KMS-400134")
	void aSecondVoidIsRefused() throws Exception {
		UUID donation = recordFood(new BigDecimal("5"));
		mvc.perform(voidRequest(donation, "Entered twice at the gate."))
				.andExpect(status().isNoContent());

		mvc.perform(voidRequest(donation, "Entered twice at the gate."))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400134"));

		// And the refusal is what keeps the store-room honest: a second void would reverse the same
		// movement again and leave the shelf short of what was actually given.
		assertThat(correctionCount()).isEqualTo(1);
		assertThat(stockOnHand()).isEqualByComparingTo("0");
		assertThat(auditCount("DONATION_VOIDED")).isEqualTo(1);
	}

	/**
	 * Acceptance 4. Temple Admin alone (D-4), and forced rather than chosen: VIEW_DONATIONS is
	 * already the Temple Admin's alone, so anything wider would let somebody strike a record they
	 * cannot read.
	 */
	@Test
	@DisplayName("only a temple admin may strike a gift")
	void onlyATempleAdminMayVoid() throws Exception {
		UUID donation = recordFood(new BigDecimal("5"));

		signIn("uid-manager");
		mvc.perform(voidRequest(donation, "Entered twice.")).andExpect(status().isForbidden());

		// The one who can record it cannot undo it — D-5, inherited knowingly.
		signIn("uid-staff");
		mvc.perform(voidRequest(donation, "Entered twice.")).andExpect(status().isForbidden());

		assertThat(admin.queryForObject(
				"SELECT voided_at FROM donations WHERE id = ?", java.sql.Timestamp.class, donation))
				.isNull();
		assertThat(stockOnHand()).isEqualByComparingTo("5000");

		signIn("uid-admin");
		mvc.perform(voidRequest(donation, "Entered twice.")).andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("a reason is required — a blank one is not an account of why an 80G figure moved")
	void aReasonIsRequired() throws Exception {
		UUID donation = recordFood(new BigDecimal("5"));

		mvc.perform(voidRequest(donation, "   "))
				.andExpect(status().isBadRequest());

		assertThat(admin.queryForObject(
				"SELECT voided_at FROM donations WHERE id = ?", java.sql.Timestamp.class, donation))
				.isNull();
		assertThat(stockOnHand()).isEqualByComparingTo("5000");
	}

	/**
	 * The limit of this feature, asserted so that it is a decision on the record rather than
	 * something a later reader discovers.
	 *
	 * <p>A void strikes the gift and reverses the food. Donated <em>equipment</em> stays in the
	 * register, still pointing at the donation it came in on: reversing an asset registration is a
	 * different act with no primitive behind it, and refusing to void a gift that brought a vessel
	 * would leave the 80G figure permanently wrong in order to protect a register entry an admin can
	 * already correct by hand. The screen says so before the button is pressed.
	 */
	@Test
	@DisplayName("donated equipment stays in the register — the void reverses food, not assets")
	void equipmentStaysInTheRegister() throws Exception {
		String body = """
				{"anonymous":false,"donorName":"Govind Das","estimatedValueInr":2500,"donatedOn":"%s",
				 "ingredients":[{"ingredientId":"%s","quantity":5,"unit":"KG"}],
				 "equipment":[{"name":"Serving Vessel"}]}
				""".formatted(today, rice);
		UUID donation = record(body);

		mvc.perform(voidRequest(donation, "Entered twice at the gate."))
				.andExpect(status().isNoContent());

		assertThat(stockOnHand()).isEqualByComparingTo("0");
		assertThat(admin.queryForObject("""
				SELECT count(*) FROM equipment_items WHERE donation_id = ? AND source = 'DONATED'
				""", Integer.class, donation)).isEqualTo(1);
	}

	// ---------------------------------------------------------------------

	private UUID recordFood(BigDecimal kilos) throws Exception {
		return record("""
				{"anonymous":false,"donorName":"Govind Das","estimatedValueInr":2500,"donatedOn":"%s",
				 "ingredients":[{"ingredientId":"%s","quantity":%s,"unit":"KG"}]}
				""".formatted(today, rice, kilos.toPlainString()));
	}

	private UUID record(String body) throws Exception {
		String response = mvc.perform(authed(post("/api/v1/donations"))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(response.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
	}

	private MockHttpServletRequestBuilder voidRequest(UUID donation, String reason) {
		return authed(post("/api/v1/donations/" + donation + "/void"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"%s\"}".formatted(reason));
	}

	/** What the store actually holds, in the base unit, summed from the ledger as the app does. */
	private BigDecimal stockOnHand() {
		return admin.queryForObject("""
				SELECT COALESCE(SUM(to_base_qty(quantity, unit)), 0)
				FROM stock_movements WHERE ingredient_id = ?
				""", BigDecimal.class, rice);
	}

	private int correctionCount() {
		Integer count = admin.queryForObject(
				"SELECT count(*) FROM stock_movements WHERE reference_type = 'CORRECTION'", Integer.class);
		return count == null ? 0 : count;
	}

	private int auditCount(String action) {
		Integer count = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = ?", Integer.class, action);
		return count == null ? 0 : count;
	}

	private UUID userId(String uid) {
		return admin.queryForObject(
				"SELECT id FROM users WHERE firebase_uid = ?", UUID.class, uid);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private UUID insertTenant() {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
	}

	private void insertUser(UUID tenantId, String uid, String email, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE')
				""", tenantId, uid, email, role);
	}

	private UUID insertIngredient(String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', 'KG')
				RETURNING id
				""", UUID.class, tenant, name);
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
