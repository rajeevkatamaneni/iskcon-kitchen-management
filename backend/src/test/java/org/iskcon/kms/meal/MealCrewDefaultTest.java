package org.iskcon.kms.meal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic behind the crew default (Q11), on its own.
 *
 * <p>The query that finds the meals is exercised end to end in {@link MealCrewIT}, where a day type
 * and a temple can be real. What is tested here is the choice made once the meals are in hand, which
 * has four cases and no database in it at all.
 */
class MealCrewDefaultTest {

	@Test
	@DisplayName("three or more meals give the middle value, not the last and not the mean")
	void threeGiveTheMiddle() {
		// The reason for the median rather than the last meal: the festival guard does not catch an
		// unusual *ordinary* day. A visiting sannyasi took twenty on a Tuesday stored REGULAR, and as
		// the most recent lunch it would otherwise set the default for every ordinary lunch after it.
		assertThat(MealCrewService.median(List.of(20, 8, 7))).isEqualTo(8);
		assertThat(MealCrewService.median(List.of(7, 8, 20))).isEqualTo(8);
		assertThat(MealCrewService.median(List.of(8, 20, 7))).isEqualTo(8);
	}

	@Test
	@DisplayName("two meals give their mean, rounded up — being short is worse than being over")
	void twoGiveTheMeanRoundedUp() {
		assertThat(MealCrewService.median(List.of(5, 8))).isEqualTo(7);
		assertThat(MealCrewService.median(List.of(8, 5))).isEqualTo(7);
		// Exact halves round up as well. Nothing here ever rounds down.
		assertThat(MealCrewService.median(List.of(4, 5))).isEqualTo(5);
		assertThat(MealCrewService.median(List.of(6, 6))).isEqualTo(6);
	}

	@Test
	@DisplayName("one meal gives itself")
	void oneGivesItself() {
		assertThat(MealCrewService.median(List.of(9))).isEqualTo(9);
	}

	@Test
	@DisplayName("no meals give nothing at all, and the field opens empty")
	void noneGivesNull() {
		// Honest. A made-up number would not be, and it would look authoritative while being wrong.
		assertThat(MealCrewService.median(List.of())).isNull();
	}
}
