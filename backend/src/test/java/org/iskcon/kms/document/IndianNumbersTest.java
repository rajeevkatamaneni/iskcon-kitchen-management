package org.iskcon.kms.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Indian digit grouping (T-279): lakhs and crores, which the JDK's en-IN {@code NumberFormat} gets
 * wrong ("100,000"), and — below a lakh — exactly the characters that formatter used to print, so
 * no caller's existing output moved.
 */
class IndianNumbersTest {

	private static String whole(String value) {
		return IndianNumbers.group(new BigDecimal(value), 0, 0);
	}

	@Test
	@DisplayName("groups the last three digits, then pairs: 99,999 / 1,00,000 / 10,00,000 / 99,99,999 / 1,00,00,000")
	void lakhsAndCrores() {
		assertThat(whole("0")).isEqualTo("0");
		assertThat(whole("999")).isEqualTo("999");
		assertThat(whole("1000")).isEqualTo("1,000");
		assertThat(whole("99999")).isEqualTo("99,999");
		assertThat(whole("100000")).isEqualTo("1,00,000");
		assertThat(whole("150000")).isEqualTo("1,50,000");
		assertThat(whole("1000000")).isEqualTo("10,00,000");
		assertThat(whole("1234567")).isEqualTo("12,34,567");
		assertThat(whole("9999999")).isEqualTo("99,99,999");
		assertThat(whole("10000000")).isEqualTo("1,00,00,000");
		assertThat(whole("123456789")).isEqualTo("12,34,56,789");
		assertThat(whole("1000000000")).isEqualTo("1,00,00,00,000");
	}

	@Test
	@DisplayName("paise: 12,34,567.50 at two places, trailing zeros dropped down to the minimum")
	void withPaise() {
		assertThat(IndianNumbers.group(new BigDecimal("1234567.5"), 2, 2)).isEqualTo("12,34,567.50");
		assertThat(IndianNumbers.group(new BigDecimal("100000.05"), 2, 2)).isEqualTo("1,00,000.05");
		assertThat(IndianNumbers.group(new BigDecimal("100000.00"), 0, 2)).isEqualTo("1,00,000");
		assertThat(IndianNumbers.group(new BigDecimal("150000.250"), 0, 3)).isEqualTo("1,50,000.25");
		assertThat(IndianNumbers.group(new BigDecimal("0.5"), 0, 3)).isEqualTo("0.5");
		// A value that arrives with a negative scale (stripTrailingZeros on 100000 is 1E+5) is still
		// written in full, never as an exponent.
		assertThat(IndianNumbers.group(new BigDecimal("100000.00").stripTrailingZeros(), 0, 0))
				.isEqualTo("1,00,000");
		// Half-even, as NumberFormat rounded: .0005 at three places goes to the even neighbour.
		assertThat(IndianNumbers.group(new BigDecimal("2.0005"), 0, 3)).isEqualTo("2");
		assertThat(IndianNumbers.group(new BigDecimal("2.0015"), 0, 3)).isEqualTo("2.002");
	}

	@Test
	@DisplayName("negatives: the minus sign first, grouping unchanged")
	void negatives() {
		assertThat(whole("-99999")).isEqualTo("-99,999");
		assertThat(whole("-100000")).isEqualTo("-1,00,000");
		assertThat(IndianNumbers.group(new BigDecimal("-12345678.5"), 2, 2)).isEqualTo("-1,23,45,678.50");
	}

	@Test
	@DisplayName("below a lakh it prints exactly what NumberFormat en-IN printed, for every caller's decimals")
	void unchangedBelowALakh() {
		// The promise T-279 made: the grouping changes at a lakh and nowhere else. Checked against
		// the formatter every caller used before, over 20,000 figures from -99,999.9999 to
		// 99,999.9999 at each (min, max) decimals pair a caller asks for: (0,0) and (2,2) for rupees,
		// (0,2) and (0,3) for quantities and pack counts.
		Random random = new Random(279);
		int[][] decimals = {{0, 0}, {2, 2}, {0, 2}, {0, 3}};
		for (int i = 0; i < 20_000; i++) {
			BigDecimal value = BigDecimal.valueOf(random.nextLong(-999_999_999L, 1_000_000_000L), 4);
			for (int[] d : decimals) {
				NumberFormat old = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"));
				old.setMinimumFractionDigits(d[0]);
				old.setMaximumFractionDigits(d[1]);
				assertThat(IndianNumbers.group(value, d[0], d[1]))
						.as("%s at %d..%d decimals", value.toPlainString(), d[0], d[1])
						.isEqualTo(old.format(value));
			}
		}
	}

	@Test
	@DisplayName("refuses a decimals range that means nothing")
	void refusesNonsense() {
		assertThatThrownBy(() -> IndianNumbers.group(BigDecimal.ONE, 3, 2))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> IndianNumbers.group(BigDecimal.ONE, -1, 2))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
