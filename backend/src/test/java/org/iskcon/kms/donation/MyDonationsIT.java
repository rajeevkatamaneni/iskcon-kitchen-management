package org.iskcon.kms.donation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.document.DocumentGenerationService;
import org.iskcon.kms.notification.NotificationService;
import org.iskcon.kms.security.PanCipher;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A devotee's own gifts and receipts (T-179), through the real endpoints, as the unprivileged app
 * role, against a real database under RLS.
 *
 * <p><strong>Privacy is the point of every test here.</strong> A receipt for an 80G gift prints the
 * donor's PAN, so a matching rule that is one clause too generous hands a stranger somebody else's
 * tax document. So nearly every test below is an absence, and each absence is paired with a presence
 * that proves the gift <em>could</em> have been listed — otherwise a page that listed nothing at all
 * would pass the privacy tests perfectly.
 *
 * <p>The caller is Gopal, a volunteer at Radha Govinda. His token proves the phone
 * {@code +919876543210} and, in the ordinary case, the verified email {@code Gopal@Example.com}
 * (mixed case on purpose). His {@code users} row carries a <em>different</em> phone and email, typed
 * by an administrator, so that a test can show the row's contacts prove nothing.
 */
@AutoConfigureMockMvc
@Import(MyDonationsIT.StubVerifierConfiguration.class)
class MyDonationsIT extends AbstractIntegrationTest {

	private static final String VERIFIED_PHONE = "+919876543210";
	private static final String VERIFIED_EMAIL = "gopal@example.com";

	/** Gopal's token: phone proven by OTP, email verified. */
	private static final String GOPAL = "token-gopal";
	/** Gopal's token when Firebase has not verified the email, and no phone sign-in. */
	private static final String GOPAL_UNVERIFIED = "token-gopal-unverified";
	private static final String ADMIN = "token-admin";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper json;

	@Autowired
	private DocumentGenerationService generationService;

	@Autowired
	private PanCipher panCipher;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private NotificationService notificationService;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID temple;
	private UUID otherTemple;
	private UUID gopal;
	private UUID gopalAtOtherTemple;
	private UUID templeAdmin;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();

		temple = tenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		otherTemple = tenant("jagannath", "Sri Jagannath Temple");

		// The users row's contacts are deliberately not the token's. See unverifiedRowContacts.
		gopal = user(temple, "uid-gopal", "Gopal Das", "office-typed@example.com", "+919000000001", "VOLUNTEER");
		templeAdmin = user(temple, "uid-admin", "Temple Admin", "admin@example.com", "+919000000002", "TEMPLE_ADMIN");
		gopalAtOtherTemple = user(otherTemple, "uid-gopal", "Gopal Das", "office-typed@example.com",
				"+919000000001", "VOLUNTEER");

		stubVerifier.accept(GOPAL, new TokenVerifier.VerifiedSubject(
				"uid-gopal", "Gopal@Example.com", VERIFIED_PHONE, true));
		stubVerifier.accept(GOPAL_UNVERIFIED, new TokenVerifier.VerifiedSubject(
				"uid-gopal", VERIFIED_EMAIL, null, false));
		stubVerifier.accept(ADMIN, new TokenVerifier.VerifiedSubject(
				"uid-admin", "admin@example.com", null, true));
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM documents");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM equipment_items");
		admin.execute("DELETE FROM donations");
		admin.execute("DELETE FROM donation_receipt_sequence");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// --- What is listed -------------------------------------------------

	@Test
	@DisplayName("lists a gift from the caller's account, and counter gifts on their verified phone or email")
	void listsOwnGifts() throws Exception {
		UUID online = gift(temple, g -> {
			g.put("donor_account_user_id", gopal);
			g.put("provider", "razorpay");
			g.put("amount_inr", new BigDecimal("5000"));
			g.put("donated_on", LocalDate.of(2026, 9, 10));
		});
		// Typed at the counter with T-157's spacing. Identical to the verified number once the spaces go.
		UUID byPhone = gift(temple, g -> {
			g.put("donor_phone", "+91 98765 43210");
			g.put("amount_inr", new BigDecimal("1200"));
			g.put("donated_on", LocalDate.of(2026, 9, 1));
		});
		// Case differs from the token's "Gopal@Example.com". Email is compared without case.
		UUID byEmail = gift(temple, g -> {
			g.put("type", "IN_KIND");
			g.put("amount_inr", null);
			g.put("donor_email", "GOPAL@example.COM");
			g.put("donated_on", LocalDate.of(2026, 8, 15));
		});
		admin.update("""
				INSERT INTO equipment_items (tenant_id, name, donation_id) VALUES (?, 'Wet grinder', ?)
				""", temple, byEmail);
		UUID rice = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Rice', 'Grains', 'KG') RETURNING id
				""", UUID.class, temple);
		admin.update("""
				INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit,
						movement_type, reference_type, reference_id, actor_user_id)
				VALUES (?, ?, gen_random_uuid(), 25.000, 'KG', 'DONATION_IN_KIND', 'DONATION', ?, ?)
				""", temple, rice, byEmail, templeAdmin);

		mvc.perform(as(GOPAL, get("/api/v1/my-donations")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(3)))
				// Newest first.
				.andExpect(jsonPath("$[0].id").value(online.toString()))
				.andExpect(jsonPath("$[0].kind").value("MONEY"))
				.andExpect(jsonPath("$[0].receivedOn").value("2026-09-10"))
				.andExpect(jsonPath("$[0].amount").value(5000.0))
				.andExpect(jsonPath("$[0].description").value("Online donation"))
				.andExpect(jsonPath("$[0].receiptNumber").isEmpty())
				.andExpect(jsonPath("$[1].id").value(byPhone.toString()))
				.andExpect(jsonPath("$[1].description").value("Given at the temple"))
				.andExpect(jsonPath("$[2].id").value(byEmail.toString()))
				.andExpect(jsonPath("$[2].kind").value("GOODS"))
				.andExpect(jsonPath("$[2].amount").isEmpty())
				.andExpect(jsonPath("$[2].description").value("Rice, 25 Kg; Wet grinder"));

		// Exactly the six fields the client type declares, and no contact, name or PAN among them.
		// Keys are inspected rather than asserted absent one by one, because a new PII field added
		// later would pass a list of named absences.
		JsonNode first = json.readTree(body(as(GOPAL, get("/api/v1/my-donations")))).get(0);
		List<String> keys = new ArrayList<>();
		first.fieldNames().forEachRemaining(keys::add);
		assertThat(keys).containsExactlyInAnyOrder(
				"id", "kind", "receivedOn", "amount", "description", "receiptNumber");
	}

	@Test
	@DisplayName("downloads the caller's own receipt; a gift with no receipt has no number and no download")
	void downloadsOwnReceipt() throws Exception {
		UUID receipted = gift(temple, g -> g.put("donor_account_user_id", gopal));
		UUID notReceipted = gift(temple, g -> g.put("donor_phone", VERIFIED_PHONE));
		String number = issueAndGenerate(receipted);

		mvc.perform(as(GOPAL, get("/api/v1/my-donations")))
				.andExpect(jsonPath("$[?(@.id == '" + receipted + "')].receiptNumber").value(number))
				.andExpect(jsonPath("$[?(@.id == '" + notReceipted + "')].receiptNumber").value(
						org.hamcrest.Matchers.contains((Object) null)));

		byte[] pdf = mvc.perform(as(GOPAL, get("/api/v1/my-donations/" + receipted + "/receipt/download")))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Disposition", "attachment; filename=\"receipt-" + number + ".pdf\""))
				.andReturn().getResponse().getContentAsByteArray();
		assertThat(new String(pdf)).startsWith("%PDF");

		refused(as(GOPAL, get("/api/v1/my-donations/" + notReceipted + "/receipt/download")));
	}

	// --- What is never listed -------------------------------------------

	@Test
	@DisplayName("a gift carrying someone else's phone or email is never listed, and its receipt is refused as not found")
	void strangersGiftsAreInvisible() throws Exception {
		UUID theirs = gift(temple, g -> {
			g.put("donor_name", "Radha Devi");
			g.put("donor_phone", "+919812345679");
			g.put("donor_email", "radha@example.com");
		});
		// A near miss on the phone: the same last ten digits under a different country code. Only an
		// exact E.164 comparison refuses this; "compare the last ten digits" would list it.
		UUID nearMiss = gift(temple, g -> g.put("donor_phone", "+449876543210"));
		// And the same national number with no country code, as a counter might type it. Not matched:
		// assuming +91 is how a receipt reaches the wrong phone.
		UUID noCountryCode = gift(temple, g -> g.put("donor_phone", "98765 43210"));
		String number = issueAndGenerate(theirs);
		UUID mine = gift(temple, g -> g.put("donor_phone", VERIFIED_PHONE));

		mvc.perform(as(GOPAL, get("/api/v1/my-donations")))
				.andExpect(jsonPath("$[*].id").value(containsInAnyOrder(mine.toString())));
		assertThat(number).isNotNull();

		// The answer a stranger gets is exactly the answer for a gift id that never existed.
		String strangerBody = refused(as(GOPAL, get("/api/v1/my-donations/" + theirs + "/receipt/download")));
		String nobodyBody = refused(as(GOPAL, get("/api/v1/my-donations/" + UUID.randomUUID() + "/receipt/download")));
		assertThat(code(strangerBody)).isEqualTo(code(nobodyBody)).isEqualTo("KMS-400030");
		assertThat(message(strangerBody)).isEqualTo(message(nobodyBody));
		refused(as(GOPAL, get("/api/v1/my-donations/" + nearMiss + "/receipt/download")));
		refused(as(GOPAL, get("/api/v1/my-donations/" + noCountryCode + "/receipt/download")));
	}

	// Two tests rather than one, so that a leak in the list and a leak in the download each fail on
	// their own. As a single test the listing assertion failed first and the download was never reached,
	// which left the more dangerous half (the PDF itself) unproven by the negative control.

	@Test
	@DisplayName("a gift carrying the caller's email is never listed when Firebase has not verified that email")
	void unverifiedEmailNeverListed() throws Exception {
		UUID emailGift = gift(temple, g -> g.put("donor_email", VERIFIED_EMAIL));
		// Present so the list is not empty for a reason unrelated to email: account gifts still show.
		UUID accountGift = gift(temple, g -> g.put("donor_account_user_id", gopal));

		mvc.perform(as(GOPAL_UNVERIFIED, get("/api/v1/my-donations")))
				.andExpect(jsonPath("$[*].id").value(containsInAnyOrder(accountGift.toString())));

		// The same gift, the same person, with the email verified: listed. This is what makes the
		// absence above a statement about verification rather than about the gift.
		mvc.perform(as(GOPAL, get("/api/v1/my-donations")))
				.andExpect(jsonPath("$[*].id").value(containsInAnyOrder(accountGift.toString(), emailGift.toString())));
	}

	@Test
	@DisplayName("the receipt for a gift carrying the caller's email is refused when that email is unverified")
	void unverifiedEmailReceiptRefused() throws Exception {
		UUID emailGift = gift(temple, g -> g.put("donor_email", VERIFIED_EMAIL));
		issueAndGenerate(emailGift);

		refused(as(GOPAL_UNVERIFIED, get("/api/v1/my-donations/" + emailGift + "/receipt/download")));

		// Verified, the same receipt downloads, so the refusal above is about verification alone.
		mvc.perform(as(GOPAL, get("/api/v1/my-donations/" + emailGift + "/receipt/download")))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("the phone and email on the caller's users row are not proof, and match nothing")
	void usersRowContactsAreNotProof() throws Exception {
		UUID rowPhone = gift(temple, g -> g.put("donor_phone", "+919000000001"));
		UUID rowEmail = gift(temple, g -> g.put("donor_email", "office-typed@example.com"));

		mvc.perform(as(GOPAL, get("/api/v1/my-donations")))
				.andExpect(jsonPath("$", hasSize(0)));
		refused(as(GOPAL, get("/api/v1/my-donations/" + rowPhone + "/receipt/download")));
		refused(as(GOPAL, get("/api/v1/my-donations/" + rowEmail + "/receipt/download")));
	}

	@Test
	@DisplayName("a voided, anonymous, pending, failed or expired gift is never listed")
	void onlySuccessfulGifts() throws Exception {
		UUID voided = gift(temple, g -> {
			g.put("donor_account_user_id", gopal);
			g.put("voided_at", java.sql.Timestamp.valueOf("2026-09-11 10:00:00"));
			g.put("voided_by", templeAdmin);
			g.put("void_reason", "Entered twice");
		});
		// Anonymous keeps no contact (V38's CHECK), but an online gift keeps its account id.
		UUID anonymous = gift(temple, g -> {
			g.put("donor_account_user_id", gopal);
			g.put("is_anonymous", true);
			g.put("donor_name", null);
		});
		UUID pending = gift(temple, g -> { g.put("donor_phone", VERIFIED_PHONE); g.put("status", "PENDING"); });
		UUID failed = gift(temple, g -> { g.put("donor_phone", VERIFIED_PHONE); g.put("status", "FAILED"); });
		UUID expired = gift(temple, g -> { g.put("donor_email", VERIFIED_EMAIL); g.put("status", "EXPIRED"); });
		UUID completed = gift(temple, g -> g.put("donor_account_user_id", gopal));
		// Receipted before it was struck, which is the realistic order: the number stays on the row.
		admin.update("UPDATE donations SET receipt_number = 'R-2026-0099', receipt_issued_at = now() WHERE id = ?", voided);

		mvc.perform(as(GOPAL, get("/api/v1/my-donations")))
				.andExpect(jsonPath("$[*].id").value(containsInAnyOrder(completed.toString())));
		for (UUID hidden : List.of(voided, anonymous, pending, failed, expired)) {
			refused(as(GOPAL, get("/api/v1/my-donations/" + hidden + "/receipt/download")));
		}
	}

	@Test
	@DisplayName("a second temple's gift is never shown, even on a verified contact and the same person")
	void secondTempleIsInvisible() throws Exception {
		UUID there = gift(otherTemple, g -> {
			g.put("donor_phone", VERIFIED_PHONE);
			g.put("donor_account_user_id", gopalAtOtherTemple);
			g.put("recorded_by", null);
		});
		admin.update("UPDATE donations SET receipt_number = 'R-2026-0001', receipt_issued_at = now() WHERE id = ?", there);
		UUID here = gift(temple, g -> g.put("donor_phone", VERIFIED_PHONE));

		// Speaking for Radha Govinda: only Radha Govinda's gift.
		mvc.perform(as(GOPAL, get("/api/v1/my-donations")).header("X-KMS-Temple", temple.toString()))
				.andExpect(jsonPath("$[*].id").value(containsInAnyOrder(here.toString())));
		refused(as(GOPAL, get("/api/v1/my-donations/" + there + "/receipt/download"))
				.header("X-KMS-Temple", temple.toString()));

		// Speaking for Jagannath, where he is also a volunteer: that temple's gift and not this one.
		// Without this half, a query that returned nothing at all would pass the half above.
		mvc.perform(as(GOPAL, get("/api/v1/my-donations")).header("X-KMS-Temple", otherTemple.toString()))
				.andExpect(jsonPath("$[*].id").value(containsInAnyOrder(there.toString())));
	}

	@Test
	@DisplayName("a gift with the caller's name and PAN but no matching contact is never listed")
	void nameAndPanNeverMatch() throws Exception {
		String pan = "ABCDE1234F";
		// His own account gift, carrying his name and PAN. Listed.
		UUID own = gift(temple, g -> {
			g.put("donor_account_user_id", gopal);
			g.put("donor_name", "Gopal Das");
			g.put("donor_address", "12 Temple Road, Bengaluru");
			g.put("donor_pan_ciphertext", panCipher.encrypt(pan));
			g.put("pan_fingerprint", panCipher.fingerprint(pan));
			g.put("wants_80g", true);
		});
		// The same name and the same PAN, recorded against somebody else's phone. The admin's donor
		// history would link these two by PAN fingerprint; this page must not.
		UUID sameNameAndPan = gift(temple, g -> {
			g.put("donor_name", "Gopal Das");
			g.put("donor_phone", "+919812345600");
			g.put("donor_address", "12 Temple Road, Bengaluru");
			g.put("donor_pan_ciphertext", panCipher.encrypt(pan));
			g.put("pan_fingerprint", panCipher.fingerprint(pan));
			g.put("wants_80g", true);
		});
		issueAndGenerate(sameNameAndPan);
		// The two really do share a fingerprint, so the absence below is a refusal to use it.
		assertThat(admin.queryForObject(
				"SELECT count(DISTINCT pan_fingerprint) FROM donations WHERE id IN (?, ?)", Integer.class,
				own, sameNameAndPan)).isEqualTo(1);

		mvc.perform(as(GOPAL, get("/api/v1/my-donations")))
				.andExpect(jsonPath("$[*].id").value(containsInAnyOrder(own.toString())));
		refused(as(GOPAL, get("/api/v1/my-donations/" + sameNameAndPan + "/receipt/download")));
	}

	@Test
	@DisplayName("a Temple Admin, who holds no VIEW_OWN_DONATIONS, is refused both endpoints with 403")
	void adminIsForbidden() throws Exception {
		UUID gift = gift(temple, g -> g.put("donor_account_user_id", templeAdmin));
		mvc.perform(as(ADMIN, get("/api/v1/my-donations"))).andExpect(status().isForbidden());
		mvc.perform(as(ADMIN, get("/api/v1/my-donations/" + gift + "/receipt/download")))
				.andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private UUID tenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, address, latitude, longitude, timezone, is_80g_approved)
				VALUES (?, ?, '12 Temple Road, Bengaluru', 12.9716, 77.5946, 'Asia/Kolkata', true)
				RETURNING id
				""", UUID.class, slug, name);
	}

	private UUID user(UUID tenantId, String uid, String name, String email, String phone, String role) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE') RETURNING id
				""", UUID.class, tenantId, uid, name, email, phone, role);
	}

	/**
	 * A completed cash gift at the given temple from a named donor with no contact, which each test
	 * then bends into the case it is about. Seeded as the superuser: the rows under test are the
	 * input, and what is being proved is what the app role then sees through the endpoints.
	 */
	private UUID gift(UUID tenantId, java.util.function.Consumer<Map<String, Object>> shape) {
		Map<String, Object> g = new HashMap<>();
		g.put("type", "ONE_TIME");
		g.put("status", "COMPLETED");
		g.put("donor_name", "A Devotee");
		g.put("donor_phone", null);
		g.put("donor_email", null);
		g.put("donor_address", null);
		g.put("donor_pan_ciphertext", null);
		g.put("pan_fingerprint", null);
		g.put("wants_80g", false);
		g.put("is_anonymous", false);
		g.put("amount_inr", new BigDecimal("1000"));
		g.put("payment_mode", "CASH");
		g.put("provider", null);
		g.put("donated_on", LocalDate.of(2026, 9, 5));
		g.put("donor_account_user_id", null);
		g.put("recorded_by", tenantId.equals(temple) ? templeAdmin : null);
		g.put("voided_at", null);
		g.put("voided_by", null);
		g.put("void_reason", null);
		shape.accept(g);
		List<String> columns = new ArrayList<>(g.keySet());
		String sql = "INSERT INTO donations (tenant_id, " + String.join(", ", columns) + ") VALUES (?"
				+ ", ?".repeat(columns.size()) + ") RETURNING id";
		List<Object> values = new ArrayList<>();
		values.add(tenantId);
		columns.forEach(c -> values.add(g.get(c)));
		return admin.queryForObject(sql, UUID.class, values.toArray());
	}

	/** Issues the receipt through the admin's own endpoint and renders it, as the office would. */
	private String issueAndGenerate(UUID gift) throws Exception {
		String response = body(as(ADMIN, post("/api/v1/donations/" + gift + "/receipt")));
		UUID documentId = UUID.fromString(json.readTree(response).get("documentId").asText());
		TenantContext.set(temple);
		try {
			generationService.generate(documentId);
		} finally {
			TenantContext.clear();
		}
		assertThat(admin.queryForObject("SELECT status FROM documents WHERE id = ?", String.class, documentId))
				.isEqualTo("READY");
		return admin.queryForObject("SELECT receipt_number FROM donations WHERE id = ?", String.class, gift);
	}

	/** Asserts the refusal every non-owner download gets, and returns its body. */
	private String refused(MockHttpServletRequestBuilder request) throws Exception {
		ResultActions result = mvc.perform(request).andExpect(status().isNotFound());
		String response = result.andReturn().getResponse().getContentAsString();
		assertThat(code(response)).isEqualTo("KMS-400030");
		return response;
	}

	private String code(String body) throws Exception {
		return json.readTree(body).path("code").asText();
	}

	private String message(String body) throws Exception {
		return json.readTree(body).path("message").asText();
	}

	private String body(MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer " + token);
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

		void accept(String token, VerifiedSubject subject) {
			accepted.put(token, subject);
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
