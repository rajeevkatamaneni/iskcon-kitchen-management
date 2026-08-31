package org.iskcon.kms.ingredientrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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
 * A kitchen asking the store for ingredients, and somebody answering (E10-S5 and E10-S6), through
 * the full stack: RLS, the cross-tenant references a foreign key would let through, the unit family
 * a request line may not leave, and every illegal move of the state machine.
 *
 * <p>Issuing has its own file, {@link IngredientIssueIT}, because it is the only part of this epic
 * that touches the stock ledger and it needs a store room seeded to prove anything.
 */
@AutoConfigureMockMvc
@Import(IngredientRequestIT.StubVerifierConfiguration.class)
class IngredientRequestIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID templeB;
	private UUID kitchenA;
	private UUID riceA;
	private UUID oilA;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();

		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");

		insertUser(templeA, "uid-admin-a", "Gopal Das", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-manager-a", "Radha Devi", "KITCHEN_MANAGER");
		insertUser(templeA, "uid-cook-a", "Bhakta Shyam", "KITCHEN_STAFF");
		insertUser(templeA, "uid-cook2-a", "Bhakta Nitai", "KITCHEN_STAFF");
		insertUser(templeB, "uid-admin-b", "Their Admin", "TEMPLE_ADMIN");

		kitchenA = insertKitchen(templeA, "Deity kitchen", false);
		riceA = insertIngredient(templeA, "Rice", "KG");
		oilA = insertIngredient(templeA, "Groundnut oil", "L");

		signIn("uid-cook-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM ingredient_request_events");
		admin.execute("DELETE FROM ingredient_request_lines");
		admin.execute("DELETE FROM ingredient_request_dishes");
		admin.execute("DELETE FROM ingredient_requests");
		admin.execute("DELETE FROM ingredient_request_sequence");
		admin.execute("DELETE FROM kitchens");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- E10-S5: the draft and its life ---------------------------------

	@Test
	@DisplayName("a request is raised as a draft, numbered for this temple, and read back whole")
	void raisesADraft() throws Exception {
		String id = createRequest();

		mvc.perform(authed(get("/api/v1/ingredient-requests/{id}", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.request.reference").value("IR-" + LocalDate.now().getYear() + "-0001"))
				.andExpect(jsonPath("$.request.status").value("DRAFT"))
				.andExpect(jsonPath("$.request.kitchenName").value("Deity kitchen"))
				.andExpect(jsonPath("$.request.requestedByName").value("Bhakta Shyam"))
				.andExpect(jsonPath("$.lines.length()").value(1))
				.andExpect(jsonPath("$.lines[0].ingredientName").value("Rice"))
				.andExpect(jsonPath("$.dishes.length()").value(1))
				.andExpect(jsonPath("$.dishes[0].dishName").value("Khichdi"))
				.andExpect(jsonPath("$.events[0].eventType").value("CREATED"));

		mvc.perform(authed(get("/api/v1/ingredient-requests")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	@DisplayName("the reference counter runs on per temple, and each temple has its own")
	void mintsAReferencePerTemple() throws Exception {
		createRequest();
		String second = createRequest();

		mvc.perform(authed(get("/api/v1/ingredient-requests/{id}", second)))
				.andExpect(jsonPath("$.request.reference").value("IR-" + LocalDate.now().getYear() + "-0002"));

		// The other temple starts at one again: the counter is theirs, not the platform's.
		UUID kitchenB = insertKitchen(templeB, "Their kitchen", false);
		UUID riceB = insertIngredient(templeB, "Rice", "KG");
		signIn("uid-admin-b");
		String theirs = create(body(kitchenB, line(riceB, "10", "KG"), dish("Khichdi", "200", "SERVINGS")));
		mvc.perform(authed(get("/api/v1/ingredient-requests/{id}", theirs)))
				.andExpect(jsonPath("$.request.reference").value("IR-" + LocalDate.now().getYear() + "-0001"));
	}

	@Test
	@DisplayName("somebody else's draft can be read and cannot be edited")
	void anotherPersonsDraftIsReadOnly() throws Exception {
		String id = createRequest();

		signIn("uid-cook2-a");
		mvc.perform(authed(get("/api/v1/ingredient-requests/{id}", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.request.requestedByName").value("Bhakta Shyam"));

		mvc.perform(authed(put("/api/v1/ingredient-requests/{id}", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(kitchenA, line(riceA, "60", "KG"), dish("Khichdi", "200", "SERVINGS"))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-4978"));
	}

	@Test
	@DisplayName("somebody else's draft cannot be deleted by a fellow cook")
	void anotherPersonsDraftCannotBeDeletedByAPeer() throws Exception {
		String id = createRequest();

		signIn("uid-cook2-a");
		mvc.perform(authed(delete("/api/v1/ingredient-requests/{id}", id)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-4978"));
	}

	@Test
	@DisplayName("an approver deletes anybody's draft, and the deletion is audited")
	void anApproverDeletesAnybodysDraft() throws Exception {
		String id = createRequest();

		// Expressed as the permission, not the role: a Kitchen Manager holds it too, and that is the
		// storekeeper this temple actually has.
		signIn("uid-manager-a");
		mvc.perform(authed(delete("/api/v1/ingredient-requests/{id}", id)))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/ingredient-requests/{id}", id)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-4977"));
		assertThat(auditCount("INGREDIENT_REQUEST_DELETED")).isEqualTo(1);
	}

	@Test
	@DisplayName("the author deletes their own draft")
	void theAuthorDeletesTheirOwnDraft() throws Exception {
		String id = createRequest();

		mvc.perform(authed(delete("/api/v1/ingredient-requests/{id}", id)))
				.andExpect(status().isNoContent());
		assertThat(auditCount("INGREDIENT_REQUEST_DELETED")).isEqualTo(1);
	}

	@Test
	@DisplayName("the author edits their own draft, and the edit replaces the lines outright")
	void theAuthorEditsTheirOwnDraft() throws Exception {
		String id = createRequest();

		mvc.perform(authed(put("/api/v1/ingredient-requests/{id}", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(kitchenA,
						line(riceA, "60", "KG") + "," + line(oilA, "4", "L"),
						dish("Khichdi", "300", "SERVINGS"))))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/ingredient-requests/{id}", id)))
				.andExpect(jsonPath("$.lines.length()").value(2))
				.andExpect(jsonPath("$.lines[0].quantity").value(60))
				.andExpect(jsonPath("$.dishes[0].quantity").value(300));
	}

	@Test
	@DisplayName("submitting a request that says nothing about what is being cooked is refused")
	void submitNeedsDishes() throws Exception {
		String id = create(body(kitchenA, line(riceA, "40", "KG"), ""));

		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/submit", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4984"));
	}

	@Test
	@DisplayName("submitting a request that asks for nothing is refused")
	void submitNeedsLines() throws Exception {
		String id = create(body(kitchenA, "", dish("Khichdi", "200", "SERVINGS")));

		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/submit", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4983"));
	}

	@Test
	@DisplayName("three litres of a rice the temple holds in kilograms is refused")
	void refusesACrossFamilyUnit() throws Exception {
		mvc.perform(createFor(body(kitchenA, line(riceA, "3", "L"), dish("Khichdi", "200", "SERVINGS"))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4001"));
	}

	@Test
	@DisplayName("500 gm of that same rice is accepted, because grams and kilograms are one family")
	void acceptsAnotherUnitOfTheSameFamily() throws Exception {
		String id = create(body(kitchenA, line(riceA, "500", "GM"), dish("Khichdi", "20", "SERVINGS")));

		mvc.perform(authed(get("/api/v1/ingredient-requests/{id}", id)))
				.andExpect(jsonPath("$.lines[0].unit").value("GM"))
				.andExpect(jsonPath("$.lines[0].quantity").value(500));
	}

	@Test
	@DisplayName("a request cannot name a kitchen from another temple")
	void refusesACrossTenantKitchen() throws Exception {
		UUID kitchenB = insertKitchen(templeB, "Their kitchen", false);

		// The foreign key would take it — FK checks run as the table owner and bypass RLS — so the
		// service looks the kitchen up through RLS first.
		mvc.perform(createFor(body(kitchenB, line(riceA, "40", "KG"), dish("Khichdi", "200", "SERVINGS"))))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-4974"));
	}

	@Test
	@DisplayName("a request cannot ask for an ingredient from another temple")
	void refusesACrossTenantIngredient() throws Exception {
		UUID riceB = insertIngredient(templeB, "Their rice", "KG");

		mvc.perform(createFor(body(kitchenA, line(riceB, "40", "KG"), dish("Khichdi", "200", "SERVINGS"))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4001"));
	}

	@Test
	@DisplayName("another temple's request is invisible and unreachable")
	void anotherTemplesRequestIsInvisible() throws Exception {
		UUID kitchenB = insertKitchen(templeB, "Their kitchen", false);
		UUID riceB = insertIngredient(templeB, "Rice", "KG");
		signIn("uid-admin-b");
		String theirs = create(body(kitchenB, line(riceB, "10", "KG"), dish("Khichdi", "200", "SERVINGS")));

		signIn("uid-cook-a");
		mvc.perform(authed(get("/api/v1/ingredient-requests")))
				.andExpect(jsonPath("$.length()").value(0));
		mvc.perform(authed(get("/api/v1/ingredient-requests/{id}", theirs)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-4977"));
	}

	@Test
	@DisplayName("a kitchen that plans its own meals may not be asked for anything, at any point")
	void refusesAKitchenThatPlansItsOwnMeals() throws Exception {
		UUID planning = insertKitchen(templeA, "Prasadam kitchen", true);

		// On create.
		mvc.perform(createFor(body(planning, line(riceA, "40", "KG"), dish("Khichdi", "200", "SERVINGS"))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4976"));

		// On edit.
		String id = createRequest();
		mvc.perform(authed(put("/api/v1/ingredient-requests/{id}", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(planning, line(riceA, "40", "KG"), dish("Khichdi", "200", "SERVINGS"))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4976"));

		// And at submission, because the flag can be turned on while a draft sits there.
		admin.update("UPDATE kitchens SET uses_meal_planner = true WHERE id = ?", kitchenA);
		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/submit", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4976"));
	}

	@Test
	@DisplayName("an archived kitchen may not be asked for anything")
	void refusesAnArchivedKitchen() throws Exception {
		admin.update("UPDATE kitchens SET status = 'ARCHIVED' WHERE id = ?", kitchenA);

		mvc.perform(createFor(body(kitchenA, line(riceA, "40", "KG"), dish("Khichdi", "200", "SERVINGS"))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4975"));
	}

	@Test
	@DisplayName("a dish may be counted in servings, and an ingredient line may not")
	void servingsAreForDishesOnly() throws Exception {
		String id = create(body(kitchenA, line(riceA, "40", "KG"), dish("Khichdi", "200", "SERVINGS")));
		mvc.perform(authed(get("/api/v1/ingredient-requests/{id}", id)))
				.andExpect(jsonPath("$.dishes[0].unit").value("SERVINGS"));

		mvc.perform(createFor(body(kitchenA, line(riceA, "40", "SERVINGS"),
				dish("Khichdi", "200", "SERVINGS"))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4001"));
	}

	@Test
	@DisplayName("submitting a complete request is audited and lands in the event trail")
	void submitsACompleteRequest() throws Exception {
		String id = submittedRequest();

		mvc.perform(authed(get("/api/v1/ingredient-requests/{id}", id)))
				.andExpect(jsonPath("$.request.status").value("SUBMITTED"))
				.andExpect(jsonPath("$.request.submittedAt").isNotEmpty())
				.andExpect(jsonPath("$.events[1].eventType").value("SUBMITTED"))
				.andExpect(jsonPath("$.events[1].actorName").value("Bhakta Shyam"));

		assertThat(auditCount("INGREDIENT_REQUEST_SUBMITTED")).isEqualTo(1);
	}

	@Test
	@DisplayName("only the author may send their own draft for review")
	void onlyTheAuthorSubmits() throws Exception {
		String id = createRequest();

		signIn("uid-cook2-a");
		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/submit", id)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-4978"));
	}

	@Test
	@DisplayName("the list can be narrowed to one status")
	void filtersByStatus() throws Exception {
		createRequest();
		submittedRequest();

		mvc.perform(authed(get("/api/v1/ingredient-requests").param("status", "SUBMITTED")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].status").value("SUBMITTED"));
		mvc.perform(authed(get("/api/v1/ingredient-requests").param("status", "DRAFT")))
				.andExpect(jsonPath("$.length()").value(1));
	}

	// ---- E10-S6: review --------------------------------------------------

	@Test
	@DisplayName("an approver approves somebody else's request, with a note, and it is audited")
	void approvesARequest() throws Exception {
		String id = submittedRequest();

		signIn("uid-manager-a");
		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/approve", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"note\":\"Take it from the older sack.\"}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/ingredient-requests/{id}", id)))
				.andExpect(jsonPath("$.request.status").value("APPROVED"))
				.andExpect(jsonPath("$.request.decidedByName").value("Radha Devi"))
				.andExpect(jsonPath("$.events[2].eventType").value("APPROVED"));

		assertThat(auditCount("INGREDIENT_REQUEST_APPROVED")).isEqualTo(1);
	}

	@Test
	@DisplayName("approving your own request works, and the audit entry says that is what happened")
	void selfApprovalIsAllowedAndVisible() throws Exception {
		// Forbidding it would deadlock a temple whose administrator is its only approver.
		signIn("uid-admin-a");
		String id = create(body(kitchenA, line(riceA, "40", "KG"), dish("Khichdi", "200", "SERVINGS")));
		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/submit", id)))
				.andExpect(status().isNoContent());

		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/approve", id))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNoContent());

		assertThat(auditCount("INGREDIENT_REQUEST_APPROVED")).isEqualTo(1);
		String reason = admin.queryForObject(
				"SELECT reason FROM audit_events WHERE action = 'INGREDIENT_REQUEST_APPROVED'",
				String.class);
		assertThat(reason).isEqualTo("Answered by the person who raised it.");
	}

	@Test
	@DisplayName("a denial carries its note and closes the request for good")
	void deniesARequest() throws Exception {
		String id = submittedRequest();

		signIn("uid-manager-a");
		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/deny", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"note\":\"No jaggery until Thursday.\"}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/ingredient-requests/{id}", id)))
				.andExpect(jsonPath("$.request.status").value("DENIED"));
		assertThat(auditCount("INGREDIENT_REQUEST_DENIED")).isEqualTo(1);
	}

	@Test
	@DisplayName("the author withdraws their submitted request back to a draft")
	void theAuthorWithdraws() throws Exception {
		String id = submittedRequest();

		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/withdraw", id)))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/ingredient-requests/{id}", id)))
				.andExpect(jsonPath("$.request.status").value("DRAFT"))
				.andExpect(jsonPath("$.request.submittedAt").doesNotExist())
				.andExpect(jsonPath("$.events[2].eventType").value("WITHDRAWN"));
	}

	@Test
	@DisplayName("an approver may hand a submitted request back rather than answer it")
	void anApproverWithdraws() throws Exception {
		String id = submittedRequest();

		signIn("uid-manager-a");
		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/withdraw", id)))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/ingredient-requests/{id}", id)))
				.andExpect(jsonPath("$.request.status").value("DRAFT"));
	}

	@Test
	@DisplayName("a bystander may not withdraw somebody else's submitted request")
	void aBystanderCannotWithdraw() throws Exception {
		String id = submittedRequest();

		signIn("uid-cook2-a");
		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/withdraw", id)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-4978"));
	}

	@Test
	@DisplayName("an approver may correct a submitted request before answering it")
	void anApproverEditsASubmittedRequest() throws Exception {
		String id = submittedRequest();

		signIn("uid-manager-a");
		mvc.perform(authed(put("/api/v1/ingredient-requests/{id}", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(kitchenA, line(riceA, "35", "KG"), dish("Khichdi", "200", "SERVINGS"))))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/ingredient-requests/{id}", id)))
				.andExpect(jsonPath("$.lines[0].quantity").value(35));
	}

	@Test
	@DisplayName("a submitted request cannot be deleted — it is withdrawn first")
	void aSubmittedRequestCannotBeDeleted() throws Exception {
		String id = submittedRequest();

		mvc.perform(authed(delete("/api/v1/ingredient-requests/{id}", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4979"));
	}

	@Test
	@DisplayName("a request already awaiting review cannot be submitted again")
	void aSubmittedRequestCannotBeResubmitted() throws Exception {
		String id = submittedRequest();

		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/submit", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4986"));
	}

	@Test
	@DisplayName("a draft cannot be approved — nobody has been asked to answer it yet")
	void aDraftCannotBeApproved() throws Exception {
		String id = createRequest();

		signIn("uid-manager-a");
		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/approve", id))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4986"));
	}

	@Test
	@DisplayName("a draft cannot be denied either")
	void aDraftCannotBeDenied() throws Exception {
		String id = createRequest();

		signIn("uid-manager-a");
		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/deny", id))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4986"));
	}

	@Test
	@DisplayName("a draft cannot be withdrawn — there is nothing to take back")
	void aDraftCannotBeWithdrawn() throws Exception {
		String id = createRequest();

		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/withdraw", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4986"));
	}

	@Test
	@DisplayName("an approved request cannot be approved again")
	void anApprovedRequestCannotBeReapproved() throws Exception {
		String id = approvedRequest();

		// Answering at all takes the approver's permission; the state machine is what refuses
		// this one, so the person asking has to be somebody the endpoint would have let through.
		signIn("uid-manager-a");

		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/approve", id))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4980"));
	}

	@Test
	@DisplayName("an approved request cannot be denied after the fact")
	void anApprovedRequestCannotBeDenied() throws Exception {
		String id = approvedRequest();

		// Answering at all takes the approver's permission; the state machine is what refuses
		// this one, so the person asking has to be somebody the endpoint would have let through.
		signIn("uid-manager-a");

		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/deny", id))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4980"));
	}

	@Test
	@DisplayName("an approved request cannot be withdrawn")
	void anApprovedRequestCannotBeWithdrawn() throws Exception {
		String id = approvedRequest();

		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/withdraw", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4980"));
	}

	@Test
	@DisplayName("an approved request cannot be edited — that would change what was approved")
	void anApprovedRequestCannotBeEdited() throws Exception {
		String id = approvedRequest();

		mvc.perform(authed(put("/api/v1/ingredient-requests/{id}", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(kitchenA, line(riceA, "80", "KG"), dish("Khichdi", "200", "SERVINGS"))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4980"));
	}

	@Test
	@DisplayName("an approved request cannot be deleted")
	void anApprovedRequestCannotBeDeleted() throws Exception {
		String id = approvedRequest();

		mvc.perform(authed(delete("/api/v1/ingredient-requests/{id}", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4980"));
	}

	@Test
	@DisplayName("a denied request cannot be edited")
	void aDeniedRequestCannotBeEdited() throws Exception {
		String id = deniedRequest();

		mvc.perform(authed(put("/api/v1/ingredient-requests/{id}", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(kitchenA, line(riceA, "10", "KG"), dish("Khichdi", "20", "SERVINGS"))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4979"));
	}

	@Test
	@DisplayName("a denied request cannot be deleted")
	void aDeniedRequestCannotBeDeleted() throws Exception {
		String id = deniedRequest();

		mvc.perform(authed(delete("/api/v1/ingredient-requests/{id}", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4979"));
	}

	@Test
	@DisplayName("a denied request cannot be approved instead")
	void aDeniedRequestCannotBeApproved() throws Exception {
		String id = deniedRequest();

		// Answering at all takes the approver's permission; the state machine is what refuses
		// this one, so the person asking has to be somebody the endpoint would have let through.
		signIn("uid-manager-a");

		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/approve", id))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4980"));
	}

	@Test
	@DisplayName("a denied request cannot be withdrawn back into play")
	void aDeniedRequestCannotBeWithdrawn() throws Exception {
		String id = deniedRequest();

		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/withdraw", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4980"));
	}

	@Test
	@DisplayName("kitchen staff may raise and read a request but may not approve one")
	void kitchenStaffCannotApprove() throws Exception {
		String id = submittedRequest();

		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/approve", id))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-4301"));

		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/deny", id))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-4301"));
	}

	@Test
	@DisplayName("a volunteer cannot see the requests at all")
	void aVolunteerIsRefused() throws Exception {
		insertUser(templeA, "uid-volunteer-a", "Seva Devotee", "VOLUNTEER");
		signIn("uid-volunteer-a");

		mvc.perform(authed(get("/api/v1/ingredient-requests")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-4301"));
	}

	@Test
	@DisplayName("the event trail reads as one line per thing that happened, in order")
	void theEventTrailReadsInOrder() throws Exception {
		String id = approvedRequest();

		mvc.perform(authed(get("/api/v1/ingredient-requests/{id}", id)))
				.andExpect(jsonPath("$.events.length()").value(3))
				.andExpect(jsonPath("$.events[0].eventType").value("CREATED"))
				.andExpect(jsonPath("$.events[1].eventType").value("SUBMITTED"))
				.andExpect(jsonPath("$.events[2].eventType").value("APPROVED"))
				.andExpect(jsonPath("$.events[2].actorName").value("Radha Devi"));
	}

	// ---------------------------------------------------------------------

	/** A draft: rice for khichdi, raised by whoever is signed in. */
	private String createRequest() throws Exception {
		return create(body(kitchenA, line(riceA, "40", "KG"), dish("Khichdi", "200", "SERVINGS")));
	}

	/** The same request, sent for review by its author. */
	private String submittedRequest() throws Exception {
		String id = createRequest();
		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/submit", id)))
				.andExpect(status().isNoContent());
		return id;
	}

	/** Submitted by the cook, approved by the manager, and left signed in as the cook. */
	private String approvedRequest() throws Exception {
		String id = submittedRequest();
		signIn("uid-manager-a");
		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/approve", id))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNoContent());
		signIn("uid-cook-a");
		return id;
	}

	private String deniedRequest() throws Exception {
		String id = submittedRequest();
		signIn("uid-manager-a");
		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/deny", id))
				.contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Not this week.\"}"))
				.andExpect(status().isNoContent());
		signIn("uid-cook-a");
		return id;
	}

	private String create(String json) throws Exception {
		String response = mvc.perform(createFor(json))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return response.replaceAll(".*\"id\"\\s*:\\s*\"([0-9a-f-]+)\".*", "$1");
	}

	private MockHttpServletRequestBuilder createFor(String json) {
		return authed(post("/api/v1/ingredient-requests"))
				.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	static String body(UUID kitchenId, String lines, String dishes) {
		return ("{\"kitchenId\":\"%s\",\"neededOn\":\"%s\",\"purpose\":\"Janmashtami feast\","
				+ "\"lines\":[%s],\"dishes\":[%s]}")
				.formatted(kitchenId, LocalDate.now().plusDays(2), lines, dishes);
	}

	static String line(UUID ingredientId, String quantity, String unit) {
		return "{\"ingredientId\":\"%s\",\"quantity\":%s,\"unit\":\"%s\"}"
				.formatted(ingredientId, quantity, unit);
	}

	static String dish(String name, String quantity, String unit) {
		return "{\"dishName\":\"%s\",\"quantity\":%s,\"unit\":\"%s\"}".formatted(name, quantity, unit);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private int auditCount(String action) {
		Integer c = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = ?", Integer.class, action);
		return c == null ? 0 : c;
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

	private UUID insertUser(UUID tenantId, String uid, String fullName, String role) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, '+919876500081', ?, 'ACTIVE') RETURNING id
				""", UUID.class, tenantId, uid, fullName, uid + "@example.com", role);
	}

	private UUID insertKitchen(UUID tenantId, String name, boolean usesMealPlanner) {
		return admin.queryForObject("""
				INSERT INTO kitchens (tenant_id, name, uses_meal_planner, created_by)
				VALUES (?, ?, ?, (SELECT id FROM users WHERE tenant_id = ? LIMIT 1)) RETURNING id
				""", UUID.class, tenantId, name, usesMealPlanner, tenantId);
	}

	private UUID insertIngredient(UUID tenantId, String name, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', ?) RETURNING id
				""", UUID.class, tenantId, name, unit);
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
