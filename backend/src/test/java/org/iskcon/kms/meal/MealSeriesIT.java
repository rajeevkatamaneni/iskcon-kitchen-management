package org.iskcon.kms.meal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.notification.NotificationService;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Repeating an event as a series, and cancelling "just this event or all events from this point
 * onwards" (T-307, Rajeev 2026-09-19).
 *
 * <p>His words, which every test here is checked against: <em>"Let us change for many weeks to
 * 'until' a date and also give them the option to pick the duration between repeats, and a cancel of
 * this repeating event should ask JUST this event OR all events from this point onwards."</em> The
 * screen reads <em>"Repeat Children's Bhagavad-gita Reading once every [1] week / weeks … until 31 Dec
 * 2026"</em>.
 *
 * <p><strong>Dates are relative to today at the temple,</strong> not fixed. The end date is refused
 * more than a year from today and "later" means not before today, so a fixed 2025 calendar would
 * test a different rule every month. {@code TODAY} is read in the temple's zone (Asia/Kolkata), the
 * same zone {@code TempleClock} answers for this tenant.
 *
 * <p>{@code NotificationService} is mocked, and only that, so this class shares the cached context of
 * {@code MealSaveIT} and the other classes that mock exactly that bean: cancelling a meal with a shift
 * tells its volunteers after commit, and a real notification service would start work nobody reads.
 *
 * <p>The forced failure in {@link #cancellingThisAndLaterIsOneTransaction} is a trigger, not a mock,
 * for the reason {@code MealSaveIT} gives: only PostgreSQL refusing a real row mid-transaction proves
 * the database undid what had already been written.
 */
@AutoConfigureMockMvc
class MealSeriesIT extends AbstractIntegrationTest {

	private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

	/** A source a week out, so every copy it makes is in the future and inside the one-year cap. */
	private static final LocalDate S = TODAY.plusWeeks(1);

	private static final String READING = "Children's Bhagavad-gita Reading";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private MealKindService mealKindService;

	@Autowired
	private DataSource dataSource;

	@MockBean
	private NotificationService notificationService;

	private final ObjectMapper json = new ObjectMapper();

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID event;
	private UUID khichdi;
	private UUID kheer;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		tenant = tenant("t307-series-temple", "Bengaluru Temple");
		insertUser(tenant, "uid-t307-admin", "t307-admin@example.com", "TEMPLE_ADMIN", "+919876530701");
		insertUser(tenant, "uid-t307-vol-a", "t307-vol-a@example.com", "VOLUNTEER", "+919876530702");
		insertUser(tenant, "uid-t307-vol-b", "t307-vol-b@example.com", "VOLUNTEER", "+919876530703");

		// Rice is forbidden on Ekadashi and milk is not, so khichdi is refused on a fast day and kheer
		// is not — the difference the fasting skip turns on.
		UUID rice = ingredient(tenant, "Rice", true);
		UUID milk = ingredient(tenant, "Milk", false);
		UUID category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Mains') RETURNING id
				""", UUID.class, tenant);
		khichdi = recipe(tenant, "Khichdi", category, rice);
		kheer = recipe(tenant, "Kheer", category, milk);

		// Every meal is cooked by one of the temple's kitchens (Epic 12), and saving one no longer makes a
		// kitchen: a real temple is given its main kitchen when it is provisioned. This temple is made by
		// hand, so it is given one here, as provisioning would (T-354).
		MealFixture.plannerKitchen(admin, tenant, null);
		TenantContext.set(tenant);
		try {
			mealKindService.seedForCurrentTenant();
		} finally {
			TenantContext.clear();
		}
		event = MealFixture.kindId(admin, tenant, "Event");
		reset(notificationService);
		stubVerifier.accept("uid-t307-admin");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DROP TRIGGER IF EXISTS t307_forced_failure ON meal_dishes");
		admin.execute("DROP FUNCTION IF EXISTS t307_forced_failure()");
		admin.execute("DELETE FROM shift_reminders");
		admin.execute("DELETE FROM shift_waitlist");
		admin.execute("DELETE FROM shift_signups");
		admin.execute("DELETE FROM shifts");
		MealFixture.deleteAll(admin);
		admin.execute("DELETE FROM calendar_days");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- "once every [N] weeks … until [date]" --------------------------------

	@Test
	@DisplayName("every week until a copy's own date includes that date; until a day short of the next stops there")
	void everyWeekUntilOnAndBetweenCopyDates() throws Exception {
		UUID source = plan(S, READING, khichdi);

		JsonNode between = preview(source, 1, S.plusWeeks(4).plusDays(3));
		assertThat(dates(between.get("dates"))).containsExactly(
				S.plusWeeks(1), S.plusWeeks(2), S.plusWeeks(3), S.plusWeeks(4));

		JsonNode made = repeat(source, 1, S.plusWeeks(4));
		assertThat(made.get("copies").asInt()).isEqualTo(4);
		assertThat(made.get("preparations").asInt()).isEqualTo(4);
		assertThat(dates(made.get("dates"))).containsExactly(
				S.plusWeeks(1), S.plusWeeks(2), S.plusWeeks(3), S.plusWeeks(4));
		assertThat(made.get("lastDate").asText()).isEqualTo(S.plusWeeks(4).toString());
		assertThat(made.get("skippedFasting")).isEmpty();
		assertThat(made.get("skippedAlreadyPlanned")).isEmpty();
		JsonNode series = made.get("series");
		assertThat(series.get("seriesId").isNull()).isFalse();
		assertThat(series.get("everyWeeks").asInt()).isEqualTo(1);
		assertThat(series.get("until").asText()).isEqualTo(S.plusWeeks(4).toString());
		assertThat(series.get("position").asInt()).isEqualTo(1);
		assertThat(series.get("count").asInt()).isEqualTo(5);

		// Each copy is a real meal with the event's name, amount and hour.
		assertThat(count("SELECT count(*) FROM meals WHERE event_name = ?", READING)).isEqualTo(5);
		mvc.perform(authed(get("/api/v1/meals").param("from", S.plusWeeks(3).toString())
						.param("to", S.plusWeeks(3).toString())))
				.andExpect(jsonPath("$[0].eventName").value(READING))
				.andExpect(jsonPath("$[0].readyBy").value("17:00:00"))
				.andExpect(jsonPath("$[0].dishes[0].targetYield").value(30.0))
				.andExpect(jsonPath("$[0].series.position").value(4))
				.andExpect(jsonPath("$[0].series.count").value(5));
	}

	@Test
	@DisplayName("every two and every three weeks land on the right days and stop at the end date")
	void everyTwoAndThreeWeeks() throws Exception {
		UUID fortnightly = plan(S, READING, khichdi);
		JsonNode two = repeat(fortnightly, 2, S.plusWeeks(9));
		assertThat(dates(two.get("dates"))).containsExactly(
				S.plusWeeks(2), S.plusWeeks(4), S.plusWeeks(6), S.plusWeeks(8));
		assertThat(two.get("series").get("everyWeeks").asInt()).isEqualTo(2);

		UUID threeWeekly = plan(S, "Kirtan Evening", kheer);
		JsonNode three = repeat(threeWeekly, 3, S.plusWeeks(9));
		assertThat(dates(three.get("dates"))).containsExactly(S.plusWeeks(3), S.plusWeeks(6), S.plusWeeks(9));
		assertThat(three.get("lastDate").asText()).isEqualTo(S.plusWeeks(9).toString());

		// Two events, two series.
		assertThat(count("SELECT count(*) FROM meal_series")).isEqualTo(2);
	}

	@Test
	@DisplayName("the end date may be a year from today and not a day more")
	void theOneYearCap() throws Exception {
		UUID source = plan(S, READING, kheer);

		mvc.perform(authed(previewRequest(source, 12, TODAY.plusYears(1))))
				.andExpect(status().isOk());

		for (MockHttpServletRequestBuilder tooFar : List.of(
				previewRequest(source, 1, TODAY.plusYears(1).plusDays(1)),
				repeatRequest(source, 1, TODAY.plusYears(1).plusDays(1)))) {
			mvc.perform(authed(tooFar))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("KMS-400176"));
		}

		// And the year's worth is real: a weekly repeat to the last allowed day is made.
		JsonNode made = repeat(source, 1, TODAY.plusYears(1));
		assertThat(made.get("lastDate").asText()).isEqualTo(
				dates(made.get("dates")).get(made.get("copies").asInt() - 1).toString());
		assertThat(LocalDate.parse(made.get("lastDate").asText())).isAfter(TODAY.plusYears(1).minusWeeks(1));
	}

	@Test
	@DisplayName("a gap of 0 or 13 weeks is refused, and an end date before the first copy makes nothing")
	void refusals() throws Exception {
		UUID source = plan(S, READING, kheer);

		for (int weeks : new int[] {0, 13}) {
			mvc.perform(authed(repeatRequest(source, weeks, S.plusWeeks(4))))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("KMS-400175"));
			mvc.perform(authed(previewRequest(source, weeks, S.plusWeeks(4))))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("KMS-400175"));
		}

		mvc.perform(authed(repeatRequest(source, 1, S.plusDays(6))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400177"));
		mvc.perform(authed(previewRequest(source, 2, S.plusWeeks(1))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400177"));

		// Nothing refused wrote anything.
		assertThat(count("SELECT count(*) FROM meals")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM meal_series")).isZero();
	}

	@Test
	@DisplayName("a week whose Ekadashi forbids a dish is skipped and named by its date")
	void theFastSkipsItsDate() throws Exception {
		LocalDate fast = S.plusWeeks(2);
		admin.update("""
				INSERT INTO calendar_days (tenant_id, cal_date, tithi, paksa, masa, is_ekadashi,
						ekadashi_name, fast_type)
				VALUES (?, ?, 11, 1, 11, true, 'Papamocani Ekadasi', 'Ekadashi')
				""", tenant, fast);

		UUID rice = plan(S, READING, khichdi);
		JsonNode made = repeat(rice, 1, S.plusWeeks(3));
		assertThat(dates(made.get("dates"))).containsExactly(S.plusWeeks(1), S.plusWeeks(3));
		assertThat(dates(made.get("skippedFasting"))).containsExactly(fast);
		assertThat(count("SELECT count(*) FROM meals m JOIN meal_plan_days pd ON pd.id = m.meal_plan_day_id "
				+ "WHERE pd.plan_date = ?", fast)).isZero();

		// Kheer suits the fast, so the same date is made for it.
		UUID milk = plan(S, "Kirtan Evening", kheer);
		JsonNode kept = repeat(milk, 1, S.plusWeeks(3));
		assertThat(dates(kept.get("dates"))).contains(fast);
		assertThat(kept.get("skippedFasting")).isEmpty();
	}

	@Test
	@DisplayName("a date the event is already planned on is skipped and named, and gets no second set of dishes")
	void anAlreadyPlannedDateIsLeftAlone() throws Exception {
		UUID source = plan(S, READING, khichdi);
		// Somebody already planned the reading two weeks on, with kheer.
		UUID already = plan(S.plusWeeks(2), READING, kheer);
		// And the week after that it was planned and then cancelled.
		UUID cancelled = plan(S.plusWeeks(3), READING, kheer);
		mvc.perform(authed(post("/api/v1/meals/{id}/cancel", cancelled))).andExpect(status().isOk());

		JsonNode made = repeat(source, 1, S.plusWeeks(3));
		assertThat(dates(made.get("dates"))).containsExactly(S.plusWeeks(1), S.plusWeeks(3));
		assertThat(dates(made.get("skippedAlreadyPlanned"))).containsExactly(S.plusWeeks(2));

		// The planned one still has exactly its one dish, not kheer and khichdi.
		assertThat(count("SELECT count(*) FROM meal_dishes WHERE meal_id = ? AND status <> 'CANCELLED'", already))
				.isEqualTo(1);
		assertThat(admin.queryForObject("SELECT series_id FROM meals WHERE id = ?", UUID.class, already)).isNull();

		// The cancelled one was brought back by the repeat, as a fresh copy in the series.
		UUID series = seriesOf(source);
		assertThat(admin.queryForObject("SELECT series_id FROM meals WHERE id = ?", UUID.class, cancelled))
				.isEqualTo(series);
		mvc.perform(authed(get("/api/v1/meals/{id}", cancelled)))
				.andExpect(jsonPath("$.status").value("PLANNED"))
				.andExpect(jsonPath("$.series.seriesId").value(series.toString()));
	}

	@Test
	@DisplayName("the preview writes nothing, and names exactly the dates the repeat then makes")
	void thePreviewIsTheRepeat() throws Exception {
		admin.update("""
				INSERT INTO calendar_days (tenant_id, cal_date, tithi, paksa, masa, is_ekadashi,
						ekadashi_name, fast_type)
				VALUES (?, ?, 11, 1, 11, true, 'Papamocani Ekadasi', 'Ekadashi')
				""", tenant, S.plusWeeks(4));
		UUID source = plan(S, READING, khichdi);
		plan(S.plusWeeks(6), READING, khichdi);

		Map<String, Long> before = rowCounts();
		JsonNode preview = preview(source, 2, S.plusWeeks(8));
		assertThat(rowCounts()).as("rows written by a preview").isEqualTo(before);

		assertThat(dates(preview.get("dates"))).containsExactly(S.plusWeeks(2), S.plusWeeks(8));
		assertThat(dates(preview.get("skippedFasting"))).containsExactly(S.plusWeeks(4));
		assertThat(dates(preview.get("skippedAlreadyPlanned"))).containsExactly(S.plusWeeks(6));
		assertThat(preview.get("series").get("seriesId").isNull()).isTrue();
		assertThat(preview.get("series").get("count").asInt()).isEqualTo(3);

		JsonNode made = repeat(source, 2, S.plusWeeks(8));
		for (String field : List.of("copies", "preparations", "dates", "skippedFasting",
				"skippedAlreadyPlanned", "lastDate")) {
			assertThat(made.get(field)).as(field).isEqualTo(preview.get(field));
		}
		assertThat(made.get("series").get("count")).isEqualTo(preview.get("series").get("count"));
		assertThat(made.get("series").get("until")).isEqualTo(preview.get("series").get("until"));
	}

	// ---- Never a copy in the past (T-310) -------------------------------------------

	@Test
	@DisplayName("repeating a past event makes copies from today on, today included, on the source's own grid")
	void aPastSourceMakesCopiesOnlyFromToday() throws Exception {
		// Three weeks ago, weekly: the first two dates on its grid are gone and the third is today.
		LocalDate past = TODAY.minusWeeks(3);
		// The first of those gone dates was a fast, and the second already has the reading planned.
		// Neither is a skip the planner can do anything about, so neither is named as one.
		admin.update("""
				INSERT INTO calendar_days (tenant_id, cal_date, tithi, paksa, masa, is_ekadashi,
						ekadashi_name, fast_type)
				VALUES (?, ?, 11, 1, 11, true, 'Papamocani Ekadasi', 'Ekadashi')
				""", tenant, past.plusWeeks(1));
		UUID source = plan(past, READING, khichdi);
		plan(past.plusWeeks(2), READING, khichdi);

		JsonNode preview = preview(source, 1, TODAY.plusWeeks(2));
		assertThat(dates(preview.get("dates"))).containsExactly(TODAY, TODAY.plusWeeks(1), TODAY.plusWeeks(2));
		assertThat(preview.get("skippedFasting")).isEmpty();
		assertThat(preview.get("skippedAlreadyPlanned")).isEmpty();
		assertThat(preview.get("copies").asInt()).isEqualTo(3);

		JsonNode made = repeat(source, 1, TODAY.plusWeeks(2));
		for (String field : List.of("copies", "preparations", "dates", "skippedFasting",
				"skippedAlreadyPlanned", "lastDate")) {
			assertThat(made.get(field)).as(field).isEqualTo(preview.get(field));
		}
		assertThat(made.get("series").get("count").asInt()).isEqualTo(4);
		assertThat(made.get("lastDate").asText()).isEqualTo(TODAY.plusWeeks(2).toString());
		// Nothing was made on the gone dates: the fast is still empty and the planned one untouched.
		assertThat(count("SELECT count(*) FROM meals m JOIN meal_plan_days pd ON pd.id = m.meal_plan_day_id "
				+ "WHERE pd.plan_date = ?", past.plusWeeks(1))).isZero();
		assertThat(seriesOf(mealOn(past.plusWeeks(2)))).isNull();
		assertThat(standing()).containsExactly(
				past, past.plusWeeks(2), TODAY, TODAY.plusWeeks(1), TODAY.plusWeeks(2));

		// Every two weeks from 24 days ago: the grid is kept, so the first copy is 4 days from now — not
		// today, and not two weeks from today.
		LocalDate older = TODAY.minusDays(24);
		UUID fortnightly = plan(older, "Kirtan Evening", kheer);
		JsonNode two = preview(fortnightly, 2, TODAY.plusDays(32));
		assertThat(dates(two.get("dates"))).containsExactly(
				TODAY.plusDays(4), TODAY.plusDays(18), TODAY.plusDays(32));
		assertThat(dates(repeat(fortnightly, 2, TODAY.plusDays(32)).get("dates"))).isEqualTo(dates(two.get("dates")));
	}

	@Test
	@DisplayName("a past event whose every date up to the end date is gone makes nothing (KMS-400177)")
	void aPastSourceWithNoDateLeftIsRefused() throws Exception {
		// Weekly from five weeks ago until yesterday: four dates, all gone.
		UUID weekly = plan(TODAY.minusWeeks(5), READING, kheer);
		// Fortnightly from 15 days ago until 5 days on: the one date on its grid was yesterday, and the
		// next falls after the end date — the end date is in the future and still nothing fits.
		UUID fortnightly = plan(TODAY.minusDays(15), "Kirtan Evening", kheer);

		Map<String, Long> before = rowCounts();
		for (MockHttpServletRequestBuilder none : List.of(
				repeatRequest(weekly, 1, TODAY.minusDays(1)),
				previewRequest(weekly, 1, TODAY.minusDays(1)),
				repeatRequest(fortnightly, 2, TODAY.plusDays(5)),
				previewRequest(fortnightly, 2, TODAY.plusDays(5)))) {
			mvc.perform(authed(none))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("KMS-400177"));
		}
		assertThat(rowCounts()).as("rows written by a refused repeat").isEqualTo(before);
		assertThat(count("SELECT count(*) FROM meal_series")).isZero();
	}

	// ---- The series ---------------------------------------------------------------

	@Test
	@DisplayName("repeating from a copy extends the same series; the summary counts only what still stands")
	void aSeriesIsExtendedAndSummarised() throws Exception {
		UUID source = plan(S, READING, kheer);
		repeat(source, 1, S.plusWeeks(2));
		UUID series = seriesOf(source);
		UUID third = mealOn(S.plusWeeks(2));
		assertThat(seriesOf(mealOn(S.plusWeeks(1)))).isEqualTo(series);
		assertThat(seriesOf(third)).isEqualTo(series);

		// From the third occurrence, fortnightly for another month: the same series, not a new one.
		JsonNode extended = repeat(third, 2, S.plusWeeks(6));
		assertThat(dates(extended.get("dates"))).containsExactly(S.plusWeeks(4), S.plusWeeks(6));
		assertThat(extended.get("series").get("seriesId").asText()).isEqualTo(series.toString());
		assertThat(count("SELECT count(*) FROM meal_series")).isEqualTo(1);
		assertThat(admin.queryForObject("SELECT every_weeks FROM meal_series WHERE id = ?", Integer.class, series))
				.isEqualTo(2);

		// From the source again, to an earlier end: extending never shortens the series.
		JsonNode again = repeat(source, 1, S.plusWeeks(3));
		assertThat(dates(again.get("dates"))).containsExactly(S.plusWeeks(3));
		assertThat(dates(again.get("skippedAlreadyPlanned"))).containsExactly(S.plusWeeks(1), S.plusWeeks(2));
		assertThat(admin.queryForObject("SELECT until_date FROM meal_series WHERE id = ?", LocalDate.class, series))
				.isEqualTo(S.plusWeeks(6));

		// S, +1, +2, +3, +4, +6: six standing, the third is third.
		mvc.perform(authed(get("/api/v1/meals/{id}", third)))
				.andExpect(jsonPath("$.series.seriesId").value(series.toString()))
				.andExpect(jsonPath("$.series.everyWeeks").value(1))
				.andExpect(jsonPath("$.series.until").value(S.plusWeeks(6).toString()))
				.andExpect(jsonPath("$.series.position").value(3))
				.andExpect(jsonPath("$.series.count").value(6));

		// Cancel the second: it drops out of the count and has no position; the third moves up.
		UUID second = mealOn(S.plusWeeks(1));
		mvc.perform(authed(post("/api/v1/meals/{id}/cancel", second))).andExpect(status().isOk());
		mvc.perform(authed(get("/api/v1/meals/{id}", second)))
				.andExpect(jsonPath("$.series.position").isEmpty())
				.andExpect(jsonPath("$.series.count").value(5));
		mvc.perform(authed(get("/api/v1/meals").param("from", S.toString()).param("to", S.plusWeeks(6).toString())))
				.andExpect(jsonPath("$[?(@.mealId == '%s')].series.position".formatted(third)).value(2))
				.andExpect(jsonPath("$[?(@.mealId == '%s')].series.count".formatted(third)).value(5));

		// A meal never repeated says so explicitly, rather than leaving the key out.
		UUID alone = plan(S, "Kirtan Evening", kheer);
		MvcResult read = mvc.perform(authed(get("/api/v1/meals/{id}", alone))).andReturn();
		JsonNode body = json.readTree(read.getResponse().getContentAsString());
		assertThat(body.has("series")).isTrue();
		assertThat(body.get("series").isNull()).isTrue();
	}

	// ---- Cancelling ---------------------------------------------------------------

	@Test
	@DisplayName("cancelling just this one leaves every other occurrence standing")
	void cancelThisLeavesTheOthers() throws Exception {
		UUID source = plan(S, READING, kheer);
		repeat(source, 1, S.plusWeeks(3));
		UUID second = mealOn(S.plusWeeks(1));

		mvc.perform(authed(post("/api/v1/meals/{id}/cancel", second))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"scope\":\"THIS\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.volunteersTold").value(0))
				.andExpect(jsonPath("$.mealsCancelled").value(1))
				.andExpect(jsonPath("$.lastDate").value(S.plusWeeks(3).toString()));

		assertThat(standing()).containsExactly(S, S.plusWeeks(2), S.plusWeeks(3));
	}

	@Test
	@DisplayName("this and every later one cancels only the later ones still to cook — never a past, cooked or recorded one")
	void cancelThisAndLater() throws Exception {
		// A fortnight ago, weekly for five weeks: last week, today, and three weeks ahead.
		LocalDate p = TODAY.minusWeeks(2);
		UUID source = plan(p, READING, kheer);
		repeat(source, 1, TODAY.plusWeeks(3));
		// A repeat no longer makes a copy on a day already gone (T-310), so last week's occurrence is
		// the one a series made a fortnight ago would already hold: planned, and joined to the series
		// as the repeat joins its copies.
		UUID lastWeek = plan(TODAY.minusWeeks(1), READING, kheer);
		admin.update("UPDATE meals SET series_id = ? WHERE id = ?", seriesOf(source), lastWeek);
		UUID today = mealOn(TODAY);
		UUID cooked = mealOn(TODAY.plusWeeks(1));
		UUID recorded = mealOn(TODAY.plusWeeks(2));
		UUID edited = mealOn(TODAY.plusWeeks(3));

		// Cooked: its dish went into a pot. Recorded: the card came back. Set by hand, each on its own,
		// so each condition is shown to exclude by itself.
		admin.update("UPDATE meal_dishes SET status = 'COOKED', cooked_at = now(), actual_servings = 30 "
				+ "WHERE meal_id = ?", cooked);
		admin.update("UPDATE meals SET recorded_at = now() WHERE id = ?", recorded);

		// The last one is changed on its own, and given a shift with one volunteer and one waiting.
		UUID dish = admin.queryForObject("SELECT id FROM meal_dishes WHERE meal_id = ?", UUID.class, edited);
		mvc.perform(authed(put("/api/v1/meals/{id}", edited))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"readyBy":"17:00","eventName":"%s","dishes":[{"id":"%s","recipeId":"%s","targetYield":45}],
								 "volunteerShift":{"title":"Help with the reading","startTime":"16:00","endTime":"18:00",
								  "location":"Hall","capacity":1,"reminderOffsetsMinutes":[1440]}}
								""".formatted(READING, dish, kheer)))
				.andExpect(status().isOk());
		UUID shift = admin.queryForObject("SELECT id FROM shifts WHERE meal_id = ?", UUID.class, edited);
		admin.update("""
				INSERT INTO shift_signups (tenant_id, shift_id, volunteer_user_id)
				VALUES (?, ?, (SELECT id FROM users WHERE firebase_uid = 'uid-t307-vol-a'))
				""", tenant, shift);
		admin.update("""
				INSERT INTO shift_waitlist (tenant_id, shift_id, volunteer_user_id)
				VALUES (?, ?, (SELECT id FROM users WHERE firebase_uid = 'uid-t307-vol-b'))
				""", tenant, shift);

		// What the confirmation shows.
		JsonNode later = read(get("/api/v1/meals/{id}/later-in-series", source));
		assertThat(later.get("seriesId").asText()).isEqualTo(seriesOf(source).toString());
		assertThat(ids(later.get("later"))).containsExactly(today, edited);
		assertThat(later.get("later").get(0).get("edited").asBoolean()).isFalse();
		assertThat(later.get("later").get(0).get("volunteersSignedUp").asInt()).isZero();
		assertThat(later.get("later").get(1).get("edited").asBoolean()).isTrue();
		assertThat(later.get("later").get(1).get("volunteersSignedUp").asInt()).isEqualTo(1);
		assertThat(later.get("lastDate").asText()).isEqualTo(TODAY.plusWeeks(3).toString());
		assertThat(later.get("volunteersToTell").asInt()).isEqualTo(2);

		// A stale confirmation — it showed only today — cancels nothing.
		Map<String, Long> before = rowCounts();
		mvc.perform(authed(cancelRequest(source, "THIS_AND_LATER", List.of(today))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400179"));
		assertThat(rowCounts()).isEqualTo(before);
		assertThat(standing()).hasSize(6);

		// The confirmation as shown.
		mvc.perform(authed(cancelRequest(source, "THIS_AND_LATER", List.of(today, edited))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.volunteersTold").value(2))
				.andExpect(jsonPath("$.mealsCancelled").value(3))
				.andExpect(jsonPath("$.lastDate").value(TODAY.plusWeeks(2).toString()));

		// Last week's, the cooked one and the recorded one stand, untouched.
		assertThat(standing()).containsExactly(TODAY.minusWeeks(1), TODAY.plusWeeks(1), TODAY.plusWeeks(2));
		assertThat(dishStatuses(lastWeek)).containsExactly("PLANNED");
		assertThat(dishStatuses(cooked)).containsExactly("COOKED");
		assertThat(dishStatuses(recorded)).containsExactly("PLANNED");
		assertThat(dishStatuses(source)).containsExactly("CANCELLED");
		assertThat(dishStatuses(today)).containsExactly("CANCELLED");

		// An audit entry for each meal cancelled, and only those.
		assertThat(admin.queryForList("""
				SELECT entity_id FROM audit_events WHERE action = 'MEAL_PLAN_CANCELLED'
				""", UUID.class)).containsExactlyInAnyOrder(source, today, edited);
		assertThat(admin.queryForObject("SELECT status FROM shifts WHERE id = ?", String.class, shift))
				.isEqualTo("CANCELLED");
		// And the series now ends on the last occurrence still standing.
		assertThat(admin.queryForObject("SELECT until_date FROM meal_series WHERE id = ?",
				LocalDate.class, seriesOf(source))).isEqualTo(TODAY.plusWeeks(2));

		// A cooked meal cannot start a "this and later" either.
		mvc.perform(authed(cancelRequest(cooked, "THIS_AND_LATER", null)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400045"));
	}

	@Test
	@DisplayName("this and later on a meal that never repeated is refused, and so is asking what is later")
	void notInASeries() throws Exception {
		UUID alone = plan(S, READING, kheer);

		mvc.perform(authed(get("/api/v1/meals/{id}/later-in-series", alone)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400178"));
		mvc.perform(authed(cancelRequest(alone, "THIS_AND_LATER", null)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400178"));
		assertThat(standing()).containsExactly(S);
	}

	@Test
	@DisplayName("this and later is one transaction: a failure on the last meal leaves every meal standing")
	void cancellingThisAndLaterIsOneTransaction() throws Exception {
		UUID source = plan(S, READING, kheer);
		repeat(source, 1, S.plusWeeks(3));
		UUID last = mealOn(S.plusWeeks(3));

		// PostgreSQL refuses to cancel the last one's dish — after the source and the two before it
		// have been cancelled in the same transaction.
		admin.execute("""
				CREATE FUNCTION t307_forced_failure() RETURNS trigger AS $$
				BEGIN
					IF NEW.meal_id = '%s'::uuid AND NEW.status = 'CANCELLED' THEN
						RAISE EXCEPTION 't307 forced failure';
					END IF;
					RETURN NEW;
				END $$ LANGUAGE plpgsql
				""".formatted(last));
		admin.execute("""
				CREATE TRIGGER t307_forced_failure BEFORE UPDATE ON meal_dishes
				FOR EACH ROW EXECUTE FUNCTION t307_forced_failure()
				""");

		Map<String, Long> before = rowCounts();
		MvcResult failed = mvc.perform(authed(cancelRequest(source, "THIS_AND_LATER", null))).andReturn();
		assertThat(failed.getResponse().getStatus()).isEqualTo(500);

		assertThat(standing()).containsExactly(S, S.plusWeeks(1), S.plusWeeks(2), S.plusWeeks(3));
		assertThat(count("SELECT count(*) FROM meal_dishes WHERE status = 'CANCELLED'")).isZero();
		assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'MEAL_PLAN_CANCELLED'")).isZero();
		assertThat(rowCounts()).isEqualTo(before);
	}

	// ---- Another temple ---------------------------------------------------------------

	@Test
	@DisplayName("another temple can neither see a series nor put its own meal into one")
	void anotherTempleCannotSeeOrJoinASeries() throws Exception {
		UUID source = plan(S, READING, kheer);
		repeat(source, 1, S.plusWeeks(2));
		UUID series = seriesOf(source);

		UUID otherTemple = tenant("t307-other-temple", "Mysuru Temple");
		insertUser(otherTemple, "uid-t307-other", "t307-other@example.com", "TEMPLE_ADMIN", "+919876530709");
		MealFixture.plannerKitchen(admin, otherTemple, null);
		TenantContext.set(otherTemple);
		try {
			mealKindService.seedForCurrentTenant();
		} finally {
			TenantContext.clear();
		}
		UUID theirCategory = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Mains') RETURNING id
				""", UUID.class, otherTemple);
		UUID theirRecipe = recipe(otherTemple, "Payasam", theirCategory,
				ingredient(otherTemple, "Jaggery", false));
		UUID theirEvent = MealFixture.kindId(admin, otherTemple, "Event");

		stubVerifier.accept("uid-t307-other");
		// Through the API: their temple has no such meal, so none of it is there to read or repeat.
		mvc.perform(authed(get("/api/v1/meals/{id}", source))).andExpect(status().isNotFound());
		mvc.perform(authed(get("/api/v1/meals/{id}/later-in-series", source))).andExpect(status().isNotFound());
		mvc.perform(authed(repeatRequest(source, 1, S.plusWeeks(4)))).andExpect(status().isNotFound());
		mvc.perform(authed(post("/api/v1/meals")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"planDate":"%s","mealKindId":"%s","readyBy":"17:00","eventName":"%s",
								 "dishes":[{"recipeId":"%s","targetYield":30}]}
								""".formatted(S, theirEvent, READING, theirRecipe))))
				.andExpect(status().isCreated());
		UUID theirs = admin.queryForObject("SELECT id FROM meals WHERE tenant_id = ?", UUID.class, otherTemple);

		// Through the database, as the application's own unprivileged role.
		JdbcTemplate app = new JdbcTemplate(dataSource);
		TenantContext.set(otherTemple);
		try {
			assertThat(app.queryForObject("SELECT count(*) FROM meal_series", Long.class)).isZero();
			// Even knowing the id, their meal cannot be put into our series: the key includes the temple.
			assertThatThrownBy(() -> app.update("UPDATE meals SET series_id = ? WHERE id = ?", series, theirs))
					.hasMessageContaining("meals_series_same_tenant");
		} finally {
			TenantContext.clear();
		}
		TenantContext.set(tenant);
		try {
			assertThat(app.queryForObject("SELECT count(*) FROM meal_series", Long.class)).isEqualTo(1);
		} finally {
			TenantContext.clear();
		}
		assertThat(admin.queryForObject("SELECT series_id FROM meals WHERE id = ?", UUID.class, theirs)).isNull();
		// Our series still has exactly its own three.
		assertThat(count("SELECT count(*) FROM meals WHERE series_id = ?", series)).isEqualTo(3);
	}

	// ---------------------------------------------------------------------

	private UUID plan(LocalDate date, String eventName, UUID recipe) throws Exception {
		MvcResult result = mvc.perform(authed(post("/api/v1/meals")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"planDate":"%s","mealKindId":"%s","readyBy":"17:00","eventName":"%s",
								 "dishes":[{"recipeId":"%s","targetYield":30}]}
								""".formatted(date, event, eventName, recipe))))
				.andExpect(status().isCreated())
				.andReturn();
		return MealRequests.idOf(result.getResponse().getContentAsString());
	}

	private MockHttpServletRequestBuilder repeatRequest(UUID meal, int everyWeeks, LocalDate until) {
		return post("/api/v1/meals/{id}/repeat", meal)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"everyWeeks\":%d,\"until\":\"%s\"}".formatted(everyWeeks, until));
	}

	private MockHttpServletRequestBuilder previewRequest(UUID meal, int everyWeeks, LocalDate until) {
		return get("/api/v1/meals/{id}/repeat-preview", meal)
				.param("everyWeeks", String.valueOf(everyWeeks))
				.param("until", until.toString());
	}

	private MockHttpServletRequestBuilder cancelRequest(UUID meal, String scope, List<UUID> expected) {
		StringBuilder body = new StringBuilder("{\"scope\":\"" + scope + "\"");
		if (expected != null) {
			body.append(",\"expectedMealIds\":[");
			for (int i = 0; i < expected.size(); i++) {
				body.append(i == 0 ? "" : ",").append('"').append(expected.get(i)).append('"');
			}
			body.append(']');
		}
		body.append('}');
		return post("/api/v1/meals/{id}/cancel", meal)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body.toString());
	}

	private JsonNode repeat(UUID meal, int everyWeeks, LocalDate until) throws Exception {
		MvcResult result = mvc.perform(authed(repeatRequest(meal, everyWeeks, until)))
				.andExpect(status().isOk())
				.andReturn();
		return json.readTree(result.getResponse().getContentAsString());
	}

	private JsonNode preview(UUID meal, int everyWeeks, LocalDate until) throws Exception {
		MvcResult result = mvc.perform(authed(previewRequest(meal, everyWeeks, until)))
				.andExpect(status().isOk())
				.andReturn();
		return json.readTree(result.getResponse().getContentAsString());
	}

	private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
		MvcResult result = mvc.perform(authed(request)).andExpect(status().isOk()).andReturn();
		return json.readTree(result.getResponse().getContentAsString());
	}

	private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request) {
		return request.header("Authorization", "Bearer valid-token");
	}

	private static List<LocalDate> dates(JsonNode array) {
		List<LocalDate> out = new ArrayList<>();
		array.forEach(n -> out.add(LocalDate.parse(n.asText())));
		return out;
	}

	private static List<UUID> ids(JsonNode later) {
		List<UUID> out = new ArrayList<>();
		later.forEach(n -> out.add(UUID.fromString(n.get("mealId").asText())));
		return out;
	}

	private UUID mealOn(LocalDate date) {
		return admin.queryForObject("""
				SELECT m.id FROM meals m JOIN meal_plan_days pd ON pd.id = m.meal_plan_day_id
				WHERE m.tenant_id = ? AND pd.plan_date = ? AND m.event_name = ?
				""", UUID.class, tenant, date, READING);
	}

	private UUID seriesOf(UUID meal) {
		return admin.queryForObject("SELECT series_id FROM meals WHERE id = ?", UUID.class, meal);
	}

	/** The dates of this temple's reading that still have a dish to cook or cooked, in order. */
	private List<LocalDate> standing() {
		return admin.queryForList("""
				SELECT pd.plan_date FROM meals m JOIN meal_plan_days pd ON pd.id = m.meal_plan_day_id
				WHERE m.tenant_id = ? AND m.event_name = ?
				  AND EXISTS (SELECT 1 FROM meal_dishes d WHERE d.meal_id = m.id AND d.status <> 'CANCELLED')
				ORDER BY pd.plan_date
				""", LocalDate.class, tenant, READING);
	}

	private List<String> dishStatuses(UUID meal) {
		return admin.queryForList("SELECT status FROM meal_dishes WHERE meal_id = ? ORDER BY created_at",
				String.class, meal);
	}

	private long count(String sql, Object... args) {
		Long n = admin.queryForObject(sql, Long.class, args);
		return n == null ? 0 : n;
	}

	/**
	 * Every table's row count and, for the two that a cancel or a repeat changes in place, a
	 * fingerprint of the columns it would change — read as the superuser so no policy hides a row.
	 */
	private Map<String, Long> rowCounts() {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String table : admin.queryForList("""
				SELECT table_name FROM information_schema.tables
				WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
				ORDER BY table_name
				""", String.class)) {
			counts.put(table, admin.queryForObject("SELECT count(*) FROM \"" + table + "\"", Long.class));
		}
		counts.put("meal_dishes cancelled", count("SELECT count(*) FROM meal_dishes WHERE status = 'CANCELLED'"));
		counts.put("shifts cancelled", count("SELECT count(*) FROM shifts WHERE status = 'CANCELLED'"));
		counts.put("meals in a series", count("SELECT count(*) FROM meals WHERE series_id IS NOT NULL"));
		return counts;
	}

	private UUID tenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);
	}

	private void insertUser(UUID tenantId, String uid, String email, String role, String phone) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, ?, ?, 'ACTIVE')
				""", tenantId, uid, email, phone, role);
	}

	private UUID ingredient(UUID tenantId, String name, boolean forbiddenOnEkadashi) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit, is_ekadashi_prohibited)
				VALUES (?, ?, 'Grains', 'KG', ?) RETURNING id
				""", UUID.class, tenantId, name, forbiddenOnEkadashi);
	}

	private UUID recipe(UUID tenantId, String name, UUID category, UUID ingredient) {
		UUID id = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, ?, ?, 100, 'KG') RETURNING id
				""", UUID.class, tenantId, name, category);
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, 5, 'KG', 0)
				""", tenantId, id, ingredient);
		return id;
	}
}
