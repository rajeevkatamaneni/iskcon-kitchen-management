package org.iskcon.kms.library;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.iskcon.kms.ingredient.IngredientNameMatcher;

/**
 * Which shelf an ingredient goes on, when a library import has to create it.
 *
 * <p>{@code ingredients.category} is {@code NOT NULL} and the books carry no category at all, so
 * importing a recipe has to supply one for every ingredient the temple does not already hold. This
 * is that supply: a keyword map, in code, meant to be read as a document the way
 * {@code RolePermissions} is.
 *
 * <p><strong>Measured, not guessed — and the measurement is now a test.</strong>
 * {@code IngredientCategoriesTest} runs these rules over the real books in
 * {@code src/main/resources/recipe-library} on every build, prints what it could not name, and holds
 * the coverage to a floor. The figures below came from that test rather than from a script somebody
 * ran once.
 *
 * <p>Against the curated catalogue, after the singular pass added on 2026-09-20 (see
 * {@link #forName}): they name <strong>92 of its 99 distinct ingredients (93%)</strong> and cover
 * <strong>430 of its 454 ingredient lines (94.7%)</strong>. Before that pass it was 88 of 99 and 423
 * of 454 (93.2%) — the seven lines are the four plurals described below. Against the 32 vendored
 * books the rules were written for, it was 1,832 of 2,238 distinct names (82%) and 44,176 of 46,337
 * lines (95.3%).
 *
 * <p>What the remainder is has changed with the data, and it is worth knowing before anyone tunes
 * these rules. In the vendored books the tail was regional and rare — <em>timur</em>,
 * <em>jakhya</em>, <em>perilla seeds</em>, <em>jambu</em>, <em>pancha phutana</em> — each in a
 * handful of recipes from one state, while salt and ghee appeared in thousands from every state. In
 * the curated catalogue the 31 uncovered lines are water (15, and its shelf matters least of any
 * ingredient in the product — it is the one the temple never buys), the temple's own spice blends
 * (<em>bisi bele bath pudi</em>, <em>huli pudi</em>, <em>chutney pudi</em>, <em>vangi bath pudi</em>),
 * <em>mixed vegetables</em> (4), <em>eno</em>, and four names — seven lines — that were only a plural
 * away from a rule that already holds their singular: <em>Cloves</em> (1), <em>Coriander seeds</em>
 * (4), <em>Lemons</em> (1), <em>Raisins</em> (1). Those four were never a regional tail, they were
 * {@code \\b} against an {@code s}, and {@link #forName}'s second pass now names them. The 24 lines
 * left are the water and the blends, which belong where they are.
 *
 * <p>The remainder lands on {@code Other}, which is not a new word: tenant provisioning already
 * files Egg there. A temple recategorises anything it disagrees with, which it may do freely — the
 * category is descriptive, drives nothing, and is theirs.
 *
 * <p>Order matters. The first rule that matches wins, so the specific sit above the general:
 * coriander <em>seed</em> and coriander <em>powder</em> are spices while coriander leaves are a
 * vegetable, and both spellings of a dal reach Pulses before "flour" can claim besan for Grains.
 */
public final class IngredientCategories {

	/** Where anything unrecognised goes. Already in the vocabulary — provisioning files Egg here. */
	public static final String FALLBACK = "Other";

	private record Rule(String category, Pattern pattern) {
	}

	private static final List<Rule> RULES = List.of(
			// Leaves first: coriander, curry and fenugreek leaves are vegetables, and their seeds
			// and powders are spices. Reversing these two rules mis-files both.
			rule("Vegetables", "\\b(coriander|curry|fenugreek|methi|mint|amaranth|colocasia|banana|drumstick|"
					+ "radish|mustard|beet|turnip|pumpkin|bottle gourd|betel)\\s+(leaf|leaves|greens)\\b"),
			rule("Vegetables", "\\b(spinach|greens|saag|xaak|palak|leaves)\\b"),

			rule("Spices", "\\b(chilli|chili|chile|pepper|peppercorn|turmeric|cumin|jeera|coriander seed|"
					+ "coriander powder|coriander$|dhania|mustard seed|mustard|rai|asafoetida|hing|cardamom|"
					+ "elaichi|clove|cinnamon|bay leaf|tej patta|fenugreek|methi seed|ajwain|carom|kalonji|"
					+ "nigella|fennel|saunf|star anise|mace|javitri|nutmeg|jaiphal|saffron|kesar|tamarind|"
					+ "amchur|amchoor|anardana|kokum|masala|podi|phoron|phutana|garam|sambar powder|"
					+ "rasam powder|chaat|timur|jakhya|jambu|szechuan|dry ginger|sonth|shunthi|black salt|"
					+ "rock salt|salt|pepper corns)\\b"),

			rule("Dairy", "\\b(curd|dahi|yog(h)?urt|milk|ghee|paneer|butter|cream|malai|khoya|mawa|chhena|"
					+ "chenna|cheese|buttermilk|majjige|chaas)\\b"),

			rule("Oils & fats", "\\b(oil|vanaspati|dalda|shortening)\\b"),

			rule("Sweeteners", "\\b(jaggery|gur\\b|bella|bellam|sugar|honey|sharkara|misri|molasses)\\b"),

			rule("Pulses", "\\b(dal|dhal|daal|lentil|gram\\b|chana|chickpea|moong|mung|urad|udad|toor|tur|"
					+ "arhar|masoor|rajma|lobia|cowpea|besan|matki|moth|kabuli|horse gram|field bean|"
					+ "sprouts?)\\b"),

			rule("Grains", "\\b(rice|wheat|atta|maida|flour|rava|sooji|suji|semolina|poha|avalakki|aval|"
					+ "beaten rice|ragi|bajra|jowar|jolada|millet|sago|sabudana|javvarisi|oats|barley|"
					+ "vermicelli|semiya|sevai|puffed|murmura|corn|makai|makki|buckwheat|kuttu|samak|"
					+ "samvat|rajgira|amaranth flour|bread|noodle)\\b"),

			rule("Nuts & seeds", "\\b(cashew|kaju|almond|badam|groundnut|peanut|sesame|til\\b|ellu|walnut|"
					+ "pistachio|pista|raisin|kishmish|coconut|copra|kopra|nariyal|poppy|khus khus|"
					+ "melon seed|magaz|pine nut|perilla|flax|sunflower seed|pumpkin seed|charoli)\\b"),

			rule("Fruit", "\\b(banana|plantain|mango|lemon|lime|nimbu|orange|sweet lime|apple|papaya|"
					+ "pineapple|dates|khajur|amla|gooseberry|jackfruit|guava|pomegranate|berry|fig|anjeer|"
					+ "grape|wood apple|bael|bela|custard apple|chikoo|sapota|watermelon|muskmelon)\\b"),

			rule("Vegetables", "\\b(potato|aloo|alugadde|tomato|carrot|beans|cabbage|cauliflower|brinjal|"
					+ "eggplant|aubergine|okra|ladies finger|bhindi|gourd|lauki|tinda|tindli|karela|"
					+ "pumpkin|kaddu|yam|suran|arbi|colocasia|sweet potato|beet|turnip|radish|mooli|peas|"
					+ "matar|capsicum|bell pepper|ginger|adrak|cucumber|kheera|drumstick|moringa|"
					+ "plantain stem|banana stem|raw banana|chow chow|knol|kohlrabi|ash gourd|snake gourd|"
					+ "ridge gourd|cluster bean|gavar|broad bean|sprout|lotus|bamboo shoot|fern|mushroom|"
					+ "onion|garlic|shallot|leek|spring)\\b"),

			rule("Other", "\\b(water|soda|eno|fruit salt|baking|papad|sev|boondi|silver|vark|leaf|colour|"
					+ "essence|vinegar|ice|starch|agar|yeast|citric|alum|camphor|tulsi|banana leaf)\\b"));

	private static final Map<String, String> EXACT = Map.of();

	private IngredientCategories() {
	}

	private static Rule rule(String category, String regex) {
		return new Rule(category, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
	}

	/**
	 * The shelf for an ingredient name, or {@link #FALLBACK}.
	 *
	 * <p>Matched on the whole name, so "Curd, fresh" and "Coriander leaves, chopped" work without
	 * the caller having to strip the cook's qualifier off first.
	 *
	 * <p><strong>Two passes: the name as written, then its singular.</strong> A rule is a word with
	 * {@code \b} at each end, and a trailing {@code s} sits inside that boundary — so the rule
	 * holding <em>clove</em> did not name "Cloves", and <em>coriander seed</em> did not name
	 * "Coriander seeds". Seven of the catalogue's lines fell to {@code Other} for that reason alone.
	 * When no rule names the name as written, the rules run again over
	 * {@link IngredientNameMatcher#normalise}'s form of it, which makes every word singular.
	 *
	 * <p>The order is the whole of it, and reversing it would be worse than leaving the bug. Several
	 * rules are written in the plural because that is how the books write the ingredient —
	 * <em>beans</em>, <em>greens</em>, <em>leaves</em>, <em>peas</em>, <em>dates</em> — and
	 * singularising first would stop all of them matching. Trying the written name first means no
	 * name that is filed correctly today can change shelf: the second pass is only ever reached by a
	 * name that was going to be {@code Other}.
	 *
	 * <p><strong>Why the duplicate-ingredient rule's singularisation and not a new one.</strong>
	 * {@link IngredientNameMatcher#normalise} is what decides that "Tomatoes" and "tomato" are the
	 * same ingredient, and this class decides which shelf that ingredient goes on; two different
	 * ideas of what a plural is would eventually file one ingredient in two places. It is reused as
	 * it stands, with no change to it. It does slightly more than singularise — it drops accents and
	 * punctuation and takes off a preparation ("Coconut, grated" becomes "coconut") — all of which
	 * is either harmless here or helpful, and none of which can reach a name a rule already named.
	 */
	public static String forName(String ingredientName) {
		if (ingredientName == null || ingredientName.isBlank()) {
			return FALLBACK;
		}
		String name = ingredientName.toLowerCase(Locale.ROOT);
		String exact = EXACT.get(name);
		if (exact != null) {
			return exact;
		}
		String found = match(name);
		if (found != null) {
			return found;
		}
		String singular = IngredientNameMatcher.normalise(ingredientName);
		if (!singular.isEmpty() && !singular.equals(name)) {
			found = match(singular);
			if (found != null) {
				return found;
			}
		}
		return FALLBACK;
	}

	/** The first rule that names {@code name}, or null. */
	private static String match(String name) {
		for (Rule rule : RULES) {
			if (rule.pattern().matcher(name).find()) {
				return rule.category();
			}
		}
		return null;
	}
}
