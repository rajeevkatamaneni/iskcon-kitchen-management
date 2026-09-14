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
import java.time.LocalTime;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.calendar.CalendarService;
import org.iskcon.kms.inventory.StockMovementService;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
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
 * what each row does to the shelf and so says what the temple actually holds. A correction is
 * exactly the operation where those two diverge, and asserting only the first would let a reversal
 * that never happened pass.
 *
 * <p>{@code onHand()} is a sum of effects rather than of quantities, which is a distinction T-122
 * had to draw: a {@code USED_BEYOND_RECORDED_STOCK} row records that a meal was cooked with more
 * than the books held, and counts as zero, because on hand is a count of a shelf and no shelf holds
 * less than nothing.
 *
 * <p>Since D-27 a meal has its own row, and the correction is addressed by that row's id — the id the
 * meal had from the moment it was planned, rather than the id of a second row made on first print.
 */
@AutoConfigureMockMvc
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
		MealFixture.deleteAll(admin);
		admin.execute("DELETE FROM stock_movements");
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

		mvc.perform(correct(mealId(), """
				{"note":"The card was read as 400; the kitchen confirms 640 went out",
				 "dishes":[{"dishId":"%s","actualServings":640,"consumedQuantity":600,
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
		mvc.perform(authed(get("/api/v1/meals")
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

		mvc.perform(correct(mealId(), """
				{"note":"640 went out","dishes":[{"dishId":"%s","actualServings":640,
				 "consumedQuantity":null,"notMade":false}]}
				""".formatted(dish)))
				.andExpect(status().is5xxServerError());

		// The mark is gone with it. Without one transaction this meal would read "corrected to 640"
		// over a store room still drawn against 400, and nothing anywhere would say which was right.
		assertThat(admin.queryForObject(
				"SELECT corrected_at FROM meals WHERE id = ?",
				java.sql.Timestamp.class, mealId())).isNull();
		assertThat(admin.queryForObject(
				"SELECT correction_note FROM meals WHERE id = ?",
				String.class, mealId())).isNull();

		// And so is the dish half: the figure, and the shadow of the figure.
		assertThat(admin.queryForObject(
				"SELECT actual_servings FROM meal_dishes WHERE id = ?", BigDecimal.class, dish))
				.isEqualByComparingTo("400");
		assertThat(admin.queryForObject(
				"SELECT original_actual_servings FROM meal_dishes WHERE id = ?", BigDecimal.class, dish))
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
				{"note":"640 went out","dishes":[{"dishId":"%s","actualServings":640,
				 "consumedQuantity":null,"notMade":false}]}
				""".formatted(dish);

		mvc.perform(correct(mealId(), body)).andExpect(status().isOk());
		mvc.perform(correct(mealId(), """
				{"note":"no, 700","dishes":[{"dishId":"%s","actualServings":700,
				 "consumedQuantity":null,"notMade":false}]}
				""".formatted(dish)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400137"));

		// And only once against stock, which is the failure the refusal is really preventing: a
		// second correction would compensate an already-compensated set and draw the store down
		// twice for food cooked once.
		assertThat(onHand(rice)).isEqualByComparingTo("43600");
		assertThat(admin.queryForObject(
				"SELECT actual_servings FROM meal_dishes WHERE id = ?", BigDecimal.class, dish))
				.isEqualByComparingTo("640");
		assertThat(admin.queryForObject(
				"SELECT original_actual_servings FROM meal_dishes WHERE id = ?", BigDecimal.class, dish))
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
				{"note":"As read off the card",
				 "dishes":[{"dishId":"%s","actualServings":400,"notMade":false}]}
				""".formatted(dish)))
				.andExpect(status().isOk());

		mvc.perform(correct(mealId(), """
				{"note":"640 went out","dishes":[{"dishId":"%s","actualServings":640,
				 "consumedQuantity":null,"notMade":false}]}
				""".formatted(dish)))
				.andExpect(status().isForbidden());

		assertThat(admin.queryForObject(
				"SELECT corrected_at FROM meals WHERE id = ?",
				java.sql.Timestamp.class, mealId())).isNull();
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
				{"note":"As read off the card",
				 "dishes":[{"dishId":"%s","actualServings":400,"notMade":false},
						   {"dishId":"%s","actualServings":300,"notMade":false}]}
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
		mvc.perform(correct(mealId(), """
				{"note":"640 went out","dishes":[{"dishId":"%s","actualServings":640,
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

		mvc.perform(correct(mealId(), """
				{"note":"It was never made — the pot went to Tuesday's event",
				 "dishes":[{"dishId":"%s","actualServings":null,"consumedQuantity":null,
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
				"SELECT cooked_at FROM meal_dishes WHERE id = ?", java.sql.Timestamp.class, dish))
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
				{"note":"As read off the card",
				 "dishes":[{"dishId":"%s","actualServings":400,"notMade":false},
						   {"dishId":"%s","actualServings":300,"notMade":false}]}
				""".formatted(first, second)))
				.andExpect(status().isOk());

		mvc.perform(correct(mealId(), """
				{"note":"The khichdi was 640; the halwa was right",
				 "dishes":[{"dishId":"%s","actualServings":640,"consumedQuantity":null,
							"notMade":false},
						   {"dishId":"%s","actualServings":300,"consumedQuantity":null,
							"notMade":false}]}
				""".formatted(first, second)))
				.andExpect(status().isOk());

		assertThat(admin.queryForObject(
				"SELECT original_actual_servings FROM meal_dishes WHERE id = ?", BigDecimal.class, first))
				.isEqualByComparingTo("400");
		assertThat(admin.queryForObject(
				"SELECT original_actual_servings FROM meal_dishes WHERE id = ?", BigDecimal.class, second))
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

		mvc.perform(correct(mealId(), """
				{"note":"Checking the card again","dishes":[{"dishId":"%s","actualServings":400,
				 "consumedQuantity":null,"notMade":false}]}
				""".formatted(dish)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));

		assertThat(admin.queryForObject(
				"SELECT corrected_at FROM meals WHERE id = ?",
				java.sql.Timestamp.class, mealId())).isNull();
	}

	/** Silence is not an answer here either, exactly as it is not when recording. */
	@Test
	@DisplayName("a dish the correction left out is refused rather than assumed unchanged")
	void everyDishMustBeAccountedFor() throws Exception {
		UUID first = plan("Lunch", khichdi, 500);
		UUID second = plan("Lunch", halwa, 500);

		mvc.perform(record("""
				{"note":"As read off the card",
				 "dishes":[{"dishId":"%s","actualServings":400,"notMade":false},
						   {"dishId":"%s","actualServings":300,"notMade":false}]}
				""".formatted(first, second)))
				.andExpect(status().isOk());

		mvc.perform(correct(mealId(), """
				{"note":"640 went out","dishes":[{"dishId":"%s","actualServings":640,
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

		mvc.perform(correct(mealId(), """
				{"note":"   ","dishes":[{"dishId":"%s","actualServings":640,
				 "consumedQuantity":null,"notMade":false}]}
				""".formatted(dish)))
				.andExpect(status().isBadRequest());

		assertThat(admin.queryForObject(
				"SELECT corrected_at FROM meals WHERE id = ?",
				java.sql.Timestamp.class, mealId())).isNull();
	}

	// ---- What a correction moves beyond the store room -----------------------

	/**
	 * <strong>A correction changes what the meal cost, because it changes what was cooked (T-212).</strong>
	 *
	 * <p>This test used to pin the opposite. Costing priced every dish at what was planned, so a
	 * correction moved the store room and left the cost where it was, and the test was written to make
	 * anyone changing that notice. Rajeev then ruled that costing follows actuals ("Costing follows
	 * actuals, option 1"): a recorded meal is costed at what its job card says was cooked. Stock was
	 * already drawn on that figure, so the cost and the store room now agree about the same meal, and
	 * when a correction re-draws the stock it moves the cost with it.
	 *
	 * <p>Khichdi is 1 Kg of rice per 100 and rice is ₹60 a Kg, for 400 people. Planned at 500 it is
	 * ₹300, ₹0.75 a serving. Recorded at 400 it is ₹240, ₹0.60. Corrected to 640 it is ₹384, ₹0.96.
	 * Both screens are checked, the report per serving and the day's figure, because they are one rule.
	 */
	@Test
	@DisplayName("a correction changes the meal's cost, because it changes what was cooked")
	void aCorrectionMovesTheCost() throws Exception {
		UUID dish = plan("Lunch", khichdi, 500, 400, 0, 0);
		price(rice, "60");

		assertThat(costPerServing()).isEqualByComparingTo("0.75");
		assertThat(dayCost()).isEqualByComparingTo("300");

		record400(dish);
		assertThat(costPerServing()).isEqualByComparingTo("0.60");
		assertThat(dayCost()).isEqualByComparingTo("240");

		mvc.perform(correct(mealId(), """
				{"note":"640 went out","dishes":[{"dishId":"%s","actualServings":640,
				 "consumedQuantity":null,"notMade":false}]}
				""".formatted(dish)))
				.andExpect(status().isOk());

		assertThat(costPerServing()).isEqualByComparingTo("0.96");
		assertThat(dayCost()).isEqualByComparingTo("384");
	}

	// ---- What a correction gives back ---------------------------------------

	/**
	 * <strong>A shortfall booked at recording is given back when the meal is corrected (T-087).</strong>
	 *
	 * <p>This is the claim that made the shortfall carry {@code MEAL_PLAN} and the dish's own id
	 * rather than a reference of its own: {@code compensateAllFor} finds everything standing against
	 * that pair, so the row travels with the draws beside it. Filed under anything else it would be
	 * the one row a correction walked silently past, and the correction would leave the temple 2.4 Kg
	 * in a hole that nothing in the application could explain — which is a worse version of exactly
	 * the defect T-087 was written to close.
	 *
	 * <p>The shape is the office's ordinary mistake, not an exotic one: a card misread as 640 when it
	 * said 400. At 640 the books could not cover it and the recording stood anyway, booking the
	 * difference. At 400 they always could. Corrected, the store room reads zero.
	 */
	@Test
	@DisplayName("a shortfall booked at recording is given back when the meal is corrected down")
	void aBookedShortfallIsReversedByACorrection() throws Exception {
		UUID ragi = ingredient("Ragi");
		UUID category = admin.queryForObject(
				"SELECT id FROM recipe_categories WHERE tenant_id = ? LIMIT 1", UUID.class, tenant);
		UUID mudde = recipe("Ragi Mudde", category);
		line(mudde, ragi, "1");
		stock(ragi, "4");

		UUID dish = plan("Lunch", mudde, 700);

		// 640 servings wants 6.4 Kg against the 4 Kg the books hold. It is recorded, not refused,
		// and the 2.4 Kg nobody can account for is booked as its own movement.
		mvc.perform(record("""
				{"note":"As read off the card",
				 "dishes":[{"dishId":"%s","actualServings":640,"notMade":false}]}
				""".formatted(dish)))
				.andExpect(status().isOk());

		assertThat(usedBeyondRecordedStock(ragi)).isEqualTo(1);
		assertThat(onHand(ragi))
				.as("4 Kg out and the shelf empty — not minus 2.4 Kg, which is no quantity of ragi (T-122)")
				.isEqualByComparingTo("0");

		// The card said 400, which the shelf covered all along.
		mvc.perform(correct(mealId(), """
				{"note":"Misread off the card; it said 400","dishes":[{"dishId":"%s",
				 "actualServings":400,"consumedQuantity":null,"notMade":false}]}
				""".formatted(dish)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.corrected").value(true));

		assertThat(onHand(ragi))
				.as("4 Kg in, 4 Kg out. And the shelf did not gain 2.4 Kg from the retraction either")
				.isEqualByComparingTo("0");
		assertThat(standingShortfalls(ragi))
				.as("the discrepancy was retracted with the draws: there is nothing left to chase")
				.isZero();
		assertThat(usedBeyondRecordedStock(ragi))
				.as("two rows now, and neither moved stock: the one that was raised and the one that "
						+ "withdrew it. No second shortfall was booked — the re-draw met the shelf the "
						+ "meal was cooked against")
				.isEqualTo(2);
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
	 * <p>Which dish is reached first is decided by the order the meal's dishes are read in — by ready-by
	 * before D-27, by the order they were added since, the ready-by being the meal's — so this is
	 * written twice, once with the rising dish first and once with the falling dish first, and both
	 * must pass. One of the two would have
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
	 * @param risingFirst whether the dish going 400 → 640 is the earlier of the two in the meal, and
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

		// Which dish the correction reaches first is what this test varies. Since D-27 the two dishes
		// share their meal's one ready-by, and a meal's dishes are read in the order they were added
		// (ServedMealService's DISH_ORDER), so the order of these two inserts is that order. Everything
		// else about the two runs is identical.
		UUID rising;
		UUID falling;
		if (risingFirst) {
			rising = plan("Lunch", khichadi, 500);
			falling = plan("Lunch", pulao, 500);
		} else {
			falling = plan("Lunch", pulao, 500);
			rising = plan("Lunch", khichadi, 500);
		}

		mvc.perform(record("""
				{"note":"As read off the card",
				 "dishes":[{"dishId":"%s","actualServings":400,"notMade":false},
						   {"dishId":"%s","actualServings":640,"notMade":false}]}
				""".formatted(rising, falling)))
				.andExpect(status().isOk());

		// Drawn to the grain: 4 Kg and 6.4 Kg out of 10.4 Kg.
		assertThat(consumed(sonaMasuri)).isEqualByComparingTo("10400");
		assertThat(onHand(sonaMasuri)).isEqualByComparingTo("0");
		assertThat(usedBeyondRecordedStock(sonaMasuri))
				.as("the temple was holding every grain of this, so nothing is booked as missing")
				.isZero();

		// The card was read across the wrong two rows. Neither figure is new to the store room; they
		// have swapped dishes, and a temple that cooked this food is entitled to be believed.
		mvc.perform(correct(mealId(), """
				{"note":"The two rice dishes were entered against each other",
				 "dishes":[{"dishId":"%s","actualServings":640,"consumedQuantity":null,
							"notMade":false},
						   {"dishId":"%s","actualServings":400,"consumedQuantity":null,
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
				"SELECT actual_servings FROM meal_dishes WHERE id = ?", BigDecimal.class, rising))
				.isEqualByComparingTo("640");
		assertThat(admin.queryForObject(
				"SELECT original_actual_servings FROM meal_dishes WHERE id = ?", BigDecimal.class, rising))
				.isEqualByComparingTo("400");
		assertThat(admin.queryForObject(
				"SELECT actual_servings FROM meal_dishes WHERE id = ?", BigDecimal.class, falling))
				.isEqualByComparingTo("400");
		assertThat(admin.queryForObject(
				"SELECT original_actual_servings FROM meal_dishes WHERE id = ?", BigDecimal.class, falling))
				.isEqualByComparingTo("640");

		// Both dishes were corrected, and both said so.
		assertThat(auditCount("MEAL_CORRECTED")).isEqualTo(2);

		// T-087's half of the same guarantee, and the reason the two tasks were sequenced.
		// Recording no longer refuses a shortfall — it books one, as a movement that says an
		// ingredient's paperwork is behind. This correction nets to zero: the same 10.4 Kg went out
		// before it and after it, and the temple was holding all of it. Not one gram may be booked
		// as missing. Had the dish-at-a-time ordering survived, the rising dish's re-draw would
		// have met a shelf still 6.4 Kg short and written exactly that false row — into the one
		// list somebody is meant to be able to trust, silently, where the old code at least
		// refused out loud.
		assertThat(usedBeyondRecordedStock(sonaMasuri))
				.as("a correction that nets to zero books no shortfall at all")
				.isZero();
		assertThat(onHand(sonaMasuri)).isEqualByComparingTo("0");
	}

	// ---------------------------------------------------------------------

	/** Records the meal at 400 as the admin, which every correction test starts from. */
	private void record400(UUID dish) throws Exception {
		mvc.perform(record("""
				{"note":"As read off the card",
				 "dishes":[{"dishId":"%s","actualServings":400,"notMade":false}]}
				""".formatted(dish)))
				.andExpect(status().isOk());
	}

	/** The day's Lunch, by its own row (D-27). */
	private UUID mealId() {
		return admin.queryForObject("""
				SELECT m.id FROM meals m
				JOIN meal_plan_days pd ON pd.id = m.meal_plan_day_id
				JOIN meal_kinds k ON k.id = m.meal_kind_id
				WHERE pd.plan_date = DATE '2025-03-17' AND k.name = 'Lunch'
				""", UUID.class);
	}

	private String movementFor(UUID dishId) {
		return admin.queryForObject("""
				SELECT id::text FROM stock_movements
				WHERE reference_type = 'MEAL_PLAN' AND reference_id = ?
				""", String.class, dishId);
	}

	/** What was ever drawn: CONSUMPTION rows only, so a reversal does not reduce it. */
	private BigDecimal consumed(UUID ingredient) {
		return admin.queryForObject("""
				SELECT COALESCE(-SUM(to_base_qty(quantity, unit)), 0)
				FROM stock_movements
				WHERE ingredient_id = ? AND movement_type = 'CONSUMPTION'
				""", BigDecimal.class, ingredient);
	}

	/**
	 * What the shelf holds, computed the way the application computes it (V116, T-122).
	 *
	 * <p>It used to sum every row, on the reading that "current stock is a sum" meant a sum of
	 * quantities. It is a sum of <em>effects</em>: a {@code USED_BEYOND_RECORDED_STOCK} row records
	 * that a meal was cooked with more than the books held, and no shelf went down by it, so it
	 * counts as zero — which is what stops this figure landing at minus forty kilos.
	 */
	private BigDecimal onHand(UUID ingredient) {
		return admin.queryForObject("""
				SELECT COALESCE(SUM(to_on_hand_qty(quantity, unit, movement_type)), 0)
				FROM stock_movements WHERE ingredient_id = ?
				""", BigDecimal.class, ingredient);
	}

	/**
	 * How many movements say the kitchen used more of this than the books held (T-087).
	 *
	 * <p>Counted rather than summed, deliberately. The claim being tested is that no such row exists
	 * at all: a sum would read zero for "none written" and for "two written that happen to cancel",
	 * and the second of those is precisely the false state a dish-at-a-time correction would leave.
	 */
	/**
	 * How many of those rows still stand — raised and not since retracted (T-122).
	 *
	 * <p>Distinct from the count above and both are needed. A meal corrected down retracts its
	 * discrepancy <em>in kind</em>, because a row that moves no stock cannot be reversed by an
	 * adjustment that does; so after a correction the ledger holds two rows of the kind and nothing
	 * outstanding. The raw count says what was written, this says what is left to chase.
	 */
	private int standingShortfalls(UUID ingredient) {
		Integer count = admin.queryForObject("""
				SELECT count(*) FROM stock_movements m
				WHERE m.ingredient_id = ? AND m.movement_type = 'USED_BEYOND_RECORDED_STOCK'
				  AND (m.reference_type IS NULL OR m.reference_type <> 'CORRECTION')
				  AND NOT EXISTS (
					  SELECT 1 FROM stock_movements c
					  WHERE c.reference_type = 'CORRECTION' AND c.reference_id = m.id)
				""", Integer.class, ingredient);
		return count == null ? 0 : count;
	}

	private int usedBeyondRecordedStock(UUID ingredient) {
		Integer count = admin.queryForObject("""
				SELECT count(*) FROM stock_movements
				WHERE ingredient_id = ? AND movement_type = 'USED_BEYOND_RECORDED_STOCK'
				""", Integer.class, ingredient);
		return count == null ? 0 : count;
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

	/** The daily figure for the day every meal in this class is on. */
	private BigDecimal dayCost() throws Exception {
		String body = mvc.perform(authed(get("/api/v1/materials-cost").param("date", "2025-03-17")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("estimatedTotal")
				.decimalValue();
	}

	private int auditCount(String action) {
		Integer count = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = ?", Integer.class, action);
		return count == null ? 0 : count;
	}

	/** One dish of the day's meal of this kind, found or created. Answers with the dish's id. */
	private UUID plan(String kind, UUID recipe, int servings) {
		return plan(kind, recipe, servings, null, null, null);
	}

	private UUID plan(String kind, UUID recipe, int servings,
			Integer adults, Integer children, Integer seniors) {
		UUID meal = MealFixture.meal(admin, tenant, LocalDate.of(2025, 3, 17), kind, LocalTime.NOON);
		if (adults != null || children != null || seniors != null) {
			MealFixture.headCount(admin, meal, adults, children, seniors);
		}
		return MealFixture.dish(admin, tenant, meal, recipe, BigDecimal.valueOf(servings),
				admin.queryForObject("SELECT id FROM users WHERE firebase_uid = 'uid-admin'", UUID.class));
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

	/**
	 * Records the day's Lunch. Every recording here is of that one meal — the dishes named in the body
	 * are all dishes of it — so the meal is looked up rather than threaded through every test.
	 */
	private MockHttpServletRequestBuilder record(String json) {
		return authed(post("/api/v1/meals/{id}/record", mealId()))
				.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder correct(UUID mealId, String json) {
		return authed(post("/api/v1/meals/{id}/correct", mealId))
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

}
