package org.iskcon.kms.meal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.geo.GeocodingProvider;
import org.iskcon.kms.geo.TravelTimeProvider;
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
 * When to leave the temple for a delivered event (E4-S16), end to end.
 *
 * <p><b>No network and no credentials, anywhere in here.</b> That is the point of the port and it is
 * the point of this class: both map services are stubs held in memory, and the same code paths run
 * that a deployment with a real Routes key would run. A suite that needed a map service would be a
 * suite nobody could run on a train, and a feature that needed one to be planned at all would be a
 * map service standing between a cook and a meal plan.
 */
@AutoConfigureMockMvc
@Import(TravelEstimateIT.Stubs.class)
class TravelEstimateIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private StubGeocoder geocoder;

	@Autowired
	private StubRouter router;

	@Autowired
	private MealKindService mealKindService;

	@Autowired
	private MealPlanService mealPlanService;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID khichdi;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		geocoder.reset();
		router.reset();

		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('travel-temple', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-travel-staff', 'Test Person', 'travel@example.com', '+919876500081',
						'KITCHEN_STAFF', 'ACTIVE')
				""", tenant);
		UUID category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Rice') RETURNING id
				""", UUID.class, tenant);
		khichdi = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, 'Khichdi', ?, 100, 'KG') RETURNING id
				""", UUID.class, tenant, category);

		TenantContext.set(tenant);
		try {
			mealKindService.seedForCurrentTenant();
		} finally {
			TenantContext.clear();
		}
		stubVerifier.accept("uid-travel-staff");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM meal_plans");
		admin.execute("DELETE FROM meal_kinds");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a delivery says when to leave, worked backwards from when the guests eat")
	void aDeliverySaysWhenToLeave() throws Exception {
		geocoder.place("Hare Krishna Hill, Rajajinagar 560010", 12.9, 77.55);
		router.answer(Duration.ofMinutes(35), Duration.ofMinutes(45));

		UUID id = create(delivery("13:00", "Hare Krishna Hill, Rajajinagar 560010"));

		mvc.perform(authed(get("/api/v1/meal-plans/{id}/travel-estimate", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.available").value(true))
				// Guests eat at 13:00; the slower end of the drive is 45 minutes. A driver can act on
				// "leave by 12:15" — nobody can act on "37 minutes".
				.andExpect(jsonPath("$.leaveBy").value("12:15:00"))
				.andExpect(jsonPath("$.optimisticMinutes").value(35))
				.andExpect(jsonPath("$.pessimisticMinutes").value(45))
				.andExpect(jsonPath("$.guestsEatAt").value("13:00:00"));

		// It is asked again every time it is shown, never remembered — both because the licence
		// forbids keeping durations and because Friday's traffic is not Tuesday's.
		router.answer(Duration.ofMinutes(50), Duration.ofMinutes(70));
		mvc.perform(authed(get("/api/v1/meal-plans/{id}/travel-estimate", id)))
				.andExpect(jsonPath("$.leaveBy").value("11:50:00"))
				.andExpect(jsonPath("$.pessimisticMinutes").value(70));
	}

	@Test
	@DisplayName("reading the estimate stores nothing, and nothing caches Google's answers")
	void readingTheEstimateStoresNothing() throws Exception {
		geocoder.place("Hare Krishna Hill, Rajajinagar 560010", 12.9, 77.55);
		router.answer(Duration.ofMinutes(35), Duration.ofMinutes(45));
		UUID id = create(delivery("13:00", "Hare Krishna Hill, Rajajinagar 560010"));
		mvc.perform(authed(get("/api/v1/meal-plans/{id}/travel-estimate", id)));

		// This test used to assert that no column anywhere could hold a duration, on the E4-S16 D4
		// reading of Maps ToS §3.2.3(b). Rajeev reversed that on 2026-09-05 — "We are splitting hairs
		// here. Just put it in the box" — and V93 gives a plan a travel_minutes the temple owns and
		// edits. What survives the reversal is what the rule was really protecting, and it is still
		// worth holding:
		//
		//   * no table caches what the routing service said, so nobody can serve a stale duration
		//     back to a temple as though it were current; and
		//   * merely LOOKING at the estimate writes nothing. The figure changes only when somebody
		//     saves a plan or prints a card, both of which are deliberate acts.
		assertThat(admin.queryForObject("""
				SELECT count(*) FROM information_schema.tables
				WHERE table_schema = 'public' AND (table_name LIKE '%travel%' OR table_name LIKE '%duration%')
				""", Integer.class)).isZero();

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM meal_plans WHERE travel_minutes IS NOT NULL", Integer.class))
				.isZero();

		// What is kept is the pin, and when we asked for it.
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM meal_plans WHERE delivery_latitude IS NOT NULL AND geocoded_at IS NOT NULL",
				Integer.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("a figure somebody set by hand is never refreshed out from under them")
	void aManualFigureStands() throws Exception {
		geocoder.place("Hare Krishna Hill, Rajajinagar 560010", 12.9, 77.55);
		router.answer(Duration.ofMinutes(35), Duration.ofMinutes(45));
		UUID id = create(delivery("13:00", "Hare Krishna Hill, Rajajinagar 560010"));

		// Untouched, the figure is Google's and a refresh moves it. This is what printing a card does.
		// Run as the tenant because RLS hides every row from a thread that has not said who it is.
		assertThat(asTenant(() -> mealPlanService.refreshTravelEstimate(id))).isEqualTo(45);
		assertThat(travelMinutesOf(id)).isEqualTo(45);
		assertThat(travelSourceOf(id)).isEqualTo("ESTIMATED");

		// Somebody who drives that road says otherwise.
		admin.update("UPDATE meal_plans SET travel_minutes = 70, travel_minutes_source = 'MANUAL' WHERE id = ?", id);

		// Rajeev asked for both a refresh at print time and a manual override, and the two collide:
		// a recalculation would discard the correction of the one person who knew better, on the
		// sheet a driver is about to act on. The source is what settles it.
		router.answer(Duration.ofMinutes(20), Duration.ofMinutes(25));
		asTenant(() -> mealPlanService.refreshTravelEstimate(id));
		assertThat(travelMinutesOf(id)).isEqualTo(70);
		assertThat(travelSourceOf(id)).isEqualTo("MANUAL");
	}

	@Test
	@DisplayName("the composer can ask for an estimate before there is a plan to ask about")
	void anEstimateBeforeTheresAPlan() throws Exception {
		router.answer(Duration.ofMinutes(35), Duration.ofMinutes(45));

		// The saved endpoint takes a plan id, which a form has not got — somebody is still typing.
		mvc.perform(authed(get("/api/v1/meal-plans/travel-estimate")
						.param("latitude", "12.9").param("longitude", "77.55")
						.param("planDate", "2025-03-20").param("guestsEatAt", "13:00")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.available").value(true))
				.andExpect(jsonPath("$.leaveBy").value("12:15:00"))
				.andExpect(jsonPath("$.pessimisticMinutes").value(45));

		// And it writes nothing, because there is nothing to write to.
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM meal_plans WHERE travel_minutes IS NOT NULL", Integer.class))
				.isZero();
	}

	@Test
	@DisplayName("an address nobody picked has no coordinates, and says so rather than guessing")
	void aTypedAddressHasNowhereToGo() throws Exception {
		router.answer(Duration.ofMinutes(35), Duration.ofMinutes(45));

		mvc.perform(authed(get("/api/v1/meal-plans/travel-estimate")
						.param("planDate", "2025-03-20").param("guestsEatAt", "13:00")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.available").value(false))
				.andExpect(jsonPath("$.reason").value("ADDRESS_NOT_FOUND"));
	}

	private <T> T asTenant(java.util.function.Supplier<T> work) {
		TenantContext.set(tenant);
		try {
			return work.get();
		} finally {
			TenantContext.clear();
		}
	}

	private Integer travelMinutesOf(UUID id) {
		return admin.queryForObject("SELECT travel_minutes FROM meal_plans WHERE id = ?", Integer.class, id);
	}

	private String travelSourceOf(UUID id) {
		return admin.queryForObject("SELECT travel_minutes_source FROM meal_plans WHERE id = ?", String.class, id);
	}

	@Test
	@DisplayName("a delivery that arrives after the guests sit down is refused, not saved")
	void aDeliveryThatCannotArriveIsRefused() throws Exception {
		geocoder.place("Hare Krishna Hill, Rajajinagar 560010", 12.9, 77.55);

		// Rajeev's own example, 2026-09-05: cooked at 16:00, guests eating at 17:00, seventy minutes
		// of driving. It arrives at 17:10 and used to save without a word — the person who found out
		// was a driver on the morning, holding a job card that promised something impossible.
		mvc.perform(authed(post("/api/v1/meal-plans")).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"planDate":"2025-03-20","mealKind":"Event","recipeId":"%s","targetYield":80,
								 "readyBy":"16:00","eventName":"Impossible delivery","isOutside":true,
								 "handover":"DELIVERY","contactName":"Mrs Latha Rao",
								 "contactPhone":"+919000000001","adults":80,
								 "deliveryAddress":"Hare Krishna Hill, Rajajinagar 560010",
								 "guestsEatAt":"17:00","travelMinutes":70}
								""".formatted(khichdi)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4994"));

		assertThat(admin.queryForObject("SELECT count(*) FROM meal_plans", Integer.class)).isZero();
	}

	@Test
	@DisplayName("the floor is the drive alone: loading is warned about on the screen, never refused")
	void theFloorIsTheDriveAlone() throws Exception {
		geocoder.place("Hare Krishna Hill, Rajajinagar 560010", 12.9, 77.55);

		// Ready at 16:00, forty-five minutes of driving, guests at 17:00. Fifteen minutes to carry it
		// out and load it is not enough in practice, and the composer says so — but nobody here knows
		// this temple's courtyard, so the endpoint does not refuse it. "That is impractical" is a
		// different statement from "that is impossible", and only the second one is a rule.
		mvc.perform(authed(post("/api/v1/meal-plans")).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"planDate":"2025-03-20","mealKind":"Event","recipeId":"%s","targetYield":80,
								 "readyBy":"16:00","eventName":"Tight but possible","isOutside":true,
								 "handover":"DELIVERY","contactName":"Mrs Latha Rao",
								 "contactPhone":"+919000000001","adults":80,
								 "deliveryAddress":"Hare Krishna Hill, Rajajinagar 560010",
								 "guestsEatAt":"17:00","travelMinutes":45}
								""".formatted(khichdi)))
				.andExpect(status().isCreated());

		assertThat(admin.queryForObject("SELECT count(*) FROM meal_plans", Integer.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("a delivery with no travel allowance yet is not refused on a drive nobody measured")
	void noAllowanceMeansNothingToCheck() throws Exception {
		geocoder.place("Hare Krishna Hill, Rajajinagar 560010", 12.9, 77.55);

		// The address was typed rather than picked, so there is no allowance. Inventing one in order
		// to refuse the plan would be worse than saying nothing.
		mvc.perform(authed(post("/api/v1/meal-plans")).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"planDate":"2025-03-20","mealKind":"Event","recipeId":"%s","targetYield":80,
								 "readyBy":"16:55","eventName":"Nobody measured it","isOutside":true,
								 "handover":"DELIVERY","contactName":"Mrs Latha Rao",
								 "contactPhone":"+919000000001","adults":80,
								 "deliveryAddress":"Hare Krishna Hill, Rajajinagar 560010",
								 "guestsEatAt":"17:00"}
								""".formatted(khichdi)))
				.andExpect(status().isCreated());
	}

	@Test
	@DisplayName("a pickup and an in-house event have nothing to travel to")
	void nothingToTravelTo() throws Exception {
		UUID pickup = create("""
				{"planDate":"2025-03-20","mealKind":"Event","recipeId":"%s","targetYield":80,
				 "readyBy":"11:00","eventName":"School collection","isOutside":true,"handover":"PICKUP",
				 "contactName":"Mrs Latha Rao","contactPhone":"+919000000001"}
				""".formatted(khichdi));
		UUID inHouse = create("""
				{"planDate":"2025-03-20","mealKind":"Event","recipeId":"%s","targetYield":30,
				 "readyBy":"17:00","eventName":"Children's Bhagavad-gita Reading"}
				""".formatted(khichdi));

		for (UUID id : List.of(pickup, inHouse)) {
			mvc.perform(authed(get("/api/v1/meal-plans/{id}/travel-estimate", id)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.available").value(false))
					.andExpect(jsonPath("$.reason").value("NOT_A_DELIVERY"));
		}
		// And neither of them was ever asked for an address.
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM meal_plans WHERE delivery_address IS NOT NULL", Integer.class))
				.isZero();
		assertThat(geocoder.asked()).as("nobody should have been geocoded").isEmpty();
	}

	@Test
	@DisplayName("with no map service the plan is unchanged, and the estimate is a quiet unavailable")
	void withNoMapServiceNothingChanges() throws Exception {
		geocoder.switchOff();
		router.switchOff();

		UUID id = create(delivery("13:00", "Hare Krishna Hill, Rajajinagar 560010"));

		// Saved whole, in the same call, with no warning — nobody looked, so nothing was not found.
		mvc.perform(authed(get("/api/v1/meal-plans/{id}", id)))
				.andExpect(jsonPath("$.deliveryAddress").value("Hare Krishna Hill, Rajajinagar 560010"))
				.andExpect(jsonPath("$.guestsEatAt").value("13:00:00"));

		mvc.perform(authed(get("/api/v1/meal-plans/{id}/travel-estimate", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.available").value(false))
				.andExpect(jsonPath("$.reason").value("NO_MAP_SERVICE"))
				.andExpect(jsonPath("$.leaveBy").doesNotExist());
	}

	@Test
	@DisplayName("an address that cannot be found reports KMS-4993, and the plan still saves")
	void anAddressThatCannotBeFoundIsToldAboutAndNotRefused() throws Exception {
		// The geocoder is configured and simply cannot place it. That is the one failure worth
		// telling somebody about, because it is the one they can fix.
		String body = mvc.perform(createRequest(delivery("13:00", "Zzzz Qqqq, 999999")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.warning.code").value("KMS-4993"))
				.andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(body.replaceAll(".*?\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));

		// Re-open it: whole, with everything that was typed. A map service that could not find a
		// street has not cost anybody the meal plan.
		mvc.perform(authed(get("/api/v1/meal-plans/{id}", id)))
				.andExpect(jsonPath("$.deliveryAddress").value("Zzzz Qqqq, 999999"))
				.andExpect(jsonPath("$.guestsEatAt").value("13:00:00"))
				.andExpect(jsonPath("$.status").value("PLANNED"));

		mvc.perform(authed(get("/api/v1/meal-plans/{id}/travel-estimate", id)))
				.andExpect(jsonPath("$.available").value(false))
				.andExpect(jsonPath("$.reason").value("ADDRESS_NOT_FOUND"));
	}

	@Test
	@DisplayName("with no map service, an address nobody could place is not reported as not found")
	void silenceWhereNobodyLooked() throws Exception {
		geocoder.switchOff();

		mvc.perform(createRequest(delivery("13:00", "Zzzz Qqqq, 999999")))
				.andExpect(status().isCreated())
				// Telling somebody their address could not be found when nobody looked would send them
				// off to fix an address that is perfectly good.
				.andExpect(jsonPath("$.warning").doesNotExist());
	}

	@Test
	@DisplayName("coordinates older than thirty days are looked up again rather than reused")
	void staleCoordinatesAreLookedUpAgain() throws Exception {
		geocoder.place("Hare Krishna Hill, Rajajinagar 560010", 12.9, 77.55);
		router.answer(Duration.ofMinutes(30), Duration.ofMinutes(40));
		UUID id = create(delivery("13:00", "Hare Krishna Hill, Rajajinagar 560010"));
		assertThat(geocoder.asked()).hasSize(1);

		// Asking again inside the thirty days spends nothing: the pin we hold is still ours to use.
		mvc.perform(authed(get("/api/v1/meal-plans/{id}/travel-estimate", id)));
		assertThat(geocoder.asked()).hasSize(1);

		// Age it past the licence and the answer has to be asked for again.
		admin.update("UPDATE meal_plans SET geocoded_at = now() - INTERVAL '31 days' WHERE id = ?", id);
		mvc.perform(authed(get("/api/v1/meal-plans/{id}/travel-estimate", id)))
				.andExpect(jsonPath("$.available").value(true));
		assertThat(geocoder.asked()).hasSize(2);
	}

	@Test
	@DisplayName("a map service having a bad day never raises and never blocks a save")
	void aProviderThatBreaksChangesNothing() throws Exception {
		geocoder.explode();
		router.explode();

		UUID id = create(delivery("13:00", "Hare Krishna Hill, Rajajinagar 560010"));
		mvc.perform(authed(get("/api/v1/meal-plans/{id}", id)))
				.andExpect(jsonPath("$.deliveryAddress").value("Hare Krishna Hill, Rajajinagar 560010"));

		mvc.perform(authed(get("/api/v1/meal-plans/{id}/travel-estimate", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.available").value(false));
	}

	@Test
	@DisplayName("a drive of hours is accepted: the estimate is advice, never a rule")
	void aLongDriveIsNeverRefused() throws Exception {
		geocoder.place("Tirupati, Andhra Pradesh", 13.62, 79.41);
		router.answer(Duration.ofHours(3), Duration.ofHours(4));

		UUID id = create(delivery("18:00", "Tirupati, Andhra Pradesh"));

		mvc.perform(authed(get("/api/v1/meal-plans/{id}/travel-estimate", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.available").value(true))
				.andExpect(jsonPath("$.leaveBy").value("14:00:00"))
				.andExpect(jsonPath("$.pessimisticMinutes").value(240));
	}

	// ---------------------------------------------------------------------

	private String delivery(String guestsEatAt, String address) {
		return """
				{"planDate":"2025-03-20","mealKind":"Event","recipeId":"%s","targetYield":80,
				 "readyBy":"10:00","eventName":"School Gita Reading","isOutside":true,
				 "handover":"DELIVERY","contactName":"Mrs Latha Rao","contactPhone":"+919000000001",
				 "deliveryAddress":"%s","guestsEatAt":"%s"}
				""".formatted(khichdi, address, guestsEatAt);
	}

	private UUID create(String json) throws Exception {
		String body = mvc.perform(createRequest(json)).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(body.replaceAll(".*?\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
	}

	private MockHttpServletRequestBuilder createRequest(String json) {
		return post("/api/v1/meal-plans").header("Authorization", "Bearer valid-token")
				.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request) {
		return request.header("Authorization", "Bearer valid-token");
	}

	// ---------------------------------------------------------------------

	@TestConfiguration
	static class Stubs {

		@Bean
		@Primary
		StubTokenVerifier stubTokenVerifier() {
			return new StubTokenVerifier();
		}

		@Bean
		@Primary
		StubGeocoder stubGeocoder() {
			return new StubGeocoder();
		}

		@Bean
		@Primary
		StubRouter stubRouter() {
			return new StubRouter();
		}
	}

	/** A map that knows the handful of places these tests name, and nothing else. */
	static class StubGeocoder implements GeocodingProvider {

		private final Map<String, Coordinates> known = new HashMap<>();
		private final List<String> asked = new ArrayList<>();
		private boolean on = true;
		private boolean broken;

		void reset() {
			known.clear();
			asked.clear();
			on = true;
			broken = false;
		}

		void place(String address, double latitude, double longitude) {
			known.put(address.toLowerCase(), new Coordinates(latitude, longitude));
		}

		void switchOff() {
			on = false;
		}

		void explode() {
			broken = true;
		}

		List<String> asked() {
			return asked;
		}

		@Override
		public Optional<Coordinates> locate(String place) {
			asked.add(place);
			if (broken) {
				throw new IllegalStateException("the map service is having a bad day");
			}
			return on ? Optional.ofNullable(known.get(place.toLowerCase())) : Optional.empty();
		}

		@Override
		public boolean configured() {
			return on;
		}
	}

	/** A router that always says the same thing, so a test can say what that is. */
	static class StubRouter implements TravelTimeProvider {

		private Duration optimistic = Duration.ofMinutes(20);
		private Duration pessimistic = Duration.ofMinutes(30);
		private boolean on = true;
		private boolean broken;

		void reset() {
			optimistic = Duration.ofMinutes(20);
			pessimistic = Duration.ofMinutes(30);
			on = true;
			broken = false;
		}

		void answer(Duration low, Duration high) {
			optimistic = low;
			pessimistic = high;
		}

		void switchOff() {
			on = false;
		}

		void explode() {
			broken = true;
		}

		@Override
		public Optional<TravelTime> drive(
				GeocodingProvider.Coordinates origin, GeocodingProvider.Coordinates destination,
				Instant departAt) {

			if (broken) {
				throw new IllegalStateException("the routing service is having a bad day");
			}
			return on ? Optional.of(new TravelTime(optimistic, pessimistic)) : Optional.empty();
		}

		@Override
		public boolean configured() {
			return on;
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
