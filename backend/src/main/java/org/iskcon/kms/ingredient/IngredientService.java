package org.iskcon.kms.ingredient;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.auth.Permission;
import org.iskcon.kms.auth.RolePermissions;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ingredient catalogue (E2-S1). Every action runs in the acting user's tenant context, so RLS
 * confines it to their own temple — an ingredient in another temple is simply not found.
 *
 * <p>Descriptive editing (name, category, unit, aliases) is ordinary kitchen work behind
 * {@code MANAGE_RECIPES}. The Ekadashi-prohibited flag is a religious-compliance decision, so it
 * moves only through {@link #setEkadashiFlag} — a Temple Admin (MANAGE_DIETARY_POLICY), always
 * audited. Setting the flag true at creation is the same decision, so it is refused here for
 * anyone who lacks that permission.
 *
 * <p>A sattvic-prohibited flag stood beside the Ekadashi one until 2026-09-08, when D-18 deleted it.
 * It only ever marked rows that provisioning inserted so that it could mark them — onion, garlic,
 * mushroom, egg — and with that seed gone there was nothing left for it to guard.
 *
 * <p>The supply flag (D-1) reads like a dietary flag and is governed like a name. LPG, leaf plates
 * and dishwashing liquid are bought, received, stored and issued exactly as food is, so they live in
 * this catalogue rather than in a second one, and this service treats the flag as an ordinary
 * descriptive field: set at creation, edited by {@link #update}, with no endpoint of its own and no
 * second permission. Nothing here filters on it either — supplies are meant to keep appearing in the
 * inventory, ingredient-request, purchase-order, donation and vendor-supplies pickers, which is the
 * whole reason D-1 refused a parallel table. The one place a supply is turned away is a recipe, and
 * that refusal belongs to {@code RecipeService}, not here.
 */
@Service
public class IngredientService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public IngredientService(JdbcTemplate jdbc, AuditService auditService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	@Transactional(readOnly = true)
	public List<IngredientView> list() {
		return jdbc.query("""
				SELECT id, name, category, canonical_unit, is_ekadashi_prohibited, is_supply,
						library_derived, aliases, created_at
				FROM ingredients ORDER BY name
				""", VIEW_MAPPER);
	}

	/**
	 * How many ingredients in this temple's catalogue a recipe import created and nobody has saved
	 * since (T-119).
	 *
	 * <p>Its own query rather than a count over {@link #list()}, because the screen that needs it
	 * most has no other business with the catalogue: {@code /recipes} is where an import is started
	 * from, and fetching several hundred ingredient rows there to arrive at one integer would be a
	 * page-load's worth of work for a sentence. The ingredients screen holds the list already and
	 * counts what it is holding.
	 *
	 * <p>RLS scopes it to the acting user's temple, so the number is this temple's own.
	 */
	@Transactional(readOnly = true)
	public int countLibraryDerived() {
		Integer count = jdbc.queryForObject(
				"SELECT count(*) FROM ingredients WHERE library_derived", Integer.class);
		return count == null ? 0 : count;
	}

	/** Name/alias prefix typeahead for recipe and inventory pickers. RLS scopes it to the tenant. */
	@Transactional(readOnly = true)
	public List<IngredientSummary> search(String query) {
		String prefix = query == null ? "" : query.trim().toLowerCase();
		if (prefix.isEmpty()) {
			return jdbc.query("""
					SELECT id, name, category, canonical_unit, is_supply
					FROM ingredients ORDER BY name LIMIT 20
					""", SUMMARY_MAPPER);
		}
		String like = escapeLike(prefix) + "%";
		return jdbc.query("""
				SELECT id, name, category, canonical_unit, is_supply
				FROM ingredients
				WHERE lower(name) LIKE ?
				   OR EXISTS (SELECT 1 FROM unnest(aliases) a WHERE lower(a) LIKE ?)
				ORDER BY name LIMIT 20
				""", SUMMARY_MAPPER, like, like);
	}

	@Transactional(readOnly = true)
	public IngredientView get(UUID id) {
		return findById(id).orElseThrow(() -> notFound(id));
	}

	@Transactional
	public UUID create(AuthenticatedUser actor, CreateIngredientRequest request) {
		Unit unit = parseUnit(request.unit());
		if (request.ekadashiProhibited() && !canManageDietaryPolicy(actor)) {
			// Marking an ingredient Ekadashi-prohibited is the same religious-compliance decision as
			// flipping the flag later, so it needs the same authority.
			throw new ApplicationException(
					ErrorCode.NOT_PERMITTED, Map.of("field", "ekadashiProhibited"));
		}
		List<String> aliases = normalizeAliases(request.aliases());
		UUID id = UUID.randomUUID();

		try {
			jdbc.update(connection -> {
				var ps = connection.prepareStatement("""
						INSERT INTO ingredients (
							id, tenant_id, name, category, canonical_unit, is_ekadashi_prohibited,
							is_supply, aliases)
						VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?)
						""");
				ps.setObject(1, id);
				ps.setString(2, request.name().trim());
				ps.setString(3, request.category().trim());
				ps.setString(4, unit.name());
				ps.setBoolean(5, request.ekadashiProhibited());
				ps.setBoolean(6, request.supply());
				ps.setArray(7, connection.createArrayOf("text", aliases.toArray()));
				return ps;
			});
		} catch (DuplicateKeyException e) {
			throw new ApplicationException(
					ErrorCode.INGREDIENT_ALREADY_EXISTS, Map.of("name", request.name()), e);
		}

		auditService.record(actor, AuditAction.INGREDIENT_ADDED, AuditEntityType.INGREDIENT, id,
				null, snapshot(request.name().trim(), request.category().trim(), unit,
						request.ekadashiProhibited(), request.supply(), aliases),
				null);
		return id;
	}

	/**
	 * Edits an ingredient's descriptive fields — and, in the same statement, clears
	 * {@code library_derived} (T-119).
	 *
	 * <p>Saving an edit <em>is</em> the review. A recipe import creates ingredients silently, and
	 * the catalogue now labels those rows and offers a filter over them; without a way of clearing
	 * the mark that filter is a list that only grows, and a list that only grows is one nobody opens
	 * a second time. Somebody who has opened an ingredient, looked at its category and unit, and
	 * pressed Save has done the only reviewing there is to do here, so no second button and no
	 * second concept is put on the screen to record it.
	 *
	 * <p><strong>It is cleared whether or not anything changed</strong>, deliberately, and this is
	 * settled rather than an oversight (Rajeev, 2026-09-10). Comparing the request against the row
	 * to decide whether the save "counted" means deciding which fields are worth counting, which is
	 * a second concept in the code for a case that barely arises — somebody who opens a row and
	 * saves it unchanged has still looked at it, which is the whole of what the mark asks for.
	 *
	 * <p>The Ekadashi flag's endpoint is deliberately <em>not</em> a second clearing path. It writes
	 * one religious-compliance flag from a one-click toggle on the row, without opening the row or
	 * showing anybody its category and unit — the very fields the import guessed — and it returns
	 * early when the flag is already what was asked for, so a click that clears the mark and a click
	 * that does not would look identical. Editing is the act that means the row was read.
	 */
	@Transactional
	public void update(AuthenticatedUser actor, UUID id, UpdateIngredientRequest request) {
		Unit unit = parseUnit(request.unit());
		IngredientView before = findById(id).orElseThrow(() -> notFound(id));
		List<String> aliases = normalizeAliases(request.aliases());

		try {
			jdbc.update(connection -> {
				var ps = connection.prepareStatement("""
						UPDATE ingredients
						SET name = ?, category = ?, canonical_unit = ?, is_supply = ?, aliases = ?,
							library_derived = false, updated_at = now()
						WHERE id = ?
						""");
				ps.setString(1, request.name().trim());
				ps.setString(2, request.category().trim());
				ps.setString(3, unit.name());
				// Written on every edit rather than only when it changed: the request carries a
				// primitive, so the value the form was showing is the value that comes back, and a
				// supply that stayed a supply says so again instead of falling back to the
				// permissive default.
				ps.setBoolean(4, request.supply());
				ps.setArray(5, connection.createArrayOf("text", aliases.toArray()));
				ps.setObject(6, id);
				return ps;
			});
		} catch (DuplicateKeyException e) {
			throw new ApplicationException(
					ErrorCode.INGREDIENT_ALREADY_EXISTS, Map.of("name", request.name()), e);
		}

		auditService.record(actor, AuditAction.INGREDIENT_UPDATED, AuditEntityType.INGREDIENT, id,
				snapshot(before.name(), before.category(), Unit.valueOf(before.unit()),
						before.ekadashiProhibited(), before.supply(), before.aliases()),
				snapshot(request.name().trim(), request.category().trim(), unit,
						before.ekadashiProhibited(), request.supply(), aliases),
				null);
	}

	/** Sets or clears the Ekadashi-prohibited flag. Temple Admin only (checked at the endpoint). */
	@Transactional
	public void setEkadashiFlag(AuthenticatedUser actor, UUID id, boolean prohibited) {
		IngredientView before = findById(id).orElseThrow(() -> notFound(id));
		if (before.ekadashiProhibited() == prohibited) {
			return;
		}

		jdbc.update("UPDATE ingredients SET is_ekadashi_prohibited = ?, updated_at = now() WHERE id = ?",
				prohibited, id);

		auditService.record(actor, AuditAction.INGREDIENT_EKADASHI_FLAG_CHANGED,
				AuditEntityType.INGREDIENT, id,
				Map.of("name", before.name(), "ekadashiProhibited", before.ekadashiProhibited()),
				Map.of("name", before.name(), "ekadashiProhibited", prohibited),
				null);
	}

	@Transactional
	public void delete(AuthenticatedUser actor, UUID id) {
		IngredientView existing = findById(id).orElseThrow(() -> notFound(id));
		if (isReferenced(id)) {
			throw new ApplicationException(ErrorCode.INGREDIENT_IN_USE, Map.of("ingredientId", id));
		}
		try {
			jdbc.update("DELETE FROM ingredients WHERE id = ?", id);
		} catch (org.springframework.dao.DataIntegrityViolationException e) {
			// A reference the check above doesn't know about (ON DELETE RESTRICT everywhere) — the
			// catalogue stays honest either way.
			throw new ApplicationException(
					ErrorCode.INGREDIENT_IN_USE, Map.of("ingredientId", id), e);
		}
		auditService.record(actor, AuditAction.INGREDIENT_DELETED, AuditEntityType.INGREDIENT, id,
				snapshot(existing.name(), existing.category(), Unit.valueOf(existing.unit()),
						existing.ekadashiProhibited(), existing.supply(), existing.aliases()),
				null, null);
	}

	// ---------------------------------------------------------------------

	/**
	 * Whether anything still points at this ingredient. Asked before the delete rather than after,
	 * because two of the referencing tables are append-only ledgers: the application role has no
	 * DELETE on them, and PostgreSQL's RESTRICT check takes a key-share lock that needs exactly that
	 * privilege — so the database answered "permission denied for table stock_movements", which
	 * reached the user as an internal error rather than "this one is in use".
	 */
	private boolean isReferenced(UUID id) {
		return Boolean.TRUE.equals(jdbc.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM recipe_ingredients   WHERE ingredient_id = ?)
					OR EXISTS (SELECT 1 FROM inventory_items      WHERE ingredient_id = ?)
					OR EXISTS (SELECT 1 FROM stock_movements      WHERE ingredient_id = ?)
					OR EXISTS (SELECT 1 FROM goods_receipt_lines  WHERE ingredient_id = ?)
					OR EXISTS (SELECT 1 FROM vendor_supplies      WHERE ingredient_id = ?)
					OR EXISTS (SELECT 1 FROM shopping_list_lines  WHERE ingredient_id = ?)
					OR EXISTS (SELECT 1 FROM purchase_order_lines WHERE ingredient_id = ?)
				""", Boolean.class, id, id, id, id, id, id, id));
	}

	private Optional<IngredientView> findById(UUID id) {
		return jdbc.query("""
				SELECT id, name, category, canonical_unit, is_ekadashi_prohibited, is_supply,
						library_derived, aliases, created_at
				FROM ingredients WHERE id = ?
				""", VIEW_MAPPER, id).stream().findFirst();
	}

	private boolean canManageDietaryPolicy(AuthenticatedUser actor) {
		return RolePermissions.forRole(actor.getRole()).contains(Permission.MANAGE_DIETARY_POLICY);
	}

	private Unit parseUnit(String unit) {
		try {
			return Unit.valueOf(unit);
		} catch (IllegalArgumentException e) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED, Map.of("field", "unit", "value", unit), e);
		}
	}

	/** Trims, drops blanks, and de-duplicates case-insensitively, preserving order. */
	private List<String> normalizeAliases(List<String> aliases) {
		if (aliases == null) {
			return List.of();
		}
		var seen = new LinkedHashSet<String>();
		var result = new ArrayList<String>();
		for (String alias : aliases) {
			if (alias == null) {
				continue;
			}
			String trimmed = alias.trim();
			if (!trimmed.isEmpty() && seen.add(trimmed.toLowerCase())) {
				result.add(trimmed);
			}
		}
		return result;
	}

	private Map<String, Object> snapshot(
			String name, String category, Unit unit, boolean ekadashiProhibited, boolean supply,
			List<String> aliases) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("name", name);
		snapshot.put("category", category);
		snapshot.put("unit", unit.name());
		snapshot.put("ekadashiProhibited", ekadashiProhibited);
		snapshot.put("supply", supply);
		snapshot.put("aliases", aliases);
		return snapshot;
	}

	private ApplicationException notFound(UUID id) {
		return new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("ingredientId", id));
	}

	private static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static List<String> readAliases(ResultSet rs) throws SQLException {
		Array array = rs.getArray("aliases");
		if (array == null) {
			return List.of();
		}
		return List.of((String[]) array.getArray());
	}

	private static final RowMapper<IngredientView> VIEW_MAPPER = (rs, rowNum) -> new IngredientView(
			rs.getObject("id", UUID.class),
			rs.getString("name"),
			rs.getString("category"),
			rs.getString("canonical_unit"),
			rs.getBoolean("is_ekadashi_prohibited"),
			rs.getBoolean("is_supply"),
			rs.getBoolean("library_derived"),
			readAliases(rs),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());

	private static final RowMapper<IngredientSummary> SUMMARY_MAPPER = (rs, rowNum) -> new IngredientSummary(
			rs.getObject("id", UUID.class),
			rs.getString("name"),
			rs.getString("category"),
			rs.getString("canonical_unit"),
			rs.getBoolean("is_supply"));
}
