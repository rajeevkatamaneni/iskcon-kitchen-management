package org.iskcon.kms.recipe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.iskcon.kms.library.SearchQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one search box on the Recipes page, over both sources (E2-S10).
 *
 * <p>A cook looking for a dish does not know or care whether it is one the temple wrote or one the
 * library holds, so the box does not ask. What differs is what a row offers: a library recipe the
 * temple has not taken carries a plus, and one it already holds does not.
 *
 * <h2>The temple's own come first</h2>
 *
 * <p>Not by rank but by rule. A temple that has written or taken a recipe has decided something
 * about it, and burying that under five variations from other states would make the box worse the
 * more the temple used it. Within each half, ordering is by relevance.
 *
 * <h2>An empty box is not an empty search</h2>
 *
 * <p>With nothing typed the page shows the temple's own recipes and no library rows at all — the
 * same list it has always shown. A temple with four recipes is not greeted by five thousand.
 *
 * <h2>Archived recipes</h2>
 *
 * <p>Returned by a search, absent from the empty-box list, and badged. The Recipes page no longer
 * carries a "show archived" tick, and without this an archived recipe would be unreachable: there
 * is no other route to its screen, and its Restore button lives there.
 */
@Service
public class RecipeSearchService {

	private static final int LIBRARY_LIMIT = 30;
	private static final int MINE_LIMIT = 60;

	private final JdbcTemplate jdbc;

	public RecipeSearchService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public List<RecipeSearchResult> search(String query) {
		List<String> terms = SearchQuery.terms(query);
		List<RecipeSearchResult> results = new ArrayList<>(mine(terms));

		if (!terms.isEmpty()) {
			// A library row whose name the temple already holds under a different provenance must
			// not appear twice on one screen. The SQL marks those alreadyAdded; this drops the ones
			// whose name is literally a row already above them.
			Set<String> shown = new LinkedHashSet<>();
			for (RecipeSearchResult r : results) {
				shown.add(r.name().toLowerCase(Locale.ROOT));
			}
			for (RecipeSearchResult row : library(SearchQuery.toTsQuery(query))) {
				if (shown.add(row.name().toLowerCase(Locale.ROOT))) {
					results.add(row);
				}
			}
		}
		return results;
	}

	/**
	 * The temple's own, matched on name, subtitle, tags and the ingredients they contain.
	 *
	 * <p>{@code ILIKE} rather than a tsvector: a temple holds hundreds of recipes, not thousands,
	 * and a substring match is what people expect of their own list — typing "idl" should find
	 * "Rave Idli" whether or not a stemmer agrees.
	 */
	private List<RecipeSearchResult> mine(List<String> terms) {
		StringBuilder sql = new StringBuilder("""
				SELECT r.id, r.name, r.subtitle, c.name AS category_name, r.status, r.badge,
				       r.sattvic_override_reason
				FROM recipes r
				JOIN recipe_categories c ON c.id = r.category_id
				WHERE
				""");
		List<Object> args = new ArrayList<>();

		if (terms.isEmpty()) {
			// Nothing typed: the temple's active recipes, as this page has always opened.
			sql.append("r.status = 'ACTIVE'\n");
		} else {
			// Archived included, because a search is the only way back to one now.
			sql.append("(");
			for (int i = 0; i < terms.size(); i++) {
				if (i > 0) {
					sql.append(" AND ");
				}
				sql.append("""
						(r.name ILIKE ? OR coalesce(r.subtitle, '') ILIKE ?
						 OR EXISTS (SELECT 1 FROM unnest(r.tags) t WHERE t ILIKE ?)
						 OR EXISTS (
						     SELECT 1 FROM recipe_ingredients ri
						     JOIN ingredients ing ON ing.id = ri.ingredient_id
						     WHERE ri.recipe_id = r.id AND ing.name ILIKE ?))
						""");
				String like = "%" + terms.get(i).replace("%", "\\%").replace("_", "\\_") + "%";
				args.add(like);
				args.add(like);
				args.add(like);
				args.add(like);
			}
			sql.append(")\n");
		}

		sql.append("ORDER BY (r.status = 'ACTIVE') DESC, r.name LIMIT ").append(MINE_LIMIT);

		return jdbc.query(sql.toString(), (rs, n) -> new RecipeSearchResult(
				"MINE",
				rs.getObject("id", UUID.class),
				rs.getString("name"),
				rs.getString("subtitle"),
				rs.getString("category_name"),
				null,
				false,
				rs.getString("badge"),
				false,
				rs.getString("status"),
				rs.getString("sattvic_override_reason") != null), args.toArray());
	}

	/** The library's, ranked by the weighted document V68 builds. */
	private List<RecipeSearchResult> library(String tsQuery) {
		if (tsQuery.isEmpty()) {
			return List.of();
		}
		return jdbc.query("""
				SELECT m.id, m.display_name, m.subtitle, m.category_name, m.state, m.badge,
				       m.disambiguated_by,
				       EXISTS (
				           SELECT 1 FROM recipes r
				           WHERE r.status = 'ACTIVE'
				             AND (r.master_recipe_id = m.id OR lower(r.name) = lower(m.display_name))
				       ) AS already_added
				FROM master_recipes m
				WHERE m.search_doc @@ to_tsquery('simple', ?)
				ORDER BY ts_rank_cd(m.search_doc, to_tsquery('simple', ?)) DESC, m.display_name
				LIMIT ?
				""", (rs, n) -> new RecipeSearchResult(
				"LIBRARY",
				rs.getObject("id", UUID.class),
				rs.getString("display_name"),
				rs.getString("subtitle"),
				rs.getString("category_name"),
				rs.getString("state"),
				rs.getInt("disambiguated_by") == 0,
				rs.getString("badge"),
				rs.getBoolean("already_added"),
				null,
				false), tsQuery, tsQuery, LIBRARY_LIMIT);
	}
}
