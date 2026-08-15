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
 * {@code MANAGE_RECIPES}. The sattvic-prohibited flag is a religious-compliance decision, so it
 * moves only through {@link #setSattvicFlag} — a Temple Admin (MANAGE_SATTVIC_POLICY), always
 * audited. Setting the flag true at creation is the same decision, so it is refused here for anyone
 * who lacks that permission.
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
				SELECT id, name, category, canonical_unit, is_sattvic_prohibited, is_ekadashi_prohibited, aliases, created_at
				FROM ingredients ORDER BY name
				""", VIEW_MAPPER);
	}

	/** Name/alias prefix typeahead for recipe and inventory pickers. RLS scopes it to the tenant. */
	@Transactional(readOnly = true)
	public List<IngredientSummary> search(String query) {
		String prefix = query == null ? "" : query.trim().toLowerCase();
		if (prefix.isEmpty()) {
			return jdbc.query("""
					SELECT id, name, category, canonical_unit, is_sattvic_prohibited
					FROM ingredients ORDER BY name LIMIT 20
					""", SUMMARY_MAPPER);
		}
		String like = escapeLike(prefix) + "%";
		return jdbc.query("""
				SELECT id, name, category, canonical_unit, is_sattvic_prohibited
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
		if ((request.sattvicProhibited() || request.ekadashiProhibited()) && !canManageSattvicPolicy(actor)) {
			// Marking an ingredient prohibited (sattvic or Ekadashi) is the same religious-compliance
			// decision as flipping the flag later, so it needs the same authority.
			throw new ApplicationException(
					ErrorCode.NOT_PERMITTED, Map.of("field", "prohibitedFlags"));
		}
		List<String> aliases = normalizeAliases(request.aliases());
		UUID id = UUID.randomUUID();

		try {
			jdbc.update(connection -> {
				var ps = connection.prepareStatement("""
						INSERT INTO ingredients (
							id, tenant_id, name, category, canonical_unit, is_sattvic_prohibited,
							is_ekadashi_prohibited, aliases)
						VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?)
						""");
				ps.setObject(1, id);
				ps.setString(2, request.name().trim());
				ps.setString(3, request.category().trim());
				ps.setString(4, unit.name());
				ps.setBoolean(5, request.sattvicProhibited());
				ps.setBoolean(6, request.ekadashiProhibited());
				ps.setArray(7, connection.createArrayOf("text", aliases.toArray()));
				return ps;
			});
		} catch (DuplicateKeyException e) {
			throw new ApplicationException(
					ErrorCode.INGREDIENT_ALREADY_EXISTS, Map.of("name", request.name()), e);
		}

		auditService.record(actor, AuditAction.INGREDIENT_ADDED, AuditEntityType.INGREDIENT, id,
				null, snapshot(request.name().trim(), request.category().trim(), unit,
						request.sattvicProhibited(), request.ekadashiProhibited(), aliases),
				null);
		return id;
	}

	@Transactional
	public void update(AuthenticatedUser actor, UUID id, UpdateIngredientRequest request) {
		Unit unit = parseUnit(request.unit());
		IngredientView before = findById(id).orElseThrow(() -> notFound(id));
		List<String> aliases = normalizeAliases(request.aliases());

		try {
			jdbc.update(connection -> {
				var ps = connection.prepareStatement("""
						UPDATE ingredients
						SET name = ?, category = ?, canonical_unit = ?, aliases = ?, updated_at = now()
						WHERE id = ?
						""");
				ps.setString(1, request.name().trim());
				ps.setString(2, request.category().trim());
				ps.setString(3, unit.name());
				ps.setArray(4, connection.createArrayOf("text", aliases.toArray()));
				ps.setObject(5, id);
				return ps;
			});
		} catch (DuplicateKeyException e) {
			throw new ApplicationException(
					ErrorCode.INGREDIENT_ALREADY_EXISTS, Map.of("name", request.name()), e);
		}

		auditService.record(actor, AuditAction.INGREDIENT_UPDATED, AuditEntityType.INGREDIENT, id,
				snapshot(before.name(), before.category(), Unit.valueOf(before.unit()),
						before.sattvicProhibited(), before.ekadashiProhibited(), before.aliases()),
				snapshot(request.name().trim(), request.category().trim(), unit,
						before.sattvicProhibited(), before.ekadashiProhibited(), aliases),
				null);
	}

	/** Sets or clears the sattvic-prohibited flag. Temple Admin only (checked at the endpoint). */
	@Transactional
	public void setSattvicFlag(AuthenticatedUser actor, UUID id, boolean prohibited) {
		IngredientView before = findById(id).orElseThrow(() -> notFound(id));
		if (before.sattvicProhibited() == prohibited) {
			return;
		}

		jdbc.update("UPDATE ingredients SET is_sattvic_prohibited = ?, updated_at = now() WHERE id = ?",
				prohibited, id);

		auditService.record(actor, AuditAction.INGREDIENT_SATTVIC_FLAG_CHANGED,
				AuditEntityType.INGREDIENT, id,
				Map.of("name", before.name(), "sattvicProhibited", before.sattvicProhibited()),
				Map.of("name", before.name(), "sattvicProhibited", prohibited),
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
						existing.sattvicProhibited(), existing.ekadashiProhibited(), existing.aliases()),
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
					OR EXISTS (SELECT 1 FROM order_list_lines     WHERE ingredient_id = ?)
					OR EXISTS (SELECT 1 FROM purchase_order_lines WHERE ingredient_id = ?)
				""", Boolean.class, id, id, id, id, id, id, id));
	}

	private Optional<IngredientView> findById(UUID id) {
		return jdbc.query("""
				SELECT id, name, category, canonical_unit, is_sattvic_prohibited, is_ekadashi_prohibited, aliases, created_at
				FROM ingredients WHERE id = ?
				""", VIEW_MAPPER, id).stream().findFirst();
	}

	private boolean canManageSattvicPolicy(AuthenticatedUser actor) {
		return RolePermissions.forRole(actor.getRole()).contains(Permission.MANAGE_SATTVIC_POLICY);
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
			String name, String category, Unit unit, boolean sattvicProhibited,
			boolean ekadashiProhibited, List<String> aliases) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("name", name);
		snapshot.put("category", category);
		snapshot.put("unit", unit.name());
		snapshot.put("sattvicProhibited", sattvicProhibited);
		snapshot.put("ekadashiProhibited", ekadashiProhibited);
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
			rs.getBoolean("is_sattvic_prohibited"),
			rs.getBoolean("is_ekadashi_prohibited"),
			readAliases(rs),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());

	private static final RowMapper<IngredientSummary> SUMMARY_MAPPER = (rs, rowNum) -> new IngredientSummary(
			rs.getObject("id", UUID.class),
			rs.getString("name"),
			rs.getString("category"),
			rs.getString("canonical_unit"),
			rs.getBoolean("is_sattvic_prohibited"));
}
