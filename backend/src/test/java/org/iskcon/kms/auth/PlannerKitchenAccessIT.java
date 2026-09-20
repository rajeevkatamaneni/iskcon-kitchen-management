package org.iskcon.kms.auth;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Only people whose kitchen plans its meals here can open the meal planner (Epic 12, T-357).
 *
 * <p>Rajeev, 2026-09-19: "Only people whose kitchen plans its meals here can open the meal planner
 * (server-enforced, and hidden from the menu) … the Temple Admin sees and plans for every kitchen." The
 * coordinator's correction the same day: a Kitchen Staff or Kitchen Manager account with no staff record
 * is refused too, and volunteers never had the planner and still do not.
 *
 * <p>Six people, one temple, and for each the planner's answer and what {@code /whoami} tells the menu:
 * <ul>
 *   <li>the Temple Admin with no staff record — allowed, every kitchen;
 *   <li>the Temple Admin working in the store kitchen — still allowed: the admin's rule is not the
 *       kitchen's;
 *   <li>a cook with no staff record — {@code KMS-400183};
 *   <li>a cook in the store kitchen, which does not plan its meals here — {@code KMS-400183} on every
 *       planner endpoint, read and save, and with a body that would fail validation too;
 *   <li>a cook and a Kitchen Manager in the main kitchen, which does — allowed;
 *   <li>a Volunteer — refused for the permission exactly as before ({@code KMS-400021}), and never asked
 *       about a kitchen.
 * </ul>
 * And the endpoints deliberately left open answer the store cook as they always did.
 *
 * <p>What "allowed" means here is "not refused by the guard": the tests send the planner the smallest
 * requests that reach it, and an empty save body is answered 400 with the field errors — which is the
 * proof the request got past both interceptors to the point where the body is read. The meal package
 * itself is another task's (T-354) and is not what this class tests.
 */
@AutoConfigureMockMvc
class PlannerKitchenAccessIT extends AbstractIntegrationTest {

	private static final String FROM = "2026-09-21";
	private static final String TO = "2026-09-27";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID temple;
	private UUID mainKitchen;
	private UUID storeKitchen;
	/** Gives each person their own phone number. */
	private int users;

	@BeforeEach
	void setUp() {
		// This class decides who has a staff record and who has none — a cook who was never hired is one
		// of the seven people it is about — so the suite's default of completing a signed-in cook's
		// fixture (TestStaffRecords) is off here. Nothing else about the sign-in changes.
		stubVerifier.withoutAutomaticStaffRecords();
		admin = new JdbcTemplate(adminDataSource());
		temple = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('planner-kitchen-access', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		UUID templeAdmin = user("uid-pk-admin", "TEMPLE_ADMIN");
		UUID storeAdmin = user("uid-pk-admin-store", "TEMPLE_ADMIN");
		user("uid-pk-cook-none", "KITCHEN_STAFF");
		UUID storeCook = user("uid-pk-cook-store", "KITCHEN_STAFF");
		UUID plannerCook = user("uid-pk-cook-main", "KITCHEN_STAFF");
		UUID plannerManager = user("uid-pk-manager-main", "KITCHEN_MANAGER");
		user("uid-pk-volunteer", "VOLUNTEER");

		mainKitchen = kitchen("Main kitchen", true, true, templeAdmin);
		storeKitchen = kitchen("Store kitchen", false, false, templeAdmin);

		staff(storeAdmin, storeKitchen);
		staff(storeCook, storeKitchen);
		staff(plannerCook, mainKitchen);
		staff(plannerManager, mainKitchen);
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM staff_profiles");
		admin.execute("DELETE FROM kitchens");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- Refused ------------------------------------------------------------------

	@Test
	@DisplayName("a cook in a kitchen that does not plan its meals here is refused on reading and on saving")
	void storeCookIsRefused() throws Exception {
		signIn("uid-pk-cook-store");

		refusedForKitchen(mvc.perform(authed(get("/api/v1/meals").param("from", FROM).param("to", TO))));
		refusedForKitchen(mvc.perform(authed(get("/api/v1/meals/{id}", UUID.randomUUID()))));
		refusedForKitchen(mvc.perform(save("""
				{"date":"2026-09-22","mealKindId":"%s","dishes":[]}
				""".formatted(UUID.randomUUID()))));
		refusedForKitchen(mvc.perform(authed(put("/api/v1/meals/{id}", UUID.randomUUID()))
				.contentType(MediaType.APPLICATION_JSON).content("{}")));
		refusedForKitchen(mvc.perform(authed(get("/api/v1/meal-plans/day-context").param("date", FROM))));
		refusedForKitchen(mvc.perform(authed(post("/api/v1/meal-plans/reuse/preview"))
				.contentType(MediaType.APPLICATION_JSON).content("{}")));
		refusedForKitchen(mvc.perform(authed(get("/api/v1/meal-crew").param("from", FROM).param("to", TO))));
		refusedForKitchen(mvc.perform(authed(get("/api/v1/job-cards/languages")
				.param("mealId", UUID.randomUUID().toString()))));
	}

	@Test
	@DisplayName("the refusal comes before the body is read: an invalid body, even unparseable, is still 403 KMS-400183")
	void refusalComesBeforeTheBody() throws Exception {
		signIn("uid-pk-cook-store");

		refusedForKitchen(mvc.perform(save("{}")));
		refusedForKitchen(mvc.perform(save("{\"dishes\": [ this is not json")));
	}

	@Test
	@DisplayName("a Kitchen Staff account with no staff record has no kitchen to plan for, and is refused")
	void cookWithNoStaffRecordIsRefused() throws Exception {
		signIn("uid-pk-cook-none");

		refusedForKitchen(mvc.perform(authed(get("/api/v1/meals").param("from", FROM).param("to", TO))));
		refusedForKitchen(mvc.perform(save("{}")));
	}

	@Test
	@DisplayName("a Volunteer is refused for the permission, as before, and never told about kitchens")
	void volunteerIsRefusedForThePermission() throws Exception {
		signIn("uid-pk-volunteer");

		mvc.perform(authed(get("/api/v1/meals").param("from", FROM).param("to", TO)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-400021"));
		mvc.perform(save("{}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-400021"));
	}

	// ---- Allowed ------------------------------------------------------------------

	@Test
	@DisplayName("the Temple Admin plans for every kitchen, with no staff record and with one in the store kitchen")
	void templeAdminIsAllowed() throws Exception {
		for (String uid : new String[] {"uid-pk-admin", "uid-pk-admin-store"}) {
			signIn(uid);
			mvc.perform(authed(get("/api/v1/meals").param("from", FROM).param("to", TO)))
					.andExpect(status().isOk());
			reachedTheBody(mvc.perform(save("{}")));
		}
	}

	@Test
	@DisplayName("a cook and a Kitchen Manager in a kitchen that plans its meals here are allowed")
	void plannerKitchenStaffAreAllowed() throws Exception {
		for (String uid : new String[] {"uid-pk-cook-main", "uid-pk-manager-main"}) {
			signIn(uid);
			mvc.perform(authed(get("/api/v1/meals").param("from", FROM).param("to", TO)))
					.andExpect(status().isOk());
			mvc.perform(authed(get("/api/v1/meal-crew").param("from", FROM).param("to", TO)))
					.andExpect(status().isOk());
			mvc.perform(authed(get("/api/v1/meal-plans/day-context").param("date", FROM)))
					.andExpect(status().isOk());
			reachedTheBody(mvc.perform(save("{}")));
		}
	}

	// ---- Left open ----------------------------------------------------------------

	@Test
	@DisplayName("the endpoints other screens rely on still answer a cook whose kitchen does not plan meals")
	void openEndpointsStillAnswer() throws Exception {
		signIn("uid-pk-cook-store");

		// Settings → Meal kinds lists them, and a list of meal names says nothing about any kitchen's plans.
		mvc.perform(authed(get("/api/v1/meal-kinds"))).andExpect(status().isOk());
		// Today: a cook in the store kitchen still has a day.
		mvc.perform(authed(get("/api/v1/today"))).andExpect(status().isOk());
		// Their own schedule.
		mvc.perform(authed(get("/api/v1/staff/schedule/me"))).andExpect(status().isOk());
	}

	// ---- What the menu is told ----------------------------------------------------

	@Test
	@DisplayName("whoami gives each person their kitchen and whether the planner is open to them")
	void whoAmISaysTheSame() throws Exception {
		whoAmI("uid-pk-admin")
				.andExpect(jsonPath("$.kitchenId").value(nullValue()))
				.andExpect(jsonPath("$.kitchenName").value(nullValue()))
				.andExpect(jsonPath("$.canPlanMeals").value(true));
		whoAmI("uid-pk-admin-store")
				.andExpect(jsonPath("$.kitchenId").value(storeKitchen.toString()))
				.andExpect(jsonPath("$.kitchenName").value("Store kitchen"))
				.andExpect(jsonPath("$.canPlanMeals").value(true));
		whoAmI("uid-pk-cook-none")
				.andExpect(jsonPath("$.kitchenId").value(nullValue()))
				.andExpect(jsonPath("$.kitchenName").value(nullValue()))
				.andExpect(jsonPath("$.canPlanMeals").value(false));
		whoAmI("uid-pk-cook-store")
				.andExpect(jsonPath("$.kitchenId").value(storeKitchen.toString()))
				.andExpect(jsonPath("$.kitchenName").value("Store kitchen"))
				.andExpect(jsonPath("$.canPlanMeals").value(false));
		whoAmI("uid-pk-cook-main")
				.andExpect(jsonPath("$.kitchenId").value(mainKitchen.toString()))
				.andExpect(jsonPath("$.kitchenName").value("Main kitchen"))
				.andExpect(jsonPath("$.canPlanMeals").value(true));
		whoAmI("uid-pk-manager-main")
				.andExpect(jsonPath("$.kitchenId").value(mainKitchen.toString()))
				.andExpect(jsonPath("$.canPlanMeals").value(true));
		whoAmI("uid-pk-volunteer")
				.andExpect(jsonPath("$.kitchenId").value(nullValue()))
				.andExpect(jsonPath("$.canPlanMeals").value(false));
	}

	@Test
	@DisplayName("a staff record whose employment has ended gives no kitchen and no planner")
	void formerStaffHaveNoKitchen() throws Exception {
		admin.update("""
				UPDATE staff_profiles SET employment_status = 'RESIGNED', last_working_day = DATE '2026-06-30'
				WHERE user_id = (SELECT id FROM users WHERE firebase_uid = 'uid-pk-cook-main')
				""");

		whoAmI("uid-pk-cook-main")
				.andExpect(jsonPath("$.kitchenId").value(nullValue()))
				.andExpect(jsonPath("$.canPlanMeals").value(false));
		refusedForKitchen(mvc.perform(authed(get("/api/v1/meals").param("from", FROM).param("to", TO))));
	}

	// ---------------------------------------------------------------------

	private static void refusedForKitchen(ResultActions result) throws Exception {
		result.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-400183"));
	}

	/** Past both interceptors to where the body is read, and refused for the body, not the kitchen. */
	private static void reachedTheBody(ResultActions result) throws Exception {
		result.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(not("KMS-400183")));
	}

	private ResultActions whoAmI(String uid) throws Exception {
		signIn(uid);
		return mvc.perform(authed(get("/api/v1/whoami"))).andExpect(status().isOk());
	}

	private MockHttpServletRequestBuilder save(String json) {
		return authed(post("/api/v1/meals")).contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private UUID user(String uid, String role) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, ?, ?, 'ACTIVE') RETURNING id
				""", UUID.class, temple, uid, uid + "@example.com", "+9198765400" + (10 + users++), role);
	}

	private UUID kitchen(String name, boolean main, boolean planner, UUID createdBy) {
		return admin.queryForObject("""
				INSERT INTO kitchens (tenant_id, name, is_main, uses_meal_planner, status, created_by)
				VALUES (?, ?, ?, ?, 'ACTIVE', ?) RETURNING id
				""", UUID.class, temple, name, main, planner, createdBy);
	}

	private void staff(UUID userId, UUID kitchen) {
		admin.update("""
				INSERT INTO staff_profiles (tenant_id, user_id, full_name, job_title, employment_type,
					date_of_joining, kitchen_id)
				VALUES (?, ?, 'Test Person', 'COOK', 'FULL_TIME', DATE '2026-01-01', ?)
				""", temple, userId, kitchen);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}
}
