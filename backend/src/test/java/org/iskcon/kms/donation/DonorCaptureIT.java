package org.iskcon.kms.donation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 80G donor capture (E7-S4): both donor paths store exactly their fields, PAN is stored encrypted and
 * read only by an audited admin, a non-80G tenant refuses the 80G path, and completed 80G donations
 * satisfy the 10BD-shaped query.
 *
 * <p>There were three paths, and the third was anonymous — a donation that kept no personal
 * information at all, which had its own test here proving every donor column came back null. It went
 * on 2026-08-29 with the public form that was the only way to reach it: an online gift is now always
 * a signed-in devotee's, and the temple already holds their name. Anonymity survives only for a gift
 * handed over at the temple and recorded by a member of staff, which is {@code DonationRecorder}'s
 * and is covered by {@code DonationIntakeIT}.
 */
@AutoConfigureMockMvc
@Import(DonorCaptureIT.StubVerifierConfiguration.class)
class DonorCaptureIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private MonetaryDonationService service;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone, is_80g_approved)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata', true)
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-admin', 'Admin', 'admin@example.com', '+919876500001', 'TEMPLE_ADMIN', 'ACTIVE')
				""", tenant);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-staff', 'Staff', 'staff@example.com', '+919876500002', 'KITCHEN_STAFF', 'ACTIVE')
				""", tenant);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM donations");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a named, non-80G donation stores name and contact but no PAN")
	void namedNo80gStoresNoPan() {
		DonorDetails d = new DonorDetails("Radha Devi", "+919812345678", "radha@example.com", null, null, false);
		UUID id = within(() -> service.createDonation(draft(d, "order-named")));
		Map<String, Object> row = donationRow(id);
		assert "Radha Devi".equals(row.get("donor_name"));
		assert row.get("donor_pan_ciphertext") == null : "no PAN without the 80G path";
		assert !(Boolean) row.get("wants_80g");
	}

	@Test
	@DisplayName("an 80G donation stores name, address and an ENCRYPTED PAN, readable only by an audited admin")
	void eightyGStoresEncryptedPan() throws Exception {
		DonorDetails d = new DonorDetails("Gopal Das", "+919812345678", null, "12 Temple Rd, Bengaluru", "ABCDE1234F", true);
		UUID id = within(() -> service.createDonation(draft(d, "order-80g")));

		Map<String, Object> row = donationRow(id);
		assert row.get("donor_pan_ciphertext") != null : "PAN must be stored";
		assert "80G".equals(row.get("section"));
		// Stored bytes are not the plaintext.
		byte[] ct = (byte[]) row.get("donor_pan_ciphertext");
		assert !new String(ct).contains("ABCDE1234F") : "PAN must not be stored in the clear";

		// Admin can read it (decrypted), and the read is audited.
		signIn("uid-admin");
		mvc.perform(authed(get("/api/v1/donations/{id}/pan", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.pan").value("ABCDE1234F"));
		Integer audits = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'DONOR_PAN_VIEWED' AND entity_id = ?",
				Integer.class, id);
		assert audits == 1 : "PAN access must be audited";

		// A non-admin (no VIEW_DONATIONS) cannot read it.
		signIn("uid-staff");
		mvc.perform(authed(get("/api/v1/donations/{id}/pan", id))).andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("a non-80G tenant refuses the 80G path")
	void non80gTenantRefuses80g() {
		admin.update("UPDATE tenants SET is_80g_approved = false WHERE id = ?", tenant);
		DonorDetails d = new DonorDetails("Gopal Das", null, null, "addr", "ABCDE1234F", true);
		try {
			within(() -> service.createDonation(draft(d, "order-x")));
			assert false : "expected 80G to be refused";
		} catch (ApplicationException e) {
			assert e.errorCode() == ErrorCode.DONOR_80G_NOT_AVAILABLE : "got " + e.errorCode();
		}
	}

	@Test
	@DisplayName("completed 80G donations satisfy the 10BD-shaped query")
	void form10bdShape() {
		DonorDetails d = new DonorDetails("Gopal Das", null, null, "12 Temple Rd", "ABCDE1234F", true);
		UUID id = within(() -> service.createDonation(draft(d, "order-10bd")));
		admin.update("UPDATE donations SET status = 'COMPLETED', payment_mode = 'UPI', amount_inr = 1001 WHERE id = ?", id);

		List<Form10bdRow> rows = within(() -> service.form10bdRows());
		assert rows.size() == 1 : "one 80G completed donation expected";
		Form10bdRow r = rows.get(0);
		assert r.donorName().equals("Gopal Das") && r.pan().equals("ABCDE1234F")
				&& r.section().equals("80G") && r.paymentMode().equals("UPI") : "10BD shape incomplete: " + r;
	}

	// ---------------------------------------------------------------------

	private DonationDraft draft(DonorDetails donor, String orderId) {
		return new DonationDraft("ONE_TIME", new BigDecimal("501"), "stub", orderId, orderId, null, null, null, null, donor);
	}

	private <T> T within(java.util.function.Supplier<T> action) {
		TenantContext.set(tenant);
		try {
			return action.get();
		} finally {
			TenantContext.clear();
		}
	}

	private Map<String, Object> donationRow(UUID id) {
		return admin.queryForMap("""
				SELECT is_anonymous, donor_name, donor_phone, donor_email, donor_address,
					   donor_pan_ciphertext, wants_80g, section FROM donations WHERE id = ?
				""", id);
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
