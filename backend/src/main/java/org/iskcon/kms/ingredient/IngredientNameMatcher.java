package org.iskcon.kms.ingredient;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Tells an ingredient apart from its preparation, and tells whether a name somebody is about to
 * create is an ingredient the temple already has.
 *
 * <p><b>Why this exists.</b> Duplicate ingredients split stock, prices and shopping-list lines —
 * "Curd", "Curd, fresh", "Curd, sour" and "Curd, whisked" were four ingredients, so the temple held
 * curd in four places and bought it on four lines (docs/work/PROCUREMENT-REQUIREMENTS.md §9A). The
 * root cause was the recipe library import, which turned every <em>preparation</em> into a separate
 * ingredient: "Cashew, halved", "Green chilli, slit", "Ginger, paste". Three callers share this one
 * class so that they cannot drift apart:
 *
 * <ul>
 *   <li>the library import (R-DUP-1) calls {@link #split} to map "Cashew, halved" to Cashew with the
 *       note "halved", then {@link #findMatch} to find Cashew;</li>
 *   <li>the create and rename guard (R-DUP-2) calls {@link #findMatch} and stops the save on a
 *       match with "Did you mean Curd?";</li>
 *   <li>the one-time merge (R-DUP-3) groups existing ingredients by {@link #normalise}d name and
 *       proposes each group, with the note {@link #split} would move onto the recipe lines.</li>
 * </ul>
 *
 * <p>Pure Java on purpose — no Spring, no database — so the rule can be read, and tested, as a rule.
 *
 * <p><b>Reading the library's names.</b> The library files a name the way a reference book does,
 * noun first: "Water, hot". {@link IngredientNames} already undoes that for translation and
 * explains the edge cases; the same edge cases apply here and are handled the same way (a doubled
 * comma, a trailing comma, a parenthetical that belongs to the head). The one difference is a
 * <em>purpose</em>: IngredientNames must leave "Tamarind, for the water" alone because moving the
 * purpose to the front reads as nonsense, whereas here the purpose is exactly what belongs on the
 * recipe line, so it becomes the note.
 *
 * <p><b>Closeness is deliberately narrow.</b> A match stops a save, and the person has to press
 * "It's a different ingredient" and confirm it to get past. Every false match costs them that, so
 * the close-spelling rule only forgives what a typist actually does to a long word — one letter
 * wrong, missing, extra or swapped — and never touches a short one: "Ghee" and "Gheer", or "Rava"
 * and "Ragi", are one letter apart and are different food. See {@link #typoClose}.
 */
public final class IngredientNameMatcher {

	private IngredientNameMatcher() {
	}

	/**
	 * What the cook does to an ingredient, as opposed to what the temple buys. A recipe line's
	 * qualifier that is one of these becomes its preparation note; anything else stays in the
	 * ingredient's name, because it names a different thing the temple stocks ("Rice, broken",
	 * "Salt, coarse", "Red chilli, dry", "Dates, seedless", "Coconut milk, thick", "Tamarind,
	 * dried", "Curry leaves, dried", "Brinjal, small purple").
	 *
	 * <p><b>PROVISIONAL — pending Rajeev's answer to Q-12.</b> Only halved, slit, paste, fresh
	 * grated, sour (R-DUP-1), fresh and whisked (the curd group in R-DUP-3) are written in the
	 * requirements. The rest is the conductor's provisional ruling of 2026-09-19, relayed to task
	 * T-249: "the cook's actions and states". When Q-12 is answered, this list is the one place to
	 * change. Entries are whole phrases, matched as a whole — "chopped fine" is listed because the
	 * library writes it, and "sliced thin", which is not listed, stays in the name until somebody
	 * decides it should not. That is deliberate: a word nobody has ruled on is left alone.
	 */
	static final Set<String> PREPARATIONS = Set.of(
			"chopped", "chopped fine",
			"grated", "grated and squeezed", "fresh grated",
			"cubed", "diced", "diced small",
			"crushed", "ground", "coarsely ground",
			"roasted and crushed", "roasted and ground", "roasted and skinned",
			"soaked", "soaked overnight",
			"sliced", "shredded", "crumbled",
			"cut", "cut and fried", "boiled and cubed",
			"paste", "halved", "slit", "whisked",
			"fresh", "sour", "chilled", "boiling");

	/**
	 * Names that end in a preparation word but are a thing the temple buys ready-made, so they are
	 * never split. Held in {@link #key} form. Also part of the provisional Q-12 ruling: "ground" is a
	 * preparation in general, but black pepper is bought ground.
	 */
	static final Set<String> STORE_BOUGHT = Set.of("black pepper ground");

	/**
	 * A tail that says what the ingredient is <em>for</em> ("for frying", "to serve", "as starter").
	 * It is not part of what is bought, so it becomes the note (provisional Q-12 ruling). Only these
	 * three openers: "for", "to" and "as" are the ones the library uses for a purpose.
	 */
	private static final Pattern PURPOSE = Pattern.compile("^(for|to|as)\\b", Pattern.CASE_INSENSITIVE);

	/**
	 * A tail that opens with any other preposition, which {@link IngredientNames} also refuses to
	 * move. "with skin" ("Urad dal, with skin") is what the temple buys, "in fingers" is a cut
	 * nobody has ruled on: neither is safe to strip, so a name holding one is left whole.
	 */
	private static final Pattern OTHER_PREPOSITION = Pattern.compile(
			"^(if|with|in|on|from|per|at|by|about|without)\\b", Pattern.CASE_INSENSITIVE);

	/** An ingredient name, split into what is bought and what the cook does to it. */
	public record Split(String base, String preparation) {

		/** True when there is a preparation note. */
		public boolean hasPreparation() {
			return preparation != null;
		}
	}

	/** An existing ingredient to compare against: its id, its name and any merged-away aliases. */
	public record Entry(UUID id, String name, List<String> aliases) {

		public Entry {
			aliases = aliases == null ? List.of() : List.copyOf(aliases);
		}

		public Entry(UUID id, String name) {
			this(id, name, List.of());
		}
	}

	/** How a candidate matched. */
	public enum MatchKind {
		/** The normalised names are equal, directly or through an alias. */
		EXACT,
		/** A one-letter slip in a long word, or the same ingredient with or without a qualifier. */
		CLOSE
	}

	/**
	 * The best match: the entry, how it matched, and which of its names matched (the name itself,
	 * or one of its aliases) so the prompt can say why.
	 */
	public record Match(Entry entry, MatchKind kind, String matchedName) {
	}

	// ---------------------------------------------------------------------------------------------
	// split
	// ---------------------------------------------------------------------------------------------

	/**
	 * {@code "Cashew, halved"} to Cashew + "halved"; {@code "Grated coconut"} to Coconut + "grated";
	 * {@code "Oil, for frying"} to Oil + "for frying". Anything else comes back whole, trimmed, with
	 * no note.
	 *
	 * <p>The rule, in order:
	 * <ol>
	 *   <li>A name on the {@link #STORE_BOUGHT} list is never split.</li>
	 *   <li>With commas: an empty segment (a doubled or trailing comma, or nothing before the first
	 *       comma) is a typo, so the name stays whole — as in IngredientNames. So does a tail opening
	 *       with a preposition other than for/to/as. Otherwise tails are taken off <em>from the
	 *       end</em> while each is a {@link #PREPARATIONS} phrase or a purpose, and the first tail that
	 *       is neither stops it: "Rice, local, soaked" becomes "Rice, local" + "soaked", and
	 *       "Mustard, cumin, asafoetida" (a tempering mix) is untouched.</li>
	 *   <li>At the front of the head: a preparation phrase is taken off ("Sour curd" is Curd +
	 *       "sour"), as long as something is left. Brackets travel with the head: "Wood apple (bela),
	 *       ripe" keeps "(bela)".</li>
	 *   <li>Without commas, at the end as well: "Ginger paste" is Ginger + "paste", which is what
	 *       R-DUP-2's "Curd sour" needs.</li>
	 * </ol>
	 * Notes read in the order they were written, front first, joined with ", ".
	 */
	public static Split split(String raw) {
		if (raw == null) {
			return new Split("", null);
		}
		String trimmed = raw.trim().replaceAll("\\s+", " ");
		if (trimmed.isEmpty() || STORE_BOUGHT.contains(key(trimmed))) {
			return new Split(trimmed, null);
		}

		List<String> notes = new ArrayList<>();
		String head;
		List<String> kept = new ArrayList<>();
		boolean hasComma = trimmed.indexOf(',') >= 0;

		if (hasComma) {
			// -1 keeps trailing empty strings, so "Ghee," is seen as the typo it is.
			String[] parts = trimmed.split(",", -1);
			head = parts[0].trim();
			if (head.isEmpty()) {
				return new Split(trimmed, null);
			}
			List<String> tails = new ArrayList<>();
			for (int i = 1; i < parts.length; i++) {
				String tail = parts[i].trim();
				if (tail.isEmpty() || OTHER_PREPOSITION.matcher(tail).find()) {
					return new Split(trimmed, null);
				}
				tails.add(tail);
			}
			int cut = tails.size();
			while (cut > 0 && isNote(tails.get(cut - 1))) {
				cut--;
			}
			kept.addAll(tails.subList(0, cut));
			notes.addAll(tails.subList(cut, tails.size()));
		} else {
			head = trimmed;
		}

		// Front of the head, then — only for a name with no commas at all — its end.
		List<String> frontNotes = new ArrayList<>();
		String[] words = head.split(" ");
		int from = 0;
		int to = words.length;
		int n;
		while ((n = preparationAt(words, from, to, true)) > 0) {
			frontNotes.add(join(words, from, from + n).toLowerCase(Locale.ROOT));
			from += n;
		}
		List<String> endNotes = new ArrayList<>();
		if (!hasComma) {
			while ((n = preparationAt(words, from, to, false)) > 0) {
				endNotes.add(0, join(words, to - n, to).toLowerCase(Locale.ROOT));
				to -= n;
			}
		}

		List<String> allNotes = new ArrayList<>(frontNotes);
		allNotes.addAll(endNotes);
		allNotes.addAll(notes);
		if (allNotes.isEmpty()) {
			return new Split(trimmed, null);
		}
		StringBuilder base = new StringBuilder(capitalise(join(words, from, to)));
		for (String tail : kept) {
			base.append(", ").append(tail);
		}
		return new Split(base.toString(), String.join(", ", allNotes));
	}

	private static boolean isNote(String tail) {
		return PURPOSE.matcher(tail).find() || PREPARATIONS.contains(phrase(tail));
	}

	/**
	 * The word count of the longest preparation phrase at the front ({@code atFront}) or the end of
	 * {@code words[from, to)}, or 0 — and 0 as well when taking it would leave no ingredient at all,
	 * so a name that is only "Paste" stays "Paste".
	 */
	private static int preparationAt(String[] words, int from, int to, boolean atFront) {
		int available = to - from;
		for (int len = Math.min(3, available - 1); len >= 1; len--) {
			String candidate = atFront ? join(words, from, from + len) : join(words, to - len, to);
			if (PREPARATIONS.contains(phrase(candidate))) {
				return len;
			}
		}
		return 0;
	}

	/** Lower case, single spaces, no surrounding punctuation: how a phrase is looked up. */
	private static String phrase(String s) {
		return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", "").trim().replaceAll("\\s+", " ");
	}

	private static String join(String[] words, int from, int to) {
		return String.join(" ", Arrays.copyOfRange(words, from, to));
	}

	// ---------------------------------------------------------------------------------------------
	// normalise
	// ---------------------------------------------------------------------------------------------

	/**
	 * The form two names are compared in (R-DUP-2): the preparation taken off by {@link #split},
	 * then lower case, accents dropped ("puréed"), punctuation and brackets turned into spaces, and
	 * each word made singular. "Tomatos", "Tomatoes" and "tomato" all become "tomato"; "Curd, sour"
	 * and "Curd sour" both become "curd".
	 */
	public static String normalise(String name) {
		return key(split(name).base());
	}

	/** Everything {@link #normalise} does except the split. */
	static String key(String s) {
		if (s == null) {
			return "";
		}
		String plain = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		String spaced = plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
		if (spaced.isEmpty()) {
			return "";
		}
		return Arrays.stream(spaced.split(" "))
				.map(IngredientNameMatcher::singular)
				.collect(Collectors.joining(" "));
	}

	/**
	 * English plurals as they appear in ingredient names. Not a dictionary: the same rule runs on
	 * both names being compared, so a word it mangles ("molasses" to "molass") is mangled the same
	 * way on both sides and still compares equal. The only risk is two different words becoming
	 * one, which is why words of three letters or fewer ("dal", "oil") and endings that are rarely
	 * plural (-ss, -us, -is: "hibiscus", "asparagus") are left alone.
	 */
	static String singular(String w) {
		if (w.length() <= 3) {
			return w;
		}
		if (w.equals("leaves")) {
			return "leaf";
		}
		if (w.endsWith("ies") && w.length() > 4) {
			String stem = w.substring(0, w.length() - 3);
			// "chillies" is the plural of "chilli", not of "chilly".
			return stem.endsWith("ll") ? stem + "i" : stem + "y";
		}
		if (w.endsWith("oes")) {
			return w.substring(0, w.length() - 2); // tomatoes, potatoes, mangoes
		}
		if (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("sses") || w.endsWith("xes")
				|| w.endsWith("zes")) {
			return w.substring(0, w.length() - 2); // peaches, radishes
		}
		if (w.endsWith("ss") || w.endsWith("us") || w.endsWith("is")) {
			return w;
		}
		if (w.endsWith("s")) {
			return w.substring(0, w.length() - 1); // tomatos, cashews, cloves, peas
		}
		return w;
	}

	// ---------------------------------------------------------------------------------------------
	// findMatch
	// ---------------------------------------------------------------------------------------------

	/**
	 * The existing ingredient a new name most likely duplicates, or empty.
	 *
	 * <p><b>EXACT</b>: the {@link #normalise}d names are equal, comparing against each entry's name
	 * and every alias. <b>CLOSE</b>, in order of preference:
	 * <ol>
	 *   <li>{@link #typoClose}: a one-letter slip in one long word, or the same letters spaced
	 *       differently ("Greenchilli").</li>
	 *   <li>The same ingredient with and without a qualifier: one name is bare and equals the
	 *       other's part before its first comma. This is how "Tomatos" meets "Tomato, ripe" (R-DUP-2
	 *       AC) without "ripe" having been ruled a preparation. It needs one side to be bare, so
	 *       "Rice, basmati" and "Rice, sona masoori" — two varieties — do not match each other.</li>
	 * </ol>
	 * Ties go to the ingredient whose name is shortest ("Curd" before "Curd, thick"), then to the
	 * order the entries were given, so the result never depends on hashing.
	 */
	public static Optional<Match> findMatch(String candidate, Collection<Entry> existing) {
		String c = normalise(candidate);
		if (c.isEmpty() || existing == null) {
			return Optional.empty();
		}
		String cHead = head(candidate);
		boolean cBare = !split(candidate).base().contains(",");

		record Scored(Match match, int rank, int order) {
		}
		List<Scored> found = new ArrayList<>();
		int order = 0;
		for (Entry entry : existing) {
			List<String> names = new ArrayList<>();
			names.add(entry.name());
			names.addAll(entry.aliases());
			int best = Integer.MAX_VALUE;
			String bestName = null;
			for (String name : names) {
				String e = normalise(name);
				if (e.isEmpty()) {
					continue;
				}
				int rank;
				if (e.equals(c)) {
					rank = 0;
				} else if (typoClose(c, e)) {
					rank = 1;
				} else if (bareMeetsQualified(cBare, cHead, name)) {
					rank = 2;
				} else {
					continue;
				}
				if (rank < best) {
					best = rank;
					bestName = name;
				}
			}
			if (bestName != null) {
				MatchKind kind = best == 0 ? MatchKind.EXACT : MatchKind.CLOSE;
				found.add(new Scored(new Match(entry, kind, bestName), best, order));
			}
			order++;
		}
		return found.stream()
				.min(Comparator.comparingInt(Scored::rank)
						.thenComparingInt(s -> s.match().entry().name().length())
						.thenComparingInt(Scored::order))
				.map(Scored::match);
	}

	private static boolean bareMeetsQualified(boolean cBare, String cHead, String existingName) {
		boolean eBare = !split(existingName).base().contains(",");
		if (cBare == eBare) {
			return false;
		}
		return cHead.equals(head(existingName));
	}

	/** The normalised part of the name before its first comma, after the split. */
	private static String head(String name) {
		String base = split(name).base();
		int comma = base.indexOf(',');
		return key(comma < 0 ? base : base.substring(0, comma));
	}

	/**
	 * Close spelling, and the reasoning for the thresholds. Two normalised names are close when:
	 * <ul>
	 *   <li>they are the same letters with the spaces in different places ("green chilli" /
	 *       "greenchilli"); or</li>
	 *   <li>they have the same number of words, exactly one word differs, and that word is one edit
	 *       away (a letter changed, added, dropped, or two neighbours swapped) and at least six
	 *       letters long.</li>
	 * </ul>
	 * One edit, one word, six letters. Each limit is there for a pair it keeps apart: two edits
	 * would make "ragi flour" (finger millet) close to "rava flour" (semolina); a slip in a second
	 * word would let two different spellings of two different things add up; and short words are
	 * where Indian ingredient names sit one letter apart while meaning different food — "chana" and
	 * "chena", "ghee" and "gheer", "rava" and "ragi". The six is the longer word's length, so
	 * "chili" still meets "chilli". The cost is that "tur dal" is not caught as "toor dal" (two
	 * edits): it still reaches a person as a second ingredient, which the merge tool (R-DUP-3) can
	 * fold back, whereas a false match would stop every person who types the real, different
	 * ingredient.
	 *
	 * <p>Measured against the recipe library (2,238 distinct names, 1,849 once normalised): 19 pairs
	 * are close, and 18 are one thing spelled two ways ("tulasi leaf" / "tulsi leaf", "panch
	 * phoran" / "panch phoron", "sweet corn" / "sweetcorn"). The one wrong pair is "chana" and
	 * "chhana" (chickpea and chhena cheese), which the rule cannot tell from a typo. Outside the
	 * library, "butter" and "batter" are the same kind of pair. Both cost one press of "It's a
	 * different ingredient", and both are pinned in the tests so a change to the rule shows up.
	 */
	static boolean typoClose(String a, String b) {
		if (a.equals(b)) {
			return false; // that is EXACT, not close
		}
		if (a.replace(" ", "").equals(b.replace(" ", ""))) {
			return true;
		}
		String[] wa = a.split(" ");
		String[] wb = b.split(" ");
		if (wa.length != wb.length) {
			return false;
		}
		int differing = -1;
		for (int i = 0; i < wa.length; i++) {
			if (!wa[i].equals(wb[i])) {
				if (differing >= 0) {
					return false;
				}
				differing = i;
			}
		}
		String x = wa[differing];
		String y = wb[differing];
		return Math.max(x.length(), y.length()) >= 6 && editDistance(x, y) <= 1;
	}

	/** Optimal string alignment distance: insert, delete, substitute, or swap two neighbours. */
	static int editDistance(String a, String b) {
		int[][] d = new int[a.length() + 1][b.length() + 1];
		for (int i = 0; i <= a.length(); i++) {
			d[i][0] = i;
		}
		for (int j = 0; j <= b.length(); j++) {
			d[0][j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			for (int j = 1; j <= b.length(); j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
				if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
					d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
				}
			}
		}
		return d[a.length()][b.length()];
	}

	private static String capitalise(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
