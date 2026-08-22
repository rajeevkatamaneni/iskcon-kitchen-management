package org.iskcon.kms.library;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Which shelf an ingredient goes on, when a library import has to create it.
 *
 * <p>{@code ingredients.category} is {@code NOT NULL} and the books carry no category at all, so
 * importing a recipe has to supply one for every ingredient the temple does not already hold. This
 * is that supply: a keyword map, in code, meant to be read as a document the way
 * {@code RolePermissions} is.
 *
 * <p><strong>Measured, not guessed.</strong> Against the real books these rules name
 * <strong>1,832 of the 2,238 distinct ingredients (82%)</strong> and cover
 * <strong>44,176 of the 46,337 ingredient lines (95.3%)</strong>. The share of lines is the number
 * that matters: the tail is regional and rare — <em>timur</em>, <em>jakhya</em>, <em>perilla
 * seeds</em>, <em>jambu</em>, <em>pancha phutana</em> — and each appears in a handful of recipes
 * from one state, while salt and ghee appear in thousands from every state.
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
		for (Rule rule : RULES) {
			if (rule.pattern().matcher(name).find()) {
				return rule.category();
			}
		}
		return FALLBACK;
	}
}
