package org.iskcon.kms.shift;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.meal.MealKindService;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.iskcon.kms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A shift points at one meal by id (D-27), through the full stack and through the planner's seam.
 *
 * <p>Rajeev: <em>"a shift is unambiguisloy linked to ONE and ONLY one meal."</em> What this class
 * proves, in the order his answers gave it:
 *
 * <ul>
 *   <li>A meal's shift is saved with the meal or not at all (answer 7) — {@link ShiftService#saveForMeal}
 *       in a transaction that rolls back leaves no shift and sends nothing, and the shift it does save
 *       takes the meal's date.
 *   <li>One live shift per meal (answer 2) — two saves racing past each other give {@code KMS-400152},
 *       and a cancelled shift does not stop the meal asking again.
 *   <li>Post a shift makes only shifts not for a meal, and the Volunteer shifts page cannot move a meal
 *       shift to another date or meal or unlink it (answers 3 and 4) — {@code KMS-400153}.
 *   <li>Times changed under signed-up volunteers keep their places and tell them, in the approved
 *       broadcast, <em>"The times changed to &lt;start&gt; to &lt;end&gt;."</em> — on both paths, to the
 *       signed-up only, after commit only, and never with the word "moved" (answer 6).
 *   <li>Cancelling a meal cancels its shift and tells signed-up and waitlisted volunteers (answer 5).
 *   <li>And what D-14 proved about the count still holds, now matched by id.
 * </ul>
 *
 * <p>Meals are written straight into the D-27 tables with SQL, so nothing here depends on how the
 * planner saves one; the planner's own tests prove that. Notifications are real rows — the
 * {@code BroadcastIT} pattern — with Quartz mocked, so a message "sent" is a row a volunteer would be
 * delivered, and a message not sent is the absence of one.
 */
@AutoConfigureMockMvc
class ShiftMealLinkIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** A Monday, and nothing turns on that. */
	private static final String DATE = "2026-09-07";
	private static final String NEXT_DAY = "2026-09-08";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private MealKindService mealKindService;

	@Autowired
	private ShiftService shiftService;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private UserRepository users;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID khichdi;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		insertUser("uid-admin", "TEMPLE_ADMIN", "+919876500001");
		insertUser("uid-vol-1", "VOLUNTEER", "+919876500091");
		insertUser("uid-vol-2", "VOLUNTEER", "+919876500092");
		insertUser("uid-vol-3", "VOLUNTEER", "+919876500093");
		// Consent, so a message is queued rather than suppressed and its text can be read back.
		admin.update("UPDATE users SET contact_consent_at = now() WHERE role = 'VOLUNTEER'");

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
		signIn("uid-admin");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM shift_broadcast_recipients");
		admin.execute("DELETE FROM shift_broadcasts");
		admin.execute("DELETE FROM shift_reminders");
		admin.execute("DELETE FROM shift_waitlist");
		admin.execute("DELETE FROM shift_signups");
		// Shifts before meals: shifts.meal_id is RESTRICT (V136).
		admin.execute("DELETE FROM shifts");
		admin.execute("DELETE FROM meal_card_sequence");
		admin.execute("DELETE FROM meal_dishes");
		admin.execute("DELETE FROM meals");
		admin.execute("DELETE FROM meal_plan_days");
		admin.execute("DELETE FROM meal_kinds");
		// The kitchen a meal names (V150) holds its temple and creator; after the meals.
		admin.execute("DELETE FROM kitchens");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM platform_audit_events");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- Saved with the meal, or not at all (answer 7) --------------------

	@Test
	@DisplayName("a meal's shift saved with the meal is created on the meal's date and reads back with its meal")
	void aCommittedSaveCreatesTheShiftOnTheMealsDate() throws Exception {
		UUID lunch = meal(DATE, "Lunch", null, null, 8);

		UUID id = inTransaction(() -> shiftService.saveForMeal(actor(), lunch, draft("08:00", "11:00", 4)));

		assert shifts() == 1 : "one shift should exist, found " + shifts();
		String stored = admin.queryForObject("SELECT shift_date::text FROM shifts WHERE id = ?", String.class, id);
		assert DATE.equals(stored) : "the shift's date should be the meal's, was " + stored;
		UUID link = admin.queryForObject("SELECT meal_id FROM shifts WHERE id = ?", UUID.class, id);
		assert lunch.equals(link) : "the shift should point at the meal";

		// What the Volunteer shifts list needs for "For Lunch, 7 September", and the warning's count.
		mvc.perform(authed(get("/api/v1/shifts/{id}", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mealId").value(lunch.toString()))
				.andExpect(jsonPath("$.mealKind").value("Lunch"))
				.andExpect(jsonPath("$.mealEventName").doesNotExist())
				.andExpect(jsonPath("$.shiftDate").value(DATE))
				.andExpect(jsonPath("$.capacity").value(4))
				.andExpect(jsonPath("$.signedUpCount").value(0));

		UUID found = asTenant(() -> shiftService.findForMeal(lunch)).map(ShiftView::id).orElse(null);
		assert id.equals(found) : "findForMeal should return the meal's shift";
	}

	@Test
	@DisplayName("a meal save that rolls back leaves no new shift, no changed times, and sends nothing")
	void aRolledBackSaveLeavesNothingAndSendsNothing() {
		// A new shift, abandoned with the meal: Rajeev's orphan.
		UUID lunch = meal(DATE, "Lunch", null, null, 8);
		inTransactionRolledBack(() -> shiftService.saveForMeal(actor(), lunch, draft("08:00", "11:00", 4)));
		assert shifts() == 0 : "a rolled-back save should leave no shift, found " + shifts();

		// An existing shift with somebody on it, whose times are changed and then abandoned. Nothing
		// may reach the volunteer: the save they would be told about never happened.
		UUID dinner = meal(DATE, "Dinner", null, null, 4);
		UUID id = inTransaction(() -> shiftService.saveForMeal(actor(), dinner, draft("16:00", "19:00", 4)));
		signUp(id, "uid-vol-1");

		// Watched on the scheduler as well as in the table. A message queued inside the transaction
		// would leave no notification row once it rolled back, but its send job would already have
		// been handed to Quartz — which is how a volunteer is told about a save that never happened.
		Mockito.clearInvocations(scheduler);
		inTransactionRolledBack(() -> shiftService.saveForMeal(actor(), dinner, draft("15:00", "18:00", 4)));
		Mockito.verifyNoInteractions(scheduler);

		String start = admin.queryForObject("SELECT start_time::text FROM shifts WHERE id = ?", String.class, id);
		assert "16:00:00".equals(start) : "the rolled-back times should not be saved, start is " + start;
		assert broadcastsTo("uid-vol-1") == 0 : "a rolled-back save must send nothing";
		Integer recorded = admin.queryForObject("SELECT count(*) FROM shift_broadcasts", Integer.class);
		assert recorded == 0 : "a rolled-back save must leave no broadcast on the roster";
	}

	@Test
	@DisplayName("the planner's save refuses to run outside a transaction, so it can never commit a shift on its own")
	void saveForMealNeedsTheCallersTransaction() {
		UUID lunch = meal(DATE, "Lunch", null, null, 8);
		AuthenticatedUser actor = actor();
		TenantContext.set(tenant);
		try {
			shiftService.saveForMeal(actor, lunch, draft("08:00", "11:00", 4));
			throw new AssertionError("saveForMeal outside a transaction should be refused");
		} catch (IllegalTransactionStateException expected) {
			// MANDATORY: the orphan shift is impossible by construction, not by convention.
		} finally {
			TenantContext.clear();
		}
		assert shifts() == 0 : "nothing should be written";
	}

	// ---- One live shift per meal (answer 2) ---------------------------------

	@Test
	@DisplayName("saving the meal again changes its one shift rather than adding a second")
	void aSecondSaveUpdatesTheOneShift() {
		UUID lunch = meal(DATE, "Lunch", null, null, 8);
		UUID first = inTransaction(() -> shiftService.saveForMeal(actor(), lunch, draft("08:00", "11:00", 4)));
		UUID second = inTransaction(() -> shiftService.saveForMeal(actor(),
				lunch, new MealShiftDraft("Kitchen help", "Chop and wash", LocalTime.parse("08:00"),
						LocalTime.parse("11:00"), "Temple kitchen", 6, List.of(120))));

		assert first.equals(second) : "the same shift should be returned";
		assert shifts() == 1 : "still one shift, found " + shifts();
		Integer capacity = admin.queryForObject("SELECT capacity FROM shifts WHERE id = ?", Integer.class, first);
		assert capacity == 6 : "the second save's volunteers requested should stand, was " + capacity;
	}

	@Test
	@DisplayName("two people saving the same meal's first shift at once: the second is told KMS-400152, not a failure at our end")
	void twoSavesAtOnceGiveMealAlreadyHasShift() throws Exception {
		UUID lunch = meal(DATE, "Lunch", null, null, 8);
		AuthenticatedUser actor = actor();

		CountDownLatch firstInserted = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		AtomicReference<Throwable> firstFailed = new AtomicReference<>();
		AtomicReference<Throwable> secondFailed = new AtomicReference<>();

		// The first save inserts and holds its transaction open, as a planner mid-save would.
		Thread first = new Thread(() -> {
			TenantContext.set(tenant);
			try {
				new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
					shiftService.saveForMeal(actor, lunch, draft("08:00", "11:00", 4));
					firstInserted.countDown();
					await(releaseFirst);
				});
			} catch (Throwable t) {
				firstFailed.set(t);
				firstInserted.countDown();
			} finally {
				TenantContext.clear();
			}
		});
		first.start();
		assert firstInserted.await(20, TimeUnit.SECONDS) : "the first save never inserted";

		// The second cannot see the first's uncommitted row, so it inserts too — and waits on the
		// unique index until the first commits.
		Thread second = new Thread(() -> {
			TenantContext.set(tenant);
			try {
				new TransactionTemplate(transactionManager).executeWithoutResult(status ->
						shiftService.saveForMeal(actor, lunch, draft("09:00", "12:00", 5)));
			} catch (Throwable t) {
				secondFailed.set(t);
			} finally {
				TenantContext.clear();
			}
		});
		second.start();

		// Released only once the second is provably blocked on that insert. Releasing earlier would let
		// it find the committed shift and update it, which is correct behaviour and not this test.
		long deadline = System.currentTimeMillis() + 20_000;
		while (true) {
			Integer waiting = admin.queryForObject("""
					SELECT count(*) FROM pg_stat_activity
					WHERE wait_event_type = 'Lock' AND query ILIKE '%INSERT INTO shifts%'
					""", Integer.class);
			if (waiting != null && waiting > 0) {
				break;
			}
			assert System.currentTimeMillis() < deadline : "the second save never blocked on the unique index";
			Thread.sleep(50);
		}
		releaseFirst.countDown();
		first.join(20_000);
		second.join(20_000);

		assert firstFailed.get() == null : "the first save should commit: " + firstFailed.get();
		Throwable refused = secondFailed.get();
		assert refused instanceof ApplicationException
				: "the second save should be a named refusal, was " + refused;
		assert ((ApplicationException) refused).errorCode() == ErrorCode.MEAL_ALREADY_HAS_SHIFT
				: "expected KMS-400152, was " + ((ApplicationException) refused).errorCode();
		assert ErrorCode.MEAL_ALREADY_HAS_SHIFT.httpStatus() == 409 : "KMS-400152 should be a 409";
		assert shifts() == 1 : "one shift should exist, found " + shifts();
	}

	@Test
	@DisplayName("a cancelled shift does not stop the meal asking for volunteers again")
	void aCancelledShiftDoesNotBlock() {
		UUID lunch = meal(DATE, "Lunch", null, null, 8);
		UUID first = inTransaction(() -> shiftService.saveForMeal(actor(), lunch, draft("08:00", "11:00", 4)));
		inTransaction(() -> shiftService.cancelForMeal(lunch, "Asked for the wrong hours"));

		assert asTenant(() -> shiftService.findForMeal(lunch)).isEmpty() : "a cancelled shift is not the meal's shift";

		UUID again = inTransaction(() -> shiftService.saveForMeal(actor(), lunch, draft("09:00", "12:00", 4)));
		assert !first.equals(again) : "asking again should raise a new shift, not revive the cancelled one";
		String firstStatus = admin.queryForObject("SELECT status FROM shifts WHERE id = ?", String.class, first);
		assert "CANCELLED".equals(firstStatus) : "the first should stay cancelled, was " + firstStatus;
		UUID found = asTenant(() -> shiftService.findForMeal(lunch)).map(ShiftView::id).orElse(null);
		assert again.equals(found) : "findForMeal should return the new shift";
	}

	@Test
	@DisplayName("the database refuses a shift for a meal that does not exist, whatever route the row comes in by")
	void theForeignKeyIsTheBackstop() {
		try {
			admin.update("""
					INSERT INTO shifts (tenant_id, title, shift_date, start_time, end_time, capacity, created_by, meal_id)
					VALUES (?, 'Lunch prep', ?::date, '06:00', '10:00', 6,
							(SELECT id FROM users WHERE firebase_uid = 'uid-admin'), ?)
					""", tenant, DATE, UUID.randomUUID());
			throw new AssertionError("a shift for no meal at all should be refused by the foreign key");
		} catch (org.springframework.dao.DataIntegrityViolationException expected) {
			assert expected.getMessage().contains("meal_id")
					: "refused by the wrong constraint: " + expected.getMessage();
		}
	}

	// ---- Post a shift, and the Volunteer shifts page (answers 3 and 4) ----------

	@Test
	@DisplayName("Post a shift cannot make a shift for a meal, whatever the request carries")
	void postAShiftMakesOnlyShiftsNotForAMeal() throws Exception {
		UUID lunch = meal(DATE, "Lunch", null, null, 8);

		// Every way a client might try: the D-27 id, and the D-14 text link.
		String id = createId("""
				{"title":"Lunch prep","shiftDate":"%s","startTime":"08:00","endTime":"11:00","capacity":4,
				 "mealId":"%s","mealDate":"%s","mealKind":"Lunch"}
				""".formatted(DATE, lunch, DATE));

		UUID link = admin.queryForObject("SELECT meal_id FROM shifts WHERE id = ?::uuid", UUID.class, id);
		assert link == null : "Post a shift should never link a meal, linked " + link;
		mvc.perform(authed(get("/api/v1/shifts/{id}", id)))
				.andExpect(jsonPath("$.mealId").doesNotExist())
				.andExpect(jsonPath("$.mealKind").doesNotExist());
		assert asTenant(() -> shiftService.findForMeal(lunch)).isEmpty() : "the meal should still have no shift";
	}

	@Test
	@DisplayName("editing a meal shift from the shifts page cannot change its date, change its meal, or unlink it")
	void theShiftsPageCannotMoveAMealShift() throws Exception {
		UUID lunch = meal(DATE, "Lunch", null, null, 8);
		UUID dinner = meal(DATE, "Dinner", null, null, 4);
		UUID id = inTransaction(() -> shiftService.saveForMeal(actor(), lunch, draft("08:00", "11:00", 4)));

		// Another date.
		mvc.perform(edit(id, NEXT_DAY, lunch, "08:00", "11:00"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400153"));
		// Another meal on the same date.
		mvc.perform(edit(id, DATE, dinner, "08:00", "11:00"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400153"));
		// No meal at all: that would make it a shift not for a meal.
		mvc.perform(edit(id, DATE, null, "08:00", "11:00"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400153"));

		// Refused whole, not half-applied: the title in those requests was never saved.
		String title = admin.queryForObject("SELECT title FROM shifts WHERE id = ?", String.class, id);
		assert "Kitchen help for lunch".equals(title) : "a refused edit should change nothing, title is " + title;

		// Everything that may change, changes, and saves at once.
		mvc.perform(authed(put("/api/v1/shifts/{id}", id)).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title":"Lunch seva","description":"Wash and chop","shiftDate":"%s","startTime":"08:00",
								 "endTime":"11:00","location":"Back kitchen","capacity":7,"reminderOffsetsMinutes":[60],
								 "mealId":"%s"}
								""".formatted(DATE, lunch)))
				.andExpect(status().isNoContent());
		mvc.perform(authed(get("/api/v1/shifts/{id}", id)))
				.andExpect(jsonPath("$.title").value("Lunch seva"))
				.andExpect(jsonPath("$.description").value("Wash and chop"))
				.andExpect(jsonPath("$.location").value("Back kitchen"))
				.andExpect(jsonPath("$.capacity").value(7))
				.andExpect(jsonPath("$.reminderOffsetsMinutes[0]").value(60))
				.andExpect(jsonPath("$.shiftDate").value(DATE))
				.andExpect(jsonPath("$.mealId").value(lunch.toString()));
	}

	@Test
	@DisplayName("a shift not for a meal cannot be linked to one from the shifts page")
	void aPlainShiftCannotBeLinkedFromTheShiftsPage() throws Exception {
		UUID lunch = meal(DATE, "Lunch", null, null, 8);
		String id = createId("""
				{"title":"Garland making","shiftDate":"%s","startTime":"06:00","endTime":"09:00","capacity":6}
				""".formatted(DATE));

		mvc.perform(edit(UUID.fromString(id), DATE, lunch, "06:00", "09:00"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));
		UUID link = admin.queryForObject("SELECT meal_id FROM shifts WHERE id = ?::uuid", UUID.class, id);
		assert link == null : "the shift should still not be for a meal";
	}

	// ---- Times changed under signed-up volunteers (answer 6) -------------------

	@Test
	@DisplayName("times changed from the shifts page keep every place and tell the signed-up, not the waitlist, that the times changed")
	void timesChangedFromTheShiftsPageTellTheSignedUp() throws Exception {
		UUID lunch = meal(DATE, "Lunch", null, null, 8);
		UUID id = inTransaction(() -> shiftService.saveForMeal(actor(), lunch, draft("08:00", "11:00", 2)));
		signUp(id, "uid-vol-1");
		signUp(id, "uid-vol-2");
		waitlist(id, "uid-vol-3");

		mvc.perform(edit(id, DATE, lunch, "09:00", "13:00")).andExpect(status().isNoContent());

		assertPlacesKeptAndToldOnce(id, "The times changed to 09:00 to 13:00.");

		// And on the roster beside the coordinator's own broadcasts, with who it went to.
		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", id)))
				.andExpect(jsonPath("$.broadcasts.length()").value(1))
				.andExpect(jsonPath("$.broadcasts[0].message").value("The times changed to 09:00 to 13:00."))
				.andExpect(jsonPath("$.broadcasts[0].recipients.length()").value(2));
	}

	@Test
	@DisplayName("times changed from the planner keep every place and tell the signed-up once the meal is saved")
	void timesChangedFromThePlannerTellTheSignedUp() {
		UUID lunch = meal(DATE, "Lunch", null, null, 8);
		UUID id = inTransaction(() -> shiftService.saveForMeal(actor(), lunch, draft("08:00", "11:00", 2)));
		signUp(id, "uid-vol-1");
		signUp(id, "uid-vol-2");
		waitlist(id, "uid-vol-3");

		inTransaction(() -> shiftService.saveForMeal(actor(), lunch, draft("07:30", "10:30", 2)));

		assertPlacesKeptAndToldOnce(id, "The times changed to 07:30 to 10:30.");
	}

	@Test
	@DisplayName("a shift not for a meal whose times change on the same day is told the same way")
	void timesChangedOnAPlainShiftTellTheSignedUpToo() throws Exception {
		String id = createId("""
				{"title":"Garland making","shiftDate":"%s","startTime":"06:00","endTime":"09:00","capacity":2}
				""".formatted(DATE));
		signUp(UUID.fromString(id), "uid-vol-1");
		signUp(UUID.fromString(id), "uid-vol-2");
		waitlist(UUID.fromString(id), "uid-vol-3");

		mvc.perform(edit(UUID.fromString(id), DATE, null, "05:30", "08:30")).andExpect(status().isNoContent());

		assertPlacesKeptAndToldOnce(UUID.fromString(id), "The times changed to 05:30 to 08:30.");
	}

	@Test
	@DisplayName("a shift not for a meal moved to another day with new times tells nobody, keeps its places, and moves its reminders")
	void aPlainShiftMovedToAnotherDayTellsNobody() throws Exception {
		// In the future, so the reminder's fire time has not passed and is actually scheduled.
		String id = createId("""
				{"title":"Garland making","shiftDate":"2026-12-01","startTime":"06:00","endTime":"09:00","capacity":2}
				""");
		UUID shift = UUID.fromString(id);
		signUp(shift, "uid-vol-1");
		signUp(shift, "uid-vol-2");

		// Date and times both change. "The times changed to 07:00 to 10:00." would send them to the
		// old day at new hours, so nothing is sent — as before D-27 for a change of date.
		Mockito.clearInvocations(scheduler);
		mvc.perform(edit(shift, "2026-12-02", null, "07:00", "10:00")).andExpect(status().isNoContent());

		Integer places = admin.queryForObject(
				"SELECT count(*) FROM shift_signups WHERE shift_id = ? AND released_at IS NULL", Integer.class, shift);
		assert places == 2 : "both places should be kept, " + places + " remain";
		assert broadcastsTo("uid-vol-1") == 0 : "a change of date must not be announced as a change of times";
		assert broadcastsTo("uid-vol-2") == 0 : "a change of date must not be announced as a change of times";
		Integer recorded = admin.queryForObject("SELECT count(*) FROM shift_broadcasts", Integer.class);
		assert recorded == 0 : "no broadcast should be recorded on the roster, found " + recorded;

		// The reminders moved: the default 24-hour reminder for each signup now fires 07:00 on
		// 1 December in the temple's zone — 24 hours before the new start — and none at the old time.
		ArgumentCaptor<org.quartz.Trigger> triggers = ArgumentCaptor.forClass(org.quartz.Trigger.class);
		Mockito.verify(scheduler, Mockito.atLeastOnce())
				.scheduleJob(ArgumentMatchers.any(org.quartz.JobDetail.class), triggers.capture());
		java.util.Date expected = java.util.Date.from(
				java.time.ZonedDateTime.parse("2026-12-01T07:00:00+05:30[Asia/Kolkata]").toInstant());
		java.util.Date old = java.util.Date.from(
				java.time.ZonedDateTime.parse("2026-11-30T06:00:00+05:30[Asia/Kolkata]").toInstant());
		List<java.util.Date> fireTimes = triggers.getAllValues().stream().map(org.quartz.Trigger::getStartTime).toList();
		assert fireTimes.stream().filter(expected::equals).count() == 2
				: "each signup's reminder should fire 24h before the new start, fire times were " + fireTimes;
		assert fireTimes.stream().noneMatch(old::equals) : "no reminder should keep the old time: " + fireTimes;
	}

	@Test
	@DisplayName("an edit that leaves the times alone tells nobody anything")
	void unchangedTimesTellNobody() throws Exception {
		UUID lunch = meal(DATE, "Lunch", null, null, 8);
		UUID id = inTransaction(() -> shiftService.saveForMeal(actor(), lunch, draft("08:00", "11:00", 2)));
		signUp(id, "uid-vol-1");

		mvc.perform(authed(put("/api/v1/shifts/{id}", id)).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title":"Renamed","shiftDate":"%s","startTime":"08:00","endTime":"11:00","capacity":3,
								 "mealId":"%s"}
								""".formatted(DATE, lunch)))
				.andExpect(status().isNoContent());
		inTransaction(() -> shiftService.saveForMeal(actor(), lunch, draft("08:00", "11:00", 5)));

		assert broadcastsTo("uid-vol-1") == 0 : "nothing changed that a volunteer needs telling";
	}

	// ---- A meal cancelled with its shift (answer 5) --------------------------

	@Test
	@DisplayName("cancelling a meal's shift tells signed-up and waitlisted volunteers after commit, and says how many")
	void cancellingTellsSignedUpAndWaitlisted() {
		UUID lunch = meal(DATE, "Lunch", null, null, 8);
		UUID id = inTransaction(() -> shiftService.saveForMeal(actor(), lunch, draft("08:00", "11:00", 1)));
		signUp(id, "uid-vol-1");
		waitlist(id, "uid-vol-2");

		// Abandoned first: the count is given, but nothing is cancelled and nobody is told — not in the
		// table, and not on the scheduler, where a send queued before the commit would already be.
		Mockito.clearInvocations(scheduler);
		Integer counted = inTransactionRolledBack(() -> shiftService.cancelForMeal(lunch, "Lunch called off"));
		Mockito.verifyNoInteractions(scheduler);
		assert counted == 2 : "the count should be signed-up plus waitlisted, was " + counted;
		assert cancellationsTo("uid-vol-1") == 0 : "a rolled-back cancel must send nothing";
		String open = admin.queryForObject("SELECT status FROM shifts WHERE id = ?", String.class, id);
		assert "OPEN".equals(open) : "a rolled-back cancel should leave the shift open";

		int told = inTransaction(() -> shiftService.cancelForMeal(lunch, "Lunch called off"));
		assert told == 2 : "two volunteers should be told, was " + told;
		assert cancellationsTo("uid-vol-1") == 1 : "the signed-up volunteer should be told once";
		assert cancellationsTo("uid-vol-2") == 1 : "the waitlisted volunteer should be told once";
		String cancelled = admin.queryForObject("SELECT status FROM shifts WHERE id = ?", String.class, id);
		assert "CANCELLED".equals(cancelled) : "the shift should be cancelled";

		// A meal with no live shift: nothing to cancel, nobody to tell.
		UUID dinner = meal(DATE, "Dinner", null, null, 4);
		int none = inTransaction(() -> shiftService.cancelForMeal(dinner, "Dinner called off"));
		assert none == 0 : "no shift means nobody told, was " + none;
	}

	// ---- What the link changes about the count (D-14, by id since D-27) ----------

	@Test
	@DisplayName("hands asked for from lunch count toward lunch, and stop inflating breakfast")
	void aLunchShiftStopsInflatingBreakfast() throws Exception {
		meal(DATE, "Breakfast", null, null, 4);
		UUID lunch = meal(DATE, "Lunch", null, null, 8);

		// Posted 06:00–10:00 to cut vegetables for lunch, as a shift not for a meal. Breakfast is due
		// at 07:30, inside those hours, so the clock hands breakfast a volunteer who will be chopping
		// for lunch — and hands lunch nobody.
		String plain = createId("""
				{"title":"Cut vegetables for lunch","shiftDate":"%s","startTime":"06:00","endTime":"10:00",
				 "capacity":6}
				""".formatted(DATE));
		signUp(UUID.fromString(plain), "uid-vol-1");
		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$[0].mealKind").value("Breakfast"))
				.andExpect(jsonPath("$[0].volunteers").value(1))
				.andExpect(jsonPath("$[1].mealKind").value("Lunch"))
				.andExpect(jsonPath("$[1].volunteers").value(0));

		// Since D-27 that shift is not converted: it is cancelled, and lunch asks for its own.
		mvc.perform(authed(post("/api/v1/shifts/{id}/cancel", plain)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"Asking from lunch instead\"}")).andExpect(status().isNoContent());
		UUID forLunch = inTransaction(() -> shiftService.saveForMeal(actor(), lunch, draft("06:00", "10:00", 6)));
		signUp(forLunch, "uid-vol-1");

		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$[0].mealKind").value("Breakfast"))
				.andExpect(jsonPath("$[0].volunteers").value(0))
				.andExpect(jsonPath("$[0].shortOfCrew").value(true))
				.andExpect(jsonPath("$[1].mealKind").value("Lunch"))
				.andExpect(jsonPath("$[1].mealId").value(lunch.toString()))
				.andExpect(jsonPath("$[1].volunteers").value(1))
				.andExpect(jsonPath("$[1].rostered").value(1));
	}

	@Test
	@DisplayName("a shift not for a meal still counts toward every meal its hours span")
	void aPlainShiftStillCountsByTheClock() throws Exception {
		meal(DATE, "Breakfast", null, null, 4);
		meal(DATE, "Lunch", null, null, 8);
		meal(DATE, "Dinner", null, null, 4);

		String id = createId("""
				{"title":"Festival, all day","shiftDate":"%s","startTime":"06:00","endTime":"22:00",
				 "capacity":20}
				""".formatted(DATE));
		signUp(UUID.fromString(id), "uid-vol-1");

		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$[0].volunteers").value(1))
				.andExpect(jsonPath("$[1].volunteers").value(1))
				.andExpect(jsonPath("$[2].volunteers").value(1));
	}

	@Test
	@DisplayName("a meal's shift counts toward its meal even where its hours cover no meal at all")
	void aMealShiftIgnoresTheClock() throws Exception {
		UUID lunch = meal(DATE, "Lunch", null, null, 8);
		// 14:00–16:00: after lunch is ready and long before dinner. The clock places it nowhere.
		UUID id = inTransaction(() -> shiftService.saveForMeal(actor(), lunch, draft("14:00", "16:00", 6)));
		signUp(id, "uid-vol-1");

		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$[0].mealKind").value("Lunch"))
				.andExpect(jsonPath("$[0].volunteers").value(1));
	}

	@Test
	@DisplayName("two events on one day are two meals, and a shift reaches only the one it is for")
	void oneEventsHandsAreNotTheOthers() throws Exception {
		UUID feast = meal(DATE, "Event", "Janmashtami Feast", "18:00", 20);
		meal(DATE, "Event", "Bhajan Prasadam", "20:00", 6);

		// Runs until 22:00, so by the clock it covers both events. It is for one of them.
		UUID id = inTransaction(() -> shiftService.saveForMeal(actor(), feast, draft("06:00", "22:00", 10)));
		signUp(id, "uid-vol-1");

		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].mealId").value(feast.toString()))
				.andExpect(jsonPath("$[0].volunteers").value(1))
				.andExpect(jsonPath("$[1].volunteers").value(0));
		mvc.perform(authed(get("/api/v1/shifts/{id}", id)))
				.andExpect(jsonPath("$.mealKind").value("Event"))
				.andExpect(jsonPath("$.mealEventName").value("Janmashtami Feast"));
	}

	@Test
	@DisplayName("renaming a meal kind renames the shift's label and loses none of its count — nothing matches on text")
	void aRenamedKindKeepsItsShift() throws Exception {
		// The trap D-14 lived with: a link stored as the kind's name matched nothing once the kind was
		// renamed, and read as a shift nobody signed up for. By id there is nothing to strand.
		UUID lunch = meal(DATE, "Lunch", null, null, 8);
		UUID id = inTransaction(() -> shiftService.saveForMeal(actor(), lunch, draft("08:00", "11:00", 6)));
		signUp(id, "uid-vol-1");

		admin.update("UPDATE meal_kinds SET name = 'Madhyahna Prasadam' WHERE tenant_id = ? AND name = 'Lunch'", tenant);

		mvc.perform(authed(get("/api/v1/shifts/{id}", id)))
				.andExpect(jsonPath("$.mealKind").value("Madhyahna Prasadam"));
		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$[0].mealId").value(lunch.toString()))
				.andExpect(jsonPath("$[0].volunteers").value(1));
	}

	// ---- helpers ----------------------------------------------------------

	/**
	 * Both signed-up volunteers still hold their places, each got exactly one {@code shift_broadcast}
	 * with exactly {@code expected} as the coordinator text, the waitlisted volunteer got none, and the
	 * word "moved" appears nowhere in what was sent.
	 */
	private void assertPlacesKeptAndToldOnce(UUID shiftId, String expected) {
		Integer places = admin.queryForObject(
				"SELECT count(*) FROM shift_signups WHERE shift_id = ? AND released_at IS NULL", Integer.class, shiftId);
		assert places == 2 : "both places should be kept, " + places + " remain";

		for (String uid : new String[] {"uid-vol-1", "uid-vol-2"}) {
			assert broadcastsTo(uid) == 1 : uid + " should be told once, was told " + broadcastsTo(uid);
			String message = admin.queryForObject("""
					SELECT n.params->>'message' FROM notifications n JOIN users u ON u.id = n.recipient_user_id
					WHERE u.firebase_uid = ? AND n.template = 'SHIFT_BROADCAST'
					""", String.class, uid);
			assert expected.equals(message) : "the coordinator text should be exactly \"" + expected + "\", was \"" + message + "\"";
			assert !message.toLowerCase().contains("moved") : "the notice must never say \"moved\": " + message;
		}
		assert broadcastsTo("uid-vol-3") == 0 : "the waitlist holds no place on these hours and is not told";
	}

	private MockHttpServletRequestBuilder edit(UUID id, String date, UUID mealId, String start, String end) {
		return authed(put("/api/v1/shifts/{id}", id)).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"title":"Edited title","shiftDate":"%s","startTime":"%s","endTime":"%s","capacity":2,
						 "mealId":%s}
						""".formatted(date, start, end, mealId == null ? "null" : "\"" + mealId + "\""));
	}

	private static MealShiftDraft draft(String start, String end, int capacity) {
		return new MealShiftDraft("Kitchen help for lunch", null, LocalTime.parse(start), LocalTime.parse(end),
				"Temple kitchen", capacity, null);
	}

	/** A meal written straight into the D-27 tables: its day, the meal with its kind by id, one dish. */
	private UUID meal(String date, String kind, String eventName, String readyBy, Integer crew) {
		UUID day = admin.queryForObject("""
				INSERT INTO meal_plan_days (tenant_id, plan_date, day_type) VALUES (?, ?::date, 'REGULAR')
				ON CONFLICT (tenant_id, plan_date) DO UPDATE SET updated_at = now()
				RETURNING id
				""", UUID.class, tenant, date);
		UUID kindId = admin.queryForObject(
				"SELECT id FROM meal_kinds WHERE tenant_id = ? AND lower(name) = lower(?)", UUID.class, tenant, kind);
		UUID meal = admin.queryForObject("""
				INSERT INTO meals (tenant_id, meal_plan_day_id, meal_kind_id, event_name, ready_by, adults)
				VALUES (?, ?, ?, ?, COALESCE(?::time, (SELECT default_ready_time FROM meal_kinds WHERE id = ?)),
						200)
				RETURNING id
				""", UUID.class, tenant, day, kindId, eventName, readyBy, kindId);
		// The meal's one kitchen (V150), carrying its People needed: the meal's own column is gone (V151).
		UUID kitchen = org.iskcon.kms.meal.MealFixture.section(admin, tenant, meal, null);
		admin.update("UPDATE meal_kitchens SET crew_required = ? WHERE meal_id = ?", crew, meal);
		admin.update("""
				INSERT INTO meal_dishes (tenant_id, meal_id, recipe_id, target_yield, status, created_by, kitchen_id)
				VALUES (?, ?, ?, 200, 'PLANNED', (SELECT id FROM users WHERE firebase_uid = 'uid-admin'), ?)
				""", tenant, meal, khichdi, kitchen);
		return meal;
	}

	/**
	 * The Temple Admin as the service sees them. Restores whatever temple context was set before, rather
	 * than clearing it: called inside a transaction, a cleared context would leave the after-commit
	 * work — which takes a fresh connection — with no temple, where row-level security hides every row.
	 * A real request keeps its context for its whole length, and so must this.
	 */
	private AuthenticatedUser actor() {
		UUID previous = TenantContext.get().orElse(null);
		TenantContext.set(tenant);
		try {
			return new AuthenticatedUser(users.findAllByFirebaseUid("uid-admin").stream()
					.filter(account -> tenant.equals(account.getTenantId())).findFirst().orElseThrow());
		} finally {
			if (previous != null) {
				TenantContext.set(previous);
			} else {
				TenantContext.clear();
			}
		}
	}

	/** Runs {@code work} as the temple would, in a transaction that commits — the planner's save. */
	private <T> T inTransaction(Supplier<T> work) {
		TenantContext.set(tenant);
		try {
			return new TransactionTemplate(transactionManager).execute(status -> work.get());
		} finally {
			TenantContext.clear();
		}
	}

	/** The same, abandoned: the planner's user leaving without saving, or a save failing after this call. */
	private <T> T inTransactionRolledBack(Supplier<T> work) {
		TenantContext.set(tenant);
		try {
			return new TransactionTemplate(transactionManager).execute(status -> {
				T result = work.get();
				status.setRollbackOnly();
				return result;
			});
		} finally {
			TenantContext.clear();
		}
	}

	private <T> T asTenant(Supplier<T> work) {
		TenantContext.set(tenant);
		try {
			return work.get();
		} finally {
			TenantContext.clear();
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(20, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private int shifts() {
		Integer n = admin.queryForObject("SELECT count(*) FROM shifts", Integer.class);
		return n == null ? 0 : n;
	}

	private int broadcastsTo(String uid) {
		return notificationsTo(uid, "SHIFT_BROADCAST");
	}

	private int cancellationsTo(String uid) {
		return notificationsTo(uid, "SHIFT_CANCELLED");
	}

	private int notificationsTo(String uid, String template) {
		Integer n = admin.queryForObject("""
				SELECT count(*) FROM notifications n JOIN users u ON u.id = n.recipient_user_id
				WHERE u.firebase_uid = ? AND n.template = ?
				""", Integer.class, uid, template);
		return n == null ? 0 : n;
	}

	private void signUp(UUID shiftId, String volunteerUid) {
		admin.update("""
				INSERT INTO shift_signups (tenant_id, shift_id, volunteer_user_id)
				VALUES (?, ?, (SELECT id FROM users WHERE firebase_uid = ?))
				""", tenant, shiftId, volunteerUid);
	}

	private void waitlist(UUID shiftId, String volunteerUid) {
		admin.update("""
				INSERT INTO shift_waitlist (tenant_id, shift_id, volunteer_user_id)
				VALUES (?, ?, (SELECT id FROM users WHERE firebase_uid = ?))
				""", tenant, shiftId, volunteerUid);
	}

	private MockHttpServletRequestBuilder create(String json) {
		return authed(post("/api/v1/shifts")).contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private String createId(String json) throws Exception {
		String body = mvc.perform(create(json)).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(body).get("id").asText();
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private void insertUser(String uid, String role, String phone) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, ?, ?, 'ACTIVE')
				""", tenant, uid, uid + "@example.com", phone, role);
	}

}
