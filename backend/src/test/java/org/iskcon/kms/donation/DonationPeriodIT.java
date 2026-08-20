package org.iskcon.kms.donation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Periods and the year-on-year comparison on the donations ledger (§8, 2026-08-20 brief).
 *
 * <p>Every date here is worked out from the temple's own today rather than written down, because a
 * test with 2026 in it starts failing on 1 April and the failure looks like a bug in the comparison
 * rather than in the test. The windows themselves are asserted by seeding a gift on the day the
 * arithmetic is supposed to reach and a second one the day outside it.
 */
@AutoConfigureMockMvc
@Import(DonationPeriodIT.StubVerifierConfiguration.class)
class DonationPeriodIT extends AbstractIntegrationTest {

	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private LocalDate today;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		today = LocalDate.now(TEMPLE_ZONE);
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-admin', 'Admin', 'admin@example.com', '+919876500001', 'TEMPLE_ADMIN', 'ACTIVE')
				""", tenant);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-cook', 'Cook', 'cook@example.com', '+919876500002', 'KITCHEN_STAFF', 'ACTIVE')
				""", tenant);
		signIn("uid-admin");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM donations");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- The four windows ------------------------------------------------

	@Test
	@DisplayName("this week reaches back to Monday and no further")
	void weekStartsOnMonday() throws Exception {
		LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		cashOn("400", monday);
		cashOn("900", monday.minusDays(1)); // the Sunday that closed the week before

		mvc.perform(authed(get(PERIOD).param("period", "WEEK")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.window.from").value(monday.toString()))
				.andExpect(jsonPath("$.window.to").value(today.toString()))
				.andExpect(jsonPath("$.byCategory.MANUAL.total").value(400));
	}

	@Test
	@DisplayName("this month runs from the first, and excludes the last day of the month before")
	void monthStartsOnTheFirst() throws Exception {
		LocalDate first = today.withDayOfMonth(1);
		cashOn("400", first);
		cashOn("900", first.minusDays(1));

		mvc.perform(authed(get(PERIOD).param("period", "MONTH")))
				.andExpect(jsonPath("$.window.from").value(first.toString()))
				.andExpect(jsonPath("$.byCategory.MANUAL.total").value(400));
	}

	@Test
	@DisplayName("this financial year runs April to today, and drops the 31 March before it")
	void financialYearRunsAprilToToday() throws Exception {
		LocalDate fyStart = financialYearStart(today);
		cashOn("400", fyStart);
		cashOn("900", fyStart.minusDays(1)); // 31 March, the last day of the previous FY

		mvc.perform(authed(get(PERIOD).param("period", "FINANCIAL_YEAR")))
				.andExpect(jsonPath("$.window.from").value(fyStart.toString()))
				.andExpect(jsonPath("$.window.to").value(today.toString()))
				.andExpect(jsonPath("$.window.financialYear").value(fyStart.getYear()))
				.andExpect(jsonPath("$.byCategory.MANUAL.total").value(400));
	}

	@Test
	@DisplayName("a closed financial year runs its own April to its own 31 March, whole")
	void aChosenYearIsTheWholeOfThatYear() throws Exception {
		int year = financialYearStart(today).getYear() - 1;
		LocalDate start = LocalDate.of(year, 4, 1);
		LocalDate end = start.plusYears(1).minusDays(1);
		cashOn("400", start);
		cashOn("600", end);
		cashOn("900", end.plusDays(1)); // the first day of the year that followed

		mvc.perform(authed(get(PERIOD).param("period", "YEAR").param("financialYear", String.valueOf(year))))
				.andExpect(jsonPath("$.window.from").value(start.toString()))
				.andExpect(jsonPath("$.window.to").value(end.toString()))
				.andExpect(jsonPath("$.byCategory.MANUAL.total").value(1000));
	}

	@Test
	@DisplayName("the picker offers every financial year from the temple's first gift to this one")
	void yearsOfferedRunFromTheFirstGift() throws Exception {
		int thisYear = financialYearStart(today).getYear();
		cashOn("400", LocalDate.of(thisYear - 2, 6, 1));

		mvc.perform(authed(get(PERIOD).param("period", "MONTH")))
				.andExpect(jsonPath("$.financialYearsWithGifts.length()").value(3))
				.andExpect(jsonPath("$.financialYearsWithGifts[0]").value(thisYear))
				.andExpect(jsonPath("$.financialYearsWithGifts[2]").value(thisYear - 2));
	}

	// ---- Given, not recorded ---------------------------------------------

	/**
	 * The point of counting by {@code donated_on}. The gift is typed in today and dated to a year the
	 * temple has already closed, which is exactly what happens when the office finds an unrecorded
	 * receipt — and it must land in the year the money was given, not the one somebody entered it in.
	 *
	 * <p>Deliberately a closed financial year rather than "last month", so the test says the same
	 * thing on every day of the calendar: last month falls into the previous financial year every
	 * April, and a test that only holds for eleven months of the year is worse than no test. Two
	 * years back rather than one, so the gift is outside this year's window and outside the window
	 * this year is compared against, whatever today's date happens to be.
	 */
	@Test
	@DisplayName("a gift given in a closed year but recorded today counts against that year")
	void countedByTheDayItWasGiven() throws Exception {
		int closedYear = financialYearStart(today).getYear() - 2;
		cashOn("2500", LocalDate.of(closedYear, 6, 15));

		// created_at defaults to now; only donated_on says when the gift was actually given.
		Integer recordedToday = admin.queryForObject(
				"SELECT COUNT(*) FROM donations WHERE created_at::date = CURRENT_DATE", Integer.class);
		assertThat(recordedToday).isEqualTo(1);

		mvc.perform(authed(get(PERIOD).param("period", "FINANCIAL_YEAR")))
				.andExpect(jsonPath("$.byCategory.MANUAL").doesNotExist());

		mvc.perform(authed(get(PERIOD).param("period", "YEAR")
						.param("financialYear", String.valueOf(closedYear))))
				.andExpect(jsonPath("$.byCategory.MANUAL.total").value(2500));
	}

	// ---- Same point to same point ----------------------------------------

	/**
	 * The whole value of the feature. The prior gift is seeded at the same number of days into last
	 * financial year as today is into this one, so it falls inside the compared window; a second gift
	 * one day later falls outside it and must not be counted, which is what stops a part-year being
	 * measured against a whole one.
	 */
	@Test
	@DisplayName("the financial year is compared with the same point last year, not the whole of it")
	void comparisonIsSamePointToSamePoint() throws Exception {
		LocalDate fyStart = financialYearStart(today);
		long elapsed = ChronoUnit.DAYS.between(fyStart, today);
		LocalDate priorStart = fyStart.minusYears(1);
		LocalDate samePointLastYear = priorStart.plusDays(elapsed);

		cashOn("1180", today);              // this year, to date
		cashOn("1000", samePointLastYear);  // last year, to the same point
		// Last year, but one day past the point this year has reached: it must not be counted, which
		// is the difference between a same-point comparison and a part-year against a whole one. On
		// 31 March that day is 1 April and belongs to this year instead, so it is not seeded then.
		LocalDate pastThePoint = samePointLastYear.plusDays(1);
		if (pastThePoint.isBefore(fyStart)) {
			cashOn("9999", pastThePoint);
		}

		mvc.perform(authed(get(PERIOD).param("period", "FINANCIAL_YEAR")))
				.andExpect(jsonPath("$.window.previousFrom").value(priorStart.toString()))
				.andExpect(jsonPath("$.window.previousTo").value(samePointLastYear.toString()))
				.andExpect(jsonPath("$.hasPriorYear").value(true))
				.andExpect(jsonPath("$.byCategory.MANUAL.total").value(1180))
				.andExpect(jsonPath("$.byCategory.MANUAL.previousTotal").value(1000))
				.andExpect(jsonPath("$.byCategory.MANUAL.changePercent").value(18));
	}

	@Test
	@DisplayName("the week is compared with the week 52 weeks back, so Monday meets Monday")
	void theWeekKeepsItsWeekdays() throws Exception {
		LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		LocalDate priorMonday = monday.minusWeeks(52);

		mvc.perform(authed(get(PERIOD).param("period", "WEEK")))
				.andExpect(jsonPath("$.window.previousFrom").value(priorMonday.toString()));
		assertThat(priorMonday.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
	}

	/**
	 * Nothing last year is not a fall, and it is not an increase of infinity either. The tile has to
	 * be handed a null and say "nothing at this point last year" in words.
	 */
	@Test
	@DisplayName("a prior window of nothing yields no percentage rather than a division by zero")
	void aZeroPriorWindowYieldsNoPercentage() throws Exception {
		LocalDate fyStart = financialYearStart(today);
		cashOn("5000", today);
		// A gift two financial years back, so the temple demonstrably has records reaching past the
		// compared window — the prior window is genuinely empty rather than merely unrecorded.
		cashOn("100", fyStart.minusYears(2));

		mvc.perform(authed(get(PERIOD).param("period", "FINANCIAL_YEAR")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.hasPriorYear").value(true))
				.andExpect(jsonPath("$.byCategory.MANUAL.previousTotal").value(0))
				.andExpect(jsonPath("$.byCategory.MANUAL.changePercent").doesNotExist());
	}

	@Test
	@DisplayName("a temple in its first year is told there is nothing to compare with, not that it fell")
	void aFirstYearTempleHasNoPriorWindow() throws Exception {
		cashOn("5000", today);

		mvc.perform(authed(get(PERIOD).param("period", "FINANCIAL_YEAR")))
				.andExpect(jsonPath("$.hasPriorYear").value(false))
				.andExpect(jsonPath("$.byCategory.MANUAL.changePercent").doesNotExist());
	}

	@Test
	@DisplayName("a category that stopped still shows its fall rather than dropping off the screen")
	void aCategoryThatStoppedStillAppears() throws Exception {
		LocalDate fyStart = financialYearStart(today);
		long elapsed = ChronoUnit.DAYS.between(fyStart, today);
		cashOn("1000", fyStart.minusYears(1).plusDays(elapsed));
		inKindOn("300", today);

		mvc.perform(authed(get(PERIOD).param("period", "FINANCIAL_YEAR")))
				.andExpect(jsonPath("$.byCategory.MANUAL.total").value(0))
				.andExpect(jsonPath("$.byCategory.MANUAL.changePercent").value(-100))
				.andExpect(jsonPath("$.byCategory.IN_KIND.total").value(300));
	}

	// ---- The export follows the period -----------------------------------

	@Test
	@DisplayName("the CSV covers exactly the period on screen, so the accountant's file matches it")
	void exportHonoursThePeriod() throws Exception {
		LocalDate fyStart = financialYearStart(today);
		cashOn("400", fyStart);
		cashOn("900", fyStart.minusDays(1));

		String csv = mvc.perform(authed(get("/api/v1/donations/ledger/export")
						.param("from", fyStart.toString())
						.param("to", today.toString())))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(csv).contains("400");
		assertThat(csv).doesNotContain("900");
	}

	// ---- Permission ------------------------------------------------------

	@Test
	@DisplayName("kitchen staff cannot read the periods either — they do not hold VIEW_DONATIONS")
	void kitchenStaffAreRefused() throws Exception {
		signIn("uid-cook");
		mvc.perform(authed(get(PERIOD).param("period", "MONTH"))).andExpect(status().isForbidden());
		mvc.perform(authed(get("/api/v1/donations/ledger"))).andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("a financial year that has not begun is refused rather than shown as zeroes")
	void aFutureYearIsRefused() throws Exception {
		int next = financialYearStart(today).getYear() + 1;
		mvc.perform(authed(get(PERIOD).param("period", "YEAR").param("financialYear", String.valueOf(next))))
				.andExpect(status().isBadRequest());
	}

	// ---------------------------------------------------------------------

	private static final String PERIOD = "/api/v1/donations/ledger/period-summary";

	private static LocalDate financialYearStart(LocalDate date) {
		return date.getMonthValue() >= 4
				? LocalDate.of(date.getYear(), 4, 1) : LocalDate.of(date.getYear() - 1, 4, 1);
	}

	/** Cash over the counter: money with no provider, which the ledger labels MANUAL. */
	private void cashOn(String amount, LocalDate donatedOn) {
		admin.update("""
				INSERT INTO donations (tenant_id, type, amount_inr, status, is_anonymous, donor_name,
					payment_mode, donated_on)
				VALUES (?, 'ONE_TIME', ?::numeric, 'COMPLETED', false, 'Walk-in Devotee', 'CASH', ?::date)
				""", tenant, amount, donatedOn);
	}

	private void inKindOn(String value, LocalDate donatedOn) {
		admin.update("""
				INSERT INTO donations (tenant_id, type, estimated_value_inr, status, is_anonymous, donor_name, donated_on)
				VALUES (?, 'IN_KIND', ?::numeric, 'COMPLETED', false, 'Vegetable seller', ?::date)
				""", tenant, value, donatedOn);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

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
