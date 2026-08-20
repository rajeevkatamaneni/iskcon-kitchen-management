package org.iskcon.kms.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Salary, payments, advances and docking (B8), through the full stack.
 *
 * <p>The arithmetic is the reason this is an integration test rather than a unit one. The advance
 * balance is a sum across three tables with a voided-row exclusion in the middle of it, and the
 * figure it produces is the one an administrator reads before deciding what to pay somebody who is
 * leaving. Proving it against a real database is proving the only number this feature computes.
 *
 * <p>The other half is who may see it. A kitchen manager runs the roster and approves leave, and is
 * refused at every door here — checked endpoint by endpoint rather than trusted to the fact that
 * they all carry the same annotation.
 */
@AutoConfigureMockMvc
@Import(StaffPayIT.StubVerifierConfiguration.class)
class StaffPayIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		// Its own slug rather than the register suite's. The container is shared by every test class
		// in the JVM, and a temple named the same as another class's leaves this one failing on a
		// duplicate key whenever that class's own clean-up did not finish.
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda-pay', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-admin', 'Temple Admin', 'admin@example.com', '+919876500001', 'TEMPLE_ADMIN', 'ACTIVE')
				""", tenant);
		signIn("uid-admin");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM staff_payment_deductions");
		admin.execute("DELETE FROM staff_payments");
		admin.execute("DELETE FROM staff_advances");
		admin.execute("DELETE FROM audit_events");
		// A hire now runs the cross-temple ban check (B9), which lands on the platform log, and that
		// log holds a foreign key to the actor. Without this the users below refuse to go.
		admin.execute("DELETE FROM platform_audit_events");
		admin.execute("DELETE FROM staff_schedule_exceptions");
		admin.execute("DELETE FROM staff_schedule_template");
		admin.execute("DELETE FROM staff_profiles");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- Salary ---------------------------------------------------------

	@Test
	@DisplayName("a hire with no agreed pay has a salary of nothing at all, not a salary of zero")
	void salaryIsOptionalAndGenuinelyNull() throws Exception {
		String id = hireId("""
				{"fullName":"Ramesh Kumar","jobTitle":"HOUSEKEEPING","employmentType":"PART_TIME",
				 "dateOfJoining":"2026-03-01"}
				""");

		assertThat(admin.queryForObject(
				"SELECT monthly_salary FROM staff_profiles WHERE id = ?::uuid", BigDecimal.class, id))
				.as("a defaulted zero would make \"no salary recorded\" impossible to say")
				.isNull();

		mvc.perform(authed(get("/api/v1/staff/members/{id}/pay", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.monthlySalary").doesNotExist())
				.andExpect(jsonPath("$.advanceBalance").value(0))
				.andExpect(jsonPath("$.lastSalaryPayment").doesNotExist());
	}

	@Test
	@DisplayName("a salary is recorded on the hire and comes back with the temple's own currency")
	void salaryAndCurrencyAreServed() throws Exception {
		String id = hireId("""
				{"fullName":"Gopal Das","jobTitle":"HEAD_COOK","employmentType":"FULL_TIME",
				 "dateOfJoining":"2026-02-01","monthlySalary":18000}
				""");

		mvc.perform(authed(get("/api/v1/staff/members/{id}/pay", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fullName").value("Gopal Das"))
				.andExpect(jsonPath("$.monthlySalary").value(18000))
				.andExpect(jsonPath("$.currency").value("INR"));
	}

	@Test
	@DisplayName("pay never reaches the roster: the schedule's view of a person carries no salary")
	void salaryStaysOutOfTheScheduleView() throws Exception {
		String id = hireId("""
				{"fullName":"Gopal Das","jobTitle":"HEAD_COOK","employmentType":"FULL_TIME",
				 "dateOfJoining":"2026-02-01","monthlySalary":18000}
				""");
		signInAs("uid-manager", "KITCHEN_MANAGER", "manager@example.com", "+919876500011");

		String body = mvc.perform(authed(get("/api/v1/staff/profiles/{id}", id)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(body)
				.as("the profile view is shared with the roster and with a person's own schedule")
				.doesNotContain("monthlySalary").doesNotContain("18000");
	}

	// ---- Advances and docking -------------------------------------------

	@Test
	@DisplayName("an advance, then a salary payment that docks it, leaves exactly the right balance")
	void dockingReducesTheBalance() throws Exception {
		String id = hireId(gopal());

		String advance = advanceId(id, """
				{"paidOn":"2026-05-10","amount":5000,"mode":"CASH"}
				""");
		mvc.perform(authed(get("/api/v1/staff/members/{id}/pay", id)))
				.andExpect(jsonPath("$.advanceBalance").value(5000));

		mvc.perform(payment(id, """
				{"paidOn":"2026-05-31","amount":18000,"mode":"CHEQUE","reference":"114523","purpose":"SALARY",
				 "deductions":[{"advanceId":"%s","amount":2000}]}
				""".formatted(advance)))
				.andExpect(status().isCreated());

		mvc.perform(authed(get("/api/v1/staff/members/{id}/pay", id)))
				.andExpect(jsonPath("$.advanceBalance").value(3000))
				.andExpect(jsonPath("$.payments[0].gross").value(18000))
				.andExpect(jsonPath("$.payments[0].deducted").value(2000))
				.andExpect(jsonPath("$.payments[0].net").value(16000))
				.andExpect(jsonPath("$.payments[0].modeLabel").value("Cheque"))
				.andExpect(jsonPath("$.lastSalaryPayment.paidOn").value("2026-05-31"))
				.andExpect(jsonPath("$.advances[0].recovered").value(2000))
				.andExpect(jsonPath("$.advances[0].outstanding").value(3000));
	}

	@Test
	@DisplayName("a docked payment can be struck, and doing so hands the advance back")
	void voidingADockedPaymentReturnsTheBalance() throws Exception {
		// Found on live, 2026-08-20: this was refused, and the refusal said to "void the deductions
		// first" — a door that does not exist. A mistyped docked salary was therefore permanent.
		String id = hireId(gopal());
		String advance = advanceId(id, """
				{"paidOn":"2026-05-10","amount":5000,"mode":"CASH"}
				""");
		String payment = recordPayment(id, """
				{"paidOn":"2026-05-31","amount":18000,"mode":"CHEQUE","reference":"114523","purpose":"SALARY",
				 "deductions":[{"advanceId":"%s","amount":2000}]}
				""".formatted(advance));

		mvc.perform(authed(post("/api/v1/staff/members/{id}/payments/{p}/void", id, payment)))
				.andExpect(status().isNoContent());

		// The deduction went with it: nothing was recovered out of money that was never paid.
		mvc.perform(authed(get("/api/v1/staff/members/{id}/pay", id)))
				.andExpect(jsonPath("$.advanceBalance").value(5000))
				.andExpect(jsonPath("$.payments[0].voided").value(true))
				.andExpect(jsonPath("$.lastSalaryPayment").doesNotExist())
				.andExpect(jsonPath("$.advances[0].recovered").value(0))
				.andExpect(jsonPath("$.advances[0].outstanding").value(5000));

		// And the advance, no longer docked by anything that stands, can be struck in its turn.
		mvc.perform(authed(post("/api/v1/staff/members/{id}/advances/{a}/void", id, advance)))
				.andExpect(status().isNoContent());
		mvc.perform(authed(get("/api/v1/staff/members/{id}/pay", id)))
				.andExpect(jsonPath("$.advanceBalance").value(0));
	}

	@Test
	@DisplayName("an advance somebody has actually been docked for cannot be struck")
	void anAdvanceWithALiveRecoveryIsRefused() throws Exception {
		String id = hireId(gopal());
		String advance = advanceId(id, """
				{"paidOn":"2026-05-10","amount":5000,"mode":"CASH"}
				""");
		mvc.perform(payment(id, """
				{"paidOn":"2026-05-31","amount":18000,"mode":"CASH","purpose":"SALARY",
				 "deductions":[{"advanceId":"%s","amount":2000}]}
				""".formatted(advance)))
				.andExpect(status().isCreated());

		// Striking it would make the net of a payment already made stop adding up, so it is refused
		// — and the message names the way out rather than a door that is not there.
		mvc.perform(authed(post("/api/v1/staff/members/{id}/advances/{a}/void", id, advance)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4961"))
				.andExpect(jsonPath("$.action").value(org.hamcrest.Matchers.containsString("Void the payment")));
	}

	@Test
	@DisplayName("several advances and part-recoveries leave the balance the entries add up to")
	void balanceAcrossSeveralAdvances() throws Exception {
		String id = hireId(gopal());

		String may = advanceId(id, """
				{"paidOn":"2026-05-10","amount":5000,"mode":"CASH"}
				""");
		String june = advanceId(id, """
				{"paidOn":"2026-06-02","amount":3000,"mode":"CHEQUE","reference":"221100"}
				""");

		// 2,000 off the May advance, then 1,500 more off May and 1,000 off June.
		recordPayment(id, """
				{"paidOn":"2026-05-31","amount":18000,"mode":"PAYROLL","reference":"PR-2026-05","purpose":"SALARY",
				 "deductions":[{"advanceId":"%s","amount":2000}]}
				""".formatted(may));
		recordPayment(id, """
				{"paidOn":"2026-06-30","amount":18000,"mode":"PAYROLL","reference":"PR-2026-06","purpose":"SALARY",
				 "deductions":[{"advanceId":"%s","amount":1500},{"advanceId":"%s","amount":1000}]}
				""".formatted(may, june));

		// 8,000 given, 4,500 recovered.
		mvc.perform(authed(get("/api/v1/staff/members/{id}/pay", id)))
				.andExpect(jsonPath("$.advanceBalance").value(3500))
				.andExpect(jsonPath("$.lastSalaryPayment.reference").value("PR-2026-06"));
	}

	@Test
	@DisplayName("deductions coming to more than the payment itself are refused")
	void deductionsCannotExceedTheGross() throws Exception {
		String id = hireId(gopal());
		String advance = advanceId(id, """
				{"paidOn":"2026-05-10","amount":9000,"mode":"CASH"}
				""");

		mvc.perform(payment(id, """
				{"paidOn":"2026-05-31","amount":5000,"mode":"CASH","purpose":"SALARY",
				 "deductions":[{"advanceId":"%s","amount":6000}]}
				""".formatted(advance)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4958"));

		// A refused payment leaves nothing behind: the deduction and the payment are one transaction.
		mvc.perform(authed(get("/api/v1/staff/members/{id}/pay", id)))
				.andExpect(jsonPath("$.advanceBalance").value(9000))
				.andExpect(jsonPath("$.payments.length()").value(0));
	}

	@Test
	@DisplayName("recovering more than is left on an advance is refused")
	void deductionCannotExceedTheAdvance() throws Exception {
		String id = hireId(gopal());
		String advance = advanceId(id, """
				{"paidOn":"2026-05-10","amount":5000,"mode":"CASH"}
				""");
		recordPayment(id, """
				{"paidOn":"2026-05-31","amount":18000,"mode":"CASH","purpose":"SALARY",
				 "deductions":[{"advanceId":"%s","amount":4000}]}
				""".formatted(advance));

		mvc.perform(payment(id, """
				{"paidOn":"2026-06-30","amount":18000,"mode":"CASH","purpose":"SALARY",
				 "deductions":[{"advanceId":"%s","amount":1500}]}
				""".formatted(advance)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4959"));
	}

	@Test
	@DisplayName("an advance already recovered in full has nothing left to dock")
	void advanceAlreadyRecovered() throws Exception {
		String id = hireId(gopal());
		String advance = advanceId(id, """
				{"paidOn":"2026-05-10","amount":5000,"mode":"CASH"}
				""");
		recordPayment(id, """
				{"paidOn":"2026-05-31","amount":18000,"mode":"CASH","purpose":"SALARY",
				 "deductions":[{"advanceId":"%s","amount":5000}]}
				""".formatted(advance));

		mvc.perform(payment(id, """
				{"paidOn":"2026-06-30","amount":18000,"mode":"CASH","purpose":"SALARY",
				 "deductions":[{"advanceId":"%s","amount":500}]}
				""".formatted(advance)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4960"));

		mvc.perform(authed(get("/api/v1/staff/members/{id}/pay", id)))
				.andExpect(jsonPath("$.advanceBalance").value(0));
	}

	// ---- The reference ---------------------------------------------------

	@Test
	@DisplayName("a cheque with no cheque number is refused; the same payment in cash is not")
	void chequeNeedsItsReference() throws Exception {
		String id = hireId(gopal());

		mvc.perform(payment(id, """
				{"paidOn":"2026-05-31","amount":18000,"mode":"CHEQUE","purpose":"SALARY"}
				"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4008"));

		mvc.perform(payment(id, """
				{"paidOn":"2026-05-31","amount":18000,"mode":"PAYROLL","purpose":"SALARY"}
				"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4008"));

		mvc.perform(payment(id, """
				{"paidOn":"2026-05-31","amount":18000,"mode":"CASH","purpose":"SALARY"}
				"""))
				.andExpect(status().isCreated());
	}

	@Test
	@DisplayName("a payment of nothing is refused in its own words rather than as a form error")
	void amountMustBePositive() throws Exception {
		String id = hireId(gopal());

		mvc.perform(payment(id, """
				{"paidOn":"2026-05-31","amount":0,"mode":"CASH","purpose":"SALARY"}
				"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4007"));
	}

	// ---- Striking a mistake ---------------------------------------------

	@Test
	@DisplayName("a payment entered wrongly is struck, kept, and left out of every total")
	void voidingAPaymentKeepsTheRow() throws Exception {
		String id = hireId(gopal());
		String payment = recordPayment(id, """
				{"paidOn":"2026-05-31","amount":81000,"mode":"CASH","purpose":"SALARY"}
				""");

		mvc.perform(authed(post("/api/v1/staff/members/{id}/payments/{paymentId}/void", id, payment)))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/staff/members/{id}/pay", id)))
				.andExpect(jsonPath("$.payments.length()").value(1))
				.andExpect(jsonPath("$.payments[0].voidedAt").exists())
				.andExpect(jsonPath("$.lastSalaryPayment").doesNotExist());
	}

	// ---- Who may see any of it ------------------------------------------

	@Test
	@DisplayName("a kitchen manager is refused at every door where pay appears")
	void kitchenManagerSeesNoPay() throws Exception {
		String id = hireId(gopal());
		String advance = advanceId(id, """
				{"paidOn":"2026-05-10","amount":5000,"mode":"CASH"}
				""");
		String payment = recordPayment(id, """
				{"paidOn":"2026-05-31","amount":18000,"mode":"CASH","purpose":"SALARY"}
				""");

		signInAs("uid-manager", "KITCHEN_MANAGER", "manager@example.com", "+919876500011");

		mvc.perform(authed(get("/api/v1/staff/members/{id}/pay", id))).andExpect(status().isForbidden());
		mvc.perform(payment(id, """
				{"paidOn":"2026-06-30","amount":18000,"mode":"CASH","purpose":"SALARY"}
				""")).andExpect(status().isForbidden());
		mvc.perform(advance(id, """
				{"paidOn":"2026-06-30","amount":1000,"mode":"CASH"}
				""")).andExpect(status().isForbidden());
		mvc.perform(authed(post("/api/v1/staff/members/{id}/payments/{paymentId}/void", id, payment)))
				.andExpect(status().isForbidden());
		mvc.perform(authed(post("/api/v1/staff/members/{id}/advances/{advanceId}/void", id, advance)))
				.andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private static String gopal() {
		return """
				{"fullName":"Gopal Das","jobTitle":"HEAD_COOK","employmentType":"FULL_TIME",
				 "dateOfJoining":"2026-02-01","monthlySalary":18000}
				""";
	}

	private MockHttpServletRequestBuilder payment(String staffId, String json) {
		return authed(post("/api/v1/staff/members/{id}/payments", staffId))
				.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder advance(String staffId, String json) {
		return authed(post("/api/v1/staff/members/{id}/advances", staffId))
				.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private String recordPayment(String staffId, String json) throws Exception {
		return idOf(mvc.perform(payment(staffId, json)).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString());
	}

	private String advanceId(String staffId, String json) throws Exception {
		return idOf(mvc.perform(advance(staffId, json)).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString());
	}

	private String hireId(String json) throws Exception {
		return idOf(mvc.perform(authed(post("/api/v1/staff/members"))
						.contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
	}

	private static String idOf(String body) throws Exception {
		return JSON.readTree(body).get("id").asText();
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private void signInAs(String uid, String role, String email, String phone) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Kitchen Manager', ?, ?, ?, 'ACTIVE')
				""", tenant, uid, email, phone, role);
		signIn(uid);
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
