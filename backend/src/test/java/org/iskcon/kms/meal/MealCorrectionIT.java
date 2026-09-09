package org.iskcon.kms.meal;

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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.calendar.CalendarService;
import org.iskcon.kms.inventory.StockMovementService;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Correcting a recorded meal (T-007, docket S5/M2), through the full stack against a real database
 * under RLS.
 *
 * <p>Recording was a one-way door: a lunch typed in as 400 when 640 went out stayed 400 for ever,
 * and the store room stayed drawn down against 400. Undoing that needed three things that did not
 * exist — a way to <em>find</em> the set of movements a meal drew (consumption writes one per
 * (ingredient, batch) draw), a way to move the meal record as well as the ledger, and both of those
 * in one transaction. Each has a test here, and the one that matters most is
 * {@link #bothHalvesRollBackTogether}: the two records are corrected in different ways — the ledger
 * is compensated because it is append-only, the meal is marked because it is read one row at a time
 * — and the only thing keeping them honest is that they commit together.
 *
 * <p>Two kinds of assertion appear throughout and they are not interchangeable. {@code consumed()}
 * sums only {@code CONSUMPTION} rows and so says what was ever <em>drawn</em>; {@code onHand()} sums
 * every row and so says what the shelf actually holds. A correction is exactly the operation where
 * those two diverge, and asserting only the first would let a reversal that never happened pass.
 */
@AutoConfigureMockMvc
@Import(MealCorrectionIT.StubVerifierConfiguration.class)
class MealCorrectionIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private MealKindService mealKindService;

	@Autowired
	private CalendarService calendarService;

	/**
	 * A spy and not a mock, and only one method is ever stubbed, in one test. Recording and
	 * correcting both have to go on writing real movements through the real service — a mock would
	 * give this test a meal with nothing behind it, and every assertion about reversing stock would
	 * pass against an empty ledger.
	 */
	@SpyBean
	private StockMovementService stockMovementService;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID rice;
	private UUID ghee;
	private UUID khichdi;
	private UUID halwa;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		insertUser("uid-admin", "admin@example.com", "TEMPLE_ADMIN", "Anand Das");
		// Holds MANAGE_MEAL_PLANS and so may record, and does not hold CORRECT_RECORDED_MEAL. The
		// whole of D-4 in one row.
		insertUser("uid-staff", "staff@example.com", "KITCHEN_STAFF", "Gopi");

		rice = ingredient("Rice");
		ghee = ingredient("Ghee");
		UUID category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Mains') RETURNING id
				""", UUID.class, tenant);

		// 1 KG of rice per 100 servings of khichdi, 1 KG of ghee per 100 of halwa — small numbers so
		// the arithmetic in the assertions is readable rather than merely correct.
		khichdi = recipe("Khichdi", category);
		line(khichdi, rice, "1");
		halwa = recipe("Halwa", category);
		line(halwa, ghee, "1");

		stock(rice, "50");
		stock(ghee, "50");

		TenantContext.set(tenant);
		try {
			mealKindService.seedForCurrentTenant();
			calendarService.precomputeForCurrentTenant(LocalDate.of(2025, 1, 1), 100);
		} finally {
			TenantContext.clear();
		}
		signIn("uid-admin");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM documents");
		admin.execute("DELETE FROM meal_services");
		admin.execute("DELETE FROM meal_card_sequence");
		admin.execute("DELETE FROM meal_plans");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM meal_kinds");
		admin.execute("DELETE FROM calendar_days");
		admin.execute("DELETE FROM calendar_precompute_state");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM vendor_supplies");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- The headline -----------------------------------------------------

	/**
	 * Acceptance 1: recorded at 400, corrected to 640, and three things hold afterwards — the ledger
	 * nets to the corrected figure, the original recording is still readable, and the correction is
	 * attributed.
	 *
	 * <p>The two stock assertions are the point of the shape. {@code consumed()} rises to 10.4 Kg
	 * because <em>both</em> draws happened and neither was erased: the ledger is append-only and a
	 * correction is a compensating entry, so 4 Kg drawn and 4 Kg given back are both still there
	 * beside the 6.4 Kg. {@code onHand()} is the figure the temple actually has, and it moved by
	 * exactly the corrected amount. A design that edited the original movement in place would show
	 * 6.4 in both, and would have quietly lost the fact that anybody ever thought it was 400.
	 */
	@Test
	@DisplayName("400 corrected to 640: the ledger nets to 640, and 400 is still readable")
	void correctsUpwardAndNetsToTheNewFigure() throws Exception {
		UUID dish = plan("Lunch", khichdi, 500);
		record400(dish);

		assertThat(consumed(rice)).isEqualByComparingTo("4000");
		assertThat(onHand(rice)).isEqualByComparingTo("46000");

		mvc.perform(correct(serviceId(), """
				{"note":"The card was read as 400; the kitchen confirms 640 went out",
				 "dishes":[{"mealPlanId":"%s","actualServings":640,"consumedQuantity":600,
							"notMade":false}]}
				""".formatted(dish)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.recorded").value(true))
				.andExpect(jsonPath("$.corrected").value(true))
				.andExpect(jsonPath("$.correctedByName").value("Anand Das"))
				.andExpect(jsonPath("$.correctionNote")
						.value("The card was read as 400; the kitchen confirms 640 went out"))
				// The dish now says 640, and says what it used to say beside it. Both, on one row —
				// which is what lets the planner write "640 cooked, corrected from 400".
				.andExpect(jsonPath("$.dishes[0].actualServings").value(640.0))
				.andExpect(jsonPath("$.dishes[0].consumedQuantity").value(600.0))
				.andExpect(jsonPath("$.dishes[0].originalActualServings").value(400.0))
				.andExpect(jsonPath("$.dishes[0].status").value("COOKED"));

		// Every draw is still in the ledger — 4 Kg out, 4 Kg back, 6.4 Kg out — so the store room
		// holds what a 640-serving lunch left behind, and the history says how it got there.
		assertThat(consumed(rice)).isEqualByComparingTo("10400");
		assertThat(onHand(rice)).isEqualByComparingTo("43600");

		// And the recording itself was never rewritten: who recorded it, and when, stand as they were.
		mvc.perform(authed(get("/api/v1/meal-services")
						.param("from", "2025-03-17").param("to", "2025-03-17")))
				.andExpect(jsonPath("$[0].recordedByName").value("Anand Das"))
				.andExpect(jsonPath("$[0].recordingNote").value("As read off the card"));

		assertThat(auditCount("MEAL_CORRECTED")).isEqualTo(1);
		// Two kinds of entry for one act, deliberately: the store room's ledger has its own readers,
		// and an entry about a meal is not one they would go looking for. One correction per movement
		// reversed, and this dish drew from exactly one batch.
		assertThat(auditCount("STOCK_MOVEMENT_CORRECTED")).isEqualTo(1);
	}

	/**
	 * Acceptance 4, and the reason this is one endpoint rather than "reverse the stock, then fix the
	 * meal".
	 *
	 * <p>The service marks the meal <em>first</em> and moves the stock after, so that a failure in
	 * the stock half has something to roll back. Written the other way round this test would pass
	 * while proving nothing: the mark would simply never have been attempted.
	 */
	@Test
	@DisplayName("a failure moving the stock leaves the meal uncorrected — neither half survives alone")
	void bothHalvesRollBackTogether() throws Exception {
		UUID dish = plan("Lunch", khichdi, 500);
		record400(dish);

		doThrow(new IllegalStateException("the stock ledger is unavailable"))
				.when(stockMovementService).compensate(any(), any(UUID.class), anyString());

		mvc.perform(correct(serviceId(), """
				{"note":"640 went out","dishes":[{"mealPlanId":"%s","actualServings":640,
				 "consumedQuantity":null,"notMade":false}]}
				""".formatted(dish)))
				.andExpect(status().is5xxServerError());

		// The mark is gone with it. Without one transaction this meal would read "corrected to 640"
		// over a store room still drawn against 400, and nothing anywhere would say which was right.
		assertThat(admin.queryForObject(
				"SELECT corrected_at FROM meal_services WHERE id = ?",
				java.sql.Timestamp.class, serviceId())).isNull();
		assertThat(admin.queryForObject(
				"SELECT correction_note FROM meal_services WHERE id = ?",
				String.class, serviceId())).isNull();

		// And so is the dish half: the figure, and the shadow of the figure.
		assertThat(admin.queryForObject(
				"SELECT actual_servings FROM meal_plans WHERE id = ?", BigDecimal.class, dish))
				.isEqualByComparingTo("400");
		assertThat(admin.queryForObject(
				"SELECT original_actual_servings FROM meal_plans WHERE id = ?", BigDecimal.class, dish))
				.isNull();

		assertThat(consumed(rice)).isEqualByComparingTo("4000");
		assertThat(onHand(rice)).isEqualByComparingTo("46000");
		assertThat(auditCount("MEAL_CORRECTED")).isZero();
	}

	/** Acceptance 2. A meal is correctable once, so that "original" can mean the first figure. */
	@Test
	@DisplayName("correcting twice is refused with KMS-400137, and the second attempt moves nothing")
	void correctingTwiceIsRefused() throws Exception {
		UUID dish = plan("Lunch", khichdi, 500);
		record400(dish);

		String body = """
				{"note":"640 went out","dishes":[{"mealPlanId":"%s","actualServings":640,
				 "consumedQuantity":null,"notMade":false}]}
				""".formatted(dish);

		mvc.perform(correct(serviceId(), body)).andExpect(status().isOk());
		mvc.perform(correct(serviceId(), """
				{"note":"no, 700","dishes":[{"mealPlanId":"%s","actualServings":700,
				 "consumedQuantity":null,"notMade":false}]}
				""".formatted(dish)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400137"));

		// And only once against stock, which is the failure the refusal is really preventing: a
		// second correction would compensate an already-compensated set and draw the store down
		// twice for food cooked once.
		assertThat(onHand(rice)).isEqualByComparingTo("43600");
		assertThat(admin.queryForObject(
				"SELECT actual_servings FROM meal_plans WHERE id = ?", BigDecimal.class, dish))
				.isEqualByComparingTo("640");
		assertThat(admin.queryForObject(
				"SELECT original_actual_servings FROM meal_plans WHERE id = ?", BigDecimal.class, dish))
				.isEqualByComparingTo("400");
	}

	/**
	 * Acceptance 5, and D-4 in one test: the permission and not the role is what the endpoint asks
	 * for, and the two are not the same question.
	 *
	 * <p>Kitchen staff hold {@code MANAGE_MEAL_PLANS} — proved here by recording the meal as them,
	 * which succeeds — and do not hold {@code CORRECT_RECORDED_MEAL}. Asserting the refusal alone
	 * would be much weaker: a 403 from somebody who could not have recorded the meal either says
	 * nothing about which of the two permissions did the refusing.
	 */
	@Test
	@DisplayName("somebody who may record a meal may not correct one")
	void recordingDoesNotImplyCorrecting() throws Exception {
		UUID dish = plan("Lunch", khichdi, 500);

		signIn("uid-staff");
		mvc.perform(record("""
				{"planDate":"2025-03-17","mealKind":"Lunch","note":"As read off the card",
				 "dishes":[{"mealPlanId":"%s","actualServings":400,"notMade":false}]}
				""".formatted(dish)))
				.andExpect(status().isOk());

		mvc.perform(correct(serviceId(), """
				{"note":"640 went out","dishes":[{"mealPlanId":"%s","actualServings":640,
				 "consumedQuantity":null,"notMade":false}]}
				""".formatted(dish)))
				.andExpect(status().isForbidden());

		assertThat(admin.queryForObject(
				"SELECT corrected_at FROM meal_services WHERE id = ?",
				java.sql.Timestamp.class, serviceId())).isNull();
		assertThat(onHand(rice)).isEqualByComparingTo("46000");
	}

	// ---- The read capability that was missing ------------------------------

	/**
	 * The filter this feature could not have been built without: a meal is a <em>set</em> of
	 * movements, and until now the ledger could be narrowed by ingredient and by type and by nothing
	 * else.
	 *
	 * <p>Two dishes drawing two different ingredients, so that filtering by {@code referenceId}
	 * returns one and not both — a filter that quietly returned everything would pass a test written
	 * against a single-dish meal.
	 */
	@Test
	@DisplayName("the ledger can be asked what one dish drew, which is what a meal is a set of")
	void movementsCanBeFoundByWhatTheyWereDrawnFor() throws Exception {
		UUID first = plan("Lunch", khichdi, 500);
		UUID second = plan("Lunch", halwa, 500);

		mvc.perform(record("""
				{"planDate":"2025-03-17","mealKind":"Lunch","note":"As read off the card",
				 "dishes":[{"mealPlanId":"%s","actualServings":400,"notMade":false},
						   {"mealPlanId":"%s","actualServings":300,"notMade":false}]}
				""".formatted(first, second)))
				.andExpect(status().isOk());

		mvc.perform(authed(get("/api/v1/inventory/movements").param("referenceId", first.toString())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].ingredientName").value("Rice"))
				.andExpect(jsonPath("$[0].type").value("CONSUMPTION"))
				.andExpect(jsonPath("$[0].referenceType").value("MEAL_PLAN"))
				.andExpect(jsonPath("$[0].referenceId").value(first.toString()));

		// An id nothing was drawn against is an empty list, not everything. This is the assertion
		// that fails if the parameter is accepted and then ignored — which is exactly what an
		// unrecognised @RequestParam does, silently.
		mvc.perform(authed(get("/api/v1/inventory/movements")
						.param("referenceId", UUID.randomUUID().toString())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	// ---- The awkward cases -------------------------------------------------

	/**
	 * The trap named in the brief: {@code compensate} allows one correction per movement and refuses
	 * a second with {@code MOVEMENT_ALREADY_CORRECTED}. That refusal is right for the inventory
	 * screen and wrong here — the point of correcting a meal is that the shelf ends up where it
	 * should be, and for a movement somebody already reversed by hand it already is.
	 *
	 * <p>Letting the refusal out would be worse than a plain failure: it would abort <em>after</em>
	 * part of a multi-dish meal had been reversed, and would explain itself by naming a stock
	 * movement to somebody who was correcting a lunch.
	 */
	@Test
	@DisplayName("a draw somebody already reversed by hand is skipped, not refused")
	void aHandCorrectedDrawDoesNotBreakTheCorrection() throws Exception {
		UUID dish = plan("Lunch", khichdi, 500);
		record400(dish);

		// Somebody spots the wrong draw on the inventory screen and compensates it there first.
		String movementId = movementFor(dish);
		mvc.perform(authed(post("/api/v1/inventory/movements/{id}/compensate", movementId)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"note":"Drawn against the wrong figure"}
								""")))
				.andExpect(status().isCreated());
		assertThat(onHand(rice)).isEqualByComparingTo("50000");

		// Correcting the meal now finds nothing left standing to reverse, and says so by simply
		// drawing the new figure. The shelf ends where a 640-serving lunch leaves it either way.
		mvc.perform(correct(serviceId(), """
				{"note":"640 went out","dishes":[{"mealPlanId":"%s","actualServings":640,
				 "consumedQuantity":null,"notMade":false}]}
				""".formatted(dish)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.corrected").value(true));

		assertThat(onHand(rice)).isEqualByComparingTo("43600");
	}

	/**
	 * Correcting a dish <em>into</em> "not made": it reverses everything and draws nothing back, and
	 * the row moves to CANCELLED, which is the only state this schema has for saying so.
	 */
	@Test
	@DisplayName("a dish corrected to not-made gives back everything it drew and takes nothing")
	void correctingADishToNotMadeReversesItEntirely() throws Exception {
		UUID dish = plan("Lunch", khichdi, 500);
		record400(dish);

		mvc.perform(correct(serviceId(), """
				{"note":"It was never made — the pot went to Tuesday's event",
				 "dishes":[{"mealPlanId":"%s","actualServings":null,"consumedQuantity":null,
							"notMade":true}]}
				""".formatted(dish)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dishes[0].status").value("CANCELLED"))
				.andExpect(jsonPath("$.dishes[0].notMade").value(true))
				.andExpect(jsonPath("$.dishes[0].actualServings").value(0.0))
				// The only surviving trace of what it was recorded as. The ledger cannot answer this
				// one: a dish that drew nothing left nothing to divide back through the recipe.
				.andExpect(jsonPath("$.dishes[0].originalActualServings").value(400.0));

		assertThat(onHand(rice)).isEqualByComparingTo("50000");
		assertThat(admin.queryForObject(
				"SELECT cooked_at FROM meal_plans WHERE id = ?", java.sql.Timestamp.class, dish))
				.isNull();
	}

	/**
	 * A dish whose figures did not move is left entirely alone — no ledger rows, no
	 * {@code original_actual_servings}.
	 *
	 * <p>Restating a figure is not changing it. Two dishes here and only one corrected: the untouched
	 * one must come back with a null original, or the planner would offer "300 cooked, corrected from
	 * 300" on every unchanged preparation of every corrected meal. The ghee assertion is the same
	 * claim made where it cannot be argued with — a pair of movements netting to nothing is still two
	 * rows on a table whose only consumer is a sum.
	 */
	@Test
	@DisplayName("a dish restated unchanged is not touched, in the ledger or on the row")
	void anUnchangedDishIsLeftAlone() throws Exception {
		UUID first = plan("Lunch", khichdi, 500);
		UUID second = plan("Lunch", halwa, 500);

		mvc.perform(record("""
				{"planDate":"2025-03-17","mealKind":"Lunch","note":"As read off the card",
				 "dishes":[{"mealPlanId":"%s","actualServings":400,"notMade":false},
						   {"mealPlanId":"%s","actualServings":300,"notMade":false}]}
				""".formatted(first, second)))
				.andExpect(status().isOk());

		mvc.perform(correct(serviceId(), """
				{"note":"The khichdi was 640; the halwa was right",
				 "dishes":[{"mealPlanId":"%s","actualServings":640,"consumedQuantity":null,
							"notMade":false},
						   {"mealPlanId":"%s","actualServings":300,"consumedQuantity":null,
							"notMade":false}]}
				""".formatted(first, second)))
				.andExpect(status().isOk());

		assertThat(admin.queryForObject(
				"SELECT original_actual_servings FROM meal_plans WHERE id = ?", BigDecimal.class, first))
				.isEqualByComparingTo("400");
		assertThat(admin.queryForObject(
				"SELECT original_actual_servings FROM meal_plans WHERE id = ?", BigDecimal.class, second))
				.isNull();

		// The halwa drew three kilos of ghee once and has not been touched since.
		assertThat(consumed(ghee)).isEqualByComparingTo("3000");
		assertThat(onHand(ghee)).isEqualByComparingTo("47000");
	}

	/** A correction that changes nothing at all would badge the meal for a change nobody made. */
	@Test
	@DisplayName("a correction that corrects nothing is refused rather than recorded")
	void aCorrectionThatChangesNothingIsRefused() throws Exception {
		UUID dish = plan("Lunch", khichdi, 500);
		record400(dish);

		mvc.perform(correct(serviceId(), """
				{"note":"Checking the card again","dishes":[{"mealPlanId":"%s","actualServings":400,
				 "consumedQuantity":null,"notMade":false}]}
				""".formatted(dish)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));

		assertThat(admin.queryForObject(
				"SELECT corrected_at FROM meal_services WHERE id = ?",
				java.sql.Timestamp.class, serviceId())).isNull();
	}

	/** Silence is not an answer here either, exactly as it is not when recording. */
	@Test
	@DisplayName("a dish the correction left out is refused rather than assumed unchanged")
	void everyDishMustBeAccountedFor() throws Exception {
		UUID first = plan("Lunch", khichdi, 500);
		UUID second = plan("Lunch", halwa, 500);

		mvc.perform(record("""
				{"planDate":"2025-03-17","mealKind":"Lunch","note":"As read off the card",
				 "dishes":[{"mealPlanId":"%s","actualServings":400,"notMade":false},
						   {"mealPlanId":"%s","actualServings":300,"notMade":false}]}
				""".formatted(first, second)))
				.andExpect(status().isOk());

		mvc.perform(correct(serviceId(), """
				{"note":"640 went out","dishes":[{"mealPlanId":"%s","actualServings":640,
				 "consumedQuantity":null,"notMade":false}]}
				""".formatted(first)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400009"));

		assertThat(onHand(rice)).isEqualByComparingTo("46000");
	}

	/** A correction with no reason is unreadable a month later, which is when somebody asks. */
	@Test
	@DisplayName("a correction with no reason is refused")
	void aReasonIsRequired() throws Exception {
		UUID dish = plan("Lunch", khichdi, 500);
		record400(dish);

		mvc.perform(correct(serviceId(), """
				{"note":"   ","dishes":[{"mealPlanId":"%s","actualServings":640,
				 "consumedQuantity":null,"notMade":false}]}
				""".formatted(dish)))
				.andExpect(status().isBadRequest());

		assertThat(admin.queryForObject(
				"SELECT corrected_at FROM meal_services WHERE id = ?",
				java.sql.Timestamp.class, serviceId())).isNull();
	}

	// ---- What a correction does NOT move, pinned deliberately ---------------

	/**
	 * <strong>A finding written as a test, not a fix.</strong>
	 *
	 * <p>The task's acceptance asked that cost-per-serving "recompute from the corrected number". It
	 * does not, and it should not: {@code MealKindCostService:167} costs each dish at
	 * {@code mp.target_yield} — what was <em>planned</em> — and divides by the head count, and its
	 * own comment at :160 says why in as many words ("The dish is costed at what was planned, not at
	 * what the returned job card said was cooked… a period of days must add up to the days in it").
	 * A correction moves {@code actual_servings}; it moves neither of the columns this report reads.
	 *
	 * <p>So this pins the behaviour rather than changing it. If somebody later decides the report
	 * should follow the recorded figure, that is a deliberate change to a documented decision in a
	 * file outside this task's contract — and this test is what will make them notice they are making
	 * it, instead of finding out from a temple whose costs moved overnight.
	 */
	@Test
	@DisplayName("cost per serving is computed from the plan, so a corrected figure does not move it")
	void costPerServingIsUnmovedByACorrection() throws Exception {
		UUID dish = plan("Lunch", khichdi, 500, 400, 0, 0);
		price(rice, "60");
		record400(dish);

		BigDecimal before = costPerServing();
		assertThat(before).isNotNull();

		mvc.perform(correct(serviceId(), """
				{"note":"640 went out","dishes":[{"mealPlanId":"%s","actualServings":640,
				 "consumedQuantity":null,"notMade":false}]}
				""".formatted(dish)))
				.andExpect(status().isOk());

		assertThat(costPerServing()).isEqualByComparingTo(before);
	}

	// ---- Two dishes, one ingredient, opposite directions (T-083) ------------

	/**
	 * <strong>The order-dependence, pinned from both ends.</strong>
	 *
	 * <p>A lunch of two rice dishes swapping figures — khichadi 400 → 640, pulao 640 → 400 — moves no
	 * rice at all on net, and the store room is holding exactly what the meal drew and not one gram
	 * more. Corrected a dish at a time, the first dish's re-draw is asked for while the second dish's
	 * old draw is still standing, and {@code FefoAllocator} sums {@code stock_movements}: it sees the
	 * one reversal that has happened and none of the ones that are about to, and refuses with
	 * {@code KMS-400042} for a shortfall that does not exist thirty lines later.
	 *
	 * <p>Which dish is reached first is decided by {@code mp.ready_by} — {@code MealPlanService:195}
	 * orders by date, then ready-by, then kind — so this is written twice, once with the rising dish
	 * due first and once with the falling dish due first, and both must pass. One of the two would have
	 * passed against the broken code: <em>reverse the dish order and the same correction succeeds</em>
	 * is the defect's own signature, so a single-order test proves nothing about it. Pinning both is
	 * the assertion that the answer no longer depends on the sort.
	 *
	 * <p>Its own ingredient and its own two recipes, rather than the 50 Kg of rice the other tests
	 * lean on: the whole scenario is "the temple is holding no spare", and against a comfortable shelf
	 * the broken code passes too.
	 */
	@Test
	@DisplayName("two dishes swapping figures against tight stock: the rising dish first")
	void oppositeCorrectionsSucceedWithTheRisingDishFirst() throws Exception {
		swappedRiceDishes(true);
	}

	/** The same correction, the same tight shelf, the plan sorted the other way round. */
	@Test
	@DisplayName("two dishes swapping figures against tight stock: the falling dish first")
	void oppositeCorrectionsSucceedWithTheFallingDishFirst() throws Exception {
		swappedRiceDishes(false);
	}

	/**
	 * Records a two-dish lunch that draws the shelf to exactly zero, then swaps the two figures.
	 *
	 * @param risingFirst whether the dish going 400 → 640 is the earlier of the two by ready-by, and
	 *     so the one the correction reaches first
	 */
	private void swappedRiceDishes(boolean risingFirst) throws Exception {
		// 1 Kg per 100 servings, as everything else in this class. 400 servings of one and 640 of the
		// other is 10.4 Kg, and 10.4 Kg is every grain the temple has.
		UUID sonaMasuri = ingredient("Sona Masuri");
		UUID category = admin.queryForObject(
				"SELECT id FROM recipe_categories WHERE tenant_id = ? LIMIT 1", UUID.class, tenant);
		UUID khichadi = recipe("Rice Khichadi", category);
		line(khichadi, sonaMasuri, "1");
		UUID pulao = recipe("Rice Pulao", category);
		line(pulao, sonaMasuri, "1");
		stock(sonaMasuri, "10.4");

		// ready_by is what decides which dish the correction reaches first, so it is what this test
		// varies. Everything else about the two runs is identical.
		UUID rising = plan("Lunch", khichadi, 500, risingFirst ? "12:00" : "12:30");
		UUID falling = plan("Lunch", pulao, 500, risingFirst ? "12:30" : "12:00");

		mvc.perform(record("""
				{"planDate":"2025-03-17","mealKind":"Lunch","note":"As read off the card",
				 "dishes":[{"mealPlanId":"%s","actualServings":400,"notMade":false},
						   {"mealPlanId":"%s","actualServings":640,"notMade":false}]}
				""".formatted(rising, falling)))
				.andExpect(status().isOk());

		// Drawn to the grain: 4 Kg and 6.4 Kg out of 10.4 Kg.
		assertThat(consumed(sonaMasuri)).isEqualByComparingTo("10400");
		assertThat(onHand(sonaMasuri)).isEqualByComparingTo("0");

		// The card was read across the wrong two rows. Neither figure is new to the store room; they
		// have swapped dishes, and a temple that cooked this food is entitled to be believed.
		mvc.perform(correct(serviceId(), """
				{"note":"The two rice dishes were entered against each other",
				 "dishes":[{"mealPlanId":"%s","actualServings":640,"consumedQuantity":null,
							"notMade":false},
						   {"mealPlanId":"%s","actualServings":400,"consumedQuantity":null,
							"notMade":false}]}
				""".formatted(rising, falling)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.corrected").value(true));

		// The net movement matches the net figure: 1040 servings of rice dishes went out before the
		// correction and 1040 after it, so the shelf is where it was — at zero — and not at the 2.4 Kg
		// deficit or the refusal a dish-at-a-time correction would have produced.
		assertThat(onHand(sonaMasuri)).isEqualByComparingTo("0");
		// Every draw is still readable: 4 and 6.4 out, both given back, 6.4 and 4 out again.
		assertThat(consumed(sonaMasuri)).isEqualByComparingTo("20800");

		assertThat(admin.queryForObject(
				"SELECT actual_servings FROM meal_plans WHERE id = ?", BigDecimal.class, rising))
				.isEqualByComparingTo("640");
		assertThat(admin.queryForObject(
				"SELECT original_actual_servings FROM meal_plans WHERE id = ?", BigDecimal.class, rising))
				.isEqualByComparingTo("400");
		assertThat(admin.queryForObject(
				"SELECT actual_servings FROM meal_plans WHERE id = ?", BigDecimal.class, falling))
				.isEqualByComparingTo("400");
		assertThat(admin.queryForObject(
				"SELECT original_actual_servings FROM meal_plans WHERE id = ?", BigDecimal.class, falling))
				.isEqualByComparingTo("640");

		// Both dishes were corrected, and both said so.
		assertThat(auditCount("MEAL_CORRECTED")).isEqualTo(2);
	}

	// ---------------------------------------------------------------------

	/** Records the meal at 400 as the admin, which every correction test starts from. */
	private void record400(UUID dish) throws Exception {
		mvc.perform(record("""
				{"planDate":"2025-03-17","mealKind":"Lunch","note":"As read off the card",
				 "dishes":[{"mealPlanId":"%s","actualServings":400,"notMade":false}]}
				""".formatted(dish)))
				.andExpect(status().isOk());
	}

	private UUID serviceId() {
		return admin.queryForObject(
				"SELECT id FROM meal_services WHERE plan_date = DATE '2025-03-17' AND meal_kind = 'Lunch'",
				UUID.class);
	}

	private String movementFor(UUID mealPlanId) {
		return admin.queryForObject("""
				SELECT id::text FROM stock_movements
				WHERE reference_type = 'MEAL_PLAN' AND reference_id = ?
				""", String.class, mealPlanId);
	}

	/** What was ever drawn: CONSUMPTION rows only, so a reversal does not reduce it. */
	private BigDecimal consumed(UUID ingredient) {
		return admin.queryForObject("""
				SELECT COALESCE(-SUM(to_base_qty(quantity, unit)), 0)
				FROM stock_movements
				WHERE ingredient_id = ? AND movement_type = 'CONSUMPTION'
				""", BigDecimal.class, ingredient);
	}

	/** What the shelf holds: every row, which is what "current stock is a sum" actually means. */
	private BigDecimal onHand(UUID ingredient) {
		return admin.queryForObject("""
				SELECT COALESCE(SUM(to_base_qty(quantity, unit)), 0)
				FROM stock_movements WHERE ingredient_id = ?
				""", BigDecimal.class, ingredient);
	}

	private BigDecimal costPerServing() throws Exception {
		String body = mvc.perform(authed(get("/api/v1/materials-cost/by-meal-kind")
						.param("from", "2025-03-01").param("to", "2025-03-31")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		com.fasterxml.jackson.databind.JsonNode rows =
				new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("kinds");
		assertThat(rows).isNotNull();
		assertThat(rows.size()).isEqualTo(1);
		return rows.get(0).get("costPerServing").decimalValue();
	}

	private int auditCount(String action) {
		Integer count = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = ?", Integer.class, action);
		return count == null ? 0 : count;
	}

	private UUID plan(String kind, UUID recipe, int servings) {
		return plan(kind, recipe, servings, null, null, null);
	}

	/**
	 * A dish due at a stated hour. Every other test leaves this at noon because it does not care;
	 * the two-dish corrections do, because ready-by is what orders the dishes of a meal
	 * ({@code MealPlanService:195}) and so decides which one a correction reaches first.
	 */
	private UUID plan(String kind, UUID recipe, int servings, String readyBy) {
		return admin.queryForObject("""
				INSERT INTO meal_plans (tenant_id, plan_date, meal_kind, ready_by, recipe_id,
						target_yield, day_type, status, created_by)
				VALUES (?, DATE '2025-03-17', ?, CAST(? AS time), ?, ?, 'REGULAR', 'PLANNED',
						(SELECT id FROM users WHERE firebase_uid = 'uid-admin'))
				RETURNING id
				""", UUID.class, tenant, kind, readyBy, recipe, BigDecimal.valueOf(servings));
	}

	private UUID plan(String kind, UUID recipe, int servings,
			Integer adults, Integer children, Integer seniors) {
		return admin.queryForObject("""
				INSERT INTO meal_plans (tenant_id, plan_date, meal_kind, ready_by, recipe_id,
						target_yield, day_type, status, adults, children, seniors, created_by)
				VALUES (?, DATE '2025-03-17', ?, TIME '12:00', ?, ?, 'REGULAR', 'PLANNED', ?, ?, ?,
						(SELECT id FROM users WHERE firebase_uid = 'uid-admin'))
				RETURNING id
				""", UUID.class, tenant, kind, recipe, BigDecimal.valueOf(servings),
				adults, children, seniors);
	}

	private UUID ingredient(String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Staples', 'KG') RETURNING id
				""", UUID.class, tenant, name);
	}

	/**
	 * A price for the costing report to work from.
	 *
	 * <p>Prices live on {@code vendor_supplies}, not on the ingredient — {@code BasketCostingService}
	 * takes the preferred vendor's last price, and the dearest on record where no vendor is
	 * preferred. So a costing test has to have a vendor.
	 */
	private void price(UUID ingredient, String rupeesPerKg) {
		UUID vendor = admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Sri Traders', '+919876500001')
				RETURNING id
				""", UUID.class, tenant);
		admin.update("""
				INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price, preferred)
				VALUES (?, ?, ?, CAST(? AS numeric), true)
				""", tenant, vendor, ingredient, rupeesPerKg);
	}

	private UUID recipe(String name, UUID category) {
		return admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, ?, ?, 100, 'KG') RETURNING id
				""", UUID.class, tenant, name, category);
	}

	private void line(UUID recipe, UUID ingredient, String quantity) {
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, CAST(? AS numeric), 'KG', 0)
				""", tenant, recipe, ingredient, quantity);
	}

	private void stock(UUID ingredient, String kilos) {
		admin.update("""
				INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit,
						movement_type, actor_user_id)
				VALUES (?, ?, ?, CAST(? AS numeric), 'KG', 'PO_RECEIPT',
						(SELECT id FROM users WHERE firebase_uid = 'uid-admin'))
				""", tenant, ingredient, UUID.randomUUID(), kilos);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request) {
		return request.header("Authorization", "Bearer valid-token");
	}

	private MockHttpServletRequestBuilder record(String json) {
		return authed(post("/api/v1/meal-services/record"))
				.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder correct(UUID serviceId, String json) {
		return authed(post("/api/v1/meal-services/{id}/correct", serviceId))
				.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private void insertUser(String uid, String email, String role, String name) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
				""", tenant, uid, name, email, "+9198765000" + Math.abs(uid.hashCode() % 90 + 10), role);
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
