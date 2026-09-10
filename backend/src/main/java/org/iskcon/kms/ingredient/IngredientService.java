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
 * {@code MANAGE_RECIPES}. The Ekadashi-prohibited flag is a religious-compliance decision and needs
 * {@code MANAGE_DIETARY_POLICY} — a Temple Admin — wherever it is set from, and every move of it is
 * audited under {@code INGREDIENT_EKADASHI_FLAG_CHANGED}.
 *
 * <p><strong>There are three routes to that flag and one rule over all of them.</strong> It can be
 * set at creation, it can be set through {@link #setEkadashiFlag}, and since T-121 it can be set by
 * an ordinary {@link #update} — because Rajeev took the one-click toggle off the catalogue row on
 * 2026-09-10 and made it a checkbox inside the editing row. Each of the three checks the permission
 * itself rather than leaning on the endpoint annotation, because only two of the three routes have
 * an annotation that says {@code MANAGE_DIETARY_POLICY}: {@code PUT /{id}} is behind
 * {@code MANAGE_RECIPES}, so a Kitchen Manager reaches it, and the check inside {@code update} is
 * the only thing standing between them and the flag.
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
	 * Edits an ingredient's descriptive fields and its Ekadashi-prohibited flag — and, where
	 * something actually moved, clears {@code library_derived} in the same statement (T-119, T-121).
	 *
	 * <p>Saving an edit <em>is</em> the review. A recipe import creates ingredients silently, and
	 * the catalogue labels those rows and offers a filter over them; without a way of clearing the
	 * mark that filter is a list that only grows, and a list that only grows is one nobody opens a
	 * second time.
	 *
	 * <p><strong>What counts as a review changed on 2026-09-10.</strong> It used to clear on any
	 * save at all, on the argument that somebody who opened a row and looked at it had done the only
	 * reviewing there is. Rajeev ruled otherwise, having watched the screen: <em>"When does the
	 * Imported lable get cleared, when the user goes to edit mode, makes atleast one modification
	 * and saves."</em> So a modification is now required, and a modification is <em>any editable
	 * field differing from what the row already holds</em> — re-typing the same value is not one,
	 * and opening the row and pressing Save is not one.
	 *
	 * <p><strong>The comparison is here rather than on the client, and that is the point of putting
	 * it here.</strong> A client that decided for itself whether its save "counted" would be sending
	 * the server a flag to trust, and a raw POST could then clear the mark of every imported row in
	 * the catalogue without touching a single value — which is exactly the shape this project
	 * already refuses elsewhere: {@code RecipeService}'s own comment, guarding the same catalogue,
	 * says <em>"a picker is not a guard: a raw POST, an import, or a screen built later never goes
	 * through it."</em> The stored row is where the truth about what changed lives, so the question
	 * is asked where the truth is.
	 *
	 * <p>One consequence worth naming rather than discovering: the comparison is against the row as
	 * it stands <em>now</em>, not against what the client was shown when it opened the form. If
	 * somebody else edited the ingredient in between, a save that looks unchanged on this screen
	 * genuinely does change the stored row, and the mark clears. That is the right answer — the row
	 * moved, and it moved because of this request.
	 *
	 * <p><strong>Ticking the Ekadashi box counts.</strong> That is a behaviour change from the old
	 * one-click toggle, which cleared nothing (see {@link #setEkadashiFlag}), and it is the right one
	 * now that the flag is set inside a deliberate edit rather than by a stray click on a row.
	 *
	 * <p>The flag's own endpoint still clears nothing, and still exists. It writes one
	 * religious-compliance flag without opening the row or showing anybody the category and unit an
	 * import guessed, so a save through it is not a review of anything.
	 */
	@Transactional
	public void update(AuthenticatedUser actor, UUID id, UpdateIngredientRequest request) {
		Unit unit = parseUnit(request.unit());
		IngredientView before = findById(id).orElseThrow(() -> notFound(id));
		List<String> aliases = normalizeAliases(request.aliases());
		String name = request.name().trim();
		String category = request.category().trim();

		// A null flag means "leave it as it is" — see UpdateIngredientRequest. It is what a client
		// without MANAGE_DIETARY_POLICY sends, and resolving it to the stored value here means such
		// an edit neither moves the flag nor trips the permission check below.
		boolean ekadashiProhibited = request.ekadashiProhibited() == null
				? before.ekadashiProhibited()
				: request.ekadashiProhibited();
		if (ekadashiProhibited != before.ekadashiProhibited() && !canManageDietaryPolicy(actor)) {
			// Same rule, same code and same reason as create(): declaring an ingredient prohibited
			// is a religious-compliance decision wherever it arrives from, and the editing row is
			// now one of the places it can arrive from. Checked against the stored value rather
			// than against the key's presence, so a client that helpfully echoes the flag back
			// unchanged is not refused for an edit it did not make.
			throw new ApplicationException(
					ErrorCode.NOT_PERMITTED, Map.of("field", "ekadashiProhibited"));
		}

		/*
		  Every editable field, compared against the row. Listed rather than looped on purpose: this
		  is the definition of "a modification" and it should be readable as one, and each side is
		  compared in its STORED form — trimmed name and category, the unit's enum name, aliases
		  after normalizeAliases has trimmed, de-duplicated and dropped blanks. Comparing the raw
		  request instead would count " Rice" against "Rice" as a change, and the row would come back
		  from the database identical.

		  Alias ORDER counts, and that is deliberate: the array is stored in the order it was given
		  and every screen shows it in that order, so re-ordering somebody's aliases is a change a
		  person can see.
		*/
		boolean modified = !name.equals(before.name())
				|| !category.equals(before.category())
				|| !unit.name().equals(before.unit())
				|| request.supply() != before.supply()
				|| ekadashiProhibited != before.ekadashiProhibited()
				|| !aliases.equals(before.aliases());

		// Only ever falls. An unmodified save on a marked row writes the mark back as it was rather
		// than leaving the column out of the statement, so the value is stated on every path and
		// there is nothing to work out from an omission.
		boolean libraryDerived = before.libraryDerived() && !modified;

		try {
			jdbc.update(connection -> {
				var ps = connection.prepareStatement("""
						UPDATE ingredients
						SET name = ?, category = ?, canonical_unit = ?, is_supply = ?,
							is_ekadashi_prohibited = ?, aliases = ?, library_derived = ?,
							updated_at = now()
						WHERE id = ?
						""");
				ps.setString(1, name);
				ps.setString(2, category);
				ps.setString(3, unit.name());
				// Written on every edit rather than only when it changed: the request carries a
				// primitive, so the value the form was showing is the value that comes back, and a
				// supply that stayed a supply says so again instead of falling back to the
				// permissive default.
				ps.setBoolean(4, request.supply());
				ps.setBoolean(5, ekadashiProhibited);
				ps.setArray(6, connection.createArrayOf("text", aliases.toArray()));
				ps.setBoolean(7, libraryDerived);
				ps.setObject(8, id);
				return ps;
			});
		} catch (DuplicateKeyException e) {
			throw new ApplicationException(
					ErrorCode.INGREDIENT_ALREADY_EXISTS, Map.of("name", request.name()), e);
		}

		auditService.record(actor, AuditAction.INGREDIENT_UPDATED, AuditEntityType.INGREDIENT, id,
				snapshot(before.name(), before.category(), Unit.valueOf(before.unit()),
						before.ekadashiProhibited(), before.supply(), before.aliases()),
				snapshot(name, category, unit, ekadashiProhibited, request.supply(), aliases),
				null);

		if (ekadashiProhibited != before.ekadashiProhibited()) {
			// The compliance trail is kept findable by its own action, whichever route moved the
			// flag. Somebody asking "who declared this prohibited, and when" greps for one action
			// name; making them also know that the answer might be buried inside an
			// INGREDIENT_UPDATED snapshot is how an audit trail stops being usable.
			auditService.record(actor, AuditAction.INGREDIENT_EKADASHI_FLAG_CHANGED,
					AuditEntityType.INGREDIENT, id,
					Map.of("name", before.name(), "ekadashiProhibited", before.ekadashiProhibited()),
					Map.of("name", name, "ekadashiProhibited", ekadashiProhibited),
					null);
		}
	}

	/**
	 * Sets or clears the Ekadashi-prohibited flag. Temple Admin only (checked at the endpoint).
	 *
	 * <p><strong>Nothing in this application calls it any more, as of T-121, and it is kept
	 * deliberately.</strong> It was reached from one place — a button in the catalogue's Ekadashi
	 * cell — and Rajeev removed that button on 2026-09-10, on the ground that the flag is a
	 * permanent fact about an ingredient rather than something to flip in passing. The setting moved
	 * into {@link #update}, and this became an endpoint with no caller.
	 *
	 * <p>Deleting it is a decision for Rajeev rather than for whoever noticed. It is a published,
	 * audited route that has existed since the ingredient module was written; some integration or
	 * script may use it, and removing it costs a deploy to find out. It also remains the only way to
	 * move the flag without an accompanying descriptive edit, which is a genuinely different act.
	 *
	 * <p>What it does <em>not</em> do, and this is now the difference that matters: it never clears
	 * {@code library_derived}. Setting a flag is not reviewing a row — it happens without ever
	 * showing anybody the category and unit an import guessed, which is the thing the mark is asking
	 * to have looked at.
	 */
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
