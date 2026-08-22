package org.iskcon.kms.library;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which of a temple's own recipe categories a library recipe lands in.
 *
 * <p>A temple starts with nine categories, seeded from RM 2019's sheet list at provisioning:
 * Beverages, Breakfast, Rice, Dal, Sabji, Roti, Sweets, Snacks and Ekadashi. The library has
 * twenty-one. This is the map between them, and it is deliberately lossy in one direction: the
 * books separate wet and dry sabjis, and a temple's list does not, so both land on Sabji rather
 * than growing the temple two categories where they had one.
 *
 * <p>The eight the temple has no home for — chutneys, khichadi, masalas, kadhi and raita, jam and
 * pickles, soups and salads, vegan and Jain — are created on first use, and only on first use. A
 * temple that never imports a pickle never acquires a Jam &amp; pickles category.
 *
 * <p>Ekadashi maps onto the seeded category that is already flagged fasting-compatible, which is
 * what E4 reads to know a recipe is allowed on a fast day. Getting this one wrong is not a tidiness
 * problem: it is a dish appearing on a fasting day that should not be there.
 */
public final class CategoryMapping {

	private record Target(String name, boolean fastingCompatible) {
	}

	private static final Map<String, Target> BY_KEY = new LinkedHashMap<>();

	static {
		// Straight onto a category every temple already has.
		BY_KEY.put("beverages", new Target("Beverages", false));
		BY_KEY.put("breakfast-items", new Target("Breakfast", false));
		BY_KEY.put("rice", new Target("Rice", false));
		BY_KEY.put("dal", new Target("Dal", false));
		BY_KEY.put("rotis", new Target("Roti", false));
		BY_KEY.put("sweets", new Target("Sweets", false));
		BY_KEY.put("fried-items-farsan-snacks", new Target("Snacks", false));

		// The fasting one. Flagged, and the flag is the point.
		BY_KEY.put("ekadashi", new Target("Ekadashi", true));

		// Two book categories, one temple category. A cook browsing Sabji wants both.
		BY_KEY.put("sabji-s-dry", new Target("Sabji", false));
		BY_KEY.put("sabji-s-wet", new Target("Sabji", false));

		// A sweet that keeps is still a sweet.
		BY_KEY.put("sweets-sustainable", new Target("Sweets", false));

		// Created on first use.
		BY_KEY.put("chutneys", new Target("Chutneys", false));
		BY_KEY.put("khichadi", new Target("Khichadi", false));
		BY_KEY.put("masalas", new Target("Masalas", false));
		BY_KEY.put("kadhi-raita", new Target("Kadhi & Raita", false));
		BY_KEY.put("jam-pickles", new Target("Jam & pickles", false));
		BY_KEY.put("soups-salads", new Target("Soups & salads", false));
		BY_KEY.put("economical-recipes", new Target("Economical", false));
		BY_KEY.put("fhc-sabjis", new Target("Catering sabjis", false));
		BY_KEY.put("vegan", new Target("Vegan", false));
		BY_KEY.put("jain", new Target("Jain", false));
	}

	private CategoryMapping() {
	}

	/**
	 * The temple category name for a library category key.
	 *
	 * <p>Falls back to the book's own English category name for a key this map has not heard of,
	 * which is what happens if a new book brings a new category: the recipe still imports, into a
	 * category named after what it is, rather than being refused.
	 */
	public static String nameFor(String categoryKey, String bookCategoryName) {
		Target target = BY_KEY.get(categoryKey);
		return target == null ? bookCategoryName : target.name();
	}

	/** Whether a category created for this key should be flagged fasting-compatible. */
	public static boolean fastingCompatible(String categoryKey) {
		Target target = BY_KEY.get(categoryKey);
		return target != null && target.fastingCompatible();
	}
}
