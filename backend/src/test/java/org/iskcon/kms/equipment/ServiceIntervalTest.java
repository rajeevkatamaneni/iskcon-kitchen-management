package org.iskcon.kms.equipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The interval arithmetic, and the derivation that reads it (E3-S10 D3, D4, D5, D6).
 *
 * <p>Plain unit tests with no database anywhere near them, because none of this is a database
 * question: a month is thirty days by decision, and where a machine stands against its next service
 * is arithmetic over four values. {@code EquipmentServicingIT} proves the same rules survive the
 * round trip through SQL and HTTP; this proves the rules themselves, including the cases that are
 * awkward to set up over the wire.
 */
class ServiceIntervalTest {

	@Nested
	@DisplayName("an interval is a number and a unit, held in days")
	class Arithmetic {

		@ParameterizedTest(name = "every {0} {1} is {2} days")
		@CsvSource({
			// The story's two named cases, which are the whole reason the unit exists: a
			// months-only field would force "every ninety days" into a lie.
			"6, MONTHS, 180",
			"90, DAYS, 90",
			// And the rest of the vocabulary.
			"1, DAYS, 1",
			"2, WEEKS, 14",
			"1, MONTHS, 30",
			"1, YEARS, 365",
			"3, YEARS, 1095",
		})
		void countConvertsToDays(int count, ServiceInterval unit, int expectedDays) {
			assertThat(unit.toDays(count)).isEqualTo(expectedDays);
		}

		@ParameterizedTest
		@EnumSource(ServiceInterval.class)
		@DisplayName("the count a person typed survives the round trip through days")
		void roundTripIsLossless(ServiceInterval unit) {
			// The reason months are 30 and years 365 rather than calendar lengths. The pair
			// (days, unit) is what is stored, and the count the person entered has to be
			// recoverable from it or the form cannot show them their own words back.
			for (int count = 1; count <= 100; count++) {
				assertThat(unit.countIn(unit.toDays(count)))
						.as("%d %s should read back as %d", count, unit, count)
						.isEqualTo(count);
			}
		}

		@Test
		@DisplayName("six months and ninety days are different intervals and stay different")
		void sixMonthsIsNotNinetyDays() {
			assertThat(ServiceInterval.MONTHS.toDays(6)).isEqualTo(180);
			assertThat(ServiceInterval.DAYS.toDays(90)).isEqualTo(90);
			assertThat(ServiceInterval.MONTHS.toDays(6)).isNotEqualTo(ServiceInterval.DAYS.toDays(90));
		}
	}

	@Nested
	@DisplayName("the next service date is derived, never stored")
	class Derivation {

		private static final LocalDate TODAY = LocalDate.of(2026, 9, 4);
		private static final int HORIZON = 30;

		@Test
		@DisplayName("a serviced machine counts from its newest service, and says so")
		void countsFromTheNewestService() {
			var derived = EquipmentService.derive(
					EquipmentCondition.GOOD, false, 180, LocalDate.of(2026, 8, 1), LocalDate.of(2020, 1, 1),
					TODAY, HORIZON);

			assertThat(derived.nextServiceOn()).isEqualTo(LocalDate.of(2027, 1, 28));
			assertThat(derived.basis()).isEqualTo(NextServiceBasis.SERVICED);
			assertThat(derived.status()).isEqualTo(ServiceStatus.OK);
		}

		@Test
		@DisplayName("a machine never serviced counts from its purchase date, and says that instead")
		void countsFromPurchaseWhenNeverServiced() {
			// The distinction the screen prints in as many words — "due 12 Mar 2027, from purchase,
			// never serviced" — so nobody reads a derived date as a service that happened.
			var derived = EquipmentService.derive(
					EquipmentCondition.GOOD, false, 365, null, LocalDate.of(2026, 3, 12), TODAY, HORIZON);

			assertThat(derived.nextServiceOn()).isEqualTo(LocalDate.of(2027, 3, 12));
			assertThat(derived.basis()).isEqualTo(NextServiceBasis.PURCHASED);
		}

		@Test
		@DisplayName("a machine with an interval but no date to count from is not scheduled")
		void neitherServiceNorPurchase() {
			var derived = EquipmentService.derive(
					EquipmentCondition.GOOD, false, 180, null, null, TODAY, HORIZON);

			assertThat(derived.nextServiceOn()).isNull();
			assertThat(derived.basis()).isEqualTo(NextServiceBasis.NONE);
			assertThat(derived.status()).isEqualTo(ServiceStatus.NOT_SCHEDULED);
		}

		@Test
		@DisplayName("a machine nobody has set an interval for is not scheduled, not overdue")
		void noInterval() {
			// The honest state for a trestle table. Not scheduled is not the same as late.
			var derived = EquipmentService.derive(
					EquipmentCondition.GOOD, false, null, LocalDate.of(2019, 1, 1), LocalDate.of(2018, 1, 1),
					TODAY, HORIZON);

			assertThat(derived.status()).isEqualTo(ServiceStatus.NOT_SCHEDULED);
			assertThat(derived.nextServiceOn()).isNull();
		}

		@ParameterizedTest(name = "{0} is {1}")
		@CsvSource({
			// Past the date is red.
			"2026-09-03, OVERDUE",
			"2025-01-01, OVERDUE",
			// Due today is amber, not red. The date has not passed, and red on the morning a
			// service falls due is the fire alarm D5 exists to avoid.
			"2026-09-04, DUE_SOON",
			// Inside the horizon is amber, right up to its last day.
			"2026-10-04, DUE_SOON",
			// A day past it is nothing at all.
			"2026-10-05, OK",
			"2027-06-01, OK",
		})
		void statusAgainstTheHorizon(LocalDate nextDue, ServiceStatus expected) {
			// Worked backwards: an interval of one day off a service the day before nextDue.
			var derived = EquipmentService.derive(
					EquipmentCondition.GOOD, false, 1, nextDue.minusDays(1), null, TODAY, HORIZON);

			assertThat(derived.nextServiceOn()).isEqualTo(nextDue);
			assertThat(derived.status()).isEqualTo(expected);
		}

		@Test
		@DisplayName("changing the temple's horizon changes which machines are amber")
		void horizonMovesTheAmberBand() {
			LocalDate lastServiced = LocalDate.of(2026, 9, 1);
			int interval = 60; // due 2026-10-31, fifty-seven days off

			assertThat(EquipmentService.derive(
					EquipmentCondition.GOOD, false, interval, lastServiced, null, TODAY, 30).status())
					.isEqualTo(ServiceStatus.OK);

			assertThat(EquipmentService.derive(
					EquipmentCondition.GOOD, false, interval, lastServiced, null, TODAY, 90).status())
					.isEqualTo(ServiceStatus.DUE_SOON);
		}

		@Test
		@DisplayName("a scrapped machine is in no service calculation, whatever its dates say")
		void scrappedIsNeverScheduled() {
			// D6. A dashboard that nags every morning about a grinder thrown away last year
			// teaches its reader to ignore it, and then it is worth nothing when a real one
			// comes due. These dates would be years overdue on any other machine.
			var derived = EquipmentService.derive(
					EquipmentCondition.SCRAPPED, false, 30, LocalDate.of(2019, 1, 1), LocalDate.of(2018, 1, 1),
					TODAY, HORIZON);

			assertThat(derived.status()).isEqualTo(ServiceStatus.NOT_SCHEDULED);
			assertThat(derived.nextServiceOn()).isNull();
			assertThat(derived.basis()).isEqualTo(NextServiceBasis.NONE);
		}

		@Test
		@DisplayName("a thing that never needs servicing reads NOT_SERVICED, not NOT_SCHEDULED")
		void neverNeedsServicingIsItsOwnState() {
			// T-120. The whole point: these two rows used to be indistinguishable, and only one of
			// them is a job somebody still has to do.
			var ladder = EquipmentService.derive(
					EquipmentCondition.GOOD, true, null, null, LocalDate.of(2018, 1, 1), TODAY, HORIZON);
			var boilerNobodyScheduled = EquipmentService.derive(
					EquipmentCondition.GOOD, false, null, null, LocalDate.of(2018, 1, 1), TODAY, HORIZON);

			assertThat(ladder.status()).isEqualTo(ServiceStatus.NOT_SERVICED);
			assertThat(boilerNobodyScheduled.status()).isEqualTo(ServiceStatus.NOT_SCHEDULED);
			assertThat(ladder.status()).isNotEqualTo(boilerNobodyScheduled.status());

			// Neither has a date, and neither claims one was counted from anything.
			assertThat(ladder.nextServiceOn()).isNull();
			assertThat(ladder.basis()).isEqualTo(NextServiceBasis.NONE);
		}

		@Test
		@DisplayName("the flag is asked before scrapping, so a scrapped ladder is still a ladder")
		void theFlagOutranksScrapping() {
			// Both would be invisible to every warning count either way, so this is about words
			// rather than about nagging: "not scheduled" on a thrown-away ladder invites somebody
			// to go and schedule it, which is the one thing this state exists to stop.
			var derived = EquipmentService.derive(
					EquipmentCondition.SCRAPPED, true, null, LocalDate.of(2019, 1, 1),
					LocalDate.of(2018, 1, 1), TODAY, HORIZON);

			assertThat(derived.status()).isEqualTo(ServiceStatus.NOT_SERVICED);
		}

		@Test
		@DisplayName("a flagged thing is out of scope for overdue, never infinitely overdue")
		void aFlaggedThingIsNeverOverdue() {
			// The reader this protects is TodayService's nudge, which counts
			// list(false, null, OVERDUE). Dates that would be seven years overdue on any other
			// machine, and the answer is still that nobody is late.
			var derived = EquipmentService.derive(
					EquipmentCondition.GOOD, true, null, LocalDate.of(2019, 1, 1),
					LocalDate.of(2018, 1, 1), TODAY, HORIZON);

			assertThat(derived.status()).isNotEqualTo(ServiceStatus.OVERDUE);
			assertThat(derived.status()).isNotEqualTo(ServiceStatus.DUE_SOON);
			assertThat(derived.status()).isEqualTo(ServiceStatus.NOT_SERVICED);
		}

		@Test
		@DisplayName("every other condition is still calculated")
		void repairStatesAreStillScheduled() {
			// Only SCRAPPED is terminal. A machine away being repaired is still a machine the
			// temple owns and still falls due.
			for (EquipmentCondition condition : new EquipmentCondition[] {
				EquipmentCondition.GOOD, EquipmentCondition.NEEDS_REPAIR, EquipmentCondition.IN_REPAIR}) {

				assertThat(EquipmentService.derive(
						condition, false, 30, LocalDate.of(2026, 1, 1), null, TODAY, HORIZON).status())
						.as("%s should still be calculated", condition)
						.isEqualTo(ServiceStatus.OVERDUE);
			}
		}
	}
}
