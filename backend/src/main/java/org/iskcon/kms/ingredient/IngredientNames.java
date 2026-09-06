package org.iskcon.kms.ingredient;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns a catalogue-inverted ingredient name back into the order somebody says it aloud.
 *
 * <p><b>Why this exists.</b> The recipe library files a name the way a reference book does — the
 * noun first, so that everything about water sorts together: {@code Water, cold}, {@code Water, hot},
 * {@code Water, warm}. 882 of its 6,333 names are written that way. That is a fine way to *file* a
 * name and a poor way to *read* one, and it stops being merely awkward the moment it is translated:
 * "Water, hot" into Kannada is a faithful "ನೀರು, ಬಿಸಿ", which reads straight back as "Water Hot".
 * That is the defect Rajeev reported from the demo on 2026-09-04, and the translator was innocent —
 * it was handed an inverted name and inverted it precisely.
 *
 * <p>Inversion is an English cataloguing device. No other language the picker offers uses it, so it
 * has no meaning to carry across and is undone before the text is sent.
 *
 * <p><b>What is deliberately left alone.</b> A comma does not always mean an inversion:
 *
 * <ul>
 *   <li>{@code Tamarind, for the water} — a purpose, not an adjective. "For the water tamarind" is
 *       nonsense, so anything whose tail opens with a preposition is left exactly as it is.</li>
 *   <li>{@code Wood apple (bela), ripe} — the parenthetical is part of the head and travels with it,
 *       giving "Ripe wood apple (bela)" rather than something spliced through the brackets.</li>
 *   <li>Anything with no comma at all, which is 85% of the library.</li>
 * </ul>
 *
 * <p>This is applied where a name is <em>read</em> in another language. It does not rewrite what is
 * stored: the ingredient master keeps the filed name, so the picker still sorts the three waters
 * together and every place that matches a line to an ingredient by name still matches.
 */
public final class IngredientNames {

	private IngredientNames() {
	}

	/** A tail that qualifies the purpose rather than the thing, which must not be moved to the front. */
	private static final Pattern PURPOSE = Pattern.compile(
			"^(for|to|as|if|with|in|on|from|per|at|by|about|without)\\b", Pattern.CASE_INSENSITIVE);

	/**
	 * {@code "Water, hot"} to {@code "Hot water"}; {@code "Banana, ripe, mashed"} to
	 * {@code "Ripe mashed banana"}. Anything this cannot read as an inversion comes back untouched.
	 */
	public static String readable(String name) {
		if (name == null) {
			return null;
		}
		String trimmed = name.trim();
		if (trimmed.indexOf(',') < 0) {
			return trimmed;
		}

		String[] parts = trimmed.split(",");
		String head = parts[0].trim();
		if (head.isEmpty()) {
			return trimmed;
		}

		List<String> qualifiers = new ArrayList<>();
		for (int i = 1; i < parts.length; i++) {
			String tail = parts[i].trim();
			// An empty segment means a doubled comma, which is a typo rather than an inversion.
			if (tail.isEmpty() || PURPOSE.matcher(tail).find()) {
				return trimmed;
			}
			qualifiers.add(tail);
		}
		// A trailing comma leaves nothing to move: Java's split drops the empty tail, so an empty list
		// here is the only signal that "Ghee," had one. Without this the method would fall through and
		// lower-case a name nobody asked it to touch.
		if (qualifiers.isEmpty()) {
			return trimmed;
		}

		// The head loses its capital once it is no longer the first word, and a brand loses it with
		// them — "Amul, unsalted" becomes "Unsalted amul". Telling a brand from an ordinary noun needs
		// a dictionary, and buying one would be out of proportion here: this string is on its way to a
		// machine translator, which does not read case, and the temple's own filed name — the one on
		// every screen — is not touched at all.
		StringBuilder out = new StringBuilder();
		for (String qualifier : qualifiers) {
			out.append(out.length() == 0 ? capitalise(qualifier) : qualifier).append(' ');
		}
		out.append(decapitalise(head));
		return out.toString();
	}

	private static String capitalise(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static String decapitalise(String s) {
		return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
	}
}
