package org.iskcon.kms.meal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * An event is its own preparation, with its own recording and its own job card (E4-S15 D1, V89) —
 * and the names it can be planned by come back for free (E4-S15 D9).
 *
 * <p><strong>Why this suite exists.</strong> V88 gave the temple events and left them all sharing
 * one name: every event of every temple is of kind <em>Event</em>. A meal was identified by its date
 * and its kind, so a morning children's reading and an evening Bhajan Prasadam on one Saturday were
 * the same meal — one recording covering both, one card number for two sheets. That is precisely
 * what D1 refused: the head count stops meaning anything, and afterwards nobody can answer what the
 * Saturday readings cost, because the reading and the Bhajan Prasadam were written down as one
 * thing.
 *
 * <p>So the whole point of these tests is the pair either side of the re-key: two events on one day
 * are two of everything, and the three main meals go on behaving exactly as they always did.
 *
 * <p>2025-03-22 is a Saturday, which is the day the story argues from and a day whose day-type is
 * checkable rather than merely plausible.
 */
@AutoConfigureMockMvc
@Import(EventIdentityIT.StubVerifierConfiguration.class)
class EventIdentityIT extends AbstractIntegrationTest {

	private static final String SATURDAY = "2025-03-22";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private MealKindService mealKindService;

	@Autowired
	private CalendarService calendarService;

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
		insertUser("uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");

		rice = ingredient("Rice");
		ghee = ingredient("Ghee");
		UUID category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Mains') RETURNING id
				""", UUID.class, tenant);

		// 1 KG of rice per 100 servings, 1 KG of ghee per 100 of halwa: small numbers, so an
		// assertion about which pot a drawing came out of is readable.
		khichdi = recipe("Khichdi", category);
		line(khichdi, rice, "1");
		halwa = recipe("Halwa", category);
		line(halwa, ghee, "1");

		stock(rice, "50");
		stock(ghee, "50");

		TenantContext.set(tenant);
		try {
			mealKindService.seedForCurrentTenant();
			calendarService.precomputeForCurrentTenant(LocalDate.of(2025, 1, 1), 200);
		} finally {
			TenantContext.clear();
		}
		signIn("uid-staff-a");
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
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- Two events, one Saturday -------------------------------------------

	@Test
	@DisplayName("two events on one Saturday are two meals, with two job cards")
	void twoEventsOnOneSaturdayAreTwoMealsWithTwoCards() throws Exception {
		event("Children's Bhagavad-gita Reading", "10:00", khichdi, 30);
		event("Bhajan Prasadam", "19:00", halwa, 80);

		// Two meals, not one meal of two dishes. Read in ready-by order, which is the order the
		// kitchen works in: the reading is cooked in the morning and the Bhajan Prasadam at night,
		// and they were never one preparation.
		mvc.perform(authed(get("/api/v1/meal-services"))
						.param("from", SATURDAY).param("to", SATURDAY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].eventName").value("Children's Bhagavad-gita Reading"))
				.andExpect(jsonPath("$[0].dishes.length()").value(1))
				.andExpect(jsonPath("$[1].eventName").value("Bhajan Prasadam"))
				.andExpect(jsonPath("$[1].dishes.length()").value(1));

		// Two cards, two numbers. One number for two sheets is the failure this whole change is
		// about: a signed sheet in a folder has to trace back to one meal six months later, and two
		// sheets carrying LC-2025-0001 trace back to both or to neither.
		String reading = cardNumber("Children's Bhagavad-gita Reading");
		String bhajan = cardNumber("Bhajan Prasadam");
		assertThat(reading).isNotEqualTo(bhajan);

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM meal_services WHERE plan_date = DATE '2025-03-22'", Integer.class))
				.as("each event should have a row of its own")
				.isEqualTo(2);

		// And the sheets say which is which. Two cards both headed "Event" would be two sheets
		// nobody in a kitchen can tell apart, which is the same failure one step downstream.
		mvc.perform(authed(get("/api/v1/job-cards/print"))
						.param("date", SATURDAY).param("mealKind", "Event")
						.param("eventName", "Bhajan Prasadam")
						.param("language", "none"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Bhajan Prasadam")))
				.andExpect(content().string(
						org.hamcrest.Matchers.not(
								org.hamcrest.Matchers.containsString("Children's Bhagavad-gita Reading"))));
	}

	@Test
	@DisplayName("recording one event leaves the other one open, and draws only its own stock")
	void recordingOneEventLeavesTheOtherOpen() throws Exception {
		UUID readingDish = event("Children's Bhagavad-gita Reading", "10:00", khichdi, 30);
		UUID bhajanDish = event("Bhajan Prasadam", "19:00", halwa, 80);

		mvc.perform(record("""
				{"planDate":"%s","mealKind":"Event","eventName":"Children's Bhagavad-gita Reading",
				 "note":"Twenty-eight children",
				 "dishes":[{"mealPlanId":"%s","actualServings":28,"notMade":false}]}
				""".formatted(SATURDAY, readingDish)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.recorded").value(true))
				.andExpect(jsonPath("$.eventName").value("Children's Bhagavad-gita Reading"))
				.andExpect(jsonPath("$.dishes.length()").value(1));

		// The Bhajan Prasadam has not happened yet and must still be waiting to be written down.
		mvc.perform(authed(get("/api/v1/meal-services"))
						.param("from", SATURDAY).param("to", SATURDAY))
				.andExpect(jsonPath("$[0].recorded").value(true))
				.andExpect(jsonPath("$[0].recordingNote").value("Twenty-eight children"))
				.andExpect(jsonPath("$[1].recorded").value(false))
				.andExpect(jsonPath("$[1].recordingNote").doesNotExist());

		// Only the reading's khichdi left the store room. The halwa's ghee is still there — and
		// before V89 it would not have been, because the whole Saturday was one meal.
		assertThat(consumed(rice)).isEqualByComparingTo("280");
		assertThat(consumed(ghee)).isEqualByComparingTo("0");

		mvc.perform(authed(get("/api/v1/meal-plans/{id}", bhajanDish)))
				.andExpect(jsonPath("$.status").value("PLANNED"));

		// And the second event records on its own terms afterwards, rather than being refused as
		// something that was already written down.
		mvc.perform(record("""
				{"planDate":"%s","mealKind":"Event","eventName":"Bhajan Prasadam",
				 "dishes":[{"mealPlanId":"%s","actualServings":90,"notMade":false}]}
				""".formatted(SATURDAY, bhajanDish)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.recorded").value(true));

		assertThat(consumed(ghee)).isEqualByComparingTo("900");
	}

	@Test
	@DisplayName("a main meal's recording is untouched by the re-key")
	void aMainMealsRecordingIsUntouchedByTheReKey() throws Exception {
		UUID lunch = mainMeal("Lunch", "12:00", khichdi, 300);
		UUID readingDish = event("Children's Bhagavad-gita Reading", "10:00", halwa, 30);

		// Lunch is addressed by exactly what it always was — a date and a kind, and no event name,
		// because a Lunch has none and never will.
		mvc.perform(record("""
				{"planDate":"%s","mealKind":"Lunch",
				 "dishes":[{"mealPlanId":"%s","actualServings":300,"notMade":false}]}
				""".formatted(SATURDAY, lunch)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mealKind").value("Lunch"))
				.andExpect(jsonPath("$.eventName").doesNotExist())
				.andExpect(jsonPath("$.recorded").value(true));

		// The event beside it is untouched: not recorded, and still editable.
		mvc.perform(authed(get("/api/v1/meal-plans/{id}", readingDish)))
				.andExpect(jsonPath("$.status").value("PLANNED"));

		assertThat(admin.queryForObject("""
				SELECT count(*) FROM meal_services
				WHERE meal_kind = 'Lunch' AND event_name IS NULL AND recorded_at IS NOT NULL
				""", Integer.class))
				.as("the lunch's row should carry no event name at all")
				.isEqualTo(1);

		// A Lunch card is still a Lunch card, and the event's has nothing to do with it.
		assertThat(cardNumber(null)).startsWith("LC-2025-");

		// And the lunch still refuses to be recorded twice, which is the rule the re-key must not
		// have loosened by accident.
		mvc.perform(record("""
				{"planDate":"%s","mealKind":"Lunch",
				 "dishes":[{"mealPlanId":"%s","actualServings":300,"notMade":false}]}
				""".formatted(SATURDAY, lunch)))
				.andExpect(status().isConflict());
	}

	@Test
	@DisplayName("recording an event does not lock the day's other events out of being edited")
	void recordingAnEventDoesNotLockTheOthers() throws Exception {
		UUID readingDish = event("Children's Bhagavad-gita Reading", "10:00", khichdi, 30);
		UUID bhajanDish = event("Bhajan Prasadam", "19:00", halwa, 80);

		mvc.perform(record("""
				{"planDate":"%s","mealKind":"Event","eventName":"Children's Bhagavad-gita Reading",
				 "dishes":[{"mealPlanId":"%s","actualServings":30,"notMade":false}]}
				""".formatted(SATURDAY, readingDish)))
				.andExpect(status().isOk());

		// Editing is refused once the meal is recorded — and *that* meal is the reading, not the
		// whole Saturday. Before the re-key this came back MEAL_ALREADY_RECORDED.
		mvc.perform(authed(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.put("/api/v1/meal-plans/{id}", bhajanDish))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"planDate":"%s","mealKind":"Event","recipeId":"%s","targetYield":120,
								 "readyBy":"19:00","eventName":"Bhajan Prasadam"}
								""".formatted(SATURDAY, halwa)))
				.andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("two events nobody named are one meal, because nothing in the record says otherwise")
	void twoUnnamedEventsAreOneMeal() throws Exception {
		// The API refuses to save an event without a name (KMS-4990), so the only way this shape
		// exists is the way it actually exists — V88 carried across an outside plan that had no
		// purpose to promote into a name. Written straight into the table for that reason.
		unnamedEvent("10:00", khichdi, 30);
		unnamedEvent("19:00", halwa, 40);

		// One meal of two preparations, sharing one card. The reasoning is on V89's header: the
		// alternative invents a distinction the temple never stated, and splitting two rows that
		// are really one event is the failure with no way back — there is no screen anywhere that
		// merges two meals into one.
		mvc.perform(authed(get("/api/v1/meal-services"))
						.param("from", SATURDAY).param("to", SATURDAY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].eventName").doesNotExist())
				.andExpect(jsonPath("$[0].dishes.length()").value(2));

		cardNumber(null, "Event");
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM meal_services WHERE plan_date = DATE '2025-03-22'", Integer.class))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("the same name typed with a different capital is the same event")
	void theSameNameInADifferentCaseIsTheSameEvent() throws Exception {
		event("Bhajan Prasadam", "19:00", khichdi, 40);
		event("bhajan prasadam", "19:00", halwa, 40);

		// One meal, two preparations. A kitchen that shifts a capital has not planned a second
		// event, and two cards for one pot is the expensive mistake in this direction.
		mvc.perform(authed(get("/api/v1/meal-services"))
						.param("from", SATURDAY).param("to", SATURDAY))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].dishes.length()").value(2));
	}

	// ---- The names it can be planned by (E4-S15 D9) --------------------------

	@Nested
	@DisplayName("the event-name suggestions")
	class Suggestions {

		@Test
		@DisplayName("come back most recently used first, and carry the previous contact forward")
		void mostRecentFirstWithTheContact() throws Exception {
			outsideEvent("2025-01-11", "School Gita Reading", "Mrs Rao", "+919876500021",
					"Jayanagar school hall");
			outsideEvent("2025-02-08", "Community Programme", "Mr Iyer", "+919876500022",
					"Community hall, Rajajinagar");
			// The same reading again, later, with a new contact. The newer answer is the one
			// somebody would ring today.
			outsideEvent("2025-03-08", "School Gita Reading", "Mr Shastri", "+919876500023",
					"Jayanagar school hall, gate 2");

			mvc.perform(authed(get("/api/v1/meal-plans/event-names")))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.length()").value(2))
					.andExpect(jsonPath("$[0].eventName").value("School Gita Reading"))
					.andExpect(jsonPath("$[0].isOutside").value(true))
					.andExpect(jsonPath("$[0].handover").value("DELIVERY"))
					.andExpect(jsonPath("$[0].contactName").value("Mr Shastri"))
					.andExpect(jsonPath("$[0].contactPhone").value("+919876500023"))
					.andExpect(jsonPath("$[0].deliveryAddress").value("Jayanagar school hall, gate 2"))
					.andExpect(jsonPath("$[1].eventName").value("Community Programme"));
		}

		@Test
		@DisplayName("match a prefix without regard to case, and only a prefix")
		void prefixMatching() throws Exception {
			outsideEvent("2025-01-11", "School Gita Reading", "Mrs Rao", "+919876500021", "A hall");
			outsideEvent("2025-02-08", "Community Programme", "Mr Iyer", "+919876500022", "A hall");

			mvc.perform(authed(get("/api/v1/meal-plans/event-names")).param("q", "sch"))
					.andExpect(jsonPath("$.length()").value(1))
					.andExpect(jsonPath("$[0].eventName").value("School Gita Reading"));

			// A prefix, not a search. "Gita" is in the middle of that name and matches nothing:
			// this is an autocomplete for a field somebody is typing into from the left.
			mvc.perform(authed(get("/api/v1/meal-plans/event-names")).param("q", "Gita"))
					.andExpect(jsonPath("$.length()").value(0));

			// A blank q is not a filter. It is the question "what have we run before?".
			mvc.perform(authed(get("/api/v1/meal-plans/event-names")).param("q", "  "))
					.andExpect(jsonPath("$.length()").value(2));

			// A wildcard is an ordinary character in an event's name, not a pattern.
			mvc.perform(authed(get("/api/v1/meal-plans/event-names")).param("q", "%"))
					.andExpect(jsonPath("$.length()").value(0));
		}

		@Test
		@DisplayName("leave out an event that was called off")
		void cancelledPlansAreNotSuggested() throws Exception {
			UUID cancelled = event("Cancelled Reading", "10:00", khichdi, 30);
			event("Bhajan Prasadam", "19:00", halwa, 40);

			mvc.perform(authed(post("/api/v1/meal-plans/{id}/cancel", cancelled)))
					.andExpect(status().isNoContent());

			mvc.perform(authed(get("/api/v1/meal-plans/event-names")))
					.andExpect(jsonPath("$.length()").value(1))
					.andExpect(jsonPath("$[0].eventName").value("Bhajan Prasadam"));
		}

		@Test
		@DisplayName("are at most ten, and are the ten most recent")
		void atMostTen() throws Exception {
			// Twelve Sundays running, so "most recent" is unambiguous and every date is inside the
			// window the calendar was precomputed for.
			for (int i = 1; i <= 12; i++) {
				outsideEvent(LocalDate.of(2025, 1, 5).plusWeeks(i - 1).toString(), "Event " + i,
						"Contact " + i, "+91987650%04d".formatted(i), "Somewhere " + i);
			}

			mvc.perform(authed(get("/api/v1/meal-plans/event-names")))
					.andExpect(jsonPath("$.length()").value(10))
					// The twelfth is the newest; the first two fall off the end.
					.andExpect(jsonPath("$[0].eventName").value("Event 12"))
					.andExpect(jsonPath("$[9].eventName").value("Event 3"));
		}

		@Test
		@DisplayName("say a name once, in the spelling it was last given")
		void distinctByNameNewestSpellingWins() throws Exception {
			event("Bhajan Prasadam", "19:00", khichdi, 40);
			// The same event next month, typed in lower case. One name, used twice.
			plan("2025-04-19", "Event", "19:00", halwa, 40, "bhajan prasadam");

			mvc.perform(authed(get("/api/v1/meal-plans/event-names")))
					.andExpect(jsonPath("$.length()").value(1))
					.andExpect(jsonPath("$[0].eventName").value("bhajan prasadam"));
		}

		@Test
		@DisplayName("are behind the permission that governs the plans they come from")
		void behindTheMealPlanPermission() throws Exception {
			insertUser("uid-vol-a", "vol-a@example.com", "VOLUNTEER");
			signIn("uid-vol-a");

			mvc.perform(authed(get("/api/v1/meal-plans/event-names")))
					.andExpect(status().isForbidden());
		}
	}

	// ------------------------------------------------------------------ helpers

	/** An in-house event: a name, an hour and an amount, and no head count (E4-S15 D2). */
	private UUID event(String name, String readyBy, UUID recipe, int amount) throws Exception {
		return plan(SATURDAY, "Event", readyBy, recipe, amount, name);
	}

	private UUID mainMeal(String kind, String readyBy, UUID recipe, int amount) throws Exception {
		String body = mvc.perform(authed(post("/api/v1/meal-plans"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"planDate":"%s","mealKind":"%s","recipeId":"%s","targetYield":%d,
								 "readyBy":"%s","adults":%d}
								""".formatted(SATURDAY, kind, recipe, amount, readyBy, amount)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return idOf(body);
	}

	private UUID plan(String date, String kind, String readyBy, UUID recipe, int amount, String name)
			throws Exception {
		String body = mvc.perform(authed(post("/api/v1/meal-plans"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"planDate":"%s","mealKind":"%s","recipeId":"%s","targetYield":%d,
								 "readyBy":"%s","eventName":"%s"}
								""".formatted(date, kind, recipe, amount, readyBy, name)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return idOf(body);
	}

	/** A delivered event, so the suggestion has a contact and an address to carry forward. */
	private void outsideEvent(String date, String name, String contact, String phone, String address)
			throws Exception {
		mvc.perform(authed(post("/api/v1/meal-plans"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"planDate":"%s","mealKind":"Event","recipeId":"%s","targetYield":50,
								 "readyBy":"10:00","eventName":"%s","isOutside":true,
								 "handover":"DELIVERY","contactName":"%s","contactPhone":"%s",
								 "deliveryAddress":"%s","guestsEatAt":"12:00"}
								""".formatted(date, khichdi, name, contact, phone, address)))
				.andExpect(status().isCreated());
	}

	/**
	 * An event with no name at all — the one shape the API refuses to create and the schema
	 * nonetheless has to answer for, because V88 carried a handful of them across.
	 */
	private void unnamedEvent(String readyBy, UUID recipe, int amount) {
		admin.update("""
				INSERT INTO meal_plans (tenant_id, plan_date, meal_kind, ready_by, recipe_id,
						target_yield, day_type, status, is_outside, created_by)
				VALUES (?, DATE '2025-03-22', 'Event', CAST(? AS time), ?, ?, 'WEEKEND', 'PLANNED', true,
						(SELECT id FROM users WHERE firebase_uid = 'uid-staff-a'))
				""", tenant, readyBy, recipe, BigDecimal.valueOf(amount));
	}

	private String cardNumber(String eventName) throws Exception {
		return cardNumber(eventName, eventName == null ? "Lunch" : "Event");
	}

	/**
	 * Prints the card for one meal and reads back the number it was issued.
	 *
	 * <p>Through the browser print view rather than the queued PDF, because the number is issued by
	 * the print either way and this needs no document worker and no object storage to say what it
	 * was. {@code language=none} asks for the worksheet on its own.
	 */
	private String cardNumber(String eventName, String mealKind) throws Exception {
		MockHttpServletRequestBuilder request = authed(get("/api/v1/job-cards/print"))
				.param("date", SATURDAY).param("mealKind", mealKind).param("language", "none");
		if (eventName != null) {
			request = request.param("eventName", eventName);
		}
		mvc.perform(request).andExpect(status().isOk());
		return admin.queryForObject("""
				SELECT card_number FROM meal_services
				WHERE plan_date = DATE '2025-03-22' AND meal_kind = ?
				  AND lower(COALESCE(event_name, '')) = lower(COALESCE(CAST(? AS text), ''))
				""", String.class, mealKind, eventName);
	}

	private static UUID idOf(String body) {
		return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
	}

	private BigDecimal consumed(UUID ingredient) {
		return admin.queryForObject("""
				SELECT COALESCE(-SUM(to_base_qty(quantity, unit)), 0)
				FROM stock_movements
				WHERE ingredient_id = ? AND movement_type = 'CONSUMPTION'
				""", BigDecimal.class, ingredient);
	}

	private UUID ingredient(String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Staples', 'KG') RETURNING id
				""", UUID.class, tenant, name);
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
						(SELECT id FROM users WHERE firebase_uid = 'uid-staff-a'))
				""", tenant, ingredient, UUID.randomUUID(), kilos);
	}

	private MockHttpServletRequestBuilder record(String json) {
		return authed(post("/api/v1/meal-services/record"))
				.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private void insertUser(String uid, String email, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE')
				""", tenant, uid, email, role);
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
