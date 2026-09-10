package org.iskcon.kms.donation;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.iskcon.kms.document.DocumentGenerationService;
import org.iskcon.kms.notification.NotificationService;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The donation screen and its receipt (T-110), through the full stack against a real database under
 * RLS.
 *
 * <p>Four claims are worth proving here and each one is a way the feature could quietly be wrong:
 *
 * <ul>
 *   <li><strong>Re-sending does not create a second document.</strong> Two receipts for one gift is
 *       the failure under 80G the whole feature exists to avoid, and it is the reason T-081 kept a
 *       split gift as one donation row. Proved by counting rows, not by reading the service.</li>
 *   <li><strong>A struck gift cannot have one issued.</strong> A void says the temple did not
 *       receive the money and every 80G figure already ignores it — a receipt would contradict the
 *       temple's own return.</li>
 *   <li><strong>A split gift shows one receipt for the whole payment.</strong> This is the test that
 *       proves T-110 and T-081 agree: the receipt reads {@code amount_inr} and knows nothing about
 *       {@code wishlist_applied_inr}.</li>
 *   <li><strong>The history carries the gifts the screen hides by default.</strong> The toggle can
 *       only reveal what the server sent, so what is asserted is that failed, expired and struck
 *       gifts come back at all — which they do, because {@code donorHistory} applies no status
 *       filter, unlike the ledger list beside it.</li>
 * </ul>
 *
 * <p>A mocked {@link Scheduler} keeps the request→enqueue path hermetic and the worker step is
 * driven synchronously through {@link DocumentGenerationService}, as the other document tests do.
 * {@link NotificationService} is mocked for the reason {@code DonationVoidIT} gives: the messages
 * this package sends are not this story's concern, and mocking it keeps the context off Quartz.
 */
@AutoConfigureMockMvc
@Import(DonationReceiptIT.StubVerifierConfiguration.class)
class DonationReceiptIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private DocumentGenerationService generationService;

	@Autowired
	private DonationReceiptService receiptService;

	@Autowired
	private MonetaryDonationService monetaryDonationService;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private NotificationService notificationService;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;
	private LocalDate today;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, address, latitude, longitude, timezone, is_80g_approved)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', '12 Temple Road, Bengaluru',
						12.9716, 77.5946, 'Asia/Kolkata', true)
				RETURNING id
				""", UUID.class);
		insertUser("uid-admin", "admin@example.com", "TEMPLE_ADMIN");
		insertUser("uid-staff", "staff@example.com", "KITCHEN_STAFF");
		today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
		signIn("uid-admin");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM documents");
		admin.execute("DELETE FROM donations");
		admin.execute("DELETE FROM donation_receipt_sequence");
		admin.execute("DELETE FROM wishlist_items");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// --- The receipt itself ---------------------------------------------

	@Test
	@DisplayName("the receipt carries the temple's 80G status and the donor's details, and downloads")
	void issuesRendersAndDownloads() throws Exception {
		UUID gift = monetaryGift(new BigDecimal("5000"), "Gopal Das");

		UUID document = issue(gift);
		assertThat(receiptNumber(gift)).startsWith("R-" + today.getYear() + "-");
		assertThat(documentStatus(document)).isEqualTo("PENDING");

		// What the worker will actually lay out, asserted before it is squashed into the stub
		// renderer's placeholder bytes. The renderer in CI has no browser, so the sheet's own text is
		// the only place these facts can be proved to have reached the page.
		String html = within(() -> receiptService.render(gift));
		assertThat(html).contains("Sri Sri Radha Govinda Temple");
		assertThat(html).contains("12 Temple Road, Bengaluru");
		assertThat(html).contains("Gopal Das");
		assertThat(html).contains("ABCDE1234F");
		assertThat(html).contains("₹5,000");
		assertThat(html).contains("Eligible for deduction under Section 80G");
		assertThat(html).contains(receiptNumber(gift));

		generate(document);
		assertThat(documentStatus(document)).isEqualTo("READY");

		byte[] pdf = mvc.perform(authed(get("/api/v1/donations/" + gift + "/receipt/download")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsByteArray();
		assertThat(pdf).isNotEmpty();
		assertThat(new String(pdf)).startsWith("%PDF");
	}

	@Test
	@DisplayName("a temple with no 80G registration issues a receipt that claims no deduction")
	void unapprovedTempleClaimsNothing() throws Exception {
		admin.update("UPDATE tenants SET is_80g_approved = false WHERE id = ?", tenant);
		// No 80G capture either — the constraint would refuse it, and a temple that cannot offer the
		// path has no gift carrying a PAN. The receipt is still worth issuing.
		UUID gift = plainGift(new BigDecimal("1200"));

		issue(gift);
		String html = within(() -> receiptService.render(gift));

		assertThat(html).contains("This receipt does not support a tax deduction.");
		assertThat(html).doesNotContain("Eligible for deduction under Section 80G");
	}

	// --- One payment, one receipt ---------------------------------------

	@Test
	@DisplayName("re-sending does not create a second document, and does not change the number")
	void reSendingReusesTheOneDocument() throws Exception {
		UUID gift = monetaryGift(new BigDecimal("5000"), "Gopal Das");

		UUID first = issue(gift);
		String number = receiptNumber(gift);
		generate(first);

		// Issued again, and then sent twice on top of that. Every one of these goes through the same
		// door, and none of them may mint a second document or a second number.
		UUID second = issue(gift);
		mvc.perform(authed(post("/api/v1/donations/" + gift + "/receipt/send")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sent").value(true));
		mvc.perform(authed(post("/api/v1/donations/" + gift + "/receipt/send")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sent").value(true));

		assertThat(second).isEqualTo(first);
		assertThat(receiptNumber(gift)).isEqualTo(number);
		assertThat(admin.queryForObject("""
				SELECT count(*) FROM documents WHERE donation_id = ? AND kind = 'DONATION_RECEIPT_PDF'
				""", Integer.class, gift)).isEqualTo(1);

		// And the bytes were not quietly remade. A receipt already READY is the document the donor
		// was given; re-rendering it would reissue it with whatever the row says today.
		assertThat(documentStatus(first)).isEqualTo("READY");
	}

	@Test
	@DisplayName("the database refuses a second receipt row even if the service ever stopped looking")
	void theUniqueIndexIsTheRealGuard() throws Exception {
		UUID gift = monetaryGift(new BigDecimal("5000"), "Gopal Das");
		issue(gift);

		// Straight past the service, as the superuser, which is the only way to test that the
		// constraint carries the rule rather than the method above it. A check that lives only in
		// Java is a check that a later refactor can delete without a test noticing.
		try {
			admin.update("""
					INSERT INTO documents (tenant_id, kind, donation_id, language, status)
					VALUES (?, 'DONATION_RECEIPT_PDF', ?, 'en', 'PENDING')
					""", tenant, gift);
			assert false : "the unique index must refuse a second receipt for one gift";
		} catch (org.springframework.dao.DuplicateKeyException expected) {
			// The rule is in the database, which is where it belongs.
		}
	}

	@Test
	@DisplayName("a voided gift cannot have a receipt issued, and none is queued")
	void aStruckGiftGetsNoReceipt() throws Exception {
		UUID gift = monetaryGift(new BigDecimal("5000"), "Gopal Das");

		mvc.perform(authed(post("/api/v1/donations/" + gift + "/void"))
						.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Entered twice at the gate.\"}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(post("/api/v1/donations/" + gift + "/receipt")))
				.andExpect(status().isConflict());

		// Nothing was queued and no number was spent. Refusing after the insert would leave a FAILED
		// document row for somebody to interpret, which is the whole reason the check runs first.
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM documents WHERE donation_id = ?", Integer.class, gift)).isZero();
		assertThat(receiptNumber(gift)).isNull();

		// The screen is told the same thing, so it can withhold the control rather than offer one
		// that refuses.
		mvc.perform(authed(get("/api/v1/donations/" + gift)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.voided").value(true))
				.andExpect(jsonPath("$.canBeReceipted").value(false));
	}

	@Test
	@DisplayName("a split gift gets one receipt, for the whole payment")
	void aSplitGiftIsOnePayment() throws Exception {
		UUID item = admin.queryForObject("""
				INSERT INTO wishlist_items (tenant_id, title, price_inr, category, status)
				VALUES (?, 'A wet grinder', 14000, 'EQUIPMENT', 'ACTIVE') RETURNING id
				""", UUID.class, tenant);
		// ₹14,000 paid, of which ₹4,000 was all the grinder still needed (T-081's own example). One
		// row, one payment, and amount_inr untouched — which is exactly what makes one receipt right.
		UUID gift = monetaryGift(new BigDecimal("14000"), "Gopal Das");
		admin.update("""
				UPDATE donations SET wishlist_item_id = ?, wishlist_applied_inr = 4000 WHERE id = ?
				""", item, gift);

		issue(gift);
		String html = within(() -> receiptService.render(gift));

		assertThat(html).contains("₹14,000");
		// Neither half of the split reaches the sheet. A donor's accountant reading two figures would
		// have to guess which of them is deductible.
		assertThat(html).doesNotContain("₹4,000");
		assertThat(html).doesNotContain("₹10,000");
		// And it still says what the money was for.
		assertThat(html).contains("A wet grinder");
	}

	@Test
	@DisplayName("a gift with nobody to reach is not an error — it simply cannot be sent")
	void anUnreachableDonorIsNotAFailure() throws Exception {
		UUID gift = plainGift(new BigDecimal("800"));
		admin.update("UPDATE donations SET donor_phone = NULL, donor_email = NULL WHERE id = ?", gift);

		mvc.perform(authed(post("/api/v1/donations/" + gift + "/receipt/send")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sent").value(false));

		// The receipt still exists. Having nobody to send it to does not stop it being issued and
		// handed over at the temple.
		assertThat(receiptNumber(gift)).isNotNull();
	}

	// --- What else this donor has given ---------------------------------

	@Test
	@DisplayName("the history carries the failed, expired and struck gifts the screen hides by default")
	void historyCarriesEverythingTheToggleReveals() throws Exception {
		UUID good = monetaryGift(new BigDecimal("5000"), "Gopal Das");
		UUID failed = monetaryGift(new BigDecimal("1000"), "Gopal Das");
		UUID expired = monetaryGift(new BigDecimal("2000"), "Gopal Das");
		UUID struck = monetaryGift(new BigDecimal("3000"), "Gopal Das");
		admin.update("UPDATE donations SET status = 'FAILED' WHERE id = ?", failed);
		admin.update("UPDATE donations SET status = 'EXPIRED' WHERE id = ?", expired);
		mvc.perform(authed(post("/api/v1/donations/" + struck + "/void"))
						.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Recorded against the wrong donor.\"}"))
				.andExpect(status().isNoContent());

		// All four come back — the server applies no status filter here, unlike the ledger list, so
		// the toggle on the screen has something to reveal and nothing has been hidden in the API.
		// Matched by PAN fingerprint: these four gifts share a PAN and nothing else.
		mvc.perform(authed(get("/api/v1/donations/ledger/donor/" + good)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(4))
				// One of them is struck and says so, which is what the screen shades and strikes
				// through. A row that came back unmarked would be counted in a total it is not in.
				.andExpect(jsonPath("$[?(@.voided == true)]", org.hamcrest.Matchers.hasSize(1)));

		// And the good gifts — what the screen opens showing — are the ones left when you remove
		// them. Asserted against the same payload so the two halves cannot drift.
		mvc.perform(authed(get("/api/v1/donations/ledger/donor/" + good)))
				.andExpect(jsonPath("$[?(@.status == 'COMPLETED' && @.voided == false)]",
						org.hamcrest.Matchers.hasSize(1)));
	}

	@Test
	@DisplayName("reading a donation is VIEW_DONATIONS, and a cook has neither the screen nor the receipt")
	void aCookCannotReadOrReceiptOne() throws Exception {
		UUID gift = monetaryGift(new BigDecimal("5000"), "Gopal Das");
		signIn("uid-staff");

		mvc.perform(authed(get("/api/v1/donations/" + gift))).andExpect(status().isForbidden());
		mvc.perform(authed(post("/api/v1/donations/" + gift + "/receipt")))
				.andExpect(status().isForbidden());
		mvc.perform(authed(post("/api/v1/donations/" + gift + "/receipt/send")))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("issuing a receipt is recorded, and so is the PAN it put on paper")
	void issuingIsAudited() throws Exception {
		UUID gift = monetaryGift(new BigDecimal("5000"), "Gopal Das");
		issue(gift);
		issue(gift);

		// Once, not twice. The number is issued once and re-pressing the button is not a second act.
		assertThat(auditCount("DONATION_RECEIPT_ISSUED")).isEqualTo(1);
		// And the PAN reaching a piece of paper is recorded under the same action an explicit reveal
		// uses — a reader auditing who has seen a donor's PAN should not have to know that a receipt
		// is a second way to see it.
		assertThat(auditCount("DONOR_PAN_VIEWED")).isEqualTo(1);
	}

	// ---------------------------------------------------------------------

	/** Issues the receipt through the API and returns the document it points at. */
	private UUID issue(UUID gift) throws Exception {
		String body = mvc.perform(authed(post("/api/v1/donations/" + gift + "/receipt")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(body.replaceAll(".*\"documentId\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
	}

	private void generate(UUID documentId) {
		TenantContext.set(tenant);
		try {
			generationService.generate(documentId);
		} finally {
			TenantContext.clear();
		}
	}

	private <T> T within(java.util.function.Supplier<T> action) {
		TenantContext.set(tenant);
		try {
			return action.get();
		} finally {
			TenantContext.clear();
		}
	}

	/**
	 * A completed monetary gift from a donor who asked for an 80G receipt.
	 *
	 * <p>Seeded as the superuser rather than driven through checkout: a real gift needs a payment
	 * provider, and what this test is about starts after the money has settled. The PAN ciphertext
	 * and fingerprint are written through the application's own cipher, because a receipt that
	 * prints a PAN has to decrypt one and a hand-made byte array would not decrypt.
	 */
	private UUID monetaryGift(BigDecimal amount, String donorName) {
		DonationDraft draft = new DonationDraft(
				"ONE_TIME", amount, "stub", "order-" + UUID.randomUUID(), "key-" + UUID.randomUUID(),
				null, null,
				new DonorDetails(donorName, "+919812345678", "gopal@example.com",
						"12 Temple Road, Bengaluru", "ABCDE1234F", true));
		UUID id = within(() -> monetaryDonationService.createDonation(draft));
		admin.update("""
				UPDATE donations SET status = 'COMPLETED', payment_mode = 'CARD',
					provider_payment_id = 'pay_abc', amount_inr = ?, donated_on = ? WHERE id = ?
				""", amount, today, id);
		return id;
	}

	/** A completed gift with no PAN and no 80G capture — cash at the gate, in effect. */
	private UUID plainGift(BigDecimal amount) {
		DonationDraft draft = new DonationDraft(
				"ONE_TIME", amount, "stub", "order-" + UUID.randomUUID(), "key-" + UUID.randomUUID(),
				null, null,
				new DonorDetails("Radha Devi", "+919812345679", "radha@example.com", null, null, false));
		UUID id = within(() -> monetaryDonationService.createDonation(draft));
		admin.update("""
				UPDATE donations SET status = 'COMPLETED', payment_mode = 'CASH', amount_inr = ?,
					donated_on = ? WHERE id = ?
				""", amount, today, id);
		return id;
	}

	private String receiptNumber(UUID gift) {
		return admin.queryForObject(
				"SELECT receipt_number FROM donations WHERE id = ?", String.class, gift);
	}

	private String documentStatus(UUID documentId) {
		return admin.queryForObject(
				"SELECT status FROM documents WHERE id = ?", String.class, documentId);
	}

	private int auditCount(String action) {
		Integer count = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = ?", Integer.class, action);
		return count == null ? 0 : count;
	}

	private void insertUser(String uid, String email, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE')
				""", tenant, uid, email, role);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
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
