package org.iskcon.kms.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.tenancy.TenantAwareDataSource;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Where somebody worked before this temple (T-428), through the whole stack.
 *
 * <p>What is worth proving:
 *
 * <ul>
 *   <li><b>That the list replaces.</b> The edit screen shows every past job at once and sends them
 *       all, so a job deleted on the screen has to be gone from the record — not merged back in.
 *   <li><b>That an absent list is not an empty one.</b> Something updating a phone number without
 *       knowing this field exists must not erase a work history on the way past. The same rule
 *       {@code pan} has, and the one that makes this safe for older clients.
 *   <li><b>That a bad date takes the whole save with it.</b> One Save is one edit; the admin must
 *       never find the phone number changed and the jobs not.
 *   <li><b>That RLS keeps one temple's out of another's</b>, proved at the database as the
 *       unprivileged application role rather than by asking the service.
 * </ul>
 */
@AutoConfigureMockMvc
class StaffPreviousEmploymentIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID otherTenant;
	private UUID kitchen;


	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		tenant = temple("radha-govinda-prev", "Bengaluru Temple");
		otherTenant = temple("jagannath-prev", "Mysuru Temple");
		insertUser(tenant, "uid-prev-admin", "Temple Admin", "prev-admin@example.com",
				"+919876540001", "TEMPLE_ADMIN");
		insertUser(otherTenant, "uid-prev-admin-b", "Other Admin", "prev-admin-b@example.com",
				"+919876540002", "TEMPLE_ADMIN");
		kitchen = insertKitchen(tenant);
		insertKitchen(otherTenant);
		signIn("uid-prev-admin");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM staff_previous_employment");
		admin.execute("DELETE FROM staff_documents");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM platform_audit_events");
		admin.execute("DELETE FROM staff_schedule_exceptions");
		admin.execute("DELETE FROM staff_schedule_template");
		admin.execute("DELETE FROM staff_profiles");
		admin.execute("DELETE FROM kitchens");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("all seven fields are kept, in the order they were entered")
	void allSevenFields() throws Exception {
		String staff = hire("Gopal Das");

		mvc.perform(update(staff, """
				"previousEmployment":[
				  {"employer":"Adyar Ananda Bhavan","theirTitle":"Tandoor Assistant",
				   "managerName":"Suresh Kumar","managerPhone":"+919845012345",
				   "fromDate":"2019-03-01","toDate":"2023-07-31","reasonForLeaving":"The branch closed."},
				  {"employer":"Sri Krishna Caterers","theirTitle":"Cook"}
				]"""))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/staff/members/{id}", staff)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.previousEmployment.length()").value(2))
				.andExpect(jsonPath("$.previousEmployment[0].employer").value("Adyar Ananda Bhavan"))
				.andExpect(jsonPath("$.previousEmployment[0].theirTitle").value("Tandoor Assistant"))
				.andExpect(jsonPath("$.previousEmployment[0].managerName").value("Suresh Kumar"))
				.andExpect(jsonPath("$.previousEmployment[0].managerPhone").value("+919845012345"))
				.andExpect(jsonPath("$.previousEmployment[0].fromDate").value("2019-03-01"))
				.andExpect(jsonPath("$.previousEmployment[0].toDate").value("2023-07-31"))
				.andExpect(jsonPath("$.previousEmployment[0].reasonForLeaving").value("The branch closed."))
				// Second, and with everything but the employer and the title missing, because that is
				// what an interview actually produces.
				.andExpect(jsonPath("$.previousEmployment[1].employer").value("Sri Krishna Caterers"))
				.andExpect(jsonPath("$.previousEmployment[1].managerPhone").isEmpty())
				.andExpect(jsonPath("$.previousEmployment[1].fromDate").isEmpty());
	}

	@Test
	@DisplayName("the list replaces: a job deleted on the screen is gone from the record")
	void theListReplaces() throws Exception {
		String staff = hire("Gopal Das");

		mvc.perform(update(staff, """
				"previousEmployment":[{"employer":"One"},{"employer":"Two"},{"employer":"Three"}]"""))
				.andExpect(status().isNoContent());
		mvc.perform(update(staff, """
				"previousEmployment":[{"employer":"One"},{"employer":"Three"}]"""))
				.andExpect(status().isNoContent());

		assertThat(employers(staff)).containsExactly("One", "Three");
	}

	@Test
	@DisplayName("an empty list clears them; leaving the field out does not")
	void emptyIsNotAbsent() throws Exception {
		String staff = hire("Gopal Das");
		mvc.perform(update(staff, """
				"previousEmployment":[{"employer":"Adyar Ananda Bhavan"}]"""))
				.andExpect(status().isNoContent());

		// An update that says nothing about past jobs — an older client, or a script fixing a phone
		// number. It must not erase somebody's work history on the way past.
		mvc.perform(update(staff, "\"phone\":\"+919876540099\""))
				.andExpect(status().isNoContent());
		assertThat(employers(staff))
				.as("absent means leave it alone, exactly as an absent pan does")
				.containsExactly("Adyar Ananda Bhavan");

		mvc.perform(update(staff, "\"previousEmployment\":[]")).andExpect(status().isNoContent());
		assertThat(employers(staff))
				.as("an empty list is the admin having deleted the last row, and is obeyed")
				.isEmpty();
	}

	@Test
	@DisplayName("an end date before its start is refused, and takes the whole save with it")
	void datesTheWrongWayRound() throws Exception {
		String staff = hire("Gopal Das");

		mvc.perform(update(staff, """
				"phone":"+919876540088",
				"previousEmployment":[
				  {"employer":"Adyar Ananda Bhavan","fromDate":"2023-07-31","toDate":"2019-03-01"}
				]"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("previousEmployment[0].toDate"));

		assertThat(employers(staff)).isEmpty();
		assertThat(admin.queryForObject(
				"SELECT phone FROM staff_profiles WHERE id = ?::uuid", String.class, staff))
				.as("one Save is one edit: a bad date must not leave the phone number changed")
				.isNotEqualTo("+919876540088");
	}

	@Test
	@DisplayName("a job with no employer is refused in the product's own words")
	void employerIsRequired() throws Exception {
		String staff = hire("Gopal Das");

		mvc.perform(update(staff, """
				"previousEmployment":[{"employer":"   ","theirTitle":"Cook"}]"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("Enter where they worked."));
	}

	@Test
	@DisplayName("a manager's number without its country code is refused, as any other number is")
	void managerPhoneShape() throws Exception {
		String staff = hire("Gopal Das");

		// KMS-400003 and not a field error: PhoneNumberDeserializer, which every phone number in this
		// application is read through, refuses a number it cannot normalise before Jakarta's @Pattern
		// ever sees it. The pattern stays on the field as the twin guard for anything that reaches it
		// another way. A manager's number behaves exactly as the person's own does, which is the point.
		mvc.perform(update(staff, """
				"previousEmployment":[{"employer":"Adyar Ananda Bhavan","managerPhone":"98450"}]"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400003"));

		assertThat(employers(staff)).isEmpty();
	}

	@Test
	@DisplayName("another temple cannot see these rows, and the database is what stops it")
	void rowLevelSecurity() throws Exception {
		String staff = hire("Gopal Das");
		mvc.perform(update(staff, """
				"previousEmployment":[{"employer":"Adyar Ananda Bhavan"}]"""))
				.andExpect(status().isNoContent());

		asApplication(otherTenant, jdbc ->
				assertThat(jdbc.queryForObject(
						"SELECT count(*) FROM staff_previous_employment", Integer.class))
						.as("row-level security, not the service, is what keeps these apart")
						.isZero());
		asApplication(tenant, jdbc ->
				assertThat(jdbc.queryForObject(
						"SELECT count(*) FROM staff_previous_employment", Integer.class))
						.isEqualTo(1));

		signIn("uid-prev-admin-b");
		mvc.perform(authed(get("/api/v1/staff/members/{id}", staff)))
				.andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------------

	private List<String> employers(String staffId) {
		return admin.queryForList(
				"SELECT employer FROM staff_previous_employment WHERE staff_profile_id = ?::uuid ORDER BY sort_order",
				String.class, staffId);
	}

	/**
	 * A whole valid edit of this person, with the caller's extra fields folded in. Every field the
	 * form always sends is here, because the endpoint requires them and this class is about one of
	 * them.
	 */
	private MockHttpServletRequestBuilder update(String staffId, String extra) {
		return authed(put("/api/v1/staff/members/{id}", staffId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"fullName":"Gopal Das","jobTitle":"HEAD_COOK","employmentType":"FULL_TIME",
						 "dateOfJoining":"2026-02-01","kitchenId":"%s", %s}
						""".formatted(kitchen, extra));
	}

	private String hire(String name) throws Exception {
		String body = mvc.perform(authed(post("/api/v1/staff/members"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"fullName":"%s","jobTitle":"HEAD_COOK","employmentType":"FULL_TIME",
								 "dateOfJoining":"2026-02-01","kitchenId":"%s"}
								""".formatted(name, kitchen)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(body).get("id").asText();
	}

	private void asApplication(UUID temple, java.util.function.Consumer<JdbcTemplate> work) {
		DriverManagerDataSource plain = new DriverManagerDataSource();
		plain.setUrl(POSTGRES.getJdbcUrl());
		plain.setUsername(APP_ROLE);
		plain.setPassword(APP_PASSWORD);

		TenantContext.set(temple);
		try {
			work.accept(new JdbcTemplate(new TenantAwareDataSource(plain)));
		} finally {
			TenantContext.clear();
		}
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private UUID temple(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug, name);
	}

	private void insertUser(UUID temple, String uid, String name, String email, String phone, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
				""", temple, uid, name, email, phone, role);
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private UUID insertKitchen(UUID temple) {
		return admin.queryForObject("""
				INSERT INTO kitchens (tenant_id, name, is_main, uses_meal_planner, status, created_by)
				SELECT ?, 'Main kitchen', true, true, 'ACTIVE', id FROM users WHERE tenant_id = ?
				ORDER BY created_at, id LIMIT 1
				RETURNING id
				""", UUID.class, temple, temple);
	}
}
