package org.iskcon.kms.library;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the recipe books' human strings into numbers the application can compute with.
 *
 * <p>The books were written to be printed, so every quantity in them is prose: a yield is
 * {@code "20 L"} or {@code "300 idlis (3 per devotee)"} or {@code "~12 Kg (lasts months)"}, and an
 * ingredient line is {@code "2 L"} or {@code "7 Pieces"}. None of it is a number beside a unit, and
 * all of it has to become one before a recipe can be scaled, costed or planned.
 *
 * <p>Kept pure and separate from the loader on purpose. These rules were derived by reading the
 * whole of the vendored library they were written for — 5,376 recipes, 46,337 ingredient lines — and
 * they are the part of the ingest that is worth testing directly rather than through a database. The
 * curated catalogue that replaced those books on 2026-09-19 is far smaller, 44 recipes and 454 lines,
 * and the rules were re-measured against it rather than assumed to still hold; the figures below are
 * that measurement, taken with this class's own code.
 *
 * <h2>What the data actually looks like</h2>
 *
 * <p><strong>Ingredient quantities parse exactly.</strong> All 454 of them, in eight unit tokens that
 * fold to six once case is ignored — {@code gm} (275), {@code Kg} (86), {@code L} (39),
 * {@code Pieces} (29), {@code ml} (20), {@code Nos} (3), {@code nos} (1), {@code pieces} (1). They
 * land on five stored units: 275 GM, 86 KG, 39 L, 34 PIECES, 20 ML. The vendored books ran to eight
 * tokens as well, overlapping but not identical — they wrote {@code pcs} and never a capitalised
 * {@code Pieces} — in quite different proportions: {@code gm} on 29,409 of their 46,337 lines.
 *
 * <p><strong>Yields do not.</strong> They carry approximations, count nouns and asides. The rule
 * that resolves all 44 is deliberately blunt: a mass token means kilograms, a volume token means
 * litres, and <em>anything else is a count</em> — 30 L, 10 PIECES, 4 KG today. That last clause is
 * what absorbs the count nouns without a dictionary that would need extending every time a book is
 * added: eight of them in this catalogue ({@code rottis}, {@code unde}, {@code idlis},
 * {@code chapatis}, {@code pooris}, {@code doses}, {@code chiroti}, {@code obbattu}), 137 in the
 * vendored library.
 *
 * <h2>Why the verbatim string is kept alongside</h2>
 *
 * <p>Because {@code 300 PIECES} tells a cook nothing and Rave Idli's own {@code 300 idlis (3 per
 * devotee)} tells them everything. The parsed pair does the arithmetic; the original does the explaining.
 */
public final class BookParser {

	/** A leading number, optionally approximate, then whatever the book wanted to say about it. */
	private static final Pattern LEADING_QUANTITY =
			Pattern.compile("^\\s*~?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(.*)$");

	/** The per-head portion where the book put it in the yield rather than its own field. */
	private static final Pattern PER_DEVOTEE =
			Pattern.compile("\\(\\s*([0-9]+(?:\\.[0-9]+)?)\\s*per\\s+devotee\\s*\\)", Pattern.CASE_INSENSITIVE);

	/** Mass and volume tokens, with what one of them is worth in the canonical unit. */
	private static final Map<String, BigDecimal> MASS = Map.of(
			"kg", BigDecimal.ONE,
			"kgs", BigDecimal.ONE,
			"gm", new BigDecimal("0.001"),
			"gms", new BigDecimal("0.001"),
			"g", new BigDecimal("0.001"));

	private static final Map<String, BigDecimal> VOLUME = Map.of(
			"l", BigDecimal.ONE,
			"lt", BigDecimal.ONE,
			"ltr", BigDecimal.ONE,
			"ml", new BigDecimal("0.001"));

	/** The five units an ingredient line may land on — the vocabulary {@code recipe_ingredients} admits. */
	private static final Map<String, String> LINE_UNITS = Map.of(
			"kg", "KG",
			"gm", "GM",
			"g", "GM",
			"l", "L",
			"ml", "ML");

	private static final MathContext PRECISION = MathContext.DECIMAL64;

	private BookParser() {
	}

	/**
	 * A quantity in one of the yield vocabulary's units.
	 *
	 * @param value the number, already converted into {@code unit} — 200 ml arrives here as 0.2
	 *              litres, so that a head count multiplied by it lands in the same unit as the
	 *              recipe's own yield and no conversion is needed downstream.
	 * @param unit  {@code L}, {@code KG} or {@code PIECES}.
	 */
	public record Quantity(BigDecimal value, String unit) {
	}

	/**
	 * An ingredient line's quantity, in the units {@code recipe_ingredients} uses. Unlike
	 * {@link Quantity} this is <em>not</em> normalised — 200 gm stays 200 GM, because the
	 * ingredient catalogue and the scaler both work in the unit the cook was given.
	 */
	public record LineQuantity(BigDecimal value, String unit) {
	}

	/**
	 * A yield string as a quantity. Never empty for any recipe in the books, and the loader treats
	 * an empty result as a reason to stop rather than a row to skip.
	 *
	 * <p>Examples, all real and all in today's catalogue: Bassaru's {@code "20 L"} to 20 L, Mysore
	 * Pak's {@code "~14 Kg (140 gm per devotee)"} to 14 KG, Rave Idli's {@code "300 idlis (3 per
	 * devotee)"} to 300 PIECES.
	 */
	public static Optional<Quantity> parseYield(String text) {
		Matcher m = LEADING_QUANTITY.matcher(text == null ? "" : text);
		if (!m.matches()) {
			return Optional.empty();
		}
		BigDecimal number = new BigDecimal(m.group(1));
		String token = firstToken(m.group(2));

		BigDecimal massFactor = MASS.get(token);
		if (massFactor != null) {
			return Optional.of(new Quantity(number.multiply(massFactor, PRECISION), "KG"));
		}
		BigDecimal volumeFactor = VOLUME.get(token);
		if (volumeFactor != null) {
			// "L", not "LITRES": one vocabulary since E11-S2, and perHead below compares this
			// token by string equality — a mismatch here silently discards every volume recipe's
			// per-head portion rather than failing.
			return Optional.of(new Quantity(number.multiply(volumeFactor, PRECISION), "L"));
		}
		// Anything else names the thing itself — idlis, laddu, rottis, pakore. It is a count.
		return Optional.of(new Quantity(number, "PIECES"));
	}

	/**
	 * What one person eats, in the same unit as the recipe's yield.
	 *
	 * <p>The books put it in two places and the loader has to look in both. In the curated catalogue,
	 * counted 2026-09-19: 31 recipes carry it in their own {@code per} field, 10 only inside the
	 * yield's {@code (3 per devotee)} parenthetical, none in both. The field wins where a recipe has
	 * both, which in the vendored books happened 110 times and agreed every time.
	 *
	 * <p>Three recipes state neither in a form this reads. Two are pickles — Limbe Uppinakayi and
	 * Mavinakayi Uppinakayi — which nobody serves by the head, as it was the masalas and the pickles
	 * in the vendored books. The third is Mysore Pak, whose yield says {@code "~14 Kg (140 gm per devotee)"}:
	 * the parenthetical rule takes a bare number before "per devotee" and this one names its own unit,
	 * so the portion is shown to the cook in the yield text and withheld from the arithmetic.
	 *
	 * <p><strong>A portion in a different family from the yield is discarded.</strong> No recipe in
	 * today's catalogue is one — every recipe that states a portion states it in the yield's own
	 * family — so this filter currently discards nothing, and it stays because a single book can
	 * reintroduce the case. The vendored library had exactly one, Delhi's Papdi: it yielded 5 Kg and
	 * was served 6 pieces a head, and no arithmetic takes a head count from one to the other. Keeping
	 * the number would have let the planner compute 600 kilos of papdi for a hundred people. The text
	 * is still stored and shown; only the arithmetic is withheld, and the planner asks.
	 *
	 * @param perField  the book's own {@code per} field; may be null
	 * @param yieldText the yield string, consulted for the parenthetical when {@code perField} is absent
	 * @param yieldUnit the unit {@link #parseYield} resolved, which the portion must agree with
	 */
	public static Optional<Quantity> perHead(String perField, String yieldText, String yieldUnit) {
		Optional<Quantity> parsed = Optional.empty();

		if (perField != null && !perField.isBlank()) {
			parsed = parseYield(perField);
		} else {
			Matcher m = PER_DEVOTEE.matcher(yieldText == null ? "" : yieldText);
			if (m.find()) {
				// A parenthetical never names its own unit — "(3 per devotee)" of whatever the yield
				// counts — so it takes the yield's.
				parsed = Optional.of(new Quantity(new BigDecimal(m.group(1)), yieldUnit));
			}
		}

		return parsed.filter(q -> q.unit().equals(yieldUnit)).filter(q -> q.value().signum() > 0);
	}

	/**
	 * An ingredient line's quantity, in the unit the book wrote it in.
	 *
	 * <p>Every one of the catalogue's 454 lines resolves, as every one of the vendored library's
	 * 46,337 did. A token outside the eight seen in the books returns
	 * empty rather than guessing, and the loader stops on it — a silently mis-parsed quantity is a
	 * kitchen cooking the wrong amount, which is worse than a load that refuses to finish.
	 */
	public static Optional<LineQuantity> ingredientQuantity(String text) {
		Matcher m = LEADING_QUANTITY.matcher(text == null ? "" : text);
		if (!m.matches()) {
			return Optional.empty();
		}
		BigDecimal number = new BigDecimal(m.group(1));
		String token = firstToken(m.group(2));

		String unit = LINE_UNITS.get(token);
		if (unit != null) {
			return Optional.of(new LineQuantity(number, unit));
		}
		if (isCountToken(token)) {
			return Optional.of(new LineQuantity(number, "PIECES"));
		}
		return Optional.empty();
	}

	/**
	 * The count tokens the books use for ingredients — a closed set, unlike yields.
	 *
	 * <p>Yields are allowed to name the dish ("300 idlis") because the noun is the point. An
	 * ingredient line is not: it says "25 nos" of lemon, never "25 lemons". So an unknown
	 * token here is a parsing failure rather than a count, and it is meant to stop the load.
	 */
	private static boolean isCountToken(String token) {
		return switch (token) {
			case "nos", "no", "pcs", "pc", "piece", "pieces", "each" -> true;
			default -> false;
		};
	}

	/** The first word of what followed the number, lowercased and stripped of trailing punctuation. */
	private static String firstToken(String rest) {
		String trimmed = rest == null ? "" : rest.trim();
		int cut = trimmed.length();
		for (int i = 0; i < trimmed.length(); i++) {
			char c = trimmed.charAt(i);
			if (Character.isWhitespace(c) || c == '(') {
				cut = i;
				break;
			}
		}
		return trimmed.substring(0, cut).toLowerCase(Locale.ROOT).replaceAll("[.,;:]+$", "");
	}
}
