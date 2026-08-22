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
 * {@code "20 L"} or {@code "300 idlis (3 per devotee)"} or {@code "~4 Kg (about 3 batches)"}, and an
 * ingredient line is {@code "8 L"}. None of it is a number beside a unit, and all of it has to
 * become one before a recipe can be scaled, costed or planned.
 *
 * <p>Kept pure and separate from the loader on purpose. These rules were derived by reading all
 * 5,376 recipes and all 46,337 ingredient lines, and they are the part of the ingest that is worth
 * testing directly rather than through a database.
 *
 * <h2>What the data actually looks like</h2>
 *
 * <p><strong>Ingredient quantities parse exactly.</strong> All 46,337 of them, with only eight unit
 * tokens between them — {@code gm} (29,409), {@code Kg} (10,829), {@code L} (3,548), {@code ml}
 * (2,107), {@code nos} (404), {@code Nos} (21), {@code pieces} (16), {@code pcs} (3).
 *
 * <p><strong>Yields do not.</strong> They carry approximations, count nouns and asides. The rule
 * that resolves all 5,376 is deliberately blunt: a mass token means kilograms, a volume token means
 * litres, and <em>anything else is a count</em>. That last clause is what absorbs a tail of 137
 * distinct nouns — {@code idlis}, {@code mudde}, {@code pakore}, {@code bobbatlu} — without a
 * dictionary that would need extending every time a book is added.
 *
 * <h2>Why the verbatim string is kept alongside</h2>
 *
 * <p>Because {@code 839 pieces} tells a cook nothing and {@code 300 idlis (3 per devotee)} tells
 * them everything. The parsed pair does the arithmetic; the original does the explaining.
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
	 * @param unit  {@code LITRES}, {@code KG} or {@code PIECES}.
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
	 * <p>Examples, all real: {@code "20 L"} to 20 LITRES, {@code "~10 Kg finished (100 gm per
	 * devotee)"} to 10 KG, {@code "300 idlis (3 per devotee)"} to 300 PIECES.
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
			return Optional.of(new Quantity(number.multiply(volumeFactor, PRECISION), "LITRES"));
		}
		// Anything else names the thing itself — idlis, laddu, rottis, pakore. It is a count.
		return Optional.of(new Quantity(number, "PIECES"));
	}

	/**
	 * What one person eats, in the same unit as the recipe's yield.
	 *
	 * <p>The books put it in two places and the loader has to look in both: 4,563 recipes carry it
	 * in their own {@code per} field, 359 only inside the yield's {@code (3 per devotee)}
	 * parenthetical, and 110 in both — where all 110 agree, so the field wins with nothing to
	 * reconcile. 344 have neither, and those are the masalas and the pickles, which nobody serves by
	 * the head.
	 *
	 * <p><strong>A portion in a different family from the yield is discarded.</strong> Delhi's Papdi
	 * is the only one in the whole library: it yields 5 Kg and is served 6 pieces a head, and no
	 * arithmetic takes a head count from one to the other. Keeping the number would let the planner
	 * compute 600 kilos of papdi for a hundred people. The text is still stored and shown; only the
	 * arithmetic is withheld, and the planner asks.
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
	 * <p>Every one of the 46,337 lines resolves. A token outside the eight seen in the books returns
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
	 * ingredient line is not: it says "20 nos" of wood apple, never "20 wood apples". So an unknown
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
