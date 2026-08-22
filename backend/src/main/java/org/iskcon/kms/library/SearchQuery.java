package org.iskcon.kms.library;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns what somebody typed into a PostgreSQL {@code tsquery}, and into the {@code ILIKE} pattern
 * the temple's own recipes are matched with.
 *
 * <p>Every token becomes a prefix — {@code maj} matches Majjige — because this feeds a box that
 * filters as you type, and a person three letters into a word has not finished typing it. Tokens
 * are joined with AND, so each further letter narrows rather than widens.
 *
 * <p>Input is reduced to letters and digits before it reaches {@code to_tsquery}. That function
 * parses its argument as an expression and raises on a stray {@code &} or {@code !}, which would
 * turn an ordinary apostrophe in a search box into a 500. Stripping is not a security measure — the
 * value is bound, never concatenated — it is what keeps the box from breaking on real typing.
 */
public final class SearchQuery {

	/** Enough words to narrow anything in a 5,376-row library; beyond this a person is pasting. */
	private static final int MAX_TERMS = 6;

	private SearchQuery() {
	}

	/** The terms, lowercased and stripped, in the order they were typed. Empty if nothing usable. */
	public static List<String> terms(String raw) {
		List<String> terms = new ArrayList<>();
		if (raw == null) {
			return terms;
		}
		for (String piece : raw.toLowerCase(Locale.ROOT).split("[^\\p{Alnum}]+")) {
			if (!piece.isEmpty()) {
				terms.add(piece);
				if (terms.size() == MAX_TERMS) {
					break;
				}
			}
		}
		return terms;
	}

	/** {@code "maj idl"} to {@code "maj:* & idl:*"}. Empty string where there is nothing to search for. */
	public static String toTsQuery(String raw) {
		return String.join(" & ", terms(raw).stream().map(t -> t + ":*").toList());
	}
}
