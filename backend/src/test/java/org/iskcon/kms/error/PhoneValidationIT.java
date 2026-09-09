package org.iskcon.kms.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A phone number in the wrong shape gets the message written for it (T-021).
 *
 * <p>{@code KMS-400003} — "That phone number isn't in a format we can use. Include the country
 * code, for example +91 98765 43210." — existed from the first day of the error scheme and had
 * never once been thrown. It could not be: every phone check in the application is a Bean
 * Validation {@code @Pattern} on a request record, and the validation handler stamped
 * {@code KMS-400001} on all of them alike. In an application where the phone number is a way to
 * sign in, the one message that would have helped was the one message unreachable.
 *
 * <p>What this class exists to hold down is the <em>boundary</em>, because the failure mode of the
 * fix is worse than the defect. A handler that reached for KMS-400003 whenever a phone number
 * appeared among the errors would answer a half-empty form by naming one field and hiding four
 * others — so the rule implemented, and asserted here from both sides, is narrower: the specific
 * code is used only when the phone number was the <em>only</em> thing wrong.
 *
 * <p>Two further boundaries are deliberate and are tested rather than left to be rediscovered: a
 * blank phone box is a <em>missing</em> number and not a malformed one, and a kitchen's contact
 * number is not held to E.164 at all, so neither may be answered with advice about country codes.
 */
@AutoConfigureMockMvc
@Import(PhoneValidationIT.StubVerifierConfiguration.class)
class PhoneValidationIT extends AbstractIntegrationTest {

	/** Long enough to fail a length check, and not a phone number by any reading. */
	private static final String TOO_LONG_FOR_A_KITCHEN = "2".repeat(40);

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID temple;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		temple = insertTenant("phone-validation-temple", "Sri Sri Radha Govinda Temple");
		insertUser(temple, "uid-admin", "admin@example.com", "TEMPLE_ADMIN");
		insertSuperAdmin();
		signIn("uid-admin");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		// Every request in this class is meant to be refused before it reaches a service, so in
		// principle nothing is ever written. A DELETE FROM recurring_plans stood here for the case
		// that suspending the rule under a negative control would let a rubbish number through, a
		// mandate row would appear, and its foreign key would hold the tenant down — turning a
		// clean test failure into a cascade of setUp errors that said nothing about the defect.
		// T-111 dropped that table with the rest of recurring giving, and no remaining path in this
		// class can write a row that outlives its own request.
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ------------------------------------------------------------------
	// The code reaches the reader, on every path that holds a number to E.164.
	// ------------------------------------------------------------------

	@Test
	@DisplayName("a malformed number on a temple's provisioning form is answered KMS-400003")
	void provisioningATemple() throws Exception {
		signIn("uid-super");

		// Everything else on this form is valid, which is the whole point: the phone number is the
		// only thing wrong, so naming it tells the reader the truth about their form.
		expectPhoneCode(post("/api/v1/tenants").content("""
				{"name":"Sri Sri Radha Krishna Temple","slug":"radha-krishna",
				 "address":"Bengaluru, Karnataka","latitude":12.9716,"longitude":77.5946,
				 "timezone":"Asia/Kolkata","currency":"INR","is80gApproved":true,
				 "adminName":"Gopal Das","adminEmail":"gopal@example.com","adminPhone":"98765"}
				"""));
	}

	@Test
	@DisplayName("a malformed number on joining a temple is answered KMS-400003")
	void joiningATemple() throws Exception {
		expectPhoneCode(post("/api/v1/temples/{id}/join", temple).content("""
				{"firstName":"Nitai","lastName":"Das","phone":"98765"}
				"""));
	}

	@Test
	@DisplayName("a malformed number on either vendor form is answered KMS-400003")
	void addingAndEditingAVendor() throws Exception {
		expectPhoneCode(post("/api/v1/vendors").content("""
				{"name":"Govind Wholesale","phone":"98765"}
				"""));

		// The id need not name a real vendor: the body is validated before the handler that would
		// look it up ever runs, which is precisely why the edit form gets the same answer.
		expectPhoneCode(put("/api/v1/vendors/{id}", UUID.randomUUID()).content("""
				{"name":"Govind Wholesale","phone":"98765"}
				"""));
	}

	@Test
	@DisplayName("a malformed number on a hire is answered KMS-400003, whichever of its two numbers")
	void hiringSomeone() throws Exception {
		expectPhoneCode(post("/api/v1/staff/members").content("""
				{"fullName":"Lakshmi Devi","phone":"98765","jobTitle":"COOK",
				 "employmentType":"FULL_TIME","dateOfJoining":"2026-01-10"}
				"""));

		// The emergency contact is a phone number like any other, and a hire that fails on it alone
		// is still a failure purely about a phone number.
		expectPhoneCode(post("/api/v1/staff/members").content("""
				{"fullName":"Lakshmi Devi","phone":"+919876500062","jobTitle":"COOK",
				 "employmentType":"FULL_TIME","dateOfJoining":"2026-01-10",
				 "emergencyContactName":"Gopal Das","emergencyContactPhone":"98765"}
				"""));

		// Both wrong at once is still one kind of wrong. The field list travels with the response
		// to say which, so the code stays the specific one.
		mvc.perform(authed(post("/api/v1/staff/members")).contentType(MediaType.APPLICATION_JSON)
						.content("""
						{"fullName":"Lakshmi Devi","phone":"98765","jobTitle":"COOK",
						 "employmentType":"FULL_TIME","dateOfJoining":"2026-01-10",
						 "emergencyContactPhone":"98765"}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400003"))
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='phone')]").exists())
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='emergencyContactPhone')]").exists());
	}

	// A fifth case stood here, at the end of the group above: a malformed number on a recurring
	// donation, POSTed to /api/v1/donations/recurring. It was the sharpest of the five — the other
	// four merely mis-reported the failure, while that path had no format check at all before T-021,
	// so "98450" was accepted outright and stored against a mandate that renews for years.
	//
	// It went on 2026-09-10 with T-111, which moved recurring donations out of Phase 1 whole: the
	// endpoint, the service, CreateRecurringRequest and the recurring_plans table are all gone, so
	// there is no longer a request that could carry the number. THIS IS NOT A GAP IN T-021's
	// COVERAGE, and it should not be read as one. The rule T-021 proved is a property of the
	// validation handler, not of any one endpoint: the four paths above still show the specific code
	// reaching a reader on every surface that holds a number to E.164, and the four below still hold
	// the boundary that makes it safe — a form with a second fault keeps the general code, a blank
	// box is a missing number rather than a malformed one, a kitchen's extension is not E.164 at all,
	// and a @Pattern that is not the phone rule is untouched. When recurring giving is built in
	// Phase 2, its request record needs the same @Pattern and this case comes back with it.

	// ------------------------------------------------------------------
	// And does not overreach, which is the part that would have been expensive.
	// ------------------------------------------------------------------

	@Test
	@DisplayName("a form wrong about a phone number AND something else stays KMS-400001, with both")
	void aFormWithMoreThanOneFaultKeepsTheGeneralCode() throws Exception {
		// The acceptance criterion that defines the boundary. A vendor with no name and a bad
		// number has two problems, and an error code that names only the number would send the
		// reader back to a form that fails again for the reason they were never told about.
		mvc.perform(authed(post("/api/v1/vendors")).contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"\",\"phone\":\"98765\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='name')]").exists())
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='phone')]").exists());
	}

	@Test
	@DisplayName("an empty phone box is a missing number, not a malformed one")
	void aBlankNumberIsNotAMalformedNumber() throws Exception {
		// Joining a temple requires a number, so a blank box trips @NotBlank as well as the format
		// rule. "That phone number isn't in a format we can use" is the wrong thing to tell
		// somebody who typed nothing — "Enter a phone number." already exists and is right. The
		// carve-out keys on the format constraint rather than on the field's name so that this
		// distinction survives.
		mvc.perform(authed(post("/api/v1/temples/{id}/join", temple))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"firstName\":\"Nitai\",\"lastName\":\"Das\",\"phone\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));
	}

	@Test
	@DisplayName("a kitchen's contact number is not held to E.164, so it is never told about country codes")
	void aKitchensNumberIsADifferentKindOfNumber() throws Exception {
		// A kitchen's number is dialled by somebody standing in the temple and an internal
		// extension is a legitimate answer, so the field carries no E.164 rule — see
		// CreateKitchenRequest, where the absence is deliberate and explained. It follows that a
		// bad one here must NOT be answered "include the country code", which would be false
		// advice on a field where "204" is correct. What is refused is only over-length.
		mvc.perform(authed(post("/api/v1/kitchens")).contentType(MediaType.APPLICATION_JSON)
						.content(kitchenBody(TOO_LONG_FOR_A_KITCHEN)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));

		mvc.perform(authed(put("/api/v1/kitchens/{id}", UUID.randomUUID()))
						.contentType(MediaType.APPLICATION_JSON)
						.content(kitchenBody(TOO_LONG_FOR_A_KITCHEN)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));
	}

	@Test
	@DisplayName("a pattern that is not the phone rule keeps the general code")
	void anotherPatternedFieldIsUnaffected() throws Exception {
		signIn("uid-super");

		// A temple's web address is @Pattern-checked too, and so is its currency. Keying the
		// carve-out on the annotation alone would have handed both of them advice about country
		// codes; it is keyed on the rule itself, so they are untouched.
		mvc.perform(authed(post("/api/v1/tenants")).contentType(MediaType.APPLICATION_JSON)
						.content("""
						{"name":"Sri Sri Radha Krishna Temple","slug":"Radha Krishna!",
						 "address":"Bengaluru, Karnataka","latitude":12.9716,"longitude":77.5946,
						 "timezone":"Asia/Kolkata","currency":"INR","is80gApproved":true,
						 "adminName":"Gopal Das","adminEmail":"gopal@example.com",
						 "adminPhone":"+919876500062"}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='slug')]").exists());
	}

	// ---------------------------------------------------------------------

	/**
	 * The assertion this class is mostly made of: this request fails, it fails with the code
	 * written for a phone number, and the words the user reads are the ones from that code.
	 */
	private void expectPhoneCode(MockHttpServletRequestBuilder request) throws Exception {
		mvc.perform(authed(request).contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400003"))
				.andExpect(jsonPath("$.action").value("Include the country code, for example +91 98765 43210."));
	}

	private String kitchenBody(String contactPhone) {
		return ("{\"name\":\"Deity kitchen\",\"description\":\"Cooks for the Deities.\","
				+ "\"location\":\"Behind the Deity hall\",\"isMain\":false,"
				+ "\"usesMealPlanner\":false,\"inChargeUserId\":null,\"contactPhone\":\"%s\"}")
				.formatted(contactPhone);
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

	private UUID insertUser(UUID tenantId, String uid, String email, String role) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE') RETURNING id
				""", UUID.class, tenantId, uid, email, role);
	}

	/** Provisioning a temple is the platform operator's act, and belongs to no tenant. */
	private void insertSuperAdmin() {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (NULL, 'uid-super', 'Platform Operator', 'super@example.com',
						'+919000000001', 'SUPER_ADMIN', 'ACTIVE')
				""");
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
