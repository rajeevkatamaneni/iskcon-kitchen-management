package org.iskcon.kms.recipe;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.ingredient.Unit;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recipes (E2-S2): create, edit, browse, and archive. Every action runs in the acting user's
 * tenant context, so RLS confines it to their own temple.
 *
 * <p>Two cross-references are validated in the application, not left to the foreign keys, because
 * an FK check runs as the table owner and is not subject to RLS — so a raw id from another temple
 * would otherwise slip through. The category and every ingredient are looked up through RLS first;
 * an id the tenant cannot see is simply rejected as unknown.
 *
 * <p>Recipes archive rather than delete <em>once they have been cooked</em> (a meal plan references
 * one; history must stay). One that has never been planned is deleted outright — see
 * {@link #delete} for why the two are not the same act. History must stay
 * renderable), and every edit bumps {@code version} so translation caches (E2-S6) invalidate.
 *
 * <p>Sattvic enforcement (E2-S4) hooked into {@link #create}/{@link #update} until 2026-09-08, when
 * D-18 deleted the ingredient flag it read. Nothing could carry the flag any more, so the block, its
 * Temple Admin override and the reason stored on the recipe all went with it — a badge that can only
 * ever read false is worse than no badge, because it looks like an answer.
 */
@Service
public class RecipeService {

	/** Validated to hold at festival scale without overflow or precision loss (E2-S3). */
	private static final BigDecimal MAX_TARGET_YIELD = BigDecimal.valueOf(50_000);

	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public RecipeService(JdbcTemplate jdbc, AuditService auditService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	@Transactional(readOnly = true)
	public List<RecipeSummary> list(UUID categoryId, UUID ingredientId, String query,
			boolean includeArchived, boolean ekadashiCompatibleOnly) {
		StringBuilder sql = new StringBuilder("""
				SELECT r.id, r.name, c.name AS category_name, c.fasting_compatible,
					   r.base_yield_qty, r.base_yield_unit, r.status,
					   r.yield_note, r.per_head_qty, r.per_head_unit,
					   (r.master_recipe_id IS NOT NULL) AS from_library
				FROM recipes r
				JOIN recipe_categories c ON c.id = r.category_id
				WHERE 1 = 1
				""");
		List<Object> args = new ArrayList<>();
		if (!includeArchived) {
			sql.append(" AND r.status = 'ACTIVE'");
		}
		if (categoryId != null) {
			sql.append(" AND r.category_id = ?");
			args.add(categoryId);
		}
		if (query != null && !query.isBlank()) {
			sql.append(" AND lower(r.name) LIKE ?");
			args.add("%" + escapeLike(query.trim().toLowerCase()) + "%");
		}
		if (ingredientId != null) {
			// "What can we make with X" reads the lines the other way.
			sql.append(" AND EXISTS (SELECT 1 FROM recipe_ingredients ri "
					+ "WHERE ri.recipe_id = r.id AND ri.ingredient_id = ?)");
			args.add(ingredientId);
		}
		if (ekadashiCompatibleOnly) {
			// Ekadashi-friendly: no line uses a grain/bean (E4-S6) — the picker filter on fasting days.
			sql.append(" AND NOT EXISTS (SELECT 1 FROM recipe_ingredients ri2 "
					+ "JOIN ingredients i2 ON i2.id = ri2.ingredient_id "
					+ "WHERE ri2.recipe_id = r.id AND i2.is_ekadashi_prohibited)");
		}
		sql.append(" ORDER BY r.name");
		return jdbc.query(sql.toString(), SUMMARY_MAPPER, args.toArray());
	}

	@Transactional(readOnly = true)
	public RecipeView get(UUID id) {
		RecipeView head = jdbc.query("""
				SELECT r.id, r.name, r.category_id, c.name AS category_name, c.fasting_compatible,
					   r.base_yield_qty, r.base_yield_unit, r.method, r.notes, r.region_tag,
					   r.status, r.version, r.created_at,
					   r.yield_note, r.per_head_qty, r.per_head_unit, r.subtitle, r.badge,
					   r.indicative_cost, r.why, r.catering_note, r.sub_region,
					   r.note_start, r.note_vessel, r.note_season, r.tags, r.serve_with,
					   r.master_recipe_id
				FROM recipes r
				JOIN recipe_categories c ON c.id = r.category_id
				WHERE r.id = ?
				""", HEAD_MAPPER, id).stream().findFirst()
				.orElseThrow(() -> notFound(id));

		List<RecipeIngredientView> lines = jdbc.query("""
				SELECT ri.ingredient_id, i.name AS ingredient_name, ri.quantity, ri.unit
				FROM recipe_ingredients ri
				JOIN ingredients i ON i.id = ri.ingredient_id
				WHERE ri.recipe_id = ?
				ORDER BY ri.line_order
				""", LINE_MAPPER, id);

		return withLines(head, lines);
	}

	/** The recipe scaled to a target yield (E2-S3). Computed on demand — no stored copy per scale. */
	@Transactional(readOnly = true)
	public ScaledRecipeView scale(UUID id, BigDecimal targetYield) {
		return scaleAll(List.of(new ScaleRequest(id, targetYield))).get(0);
	}

	/**
	 * Several recipes scaled in one pass — two statements for the whole batch, not two per recipe
	 * (T-141).
	 *
	 * <p><strong>Why this exists.</strong> {@link #scale} reads its recipe through {@link #get},
	 * which is two statements: the head row, then the ingredient lines. That is the right shape for a
	 * screen showing one recipe, and the wrong shape for the caller this was written for.
	 * {@code CommittedStockService} scales <em>every dish the plan intends to cook in the buying
	 * window</em> — a fortnight of three meals a day, plus whatever festival the horizon reaches — and
	 * asked one recipe at a time. Measured at the JDBC boundary on a temple with five years behind it,
	 * one load of the shopping-list screen sent <strong>348 statements from {@code get}</strong> out of
	 * 392 in total. Each one is cheap and none of that is visible on a local database; behind Cloud Run
	 * and Cloud SQL it is 348 round trips, and the round trip is the cost.
	 *
	 * <p><strong>The batch is over distinct recipes, and the requests are over recipe-and-yield
	 * pairs</strong>, because those are different sets: a temple cooks the same khichadi most days of
	 * the week, sometimes at 80 kg and sometimes at 140, and those are two genuinely different answers
	 * from one reading of the recipe. So the two statements below fetch each recipe once however many
	 * yields ask for it, and the scaling itself — which is arithmetic, not I/O — happens per request.
	 *
	 * <p><strong>{@link #scale} now goes through here rather than beside it.</strong> One recipe is a
	 * batch of one and costs the same two statements it always did, and there is one definition of
	 * what scaling a recipe means rather than two that could drift.
	 *
	 * @param requests recipe and target yield, one per answer wanted; the returned list is in the same
	 *                 order, so a caller may zip it back against its own input by position
	 * @throws ApplicationException {@code VALIDATION_FAILED} for a yield that is absent, not positive
	 *                              or beyond {@link #MAX_TARGET_YIELD}; {@code RESOURCE_NOT_FOUND} for
	 *                              a recipe this tenant cannot see. Both are thrown for exactly the
	 *                              cases {@link #scale} threw them for, and the yields are all checked
	 *                              before anything is read, so a bad yield is still refused without a
	 *                              database round trip.
	 */
	@Transactional(readOnly = true)
	public List<ScaledRecipeView> scaleAll(List<ScaleRequest> requests) {
		for (ScaleRequest request : requests) {
			BigDecimal targetYield = request.targetYield();
			if (targetYield == null || targetYield.signum() <= 0) {
				throw new ApplicationException(
						ErrorCode.VALIDATION_FAILED,
						Map.of("field", "targetYield", "value", String.valueOf(targetYield)));
			}
			if (targetYield.compareTo(MAX_TARGET_YIELD) > 0) {
				throw new ApplicationException(
						ErrorCode.VALIDATION_FAILED,
						Map.of("field", "targetYield", "max", MAX_TARGET_YIELD.toPlainString()));
			}
		}
		if (requests.isEmpty()) {
			return List.of();
		}

		Set<UUID> ids = new LinkedHashSet<>();
		for (ScaleRequest request : requests) {
			ids.add(request.recipeId());
		}
		Map<UUID, ScalableRecipe> loaded = loadForScaling(ids);

		List<ScaledRecipeView> out = new ArrayList<>(requests.size());
		for (ScaleRequest request : requests) {
			ScalableRecipe recipe = loaded.get(request.recipeId());
			if (recipe == null) {
				throw notFound(request.recipeId());
			}
			BigDecimal ratio = RecipeScaler.ratio(recipe.baseYieldQty(), request.targetYield());

			List<ScaledLine> scaled = new ArrayList<>();
			for (RecipeIngredientView line : recipe.ingredients()) {
				ScaledQuantity q = RecipeScaler.scale(line.quantity(), Unit.valueOf(line.unit()), ratio);
				scaled.add(new ScaledLine(line.ingredientId(), line.ingredientName(),
						q.rawQuantity(), q.rawUnit(), q.displayQuantity(), q.displayUnit()));
			}

			out.add(new ScaledRecipeView(recipe.id(), recipe.name(), recipe.baseYieldQty(),
					recipe.baseYieldUnit(), request.targetYield(), ratio, scaled));
		}
		return out;
	}

	/**
	 * One recipe to scale, and the yield to scale it to.
	 *
	 * <p>Nested rather than given its own file for the reason {@code CommittedStockService.MealClaim}
	 * is: it says nothing on its own, and reads as an argument to {@link #scaleAll}.
	 */
	public record ScaleRequest(UUID recipeId, BigDecimal targetYield) {
	}

	@Transactional
	public UUID create(AuthenticatedUser actor, CreateRecipeRequest request) {
		Unit yieldUnit = parseYieldUnit(request.baseYieldUnit());
		resolveCategory(request.categoryId());
		resolveIngredients(request.ingredients());

		UUID id = UUID.randomUUID();
		try {
			jdbc.update("""
					INSERT INTO recipes (id, tenant_id, name, category_id, base_yield_qty,
							base_yield_unit, method, notes, region_tag,
							yield_note, per_head_qty, per_head_unit, subtitle, badge, indicative_cost,
							why, catering_note, sub_region, note_start, note_vessel, note_season,
							tags, serve_with, status, version)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?, ?,
							?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
							CAST(? AS text[]), CAST(? AS text[]), 'ACTIVE', 1)
					""",
					id, request.name().trim(), request.categoryId(), request.baseYieldQty(),
					yieldUnit.name(), request.method(), request.notes(), request.regionTag(),
					request.yieldNote(), request.perHeadQty(), request.perHeadUnit(),
					request.subtitle(), request.badge(), request.indicativeCost(),
					request.why(), request.cateringNote(), request.subRegion(),
					request.noteStart(), request.noteVessel(), request.noteSeason(),
					pgArray(request.tags()), pgArray(request.serveWith()));
		} catch (DuplicateKeyException e) {
			throw new ApplicationException(
					ErrorCode.RECIPE_ALREADY_EXISTS, Map.of("name", request.name()), e);
		}

		insertLines(id, request.ingredients());

		auditService.record(actor, AuditAction.RECIPE_CREATED, AuditEntityType.RECIPE, id,
				null, recipeSnapshot(request.name().trim(), yieldUnit, request.ingredients().size()), null);
		return id;
	}

	@Transactional
	public void update(AuthenticatedUser actor, UUID id, UpdateRecipeRequest request) {
		Unit yieldUnit = parseYieldUnit(request.baseYieldUnit());
		resolveCategory(request.categoryId());
		resolveIngredients(request.ingredients());

		RecipeView before = get(id);

		try {
			int updated = jdbc.update("""
					UPDATE recipes
					SET name = ?, category_id = ?, base_yield_qty = ?, base_yield_unit = ?,
						method = ?, notes = ?, region_tag = ?,
						yield_note = ?, per_head_qty = ?, per_head_unit = ?, subtitle = ?, badge = ?,
						indicative_cost = ?, why = ?, catering_note = ?, sub_region = ?,
						note_start = ?, note_vessel = ?, note_season = ?,
						tags = CAST(? AS text[]), serve_with = CAST(? AS text[]),
						version = version + 1, updated_at = now()
					WHERE id = ? AND status = 'ACTIVE'
					""",
					request.name().trim(), request.categoryId(), request.baseYieldQty(),
					yieldUnit.name(), request.method(), request.notes(), request.regionTag(),
					request.yieldNote(), request.perHeadQty(), request.perHeadUnit(),
					request.subtitle(), request.badge(), request.indicativeCost(),
					request.why(), request.cateringNote(), request.subRegion(),
					request.noteStart(), request.noteVessel(), request.noteSeason(),
					pgArray(request.tags()), pgArray(request.serveWith()), id);
			if (updated == 0) {
				throw notFound(id);
			}
		} catch (DuplicateKeyException e) {
			throw new ApplicationException(
					ErrorCode.RECIPE_ALREADY_EXISTS, Map.of("name", request.name()), e);
		}

		jdbc.update("DELETE FROM recipe_ingredients WHERE recipe_id = ?", id);
		insertLines(id, request.ingredients());

		auditService.record(actor, AuditAction.RECIPE_UPDATED, AuditEntityType.RECIPE, id,
				recipeSnapshot(before.name(), Unit.valueOf(before.baseYieldUnit()), before.ingredients().size()),
				recipeSnapshot(request.name().trim(), yieldUnit, request.ingredients().size()), null);
	}

	@Transactional
	public void archive(AuthenticatedUser actor, UUID id) {
		RecipeView before = get(id);
		if ("ARCHIVED".equals(before.status())) {
			return;
		}
		jdbc.update("UPDATE recipes SET status = 'ARCHIVED', updated_at = now() WHERE id = ?", id);
		auditService.record(actor, AuditAction.RECIPE_ARCHIVED, AuditEntityType.RECIPE, id,
				Map.of("status", "ACTIVE"), Map.of("status", "ARCHIVED"), null);
	}

	/** Brings an archived recipe back, so archiving is a decision and not a one-way door. */
	@Transactional
	public void restore(AuthenticatedUser actor, UUID id) {
		RecipeView before = get(id);
		if ("ACTIVE".equals(before.status())) {
			return;
		}
		jdbc.update("UPDATE recipes SET status = 'ACTIVE', updated_at = now() WHERE id = ?", id);
		auditService.record(actor, AuditAction.RECIPE_RESTORED, AuditEntityType.RECIPE, id,
				Map.of("status", "ARCHIVED"), Map.of("status", "ACTIVE"), null);
	}

	/**
	 * Removes a recipe outright — but only one that has never been cooked.
	 *
	 * <p>Two different things get called "delete", and conflating them is how a temple loses its
	 * history. A recipe somebody typed twice, or misspelled, or was trying the form out with, is
	 * genuinely rubbish and should leave without a trace. A recipe that has fed the hall is part of
	 * the record of what was served, and {@code meal_plans.recipe_id} is {@code ON DELETE RESTRICT}
	 * precisely so that record cannot be quietly hollowed out.
	 *
	 * <p>So the system decides which one this is, rather than asking. Never planned, never cooked:
	 * it goes. Otherwise the caller is refused and told to archive, which takes it out of the
	 * planner while leaving every meal that named it still able to say what it was.
	 *
	 * <p>Everything a recipe owns — its ingredient lines, its translations, its generated cards —
	 * is {@code ON DELETE CASCADE} and goes with it. That is right: none of them means anything
	 * without the recipe, and a card can be produced again from a recipe that still exists.
	 */
	@Transactional
	public void delete(AuthenticatedUser actor, UUID id) {
		RecipeView before = get(id);

		Integer planned = jdbc.queryForObject(
				"SELECT count(*) FROM meal_plans WHERE recipe_id = ?", Integer.class, id);
		if (planned != null && planned > 0) {
			throw new ApplicationException(ErrorCode.RECIPE_IN_USE,
					Map.of("recipeId", id, "mealPlans", planned));
		}

		// Audited before the row goes, so the entry describes something that still exists to be
		// described. The after-state is deliberately null: there is no after.
		auditService.record(actor, AuditAction.RECIPE_DELETED, AuditEntityType.RECIPE, id,
				recipeSnapshot(before.name(), Unit.valueOf(before.baseYieldUnit()),
						before.ingredients().size()),
				null, "Never planned, so nothing references it.");

		jdbc.update("DELETE FROM recipes WHERE id = ?", id);
	}

	// ---------------------------------------------------------------------

	/**
	 * Every named recipe, with its ingredient lines, in two statements — the head rows, then all of
	 * their lines at once.
	 *
	 * <p><strong>Only the columns scaling actually uses.</strong> {@link #get} selects the whole
	 * recipe because it answers a recipe screen; scaling needs the name, the base yield and the lines,
	 * and nothing here should be mistaken for a {@link RecipeView} that happens to be missing most of
	 * itself. Hence its own small type rather than a half-filled one.
	 *
	 * <p><strong>{@code = ANY(?)} is still fully RLS-scoped.</strong> The policy on {@code recipes}
	 * qualifies this read exactly as it qualifies the single-row form, so an id belonging to another
	 * temple simply does not come back and the caller reads that as not found — which is the same
	 * answer {@link #get} gives, by the same mechanism.
	 *
	 * <p>A recipe with no ingredient lines comes back present and empty rather than absent: it exists,
	 * it can be scaled, and what it scales to is nothing. That is the same thing {@link #get} says
	 * about it.
	 */
	private Map<UUID, ScalableRecipe> loadForScaling(Set<UUID> ids) {
		Object[] idArray = ids.toArray();

		Map<UUID, List<RecipeIngredientView>> linesByRecipe = new LinkedHashMap<>();
		for (UUID id : ids) {
			linesByRecipe.put(id, new ArrayList<>());
		}

		// Ordered by recipe and then by line_order, so each recipe's lines arrive in the order the
		// recipe states them — the order get() returns them in, and the order a job card prints.
		jdbc.query(connection -> {
			var ps = connection.prepareStatement("""
					SELECT ri.recipe_id, ri.ingredient_id, i.name AS ingredient_name, ri.quantity, ri.unit
					FROM recipe_ingredients ri
					JOIN ingredients i ON i.id = ri.ingredient_id
					WHERE ri.recipe_id = ANY(?)
					ORDER BY ri.recipe_id, ri.line_order
					""");
			ps.setArray(1, connection.createArrayOf("uuid", idArray));
			return ps;
		}, (rs, rowNum) -> {
			UUID recipeId = rs.getObject("recipe_id", UUID.class);
			List<RecipeIngredientView> lines = linesByRecipe.get(recipeId);
			if (lines != null) {
				lines.add(LINE_MAPPER.mapRow(rs, rowNum));
			}
			return recipeId;
		});

		Map<UUID, ScalableRecipe> out = new LinkedHashMap<>();
		jdbc.query(connection -> {
			var ps = connection.prepareStatement("""
					SELECT r.id, r.name, r.base_yield_qty, r.base_yield_unit
					FROM recipes r
					WHERE r.id = ANY(?)
					""");
			ps.setArray(1, connection.createArrayOf("uuid", idArray));
			return ps;
		}, (rs, rowNum) -> {
			UUID id = rs.getObject("id", UUID.class);
			out.put(id, new ScalableRecipe(id, rs.getString("name"),
					rs.getBigDecimal("base_yield_qty"), rs.getString("base_yield_unit"),
					linesByRecipe.getOrDefault(id, List.of())));
			return id;
		});
		return out;
	}

	/** As much of a recipe as scaling it needs, and deliberately no more. */
	private record ScalableRecipe(UUID id, String name, BigDecimal baseYieldQty, String baseYieldUnit,
			List<RecipeIngredientView> ingredients) {
	}

	private void insertLines(UUID recipeId, List<RecipeIngredientLine> lines) {
		int order = 0;
		for (RecipeIngredientLine line : lines) {
			jdbc.update("""
					INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
					VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?)
					""",
					recipeId, line.ingredientId(), line.quantity(),
					Unit.valueOf(line.unit()).name(), order++);
		}
	}

	/** Confirms the category is one this tenant can see; returns nothing but throws if not. */
	private void resolveCategory(UUID categoryId) {
		Integer found = jdbc.queryForObject(
				"SELECT count(*) FROM recipe_categories WHERE id = ?", Integer.class, categoryId);
		if (found == null || found == 0) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED, Map.of("field", "categoryId", "value", categoryId));
		}
	}

	/**
	 * Checks the ingredient lines: units are known, and every referenced ingredient is one this
	 * tenant can actually see (RLS) — which also rejects a raw id borrowed from another temple.
	 *
	 * <p>It also refuses a supply. LPG, leaf plates and dishwashing liquid share the catalogue with
	 * food (D-1) because they share the whole of its lifecycle — bought, received, stored, issued —
	 * and a recipe is the single place the two part company: a mop is not an ingredient of anything.
	 * The recipe picker on the client already hides them, and this is here anyway because a picker is
	 * not a guard: a raw POST, an import, or a screen built later never goes through it. Named in the
	 * detail so the log says which line was the problem; the person only ever sees KMS-400127.
	 *
	 * <p>Returns nothing. It used to hand back each ingredient's name and dietary flag for the block
	 * D-18 removed; what it still has to say — "these all exist here, and none of them is a mop" — it
	 * says by not throwing.
	 */
	private void resolveIngredients(List<RecipeIngredientLine> lines) {
		for (RecipeIngredientLine line : lines) {
			parseUnit(line.unit());
		}
		Set<UUID> requested = new LinkedHashSet<>();
		for (RecipeIngredientLine line : lines) {
			requested.add(line.ingredientId());
		}
		record Candidate(UUID id, String name, boolean supply) {
		}
		List<Candidate> found = jdbc.query(connection -> {
			var ps = connection.prepareStatement(
					"SELECT id, name, is_supply FROM ingredients WHERE id = ANY(?)");
			ps.setArray(1, connection.createArrayOf("uuid", requested.toArray()));
			return ps;
		}, (rs, rowNum) -> new Candidate(
				rs.getObject("id", UUID.class), rs.getString("name"), rs.getBoolean("is_supply")));

		if (found.size() != requested.size()) {
			Set<UUID> missing = new LinkedHashSet<>(requested);
			found.forEach(c -> missing.remove(c.id()));
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED,
					Map.of("field", "ingredients", "unknownIngredientIds", missing.toString()));
		}

		List<String> supplies = found.stream().filter(Candidate::supply).map(Candidate::name).toList();
		if (!supplies.isEmpty()) {
			throw new ApplicationException(
					ErrorCode.NOT_A_FOOD_INGREDIENT,
					Map.of("field", "ingredients", "supplies", String.join(", ", supplies)));
		}
	}

	private Unit parseYieldUnit(String unit) {
		try {
			return Unit.valueOf(unit);
		} catch (IllegalArgumentException e) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED, Map.of("field", "baseYieldUnit", "value", unit), e);
		}
	}

	private Unit parseUnit(String unit) {
		try {
			return Unit.valueOf(unit);
		} catch (IllegalArgumentException e) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED, Map.of("field", "unit", "value", unit), e);
		}
	}

	private Map<String, Object> recipeSnapshot(String name, Unit yieldUnit, int lineCount) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("name", name);
		snapshot.put("baseYieldUnit", yieldUnit.name());
		snapshot.put("ingredientLineCount", lineCount);
		return snapshot;
	}

	private ApplicationException notFound(UUID id) {
		return new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("recipeId", id));
	}

	private static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static RecipeView withLines(RecipeView head, List<RecipeIngredientView> lines) {
		return new RecipeView(head.id(), head.name(), head.categoryId(), head.categoryName(),
				head.fastingCompatible(), head.baseYieldQty(), head.baseYieldUnit(), head.method(),
				head.notes(), head.regionTag(),
				head.yieldNote(), head.perHeadQty(), head.perHeadUnit(), head.subtitle(), head.badge(),
				head.indicativeCost(), head.why(), head.cateringNote(), head.subRegion(),
				head.noteStart(), head.noteVessel(), head.noteSeason(), head.tags(), head.serveWith(),
				head.masterRecipeId(),
				head.status(), head.version(), lines, head.createdAt());
	}

	/**
	 * A {@code text[]} literal, quoted and escaped — the driver will not take a bare String[]
	 * through JdbcTemplate's varargs, and every element is quoted so a comma in a name cannot end
	 * the array early.
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
			out.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
		}
		return out.append('}').toString();
	}

	/** A {@code text[]} column as a list, empty where the column is null. */
	private static List<String> textArray(java.sql.Array array) throws java.sql.SQLException {
		return array == null ? List.of() : List.of((String[]) array.getArray());
	}

	private static final RowMapper<RecipeSummary> SUMMARY_MAPPER = (rs, rowNum) -> new RecipeSummary(
			rs.getObject("id", UUID.class),
			rs.getString("name"),
			rs.getString("category_name"),
			rs.getBoolean("fasting_compatible"),
			rs.getBigDecimal("base_yield_qty"),
			rs.getString("base_yield_unit"),
			rs.getString("status"),
			rs.getString("yield_note"),
			rs.getBigDecimal("per_head_qty"),
			rs.getString("per_head_unit"),
			rs.getBoolean("from_library"));

	private static final RowMapper<RecipeView> HEAD_MAPPER = (rs, rowNum) -> new RecipeView(
			rs.getObject("id", UUID.class),
			rs.getString("name"),
			rs.getObject("category_id", UUID.class),
			rs.getString("category_name"),
			rs.getBoolean("fasting_compatible"),
			rs.getBigDecimal("base_yield_qty"),
			rs.getString("base_yield_unit"),
			rs.getString("method"),
			rs.getString("notes"),
			rs.getString("region_tag"),
			rs.getString("yield_note"),
			rs.getBigDecimal("per_head_qty"),
			rs.getString("per_head_unit"),
			rs.getString("subtitle"),
			rs.getString("badge"),
			rs.getBigDecimal("indicative_cost"),
			rs.getString("why"),
			rs.getString("catering_note"),
			rs.getString("sub_region"),
			rs.getString("note_start"),
			rs.getString("note_vessel"),
			rs.getString("note_season"),
			textArray(rs.getArray("tags")),
			textArray(rs.getArray("serve_with")),
			rs.getObject("master_recipe_id", UUID.class),
			rs.getString("status"),
			rs.getInt("version"),
			List.of(),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());

	private static final RowMapper<RecipeIngredientView> LINE_MAPPER = (rs, rowNum) -> new RecipeIngredientView(
			rs.getObject("ingredient_id", UUID.class),
			rs.getString("ingredient_name"),
			rs.getBigDecimal("quantity"),
			rs.getString("unit"));
}
