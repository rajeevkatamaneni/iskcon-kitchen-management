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
import org.iskcon.kms.auth.Permission;
import org.iskcon.kms.auth.RolePermissions;
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
 * Sattvic enforcement (E2-S4) hooks into {@link #create}/{@link #update}.
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
					   (r.sattvic_override_reason IS NOT NULL) AS overridden
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
					   r.status, r.sattvic_override_reason, r.version, r.created_at
				FROM recipes r
				JOIN recipe_categories c ON c.id = r.category_id
				WHERE r.id = ?
				""", HEAD_MAPPER, id).stream().findFirst()
				.orElseThrow(() -> notFound(id));

		List<RecipeIngredientView> lines = jdbc.query("""
				SELECT ri.ingredient_id, i.name AS ingredient_name, ri.quantity, ri.unit,
					   i.is_sattvic_prohibited
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
		if (targetYield == null || targetYield.signum() <= 0) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED, Map.of("field", "targetYield", "value", String.valueOf(targetYield)));
		}
		if (targetYield.compareTo(MAX_TARGET_YIELD) > 0) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED,
					Map.of("field", "targetYield", "max", MAX_TARGET_YIELD.toPlainString()));
		}

		RecipeView recipe = get(id);
		BigDecimal ratio = RecipeScaler.ratio(recipe.baseYieldQty(), targetYield);

		List<ScaledLine> scaled = new ArrayList<>();
		for (RecipeIngredientView line : recipe.ingredients()) {
			ScaledQuantity q = RecipeScaler.scale(line.quantity(), Unit.valueOf(line.unit()), ratio);
			scaled.add(new ScaledLine(line.ingredientId(), line.ingredientName(),
					q.rawQuantity(), q.rawUnit(), q.displayQuantity(), q.displayUnit(),
					line.sattvicProhibited()));
		}

		return new ScaledRecipeView(recipe.id(), recipe.name(), recipe.baseYieldQty(),
				recipe.baseYieldUnit(), targetYield, ratio, scaled);
	}

	@Transactional
	public UUID create(AuthenticatedUser actor, CreateRecipeRequest request) {
		YieldUnit yieldUnit = parseYieldUnit(request.baseYieldUnit());
		resolveCategory(request.categoryId());
		List<IngredientRef> refs = resolveIngredients(request.ingredients());
		String override = applySattvicEnforcement(actor, refs, request.sattvicOverrideReason());

		UUID id = UUID.randomUUID();
		try {
			jdbc.update("""
					INSERT INTO recipes (id, tenant_id, name, category_id, base_yield_qty,
							base_yield_unit, method, notes, region_tag, sattvic_override_reason, status, version)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1)
					""",
					id, request.name().trim(), request.categoryId(), request.baseYieldQty(),
					yieldUnit.name(), request.method(), request.notes(), request.regionTag(), override);
		} catch (DuplicateKeyException e) {
			throw new ApplicationException(
					ErrorCode.RECIPE_ALREADY_EXISTS, Map.of("name", request.name()), e);
		}

		insertLines(id, request.ingredients());

		auditService.record(actor, AuditAction.RECIPE_CREATED, AuditEntityType.RECIPE, id,
				null, recipeSnapshot(request.name().trim(), yieldUnit, request.ingredients().size()), null);
		if (override != null) {
			auditSattvicOverride(actor, id, refs, override);
		}
		return id;
	}

	@Transactional
	public void update(AuthenticatedUser actor, UUID id, UpdateRecipeRequest request) {
		YieldUnit yieldUnit = parseYieldUnit(request.baseYieldUnit());
		resolveCategory(request.categoryId());
		List<IngredientRef> refs = resolveIngredients(request.ingredients());
		String override = applySattvicEnforcement(actor, refs, request.sattvicOverrideReason());

		RecipeView before = get(id);

		try {
			int updated = jdbc.update("""
					UPDATE recipes
					SET name = ?, category_id = ?, base_yield_qty = ?, base_yield_unit = ?,
						method = ?, notes = ?, region_tag = ?, sattvic_override_reason = ?,
						version = version + 1, updated_at = now()
					WHERE id = ? AND status = 'ACTIVE'
					""",
					request.name().trim(), request.categoryId(), request.baseYieldQty(),
					yieldUnit.name(), request.method(), request.notes(), request.regionTag(), override, id);
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
				recipeSnapshot(before.name(), YieldUnit.valueOf(before.baseYieldUnit()), before.ingredients().size()),
				recipeSnapshot(request.name().trim(), yieldUnit, request.ingredients().size()), null);
		if (override != null) {
			auditSattvicOverride(actor, id, refs, override);
		}
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
				recipeSnapshot(before.name(), YieldUnit.valueOf(before.baseYieldUnit()),
						before.ingredients().size()),
				null, "Never planned, so nothing references it.");

		jdbc.update("DELETE FROM recipes WHERE id = ?", id);
	}

	// ---------------------------------------------------------------------

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

	private record IngredientRef(UUID id, String name, boolean prohibited) {
	}

	/**
	 * Resolves the ingredient lines: units are known, and every referenced ingredient is one this
	 * tenant can actually see (RLS) — which also rejects a raw id borrowed from another temple.
	 * Returns each ingredient's name and prohibited flag, for sattvic enforcement.
	 */
	private List<IngredientRef> resolveIngredients(List<RecipeIngredientLine> lines) {
		for (RecipeIngredientLine line : lines) {
			parseUnit(line.unit());
		}
		Set<UUID> requested = new LinkedHashSet<>();
		for (RecipeIngredientLine line : lines) {
			requested.add(line.ingredientId());
		}
		List<IngredientRef> found = jdbc.query(connection -> {
			var ps = connection.prepareStatement(
					"SELECT id, name, is_sattvic_prohibited FROM ingredients WHERE id = ANY(?)");
			ps.setArray(1, connection.createArrayOf("uuid", requested.toArray()));
			return ps;
		}, (rs, rowNum) -> new IngredientRef(
				rs.getObject("id", UUID.class), rs.getString("name"),
				rs.getBoolean("is_sattvic_prohibited")));

		if (found.size() != requested.size()) {
			Set<UUID> missing = new LinkedHashSet<>(requested);
			found.forEach(ref -> missing.remove(ref.id()));
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED,
					Map.of("field", "ingredients", "unknownIngredientIds", missing.toString()));
		}
		return found;
	}

	/**
	 * Sattvic enforcement (E2-S4). A recipe containing a prohibited ingredient is hard-blocked in
	 * this one service-layer place — never UI-only. The single escape is a Temple Admin
	 * (OVERRIDE_SATTVIC_ENFORCEMENT) supplying a reason; the override is then persisted (badging the
	 * recipe) and audited by the caller.
	 *
	 * @return the reason to store on the recipe: the trimmed override when one applies, or null —
	 *     which also clears any prior override once the prohibited ingredient is removed.
	 */
	private String applySattvicEnforcement(
			AuthenticatedUser actor, List<IngredientRef> refs, String overrideReason) {
		List<String> prohibited = refs.stream()
				.filter(IngredientRef::prohibited)
				.map(IngredientRef::name)
				.toList();
		if (prohibited.isEmpty()) {
			return null;
		}
		boolean hasReason = overrideReason != null && !overrideReason.isBlank();
		boolean canOverride =
				RolePermissions.forRole(actor.getRole()).contains(Permission.OVERRIDE_SATTVIC_ENFORCEMENT);
		if (canOverride && hasReason) {
			return overrideReason.trim();
		}
		throw new ApplicationException(
				ErrorCode.SATTVIC_INGREDIENT_BLOCKED, Map.of("ingredients", prohibited.toString()));
	}

	private void auditSattvicOverride(
			AuthenticatedUser actor, UUID recipeId, List<IngredientRef> refs, String reason) {
		List<String> prohibited = refs.stream()
				.filter(IngredientRef::prohibited)
				.map(IngredientRef::name)
				.toList();
		auditService.record(actor, AuditAction.RECIPE_SATTVIC_OVERRIDDEN, AuditEntityType.RECIPE,
				recipeId, null,
				Map.of("prohibitedIngredients", prohibited.toString(), "reason", reason), reason);
	}

	private YieldUnit parseYieldUnit(String unit) {
		try {
			return YieldUnit.valueOf(unit);
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

	private Map<String, Object> recipeSnapshot(String name, YieldUnit yieldUnit, int lineCount) {
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
				head.notes(), head.regionTag(), head.status(), head.sattvicOverrideReason(),
				head.version(), lines, head.createdAt());
	}

	private static final RowMapper<RecipeSummary> SUMMARY_MAPPER = (rs, rowNum) -> new RecipeSummary(
			rs.getObject("id", UUID.class),
			rs.getString("name"),
			rs.getString("category_name"),
			rs.getBoolean("fasting_compatible"),
			rs.getBigDecimal("base_yield_qty"),
			rs.getString("base_yield_unit"),
			rs.getString("status"),
			rs.getBoolean("overridden"));

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
			rs.getString("status"),
			rs.getString("sattvic_override_reason"),
			rs.getInt("version"),
			List.of(),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());

	private static final RowMapper<RecipeIngredientView> LINE_MAPPER = (rs, rowNum) -> new RecipeIngredientView(
			rs.getObject("ingredient_id", UUID.class),
			rs.getString("ingredient_name"),
			rs.getBigDecimal("quantity"),
			rs.getString("unit"),
			rs.getBoolean("is_sattvic_prohibited"));
}
