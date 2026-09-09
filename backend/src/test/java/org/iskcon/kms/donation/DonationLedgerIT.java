package org.iskcon.kms.donation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The donations ledger (E7-S7): every type appears with linkage and filters, each filter selects
 * exactly the rows its own Type column labels, anonymity leaks no PII (export included), totals
 * reconcile, and the Indian FY boundary buckets correctly.
 */
@AutoConfigureMockMvc
@Import(DonationLedgerIT.StubVerifierConfiguration.class)
class DonationLedgerIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-admin', 'Admin', 'admin@example.com', '+919876500001', 'TEMPLE_ADMIN', 'ACTIVE')
				""", tenant);
		signIn("uid-admin");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM donations");
		admin.execute("DELETE FROM wishlist_items");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("all three donation types appear with the right category and are filterable")
	void allTypesAppearAndFilter() throws Exception {
		UUID item = admin.queryForObject("""
				INSERT INTO wishlist_items (tenant_id, title, price_inr, category, quantity_wanted, status)
				VALUES (?, 'Rice sacks', 1000, 'CONSUMABLE', 10, 'ACTIVE') RETURNING id
				""", UUID.class, tenant);
		money("ONE_TIME", "501", "Radha", null, null);
		money("ONE_TIME", "2000", "Shyam", item, null);   // wish-list
		inKind("Vegetables", "300");

		mvc.perform(authed(get("/api/v1/donations/ledger"))).andExpect(jsonPath("$.length()").value(3));
		mvc.perform(authed(get("/api/v1/donations/ledger").param("type", "WISHLIST")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].linkedTo").value("Wish list: Rice sacks"));
	}

	@Test
	@DisplayName("a gift that went two ways says so, and still counts once as a wish-list gift")
	void aSplitGiftIsLabelledWithBothHalves() throws Exception {
		// T-081. One ₹14,000 payment: ₹4,000 of it finished the grinder, ₹10,000 went to general
		// funds. It stays one row, because one card payment is one 80G receipt — which means the
		// plain "Wish list: …" label would show ₹14,000 against a grinder that got ₹4,000 of it, on
		// the one screen an accountant reconciles. So the label carries both figures and the amount
		// beside it stays the payment.
		UUID item = admin.queryForObject("""
				INSERT INTO wishlist_items (tenant_id, title, price_inr, category, quantity_wanted, status)
				VALUES (?, 'Commercial wet grinder', 14000, 'EQUIPMENT', 1, 'ACTIVE') RETURNING id
				""", UUID.class, tenant);
		money("ONE_TIME", "14000", "Shyam", item, null);
		admin.update("UPDATE donations SET wishlist_applied_inr = 4000 WHERE wishlist_item_id = ?", item);

		mvc.perform(authed(get("/api/v1/donations/ledger").param("type", "WISHLIST")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].linkedTo")
						.value("Wish list: Commercial wet grinder (₹4,000) and general kitchen (₹10,000)"))
				// The amount is untouched: it is the payment, and it is what an 80G receipt reports.
				.andExpect(jsonPath("$[0].amountInr").value(14000));
	}

	@Test
	@DisplayName("every filter returns exactly the rows its own Type column labels")
	void filtersMatchTheColumnTheyName() throws Exception {
		UUID item = admin.queryForObject("""
				INSERT INTO wishlist_items (tenant_id, title, price_inr, category, quantity_wanted, status)
				VALUES (?, 'Rice sacks', 1000, 'CONSUMABLE', 10, 'ACTIVE') RETURNING id
				""", UUID.class, tenant);
		money("ONE_TIME", "501", "Radha", null, null);    // collected by the gateway
		money("ONE_TIME", "2000", "Shyam", item, null);   // wish-list
		inKind("Vegetables", "300");
		cash("5000", "Walk-in Devotee");                  // hand-recorded, no provider

		mvc.perform(authed(get("/api/v1/donations/ledger"))).andExpect(jsonPath("$.length()").value(4));

		// The cash gift is one-time money, but it must not be counted among the gifts a gateway collected.
		for (String category : List.of("ONE_TIME", "WISHLIST", "IN_KIND", "MANUAL")) {
			mvc.perform(authed(get("/api/v1/donations/ledger").param("type", category)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.length()").value(1))
					.andExpect(jsonPath("$[0].category").value(category));
		}
	}

	/**
	 * A gift recorded before recurring giving left Phase 1 is still money the temple received, and
	 * the ledger must not lose it (T-111).
	 *
	 * <p>Recurring donations were removed whole on 2026-09-10: the table, the endpoints, the screen.
	 * What was deliberately <em>not</em> removed is {@code 'RECURRING'} from the CHECK on
	 * {@code donations.type}, because narrowing it would have meant rewriting or destroying a row
	 * that records a real gift — and a donation row is the temple's account of money it took.
	 *
	 * <p>So the value survives with nothing able to write it, and this test pins what an old row now
	 * does: it is labelled ONE_TIME, it is returned by the ONE_TIME filter, and its amount is counted
	 * exactly once. That is the accepted consequence of the removal, written down. The filter arms
	 * say {@code type <> 'IN_KIND'} rather than {@code type = 'ONE_TIME'} for precisely this row —
	 * written the other way it would carry a label no filter matched, and an accountant reconciling
	 * by category would come up short by its value with nothing on screen saying why.
	 */
	@Test
	@DisplayName("a donation recorded as RECURRING before the feature was withdrawn is still counted, as one-time")
	void aWithdrawnRecurringGiftIsStillOnTheLedger() throws Exception {
		money("ONE_TIME", "501", "Radha", null, null);
		money("RECURRING", "1001", "Gopal", null, null);   // as an old row would sit in the table

		mvc.perform(authed(get("/api/v1/donations/ledger"))).andExpect(jsonPath("$.length()").value(2));

		mvc.perform(authed(get("/api/v1/donations/ledger").param("type", "ONE_TIME")))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[?(@.donorDisplay=='Gopal')].category",
						org.hamcrest.Matchers.contains("ONE_TIME")))
				.andExpect(jsonPath("$[?(@.donorDisplay=='Gopal')].linkedTo",
						org.hamcrest.Matchers.contains("General kitchen")))
				// Serialised as a JSON number, so the matcher must be typed the way Jackson wrote it.
				.andExpect(jsonPath("$[?(@.donorDisplay=='Gopal')].amountInr",
						org.hamcrest.Matchers.contains(1001.0)));

		// And the category the ledger no longer offers is not a filter that quietly returns
		// everything: an unknown value falls through to no clause at all, as it always has.
		mvc.perform(authed(get("/api/v1/donations/ledger").param("type", "RECURRING")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2));
	}

	/**
	 * A gift attached to nothing is not a gift we know nothing about. "Linked to" used to go blank for
	 * every general gift — the online one-time, and the cash in the hundi — which read as a missing
	 * fact rather than the definite one it is: money the kitchen may spend on whatever it needs next.
	 */
	@Test
	@DisplayName("a gift earmarked to nothing reads as the general kitchen, never as a blank")
	void generalGiftsAreNamedNotBlank() throws Exception {
		money("ONE_TIME", "501", "Radha", null, null);  // collected online, no earmark
		cash("5000", "Walk-in Devotee");                // hand-recorded cash, no earmark

		mvc.perform(authed(get("/api/v1/donations/ledger")))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].linkedTo").value("General kitchen"))
				.andExpect(jsonPath("$[1].linkedTo").value("General kitchen"));
	}

	@Test
	@DisplayName("anonymous shows as Anonymous, and a named donor's contact never appears in the export")
	void anonymityLeaksNoPii() throws Exception {
		money("ONE_TIME", "501", "Radha Devi", null, "+919812345678"); // named, with a phone captured
		money("ONE_TIME", "5000", null, null, null);                    // anonymous — zero PII

		String csv = mvc.perform(authed(get("/api/v1/donations/ledger/export")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assert csv.contains("Anonymous") : "the anonymous gift should read Anonymous";
		assert csv.contains("Radha Devi") : "a named donor's name is fine to show";
		assert !csv.contains("+919812345678") : "the ledger must never export contact PII";
	}

	@Test
	@DisplayName("the FY summary buckets a Mar-31 gift and an Apr-1 gift into different years")
	void fyBoundary() throws Exception {
		// Current date is 2026-08 → FY starts 2026-04-01. Mar 31 2026 is the previous FY.
		moneyOn("ONE_TIME", "100", "2026-03-31");
		moneyOn("ONE_TIME", "700", "2026-04-01");

		// April–March, asserted against the period summary — the parameterless /summary it used to
		// use went when Today stopped showing month-to-date giving, and LedgerPeriod is now the one
		// place the financial year is decided.
		mvc.perform(authed(get("/api/v1/donations/ledger/period-summary")
						.param("period", "FINANCIAL_YEAR")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.window.from").value("2026-04-01"))
				// The FY-to-date one-time total is the Apr 1 gift alone, not 800.
				.andExpect(jsonPath("$.byCategory.ONE_TIME.total").value(700));
	}

	// ---------------------------------------------------------------------

	private void money(String type, String amount, String donorName, UUID wishlistItem, String phone) {
		boolean anon = donorName == null;
		admin.update("""
				INSERT INTO donations (tenant_id, type, amount_inr, status, is_anonymous, donor_name, donor_phone,
					wishlist_item_id, payment_mode, provider, donated_on)
				VALUES (?, ?, ?::numeric, 'COMPLETED', ?, ?, ?, ?, 'UPI', 'stub', CURRENT_DATE)
				""", tenant, type, amount, anon, donorName, phone, wishlistItem);
	}

	private void moneyOn(String type, String amount, String date) {
		admin.update("""
				INSERT INTO donations (tenant_id, type, amount_inr, status, is_anonymous, donor_name,
					payment_mode, provider, donated_on)
				VALUES (?, ?, ?::numeric, 'COMPLETED', false, 'Donor', 'UPI', 'stub', ?::date)
				""", tenant, type, amount, date);
	}

	/** Cash over the counter: money, but with no provider, because a person wrote the row. */
	private void cash(String amount, String donorName) {
		admin.update("""
				INSERT INTO donations (tenant_id, type, amount_inr, status, is_anonymous, donor_name,
					payment_mode, donated_on)
				VALUES (?, 'ONE_TIME', ?::numeric, 'COMPLETED', false, ?, 'CASH', CURRENT_DATE)
				""", tenant, amount, donorName);
	}

	private void inKind(String name, String value) {
		admin.update("""
				INSERT INTO donations (tenant_id, type, estimated_value_inr, status, is_anonymous, donor_name, donated_on)
				VALUES (?, 'IN_KIND', ?::numeric, 'COMPLETED', false, ?, CURRENT_DATE)
				""", tenant, value, name);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

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
