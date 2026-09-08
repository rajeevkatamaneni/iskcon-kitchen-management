package org.iskcon.kms.meal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.iskcon.kms.occasion.OccasionService;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Meal planning (E4-S4) through the full stack: day-type auto-suggestion from the calendar, the event
 * block E4-S15 put in place of catering, and the mark-cooked → consumption → status flow with its
 * guard rails.
 */
@AutoConfigureMockMvc
@Import(MealPlanIT.StubVerifierConfiguration.class)
class MealPlanIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private MealKindService mealKindService;

	@Autowired
	private OccasionService occasionService;

	@Autowired
	private CalendarService calendarService;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID rice;
	private UUID khichdi;
	private UUID payasam;

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
		insertUser("uid-vol-a", "vol-a@example.com", "VOLUNTEER");

		rice = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Rice', 'Grains', 'KG') RETURNING id
				""", UUID.class, tenant);
		UUID category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Rice') RETURNING id
				""", UUID.class, tenant);
		khichdi = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, 'Khichdi', ?, 100, 'KG') RETURNING id
				""", UUID.class, tenant, category);
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, 5, 'KG', 0)
				""", tenant, khichdi, rice);
		// A second recipe, so a dish can be swapped for something rather than merely re-scaled.
		payasam = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, 'Payasam', ?, 100, 'KG') RETURNING id
				""", UUID.class, tenant, category);
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, 1, 'KG', 0)
				""", tenant, payasam, rice);
		// 10 KG rice in stock.
		admin.update("""
				INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit,
						movement_type, actor_user_id)
				VALUES (?, ?, ?, 10, 'KG', 'PO_RECEIPT',
						(SELECT id FROM users WHERE firebase_uid = 'uid-staff-a'))
				""", tenant, rice, UUID.randomUUID());

		TenantContext.set(tenant);
		try {
			mealKindService.seedForCurrentTenant();
			occasionService.seedForCurrentTenant();
			calendarService.precomputeForCurrentTenant(LocalDate.of(2025, 1, 1), 100);
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
		admin.execute("DELETE FROM occasions");
		admin.execute("DELETE FROM calendar_days");
		admin.execute("DELETE FROM calendar_precompute_state");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM audit_events");
		// Anything that moved through the stock ledger is tracked now, so the item rows exist
		// even where the test never asked for them, and they hold the ingredient down.
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("day-context suggests festival on Gaura Purnima and regular on a weekday")
	void dayContextSuggestsFromCalendar() throws Exception {
		mvc.perform(get("/api/v1/meal-plans/day-context").param("date", "2025-03-14")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.suggestedDayType").value("FESTIVAL"))
				.andExpect(jsonPath("$.occasionName").value("Gaura Purnima"))
				.andExpect(jsonPath("$.suggestedServings").value(1000));

		mvc.perform(get("/api/v1/meal-plans/day-context").param("date", "2025-03-17") // a Monday
						.header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.suggestedDayType").value("REGULAR"));
	}

	@Test
	@DisplayName("planning on a festival date auto-tags the day-type and records the occasion")
	void planningFestivalAutoTags() throws Exception {
		UUID id = create("""
				{"planDate":"2025-03-14","mealKind":"Lunch","recipeId":"%s","targetYield":800,"adults":800}
				""".formatted(khichdi));

		mvc.perform(get("/api/v1/meal-plans/{id}", id).header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.dayType").value("FESTIVAL"))
				.andExpect(jsonPath("$.occasionName").value("Gaura Purnima"));
	}

	@Test
	@DisplayName("an event has a name, and nothing is asked of an in-house one but that")
	void anEventNeedsItsName() throws Exception {
		// The name is the whole point of splitting events out of the main meals: without it the
		// Saturday reading is a rounding error inside breakfast a year later.
		mvc.perform(createRequest("""
				{"planDate":"2025-03-22","mealKind":"Event","recipeId":"%s","targetYield":30,
				 "readyBy":"17:00"}
				""".formatted(khichdi)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400075"));

		// In-house, and that is the end of the questions. No contact, no handover, no address, no
		// serving time — a Bhajan Prasadam in the temple hall has none of those, and a form should not
		// ask a question with no answer.
		UUID reading = create("""
				{"planDate":"2025-03-22","mealKind":"Event","recipeId":"%s","targetYield":30,
				 "readyBy":"17:00","eventName":"Children's Bhagavad-gita Reading"}
				""".formatted(khichdi));

		mvc.perform(get("/api/v1/meal-plans/{id}", reading).header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.eventName").value("Children's Bhagavad-gita Reading"))
				.andExpect(jsonPath("$.isOutside").value(false))
				.andExpect(jsonPath("$.handover").doesNotExist())
				.andExpect(jsonPath("$.contactName").doesNotExist())
				.andExpect(jsonPath("$.deliveryAddress").doesNotExist())
				.andExpect(jsonPath("$.guestsEatAt").doesNotExist())
				// Catering was a kind of DAY once. A Saturday event is a Saturday.
				.andExpect(jsonPath("$.dayType").value("WEEKEND"));
	}

	@Test
	@DisplayName("an event saves with an amount and nobody counted; a Breakfast still does not")
	void anEventIsQuantifiedByAmountAndNotByHeads() throws Exception {
		// Thirty laddus and some chiwda. The temple's own FHC Sabjis sheet plans bulk distribution in
		// gross kilograms per dish with no head count anywhere on it, so an event is quantified by how
		// much to make and the head count is context (E4-S15 D2).
		UUID id = create("""
				{"planDate":"2025-03-22","mealKind":"Event","recipeId":"%s","targetYield":30,
				 "readyBy":"17:00","eventName":"Children's Bhagavad-gita Reading",
				 "adults":0,"children":0,"seniors":0}
				""".formatted(khichdi));

		mvc.perform(get("/api/v1/meal-plans/{id}", id).header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.targetYield").value(30.0))
				// Nothing was invented to fill the hole. Null is the honest answer.
				.andExpect(jsonPath("$.adults").value(0));

		// And the exemption does not loosen for the three main meals by one inch.
		mvc.perform(createRequest("""
				{"planDate":"2025-03-22","mealKind":"Breakfast","recipeId":"%s","targetYield":100,
				 "adults":0,"children":0,"seniors":0}
				""".formatted(khichdi)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400080"));
	}

	@Test
	@DisplayName("an event going outside needs a contact, and a delivered one an address and a serving time")
	void goingOutsideAsksInAChain() throws Exception {
		// Both halves of the contact. A contact you cannot ring is not a contact.
		mvc.perform(createRequest("""
				{"planDate":"2025-03-20","mealKind":"Event","recipeId":"%s","targetYield":200,
				 "readyBy":"11:00","eventName":"Vidyaranyapura School Gita Reading","isOutside":true,
				 "handover":"PICKUP","contactPhone":"+91 98862 30011"}
				""".formatted(khichdi)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400076"));

		mvc.perform(createRequest("""
				{"planDate":"2025-03-20","mealKind":"Event","recipeId":"%s","targetYield":200,
				 "readyBy":"11:00","eventName":"Vidyaranyapura School Gita Reading","isOutside":true,
				 "handover":"PICKUP","contactName":"Mrs Latha Rao"}
				""".formatted(khichdi)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400076"));

		// A pickup is complete there: somebody is coming to collect it, so no address is asked for.
		UUID pickup = create("""
				{"planDate":"2025-03-20","mealKind":"Event","recipeId":"%s","targetYield":200,
				 "readyBy":"11:00","eventName":"Vidyaranyapura School Gita Reading","isOutside":true,
				 "handover":"PICKUP","contactName":"Mrs Latha Rao","contactPhone":"+91 98862 30011"}
				""".formatted(khichdi));
		mvc.perform(get("/api/v1/meal-plans/{id}", pickup).header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.handover").value("PICKUP"))
				.andExpect(jsonPath("$.contactName").value("Mrs Latha Rao"))
				.andExpect(jsonPath("$.deliveryAddress").doesNotExist());

		// A delivery asks for two more, and refuses without either of them.
		mvc.perform(createRequest("""
				{"planDate":"2025-03-21","mealKind":"Event","recipeId":"%s","targetYield":200,
				 "readyBy":"11:00","eventName":"Community programme","isOutside":true,
				 "handover":"DELIVERY","contactName":"Mrs Latha Rao","contactPhone":"+91 98862 30011",
				 "guestsEatAt":"13:00"}
				""".formatted(khichdi)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400077"));

		mvc.perform(createRequest("""
				{"planDate":"2025-03-21","mealKind":"Event","recipeId":"%s","targetYield":200,
				 "readyBy":"11:00","eventName":"Community programme","isOutside":true,
				 "handover":"DELIVERY","contactName":"Mrs Latha Rao","contactPhone":"+91 98862 30011",
				 "deliveryAddress":"Hare Krishna Hill, Rajajinagar 560010"}
				""".formatted(khichdi)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400077"));

		UUID delivery = create("""
				{"planDate":"2025-03-21","mealKind":"Event","recipeId":"%s","targetYield":200,
				 "readyBy":"11:00","eventName":"Community programme","isOutside":true,
				 "handover":"DELIVERY","contactName":"Mrs Latha Rao","contactPhone":"+91 98862 30011",
				 "deliveryAddress":"Hare Krishna Hill, Rajajinagar 560010","guestsEatAt":"13:00"}
				""".formatted(khichdi));
		mvc.perform(get("/api/v1/meal-plans/{id}", delivery).header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.deliveryAddress").value("Hare Krishna Hill, Rajajinagar 560010"))
				.andExpect(jsonPath("$.guestsEatAt").value("13:00:00"));
	}

	/**
	 * The pin survives an edit, and a placeholder never becomes a place (T-044).
	 *
	 * <p>This is asserted on the row rather than on the response, because the response is the one
	 * thing that looked right while the defect was live: the meal came back with its address, its
	 * contact and its serving time intact, and only the two columns nothing rendered had been moved to
	 * 0°N 0°E. What a person saw next was a departure time on a job card, worked back from the drive
	 * to the Gulf of Guinea, and nothing on any screen said where the number had come from.
	 *
	 * <p>There is no map service in this context and that is the point rather than a limitation: with
	 * neither Places nor a geocoder to fall back on, anything that survives here survived because it
	 * was already on the row, so an assertion that passes cannot be passing on a fresh lookup.
	 */
	@Test
	@DisplayName("a placed delivery keeps its pin through an edit, and 0,0 never becomes a place")
	void editingAPlacedDeliveryLeavesItsPinAlone() throws Exception {
		UUID delivery = create("""
				{"planDate":"2025-03-21","mealKind":"Event","recipeId":"%s","targetYield":200,
				 "readyBy":"11:00","eventName":"Mantri Serenity programme","isOutside":true,
				 "handover":"DELIVERY","contactName":"Mrs Latha Rao","contactPhone":"+91 98862 30011",
				 "deliveryAddress":"Mantri Serenity, Kanakapura Main Rd, Bengaluru 560062",
				 "deliveryPlaceId":"place-1",
				 "deliveryLatitude":12.856230,"deliveryLongitude":77.548110,
				 "guestsEatAt":"13:00"}
				""".formatted(khichdi));

		// The view carries the pin. Without it the composer has nothing to reopen an edit on, which is
		// how it came to invent one.
		mvc.perform(get("/api/v1/meal-plans/{id}", delivery).header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.deliveryPlaceId").value("place-1"))
				.andExpect(jsonPath("$.deliveryLatitude").value(12.85623))
				.andExpect(jsonPath("$.deliveryLongitude").value(77.54811));

		// An ordinary edit — fifty more guests — sending the pin back exactly as it came.
		mvc.perform(updateRequest(delivery, """
				{"planDate":"2025-03-21","mealKind":"Event","recipeId":"%s","targetYield":250,
				 "readyBy":"11:00","eventName":"Mantri Serenity programme","isOutside":true,
				 "handover":"DELIVERY","contactName":"Mrs Latha Rao","contactPhone":"+91 98862 30011",
				 "deliveryAddress":"Mantri Serenity, Kanakapura Main Rd, Bengaluru 560062",
				 "deliveryPlaceId":"place-1",
				 "deliveryLatitude":12.856230,"deliveryLongitude":77.548110,
				 "guestsEatAt":"13:00"}
				""".formatted(khichdi)))
				.andExpect(status().isNoContent());
		assertThat(pinOf(delivery)[0]).isEqualByComparingTo("12.856230");
		assertThat(pinOf(delivery)[1]).isEqualByComparingTo("77.548110");

		// And the edit as the old composer actually sent it: a real place id with the placeholder it
		// had instead of coordinates. It used to be stored, because zero is not null. The place id is
		// still on the request, so the event is still going where it was going — the pin the row
		// already holds is kept rather than overwritten with a point in the Atlantic.
		mvc.perform(updateRequest(delivery, """
				{"planDate":"2025-03-21","mealKind":"Event","recipeId":"%s","targetYield":250,
				 "readyBy":"11:00","eventName":"Mantri Serenity programme","isOutside":true,
				 "handover":"DELIVERY","contactName":"Mrs Latha Rao","contactPhone":"+91 98862 30011",
				 "deliveryAddress":"Mantri Serenity, Kanakapura Main Rd, Bengaluru 560062",
				 "deliveryPlaceId":"place-1","deliveryLatitude":0,"deliveryLongitude":0,
				 "guestsEatAt":"13:00"}
				""".formatted(khichdi)))
				.andExpect(status().isNoContent());
		assertThat(pinOf(delivery)[0]).isEqualByComparingTo("12.856230");
		assertThat(pinOf(delivery)[1]).isEqualByComparingTo("77.548110");

		// The same placeholder on a new plan, where there is no earlier pin to fall back on. No map
		// service here, so nobody can say where this is — and no pin at all is the honest answer.
		// Storing 0,0 would have been an answer, and a wrong one that no screen would question.
		UUID fresh = create("""
				{"planDate":"2025-03-22","mealKind":"Event","recipeId":"%s","targetYield":80,
				 "readyBy":"11:00","eventName":"Somewhere else entirely","isOutside":true,
				 "handover":"DELIVERY","contactName":"Mrs Latha Rao","contactPhone":"+91 98862 30011",
				 "deliveryAddress":"Hare Krishna Hill, Rajajinagar 560010",
				 "deliveryPlaceId":"place-2","deliveryLatitude":0,"deliveryLongitude":0,
				 "guestsEatAt":"13:00"}
				""".formatted(khichdi));
		assertThat(pinOf(fresh)[0]).isNull();
		assertThat(pinOf(fresh)[1]).isNull();
	}

	@Test
	@DisplayName("Breakfast, Lunch and Dinner see none of the event block")
	void theMainMealsAreUntouched() throws Exception {
		// A caller that sends the event fields on a Lunch stores none of them: the three main meals
		// are not answerable for a shape that has nothing to do with them.
		UUID lunch = create("""
				{"planDate":"2025-03-20","mealKind":"Lunch","recipeId":"%s","targetYield":100,"adults":100,
				 "eventName":"Not a thing","isOutside":true,"handover":"DELIVERY",
				 "contactName":"Nobody","contactPhone":"+910000000000",
				 "deliveryAddress":"Nowhere","guestsEatAt":"13:00"}
				""".formatted(khichdi));

		mvc.perform(get("/api/v1/meal-plans/{id}", lunch).header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.eventName").doesNotExist())
				.andExpect(jsonPath("$.isOutside").value(false))
				.andExpect(jsonPath("$.handover").doesNotExist())
				.andExpect(jsonPath("$.contactName").doesNotExist())
				.andExpect(jsonPath("$.deliveryAddress").doesNotExist())
				.andExpect(jsonPath("$.readyBy").value("12:00:00"));
	}

	@Test
	@DisplayName("upcoming outside commitments: future, in date order, cancelled ones gone, in-house never on it")
	void outsideCommitmentsAreWhatLeavesTheTemple() throws Exception {
		LocalDate today = LocalDate.now();
		// In-house. It is not a commitment to anybody outside, so it is not on the list.
		create("""
				{"planDate":"%s","mealKind":"Event","recipeId":"%s","targetYield":30,"readyBy":"17:00",
				 "eventName":"Children's Bhagavad-gita Reading"}
				""".formatted(today.plusDays(3), khichdi));
		// Past. Upcoming means upcoming.
		create("""
				{"planDate":"%s","mealKind":"Event","recipeId":"%s","targetYield":50,"readyBy":"11:00",
				 "eventName":"Last month's school delivery","isOutside":true,"handover":"PICKUP",
				 "contactName":"Mr Rao","contactPhone":"+919000000001"}
				""".formatted(today.minusDays(20), khichdi));
		// Two future ones, planned out of order on purpose.
		create("""
				{"planDate":"%s","mealKind":"Event","recipeId":"%s","targetYield":80,"readyBy":"11:00",
				 "eventName":"Community programme","isOutside":true,"handover":"PICKUP",
				 "contactName":"Mrs Latha Rao","contactPhone":"+919000000002"}
				""".formatted(today.plusDays(20), khichdi));
		UUID soonest = create("""
				{"planDate":"%s","mealKind":"Event","recipeId":"%s","targetYield":80,"readyBy":"10:00",
				 "eventName":"School Gita Reading","isOutside":true,"handover":"DELIVERY",
				 "contactName":"Mrs Shanta","contactPhone":"+919000000003",
				 "deliveryAddress":"Vidyaranyapura, Bengaluru","guestsEatAt":"13:00"}
				""".formatted(today.plusDays(5), khichdi));

		mvc.perform(get("/api/v1/meal-plans/outside-commitments")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].eventName").value("School Gita Reading"))
				.andExpect(jsonPath("$[0].contactName").value("Mrs Shanta"))
				.andExpect(jsonPath("$[0].deliveryAddress").value("Vidyaranyapura, Bengaluru"))
				.andExpect(jsonPath("$[1].eventName").value("Community programme"));

		// A cancelled commitment is not a commitment.
		mvc.perform(post("/api/v1/meal-plans/{id}/cancel", soonest)
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isNoContent());

		mvc.perform(get("/api/v1/meal-plans/outside-commitments")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].eventName").value("Community programme"));
	}

	@Test
	@DisplayName("an event repeats forward as copies, and editing one leaves the others alone")
	void repeatForwardMakesCopiesNotASeries() throws Exception {
		UUID first = create("""
				{"planDate":"2025-03-22","mealKind":"Event","recipeId":"%s","targetYield":30,
				 "readyBy":"17:00","eventName":"Children's Bhagavad-gita Reading"}
				""".formatted(khichdi));

		mvc.perform(post("/api/v1/meal-plans/{id}/repeat", first).param("weeks", "6")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.copied").value(6))
				.andExpect(jsonPath("$.weeksCopied").value(6));

		// Six copies on the next six Saturdays, each carrying the name, the amount and the hour.
		mvc.perform(get("/api/v1/meal-plans").param("from", "2025-03-22").param("to", "2025-05-10")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.length()").value(7))
				.andExpect(jsonPath("$[3].eventName").value("Children's Bhagavad-gita Reading"))
				.andExpect(jsonPath("$[3].planDate").value("2025-04-12"));

		String body = mvc.perform(get("/api/v1/meal-plans").param("from", "2025-04-05")
						.param("to", "2025-04-05").header("Authorization", "Bearer valid-token"))
				.andReturn().getResponse().getContentAsString();
		UUID third = UUID.fromString(body.replaceAll(".*?\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));

		mvc.perform(updateRequest(third, """
				{"planDate":"2025-04-05","mealKind":"Event","recipeId":"%s","targetYield":50,
				 "readyBy":"17:00","eventName":"Children's Bhagavad-gita Reading"}
				""".formatted(khichdi)))
				.andExpect(status().isNoContent());

		// Copies, not a series: the others are untouched, and nothing asked "this one or all of them?"
		mvc.perform(get("/api/v1/meal-plans").param("from", "2025-04-12").param("to", "2025-04-12")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$[0].targetYield").value(30.0));
		mvc.perform(get("/api/v1/meal-plans").param("from", "2025-03-29").param("to", "2025-03-29")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$[0].targetYield").value(30.0));
	}

	@Test
	@DisplayName("an everyday meal takes the temple's time; an occasional one insists on being given one")
	void readyByComesFromTheKindOrIsRequired() throws Exception {
		// Lunch has a temple default, so planning one need not state a time.
		UUID lunch = create("""
				{"planDate":"2025-03-17","mealKind":"Lunch","recipeId":"%s","targetYield":100,"adults":100}
				""".formatted(khichdi));
		mvc.perform(get("/api/v1/meal-plans/{id}", lunch).header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.readyBy").value("12:00:00"));

		// A deity offering has none — guessing would be worse than asking.
		mvc.perform(createRequest("""
				{"planDate":"2025-03-17","mealKind":"Deity Offering","recipeId":"%s","targetYield":20,"adults":20}
				""".formatted(khichdi)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400072"));

		UUID offering = create("""
				{"planDate":"2025-03-17","mealKind":"Deity Offering","recipeId":"%s","targetYield":20,"adults":20,
				 "readyBy":"05:30"}
				""".formatted(khichdi));
		mvc.perform(get("/api/v1/meal-plans/{id}", offering).header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.readyBy").value("05:30:00"));
	}

	@Test
	@DisplayName("recording a meal draws stock and flips its dishes; a cooked meal can't be cancelled")
	void recordingDrawsStockAndLocks() throws Exception {
		// The per-dish "mark cooked" button and its endpoint are gone (brief §2): a meal is recorded
		// once, as a whole, from the card that came back. Same guard rails, through the path that
		// replaced it.
		UUID id = create("""
				{"planDate":"2025-03-17","mealKind":"Lunch","recipeId":"%s","targetYield":100,"adults":100}
				""".formatted(khichdi));

		mvc.perform(record("""
				{"planDate":"2025-03-17","mealKind":"Lunch",
				 "dishes":[{"mealPlanId":"%s","actualServings":100,"notMade":false}]}
				""".formatted(id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.recorded").value(true));

		// 10 KG - 5 KG drawn = 5 KG left.
		assertThat(admin.queryForObject("""
				SELECT COALESCE(SUM(to_base_qty(quantity, unit)),0)
				FROM stock_movements WHERE ingredient_id = ?
				""", java.math.BigDecimal.class, rice)).isEqualByComparingTo("5000");

		mvc.perform(get("/api/v1/meal-plans/{id}", id).header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.status").value("COOKED"))
				.andExpect(jsonPath("$.actualServings").value(100.0));

		mvc.perform(post("/api/v1/meal-plans/{id}/cancel", id).header("Authorization", "Bearer valid-token"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400045"));
	}

	@Test
	@DisplayName("recording is refused, all-or-nothing, when stock is short")
	void recordingShortIsRefused() throws Exception {
		UUID id = create("""
				{"planDate":"2025-03-17","mealKind":"Dinner","recipeId":"%s","targetYield":1000,"adults":1000}
				""".formatted(khichdi)); // needs 50 KG, only 10 available

		mvc.perform(record("""
				{"planDate":"2025-03-17","mealKind":"Dinner",
				 "dishes":[{"mealPlanId":"%s","actualServings":1000,"notMade":false}]}
				""".formatted(id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400042"));

		// Nothing drawn, status unchanged, and no half-recorded meal left behind.
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM stock_movements WHERE movement_type = 'CONSUMPTION'", Integer.class))
				.isZero();
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM meal_services WHERE recorded_at IS NOT NULL", Integer.class))
				.isZero();
		mvc.perform(get("/api/v1/meal-plans/{id}", id).header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.status").value("PLANNED"));
	}

	@Test
	@DisplayName("a kind the temple doesn't have is refused, and a volunteer cannot plan")
	void slotValidationAndPermission() throws Exception {
		mvc.perform(createRequest("""
				{"planDate":"2025-03-17","mealKind":"Brunch","recipeId":"%s","targetYield":50,"adults":50}
				""".formatted(khichdi)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400071"));

		signIn("uid-vol-a");
		mvc.perform(createRequest("""
				{"planDate":"2025-03-17","mealKind":"Lunch","recipeId":"%s","targetYield":50,"adults":50}
				""".formatted(khichdi)))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("a dish is swapped and re-scaled in place until the meal is recorded, and never after")
	void dishIsEditableUntilRecorded() throws Exception {
		UUID id = create("""
				{"planDate":"2025-03-17","mealKind":"Lunch","recipeId":"%s","targetYield":100,
				 "adults":100,"children":0,"seniors":0}
				""".formatted(khichdi));

		// The commonest correction is not the recipe at all — it is that forty more people are coming.
		mvc.perform(updateRequest(id, """
				{"planDate":"2025-03-17","mealKind":"Lunch","recipeId":"%s","targetYield":140,
				 "adults":140,"children":0,"seniors":0,"kitchenNotes":"Cook it thin."}
				""".formatted(payasam)))
				.andExpect(status().isNoContent());

		mvc.perform(get("/api/v1/meal-plans/{id}", id).header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.recipeId").value(payasam.toString()))
				.andExpect(jsonPath("$.targetYield").value(140.0))
				.andExpect(jsonPath("$.adults").value(140))
				.andExpect(jsonPath("$.kitchenNotes").value("Cook it thin."))
				// The row is the same row: swapping kept its history rather than cancelling and re-adding.
				.andExpect(jsonPath("$.id").value(id.toString()));

		mvc.perform(record("""
				{"planDate":"2025-03-17","mealKind":"Lunch",
				 "dishes":[{"mealPlanId":"%s","actualServings":140,"notMade":false}]}
				""".formatted(id)))
				.andExpect(status().isOk());

		mvc.perform(updateRequest(id, """
				{"planDate":"2025-03-17","mealKind":"Lunch","recipeId":"%s","targetYield":200,"adults":200}
				""".formatted(khichdi)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400098"));
	}

	@Test
	@DisplayName("six kinds, and neither Catering order nor Outside event is one of them")
	void theKindListHasNoCateringInIt() throws Exception {
		// E4-S15 folded *Outside event* and *Catering order* into one Event. A temple that does
		// catering plans a catering event and gains six fields by it; what must not exist is a kind
		// the product seeded with the old name in it — not greyed, not at the bottom, gone.
		mvc.perform(get("/api/v1/meal-kinds").header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(6))
				.andExpect(jsonPath("$[0].name").value("Breakfast"))
				.andExpect(jsonPath("$[1].name").value("Lunch"))
				.andExpect(jsonPath("$[2].name").value("Dinner"))
				.andExpect(jsonPath("$[3].name").value("Festival feast"))
				.andExpect(jsonPath("$[4].name").value("Deity Offering"))
				.andExpect(jsonPath("$[5].name").value("Event"))
				.andExpect(jsonPath("$[5].isEvent").value(true))
				// An event is never at the same hour twice, so it always asks.
				.andExpect(jsonPath("$[5].defaultReadyTime").doesNotExist())
				.andExpect(jsonPath("$[?(@.name=='Catering order')]").isEmpty())
				.andExpect(jsonPath("$[?(@.name=='Outside event')]").isEmpty())
				// Only the Event kind is one. Lunch must not have caught the flag.
				.andExpect(jsonPath("$[1].isEvent").value(false));
	}

	@Test
	@DisplayName("a preparation with nobody to eat it is refused, whether the count is nought or absent")
	void headCountIsRequiredForAPreparation() throws Exception {
		// The composer used to open on 100 adults, so every meal it planned carried a head count
		// nobody had chosen — and the application then costed, scaled and rostered against it. The
		// planner picks the number; this is where that is enforced rather than on the screen.
		mvc.perform(createRequest("""
				{"planDate":"2025-03-17","mealKind":"Lunch","recipeId":"%s","targetYield":100,
				 "adults":0,"children":0,"seniors":0}
				""".formatted(khichdi)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400080"));

		// Leaving the three counters out entirely is the same meal with the same hole in it. A guard
		// a caller escapes by omitting a field is not a guard.
		mvc.perform(createRequest("""
				{"planDate":"2025-03-17","mealKind":"Lunch","recipeId":"%s","targetYield":100}
				""".formatted(khichdi)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400080"));

		assertThat(admin.queryForObject("SELECT count(*) FROM meal_plans", Integer.class)).isZero();
	}

	@Test
	@DisplayName("one child is a head count: 0.6 of a portion is not nothing")
	void aWeightedCountThatRoundsSmallIsStillACount() throws Exception {
		// Children count 0.6 of a portion and seniors 0.8. Checking the weighted total instead of the
		// three counters would refuse a hall somebody had actually counted, which is the opposite
		// mistake to inventing one.
		create("""
				{"planDate":"2025-03-17","mealKind":"Lunch","recipeId":"%s","targetYield":2,
				 "adults":0,"children":1,"seniors":0}
				""".formatted(khichdi));

		mvc.perform(get("/api/v1/meal-plans").param("from", "2025-03-17").param("to", "2025-03-17")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].children").value(1));
	}

	@Test
	@DisplayName("a meal cannot have its head count taken away by an edit either")
	void headCountCannotBeClearedByAnEdit() throws Exception {
		UUID id = create("""
				{"planDate":"2025-03-17","mealKind":"Lunch","recipeId":"%s","targetYield":100,
				 "adults":100,"children":0,"seniors":0}
				""".formatted(khichdi));

		mvc.perform(updateRequest(id, """
				{"planDate":"2025-03-17","mealKind":"Lunch","recipeId":"%s","targetYield":100,
				 "adults":0,"children":0,"seniors":0}
				""".formatted(khichdi)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400080"));

		// Refused, and the meal is left as it was rather than half-edited.
		mvc.perform(get("/api/v1/meal-plans/{id}", id).header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.adults").value(100));
	}

	// ---------------------------------------------------------------------

	private UUID create(String json) throws Exception {
		String body = mvc.perform(createRequest(json)).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
	}

	/**
	 * Where a delivery is pinned, read straight off the row (T-044).
	 *
	 * <p>Read with the admin connection on purpose: this has to be the two columns as they were
	 * written, not the two fields as an endpoint chose to present them, because presenting them
	 * correctly is not the thing that went wrong.
	 */
	private BigDecimal[] pinOf(UUID id) {
		return admin.queryForObject(
				"SELECT delivery_latitude, delivery_longitude FROM meal_plans WHERE id = ?",
				(rs, n) -> new BigDecimal[] { rs.getBigDecimal(1), rs.getBigDecimal(2) }, id);
	}

	private MockHttpServletRequestBuilder createRequest(String json) {
		return post("/api/v1/meal-plans").header("Authorization", "Bearer valid-token")
				.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder record(String json) {
		return post("/api/v1/meal-services/record").header("Authorization", "Bearer valid-token")
				.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder updateRequest(UUID id, String json) {
		return put("/api/v1/meal-plans/{id}", id).header("Authorization", "Bearer valid-token")
				.contentType(MediaType.APPLICATION_JSON).content(json);
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
