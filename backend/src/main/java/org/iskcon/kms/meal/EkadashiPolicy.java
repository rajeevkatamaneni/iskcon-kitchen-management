package org.iskcon.kms.meal;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether a recipe may be cooked on Ekadashi (E4-S6). A recipe is compatible iff none of its lines
 * uses an Ekadashi-prohibited ingredient (a grain, bean, or flour). The Ekadashi recipe category
 * passes by construction, since those recipes avoid grains.
 */
@Service
public class EkadashiPolicy {

	private final JdbcTemplate jdbc;

	public EkadashiPolicy(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** A recipe's Ekadashi compatibility and, if not, the offending ingredients by name. */
	@Transactional(readOnly = true)
	public Compatibility of(UUID recipeId) {
		List<String> offending = jdbc.query("""
				SELECT DISTINCT i.name
				FROM recipe_ingredients ri
				JOIN ingredients i ON i.id = ri.ingredient_id
				WHERE ri.recipe_id = ? AND i.is_ekadashi_prohibited
				ORDER BY i.name
				""", (rs, n) -> rs.getString("name"), recipeId);
		return new Compatibility(offending.isEmpty(), offending);
	}

	/** Compatible when there are no offending ingredients. */
	public record Compatibility(boolean compatible, List<String> offendingIngredients) {
	}
}
