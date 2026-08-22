package org.iskcon.kms.library;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading and curating the shared recipe library (E2-S9, E2-S15).
 *
 * <p>Every temple reads this; only a platform operator writes it. Both halves of that are enforced
 * twice — by {@code MANAGE_RECIPE_LIBRARY} on the endpoints, and by the RLS policies in V68, which
 * name {@code SUPER_ADMIN} in the database itself. The service check is what gives a clear refusal;
 * the policy is what makes the refusal true.
 *
 * <p>Nothing here is tenant-scoped, because the library belongs to no temple. The one place a
 * tenant leaks into these queries is deliberate and read-only: whether <em>this</em> temple already
 * holds a copy, which decides if a search row offers a plus. That subquery reads {@code recipes},
 * which is under ordinary tenant RLS, so it answers for the caller's temple and no other — and for
 * an operator, who has no temple, it answers no.
 */
@Service
public class MasterRecipeService {

	/** Enough to fill a screen and then some; a person narrows by typing, not by scrolling. */
	private static final int SEARCH_LIMIT = 40;

	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;
	private final AuditService audit;

	public MasterRecipeService(JdbcTemplate jdbc, ObjectMapper mapper, AuditService audit) {
		this.jdbc = jdbc;
		this.mapper = mapper;
		this.audit = audit;
	}

	/**
	 * The library rows matching what was typed, best first.
	 *
	 * <p>Matching is the weighted document built in V68 — name and subtitle heaviest, then
	 * ingredients and tags, then category, state and badge. Method steps and the "why" are
	 * deliberately outside it: nearly every recipe boils something, so indexing the prose would make
	 * "boil" return three thousand rows and the filter stop filtering exactly when the list is
	 * longest.
	 */
	@Transactional(readOnly = true)
	public List<MasterRecipeSummary> search(String query, int limit) {
		String tsQuery = SearchQuery.toTsQuery(query);
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
				""", SUMMARY, tsQuery, tsQuery, Math.min(limit <= 0 ? SEARCH_LIMIT : limit, SEARCH_LIMIT));
	}

	/** Browsing without a search — what an operator opens the library on. */
	@Transactional(readOnly = true)
	public List<MasterRecipeSummary> browse(String stateSlug, String categoryKey, int limit) {
		return jdbc.query("""
				SELECT m.id, m.display_name, m.subtitle, m.category_name, m.state, m.badge,
				       m.disambiguated_by,
				       EXISTS (
				           SELECT 1 FROM recipes r
				           WHERE r.status = 'ACTIVE'
				             AND (r.master_recipe_id = m.id OR lower(r.name) = lower(m.display_name))
				       ) AS already_added
				FROM master_recipes m
				WHERE (? IS NULL OR m.state_slug = ?)
				  AND (? IS NULL OR m.category_key = ?)
				ORDER BY m.state, m.category_name, m.display_name
				LIMIT ?
				""", SUMMARY, stateSlug, stateSlug, categoryKey, categoryKey,
				Math.min(limit <= 0 ? SEARCH_LIMIT : limit, 500));
	}

	@Transactional(readOnly = true)
	public MasterRecipeView get(UUID id) {
		try {
			return jdbc.queryForObject("""
					SELECT m.*,
					       EXISTS (
					           SELECT 1 FROM recipes r
					           WHERE r.status = 'ACTIVE'
					             AND (r.master_recipe_id = m.id OR lower(r.name) = lower(m.display_name))
					       ) AS already_added
					FROM master_recipes m
					WHERE m.id = ?
					""", detailMapper(), id);
		} catch (EmptyResultDataAccessException e) {
			throw new ApplicationException(ErrorCode.MASTER_RECIPE_NOT_FOUND, Map.of("id", String.valueOf(id)));
		}
	}

	/** The states with books, for the operator's filter. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> states() {
		return jdbc.query(
				"SELECT state_slug, state, count(*) AS recipes FROM master_recipes GROUP BY 1, 2 ORDER BY state",
				(rs, n) -> Map.of(
						"slug", rs.getString("state_slug"),
						"name", rs.getString("state"),
						"recipes", rs.getInt("recipes")));
	}

	// ------------------------------------------------------------------ curation

	@Transactional
	public UUID create(AuthenticatedUser actor, MasterRecipeInput input) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO master_recipes (
					id, state_slug, state, book_language, recipe_slug,
					name, display_name, disambiguated_by, subtitle,
					category_key, category_name, badge,
					yield_text, yield_qty, yield_unit,
					per_head_text, per_head_qty, per_head_unit,
					indicative_cost, region, why, catering_note,
					note_start, note_vessel, note_season,
					tags, serve_with, ingredient_names, ingredients, method,
					source_ref, updated_by_user_id)
				VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
					CAST(? AS text[]), CAST(? AS text[]), CAST(? AS text[]),
					CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)
				""", bindings(id, input, actor));

		audit.recordPlatform(actor, AuditAction.MASTER_RECIPE_CREATED, AuditEntityType.MASTER_RECIPE, id,
				null, Map.of("name", input.name(), "state", input.state()), null);
		return id;
	}

	@Transactional
	public void update(AuthenticatedUser actor, UUID id, MasterRecipeInput input) {
		MasterRecipeView before = get(id);
		int rows = jdbc.update("""
				UPDATE master_recipes SET
					state_slug = ?, state = ?, book_language = ?, recipe_slug = ?,
					name = ?, display_name = ?, subtitle = ?,
					category_key = ?, category_name = ?, badge = ?,
					yield_text = ?, yield_qty = ?, yield_unit = ?,
					per_head_text = ?, per_head_qty = ?, per_head_unit = ?,
					indicative_cost = ?, region = ?, why = ?, catering_note = ?,
					note_start = ?, note_vessel = ?, note_season = ?,
					tags = CAST(? AS text[]), serve_with = CAST(? AS text[]), ingredient_names = CAST(? AS text[]),
					ingredients = CAST(? AS jsonb), method = CAST(? AS jsonb),
					source_ref = ?, updated_by_user_id = ?, updated_at = now()
				WHERE id = ?
				""", updateBindings(id, input, actor));

		if (rows == 0) {
			throw new ApplicationException(ErrorCode.MASTER_RECIPE_NOT_FOUND, Map.of("id", String.valueOf(id)));
		}
		audit.recordPlatform(actor, AuditAction.MASTER_RECIPE_UPDATED, AuditEntityType.MASTER_RECIPE, id,
				Map.of("name", before.displayName()), Map.of("name", input.name()), null);
	}

	/**
	 * Removes a recipe from the library.
	 *
	 * <p>Copies temples have already taken are untouched — {@code recipes.master_recipe_id} is
	 * {@code ON DELETE SET NULL}, so a kitchen that has been cooking a dish for a year does not lose
	 * it because an operator tidied the catalogue. It loses only the record of where it came from.
	 */
	@Transactional
	public void delete(AuthenticatedUser actor, UUID id) {
		MasterRecipeView before = get(id);
		jdbc.update("DELETE FROM master_recipes WHERE id = ?", id);
		audit.recordPlatform(actor, AuditAction.MASTER_RECIPE_DELETED, AuditEntityType.MASTER_RECIPE, id,
				Map.of("name", before.displayName(), "state", String.valueOf(before.state())), null, null);
	}

	// ------------------------------------------------------------------ plumbing

	private Object[] bindings(UUID id, MasterRecipeInput in, AuthenticatedUser actor) {
		List<Object> args = new ArrayList<>();
		args.add(id);
		args.addAll(commonBindings(in));
		args.add("operator:" + actor.getUserId());
		args.add(actor.getUserId());
		return args.toArray();
	}

	private Object[] updateBindings(UUID id, MasterRecipeInput in, AuthenticatedUser actor) {
		List<Object> args = new ArrayList<>(commonBindings(in));
		// The update statement has no disambiguated_by, which create sets to 0 and only the loader
		// moves; an operator's hand-written recipe is named by hand.
		args.remove(6);
		args.add("operator:" + actor.getUserId());
		args.add(actor.getUserId());
		args.add(id);
		return args.toArray();
	}

	private List<Object> commonBindings(MasterRecipeInput in) {
		List<Object> args = new ArrayList<>();
		args.add(in.stateSlug());
		args.add(in.state());
		args.add(in.bookLanguage() == null ? "English" : in.bookLanguage());
		args.add(in.recipeSlug());
		args.add(in.name());
		args.add(in.name());
		args.add(0);
		args.add(in.subtitle());
		args.add(in.categoryKey());
		args.add(in.categoryName());
		args.add(in.badge());
		args.add(in.yieldText());
		args.add(in.yieldQty());
		args.add(in.yieldUnit());
		args.add(in.perHeadText());
		args.add(in.perHeadQty());
		args.add(in.perHeadUnit());
		args.add(in.indicativeCost());
		args.add(in.region());
		args.add(in.why());
		args.add(in.cateringNote());
		args.add(in.noteStart());
		args.add(in.noteVessel());
		args.add(in.noteSeason());
		args.add(pgArray(in.tags()));
		args.add(pgArray(in.serveWith()));
		args.add(pgArray(in.ingredients().stream().map(MasterRecipeInput.Line::name).toList()));
		args.add(json(in.ingredients().stream().map(MasterRecipeService::lineMap).toList()));
		args.add(json(in.method()));
		return args;
	}

	/**
	 * A {@code text[]} literal, quoted and escaped.
	 *
	 * <p>The driver will not take a bare {@code String[]} for a text array through JdbcTemplate's
	 * varargs, and a literal is the honest alternative to threading a PreparedStatementSetter
	 * through every call. Every element is quoted, so a comma, a brace or a curry leaf in a name
	 * cannot end the array early.
	 */
	private static String pgArray(List<String> values) {
		if (values == null || values.isEmpty()) {
			return "{}";
		}
		StringBuilder out = new StringBuilder("{");
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			out.append('"')
					.append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\""))
					.append('"');
		}
		return out.append('}').toString();
	}

	private static Map<String, Object> lineMap(MasterRecipeInput.Line line) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", line.name());
		m.put("qty", line.qty());
		BookParser.LineQuantity parsed = BookParser.ingredientQuantity(line.qty()).orElseThrow(
				() -> new ApplicationException(ErrorCode.VALIDATION_FAILED,
						Map.of("field", "ingredients", "value", String.valueOf(line.qty()))));
		m.put("qtyValue", parsed.value());
		m.put("qtyUnit", parsed.unit());
		return m;
	}

	private String json(Object value) {
		try {
			return mapper.writeValueAsString(value);
		} catch (Exception e) {
			throw new IllegalStateException("Could not serialise a library recipe fragment", e);
		}
	}

	private static final RowMapper<MasterRecipeSummary> SUMMARY = (rs, n) -> new MasterRecipeSummary(
			rs.getObject("id", UUID.class),
			rs.getString("display_name"),
			rs.getString("subtitle"),
			rs.getString("category_name"),
			rs.getString("state"),
			rs.getString("badge"),
			rs.getInt("disambiguated_by") == 0,
			rs.getBoolean("already_added"));

	private RowMapper<MasterRecipeView> detailMapper() {
		return (rs, n) -> new MasterRecipeView(
				rs.getObject("id", UUID.class),
				rs.getString("name"),
				rs.getString("display_name"),
				rs.getString("subtitle"),
				rs.getString("category_key"),
				rs.getString("category_name"),
				rs.getString("state"),
				rs.getString("region"),
				rs.getString("badge"),
				rs.getString("yield_text"),
				rs.getBigDecimal("yield_qty"),
				rs.getString("yield_unit"),
				rs.getString("per_head_text"),
				rs.getBigDecimal("per_head_qty"),
				rs.getString("per_head_unit"),
				rs.getBigDecimal("indicative_cost"),
				rs.getString("why"),
				rs.getString("catering_note"),
				rs.getString("note_start"),
				rs.getString("note_vessel"),
				rs.getString("note_season"),
				list(rs.getArray("tags")),
				list(rs.getArray("serve_with")),
				ingredients(rs.getString("ingredients")),
				method(rs.getString("method")),
				rs.getString("source_ref"),
				rs.getBoolean("already_added"));
	}

	private static List<String> list(Array array) throws SQLException {
		if (array == null) {
			return List.of();
		}
		return List.of((String[]) array.getArray());
	}

	private List<MasterRecipeView.MasterRecipeIngredient> ingredients(String json) {
		List<Map<String, Object>> raw = read(json, new TypeReference<>() {
		});
		List<MasterRecipeView.MasterRecipeIngredient> out = new ArrayList<>();
		for (Map<String, Object> line : raw) {
			@SuppressWarnings("unchecked")
			Map<String, String> scaled = (Map<String, String>) line.get("scaled");
			out.add(new MasterRecipeView.MasterRecipeIngredient(
					String.valueOf(line.get("name")),
					String.valueOf(line.get("qty")),
					new BigDecimal(String.valueOf(line.get("qtyValue"))),
					String.valueOf(line.get("qtyUnit")),
					scaled));
		}
		return out;
	}

	private List<String> method(String json) {
		return read(json, new TypeReference<>() {
		});
	}

	private <T> T read(String json, TypeReference<T> type) {
		try {
			return mapper.readValue(json, type);
		} catch (Exception e) {
			throw new IllegalStateException("A library recipe's stored JSON could not be read", e);
		}
	}
}
