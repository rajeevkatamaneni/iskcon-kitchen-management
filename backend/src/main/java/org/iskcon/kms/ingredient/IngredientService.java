package org.iskcon.kms.ingredient;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
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
import org.iskcon.kms.error.ErrorResponse.FieldError;
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
 * <p><strong>The not-bought flag (T-402) is governed the same way and by a different
 * permission.</strong> Saying the temple never buys water is not a religious-compliance decision, so
 * it is {@code MANAGE_BUYING_POLICY} rather than {@code MANAGE_DIETARY_POLICY} — the Temple Admin in
 * both cases today, deliberately separate so either can move without dragging the other. There are
 * only <em>two</em> routes to it, not three: {@link #create} and {@link #setNotBought}. It is
 * deliberately not on {@link #update}, because the supplies screen's editing row sends a whole
 * update payload built from the fields it knows about and a flag riding on that PUT would be un-set
 * by somebody renaming a mop. Both routes check the permission themselves, following the rule
 * already established above: {@code POST /ingredients} is behind {@code MANAGE_RECIPES}, so a
 * Kitchen Manager reaches it, and the check inside {@code create} is the only thing between them and
 * the flag. Every move is audited under {@code INGREDIENT_NOT_BOUGHT_CHANGED}.
 *
 * <p>Nothing in this service filters on that flag either, and that is the point of it: a marked
 * ingredient stays in the catalogue, in every picker but one, and goes on consuming stock and being
 * costed exactly as before. The single place it is read is {@code ShoppingListService}.
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
	private final PackSizeService packSizeService;

	public IngredientService(JdbcTemplate jdbc, AuditService auditService, PackSizeService packSizeService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.packSizeService = packSizeService;
	}

	/**
	 * The whole catalogue, each ingredient with its pack sizes and market rate (R-ING-1, R-ING-3).
	 *
	 * <p>Two queries whatever the catalogue's size: the ingredients, then every pack size in the
	 * temple at once, matched up here. Not a pack query per row — a temple's catalogue is about 230
	 * ingredients, and this is the list every ingredient picker loads.
	 */
	@Transactional(readOnly = true)
	public List<IngredientView> list() {
		Map<UUID, List<PackSizeView>> packs = packSizeService.allByIngredient();
		return jdbc.query("SELECT " + VIEW_COLUMNS + " FROM ingredients ORDER BY name", viewMapper(packs));
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

	/**
	 * Name/alias prefix typeahead for recipe and inventory pickers. RLS scopes it to the tenant.
	 *
	 * <p>An alias is looked for in both places one lives: the {@code aliases} array on the row, and
	 * the {@code ingredient_aliases} table (V144). The merge tool (R-DUP-3, T-270) writes a
	 * merged-away name to both, so "Curd, sour" finds Curd through either; the table is read as well so
	 * that an alias the array does not carry — one V144's backfill or an import left only there — is
	 * not the one name the picker cannot find.
	 */
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
				   OR EXISTS (SELECT 1 FROM ingredient_aliases t
							  WHERE t.ingredient_id = ingredients.id AND lower(t.alias) LIKE ?)
				ORDER BY name LIMIT 20
				""", SUMMARY_MAPPER, like, like, like);
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
		if (request.notBought() && !canManageBuyingPolicy(actor)) {
			// T-402. Same shape as the check above and a different permission: declaring that the
			// temple never buys a thing takes it off every shopping list from here on, and the list
			// says nothing about what is missing from it. Checked here as well as at
			// setNotBought's endpoint because POST /ingredients is behind MANAGE_RECIPES, so a
			// Kitchen Manager reaches this method and the annotation above it says nothing about
			// buying policy. Only `true` is refused: creating an ordinary bought ingredient is
			// ordinary catalogue work and always was.
			throw new ApplicationException(ErrorCode.NOT_PERMITTED, Map.of("field", "notBought"));
		}
		List<String> aliases = normalizeAliases(request.aliases());
		UUID id = UUID.randomUUID();
		String name = request.name().trim();

		Optional<IngredientNameMatcher.Match> overridden =
				guardAgainstLookalike(id, name, request.confirmDifferent());

		try {
			jdbc.update(connection -> {
				var ps = connection.prepareStatement("""
						INSERT INTO ingredients (
							id, tenant_id, name, category, canonical_unit, is_ekadashi_prohibited,
							is_supply, is_not_bought, aliases)
						VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?, ?)
						""");
				ps.setObject(1, id);
				ps.setString(2, request.name().trim());
				ps.setString(3, request.category().trim());
				ps.setString(4, unit.name());
				ps.setBoolean(5, request.ekadashiProhibited());
				ps.setBoolean(6, request.supply());
				ps.setBoolean(7, request.notBought());
				ps.setArray(8, connection.createArrayOf("text", aliases.toArray()));
				return ps;
			});
		} catch (DuplicateKeyException e) {
			throw new ApplicationException(
					ErrorCode.INGREDIENT_ALREADY_EXISTS, Map.of("name", request.name()), e);
		}
		syncAliasRows(id, aliases, List.of());

		Map<String, Object> after = snapshot(name, request.category().trim(), unit,
				request.ekadashiProhibited(), request.supply(), request.notBought(), aliases);
		overridden.ifPresent(match -> after.put("confirmedDifferentFrom", lookalikeSnapshot(match)));
		auditService.record(actor, AuditAction.INGREDIENT_ADDED, AuditEntityType.INGREDIENT, id,
				null, after, overridden.map(match -> overrideReason("Added", match)).orElse(null));
		if (request.notBought()) {
			// T-402. The same second entry the Ekadashi flag gets when it moves, written here too so
			// that "who said we never buy this" has one action to grep for whether the decision was
			// made at creation or afterwards. An ingredient created already marked is the ordinary
			// case — a recipe import that knows the line is water creates it this way — and it would
			// otherwise be the one route that left no findable trace.
			recordNotBoughtChange(actor, id, name, false, true);
		}
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

		requireNoPackSizesAcrossFamilies(id, Unit.valueOf(before.unit()), unit);

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
		/*
		  The lookalike guard (R-DUP-2) runs on a RENAME only. Saving a row whose name did not move
		  must never be stopped by it: the catalogue already holds near-duplicates from before this
		  guard existed ("Curd" and "Curd, sour" both, until the merge tool settles them), and an
		  unrelated edit to either row — its unit, its aliases, its Ekadashi flag — is not the moment
		  to relitigate that. The comparison is on the stored, trimmed name, exactly as "modified"
		  below compares it, so " Curd " typed over "Curd" is not a rename either.

		  A case-only rename ("curd" to "Curd") IS a rename and is checked. The row itself is left out
		  of what it is compared against, so it can never match itself; what it can still meet is a
		  different row it resembles, which is the point.
		*/
		Optional<IngredientNameMatcher.Match> overridden = name.equals(before.name())
				? Optional.empty()
				: guardAgainstLookalike(id, name, request.confirmDifferent());

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
		syncAliasRows(id, aliases, before.aliases());

		// The not-bought flag is not editable here and is not in the UPDATE above, so it is carried
		// through from the stored row on both sides of the snapshot: the audit entry shows what the
		// ingredient is rather than leaving a reader to infer that an absent key meant "unchanged".
		Map<String, Object> after = snapshot(
				name, category, unit, ekadashiProhibited, request.supply(), before.notBought(), aliases);
		overridden.ifPresent(match -> after.put("confirmedDifferentFrom", lookalikeSnapshot(match)));
		auditService.record(actor, AuditAction.INGREDIENT_UPDATED, AuditEntityType.INGREDIENT, id,
				snapshot(before.name(), before.category(), Unit.valueOf(before.unit()),
						before.ekadashiProhibited(), before.supply(), before.notBought(),
						before.aliases()),
				after,
				overridden.map(match -> overrideReason("Renamed", match)).orElse(null));

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

	/**
	 * Marks, or unmarks, an ingredient the temple never buys — water, ice (T-402). Temple Admin only
	 * under {@code MANAGE_BUYING_POLICY}, checked at the endpoint and asserted here by the test that
	 * drives it.
	 *
	 * <p>A route of its own rather than a field on {@link #update}, and that is the deliberate part.
	 * The supplies screen's editing row sends a whole update payload built from the fields it knows
	 * about, so any flag that rides on {@code PUT} and is missing from that payload gets un-set by
	 * somebody renaming a mop. The Ekadashi flag survives that only because
	 * {@code UpdateIngredientRequest.ekadashiProhibited} is a boxed {@code Boolean} whose null means
	 * "leave alone", a subtlety it took T-121 to get right. This one does not join the PUT at all.
	 *
	 * <p>Like {@link #setEkadashiFlag}, it never clears {@code library_derived}: setting a flag is
	 * not reviewing a row, because it happens without showing anybody the category and unit an import
	 * guessed, which is the thing the mark is asking to have looked at.
	 *
	 * <p>Setting it to the value it already holds writes nothing and audits nothing, so a screen that
	 * re-sends what it is showing does not fill the trail with moves nobody made.
	 */
	@Transactional
	public void setNotBought(AuthenticatedUser actor, UUID id, boolean notBought) {
		IngredientView before = findById(id).orElseThrow(() -> notFound(id));
		if (before.notBought() == notBought) {
			return;
		}

		jdbc.update("UPDATE ingredients SET is_not_bought = ?, updated_at = now() WHERE id = ?",
				notBought, id);

		recordNotBoughtChange(actor, id, before.name(), before.notBought(), notBought);
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
						existing.ekadashiProhibited(), existing.supply(), existing.notBought(),
						existing.aliases()),
				null, null);
	}

	/** The one place {@code INGREDIENT_NOT_BOUGHT_CHANGED} is written, from create and from the flag. */
	private void recordNotBoughtChange(
			AuthenticatedUser actor, UUID id, String name, boolean was, boolean now) {
		auditService.record(actor, AuditAction.INGREDIENT_NOT_BOUGHT_CHANGED,
				AuditEntityType.INGREDIENT, id,
				Map.of("name", name, "notBought", was),
				Map.of("name", name, "notBought", now),
				null);
	}

	// ---------------------------------------------------------------------
	// R-DUP-2: no duplicate ingredients (T-251)
	// ---------------------------------------------------------------------

	/**
	 * Stops a create or a rename whose name is one the temple already has, or very nearly has, and
	 * returns the match it let through when the person has already said it is a different
	 * ingredient — so the caller can put that on the audit trail.
	 *
	 * <p><b>Why.</b> Duplicate ingredients split stock, prices and shopping-list lines: "Curd", "Curd,
	 * fresh", "Curd, sour" and "Curd, whisked" were four ingredients, so the temple held curd in four
	 * places and bought it on four lines (PROCUREMENT-REQUIREMENTS.md §9A). Everything downstream of
	 * the catalogue is only as clean as the catalogue, so the catalogue is where it is stopped.
	 *
	 * <p><b>Two checks, in this order, and the order is the decision.</b>
	 * <ol>
	 *   <li><b>The literal same name</b> (case-insensitive, which is what the database's own unique
	 *       index {@code ingredients_name_per_tenant} holds) keeps its old answer,
	 *       {@code INGREDIENT_ALREADY_EXISTS} (KMS-400034), and <em>nothing overrides it</em>. The new
	 *       check does not supersede it, because the new check's way past — "It's a different
	 *       ingredient" — cannot be honoured for a name the database will refuse anyway: two rows
	 *       called "Curd" are impossible whatever anybody confirms. Offering a button that could only
	 *       end in a second refusal would be a prompt that lies. Checked here rather than left to the
	 *       index so it is answered before anything else is weighed; the index's own refusal is still
	 *       caught around the INSERT and UPDATE, for the race between two people typing at once.</li>
	 *   <li><b>A lookalike</b>: {@link IngredientNameMatcher#findMatch} over every other ingredient in
	 *       the temple, by its name and by every alias it answers to — both the {@code aliases} array
	 *       on the row and the {@code ingredient_aliases} table (V144), since the table was backfilled
	 *       from the array and later writes keep them in step, but a name merged away by R-DUP-3 will
	 *       live only in the table. An EXACT or CLOSE match refuses with
	 *       {@code INGREDIENT_LOOKS_LIKE_EXISTING} (KMS-400156), and the refusal names the ingredient
	 *       in its {@code details} — its id and its name — because the prompt on the screen has to say
	 *       "Did you mean Curd?" and "Use Curd" has to know which Curd.</li>
	 * </ol>
	 *
	 * <p><b>The way past.</b> {@code confirmDifferent: true} on the request says the person was shown
	 * the prompt and confirmed it is a different ingredient. The save then goes ahead and the match
	 * is returned, so the audit entry records the override and what it looked like — the spec says
	 * the override "is audited", and it is the one decision here that a reviewer later needs to be
	 * able to find and question. The flag is trusted only for the lookalike check; it never reaches
	 * the literal-name check above, and a flag sent when nothing matched records nothing.
	 *
	 * <p>RLS scopes every read to the acting temple, so another temple's "Curd" is never a match.
	 * That is the database's job and not a WHERE clause's, as everywhere else.
	 *
	 * @param self the row being created or renamed, left out of the comparison so it never matches
	 *     itself (on a create it is the fresh id, which is in no row yet)
	 */
	private Optional<IngredientNameMatcher.Match> guardAgainstLookalike(
			UUID self, String name, Boolean confirmDifferent) {

		jdbc.query("SELECT id FROM ingredients WHERE lower(name) = lower(?) AND id <> ? LIMIT 1",
				(rs, rowNum) -> rs.getObject("id", UUID.class), name, self)
				.stream().findFirst().ifPresent(existing -> {
					throw new ApplicationException(
							ErrorCode.INGREDIENT_ALREADY_EXISTS,
							Map.of("name", name, "existingIngredientId", existing));
				});

		Optional<IngredientNameMatcher.Match> match =
				IngredientNameMatcher.findMatch(name, otherIngredients(self));
		if (match.isEmpty() || Boolean.TRUE.equals(confirmDifferent)) {
			return match;
		}

		IngredientNameMatcher.Entry existing = match.get().entry();
		throw new ApplicationException(
				ErrorCode.INGREDIENT_LOOKS_LIKE_EXISTING,
				Map.of("name", name, "matchedName", match.get().matchedName(),
						"kind", match.get().kind().name()),
				// The temple's own words about the temple's own data, which is what ApplicationException
				// allows `details` to carry: the ingredient this looks like, so the screen can ask
				// "Did you mean Curd?" and send "Use Curd" to the right row. The id is the row's key
				// rather than anything internal to the server — the screen already holds every
				// ingredient's id from the list it loaded.
				List.of(new FieldError("existingIngredientId", existing.id().toString()),
						new FieldError("existingIngredientName", existing.name())),
				null);
	}

	/**
	 * Every ingredient in the temple except {@code self}, with every name it answers to. Ordered by
	 * name so that the matcher's tie-break ("the order the entries were given") is the same on every
	 * call rather than whatever order the planner happened to return.
	 */
	private List<IngredientNameMatcher.Entry> otherIngredients(UUID self) {
		return jdbc.query("""
				SELECT i.id, i.name, i.aliases,
						COALESCE(array_agg(a.alias ORDER BY a.alias) FILTER (WHERE a.alias IS NOT NULL),
								'{}'::text[]) AS table_aliases
				FROM ingredients i
				LEFT JOIN ingredient_aliases a ON a.ingredient_id = i.id
				WHERE i.id <> ?
				GROUP BY i.id, i.name, i.aliases
				ORDER BY i.name
				""", (rs, rowNum) -> {
			var names = new LinkedHashSet<String>(readAliases(rs));
			Array table = rs.getArray("table_aliases");
			if (table != null) {
				names.addAll(List.of((String[]) table.getArray()));
			}
			return new IngredientNameMatcher.Entry(
					rs.getObject("id", UUID.class), rs.getString("name"), List.copyOf(names));
		}, self);
	}

	/**
	 * Keeps {@code ingredient_aliases} in step with the aliases typed on the ingredient, one row per
	 * normalised name. The {@code aliases} array on the row stays exactly as it was and is still what
	 * every screen shows; this table is what R-DUP-3's merge and the lookalike check read.
	 *
	 * <p>Normalised with {@link IngredientNameMatcher#normalise}, which V144 said would fill this
	 * column for every new alias: its backfill used plain lower/trim, a strict subset. Two aliases
	 * on one ingredient that normalise alike ("Dahi" and "Dahis") are one row, the first as typed.
	 * An alias that normalises to nothing (punctuation only) has no row, since the table's CHECK
	 * refuses a blank key and there is nothing in it to match on.
	 *
	 * <p><b>An alias another ingredient already answers to.</b> The table holds one ingredient per
	 * name per temple, so the row cannot be written twice. A <em>newly typed</em> alias that clashes
	 * is refused with {@code INGREDIENT_ALREADY_EXISTS} — it is the same claim as a name clash, a
	 * name that already means another ingredient here — and the whole save rolls back with it. An
	 * alias the ingredient <em>already had</em> is let through without a row instead: V144's backfill
	 * gave a shared alias to the older ingredient and reported the rest rather than failing, so a
	 * catalogue can arrive here already holding such a pair, and refusing would mean that ingredient
	 * could never be saved again for a clash nobody introduced in this edit. The merge tool is where
	 * those pairs are settled by a person.
	 *
	 * @param previous the aliases the row held before this save (empty on a create)
	 */
	private void syncAliasRows(UUID id, List<String> aliases, List<String> previous) {
		Map<String, String> wanted = new LinkedHashMap<>();
		for (String alias : aliases) {
			String key = IngredientNameMatcher.normalise(alias);
			if (!key.isBlank()) {
				wanted.putIfAbsent(key, alias);
			}
		}
		var previousKeys = new LinkedHashSet<String>();
		for (String alias : previous) {
			previousKeys.add(IngredientNameMatcher.normalise(alias));
		}

		jdbc.update(connection -> {
			var ps = connection.prepareStatement(
					"DELETE FROM ingredient_aliases WHERE ingredient_id = ? AND NOT (normalised_alias = ANY (?))");
			ps.setObject(1, id);
			ps.setArray(2, connection.createArrayOf("text", wanted.keySet().toArray()));
			return ps;
		});

		for (Map.Entry<String, String> entry : wanted.entrySet()) {
			// ON CONFLICT against the named constraint: a row this ingredient already holds takes the
			// spelling as now typed, and a row another ingredient holds is left alone and reported
			// back as zero rows, which is how the clash below is noticed without an exception that
			// would poison the transaction.
			int written = jdbc.update("""
					INSERT INTO ingredient_aliases (tenant_id, ingredient_id, alias, normalised_alias)
					VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
					ON CONFLICT ON CONSTRAINT ingredient_aliases_one_per_name
					DO UPDATE SET alias = EXCLUDED.alias
					WHERE ingredient_aliases.ingredient_id = EXCLUDED.ingredient_id
					""", id, entry.getValue(), entry.getKey());
			if (written == 0 && !previousKeys.contains(entry.getKey())) {
				throw new ApplicationException(
						ErrorCode.INGREDIENT_ALREADY_EXISTS,
						Map.of("alias", entry.getValue(), "normalisedAlias", entry.getKey()));
			}
		}
	}

	/** What the audit entry keeps about the ingredient a confirmed-different save looked like. */
	private static Map<String, Object> lookalikeSnapshot(IngredientNameMatcher.Match match) {
		Map<String, Object> lookalike = new LinkedHashMap<>();
		lookalike.put("id", match.entry().id().toString());
		lookalike.put("name", match.entry().name());
		lookalike.put("matchedName", match.matchedName());
		lookalike.put("kind", match.kind().name());
		return lookalike;
	}

	/**
	 * The audit entry's reason, which the audit screen prints as written. It names the ingredient
	 * this looked like in the temple's own words, so a reviewer reads the override without opening
	 * the snapshot.
	 */
	private static String overrideReason(String verb, IngredientNameMatcher.Match match) {
		return verb + " although it looks like \u201c" + match.entry().name()
				+ "\u201d: confirmed as a different ingredient.";
	}

	// ---------------------------------------------------------------------

	/**
	 * Refuses moving an ingredient's canonical unit into another family while it has pack sizes
	 * ({@code INGREDIENT_UNIT_HAS_PACK_SIZES}, KMS-400160). V144 left this to the service that edits
	 * the ingredient, because its trigger fires on the pack side only.
	 *
	 * <p><b>Why.</b> A "Bag = 25 Kg" on rice that is now counted in litres would be a pack the
	 * shopping list divides litres by, and every figure derived from it would be nonsense. The same
	 * family is allowed — Kg to gm leaves "Bag = 25 Kg" exactly as true as it was, because packs
	 * store the size as entered and their canonical-unit figure is worked out on every read.
	 *
	 * <p>The ingredient's row is locked before the packs are counted. {@link PackSizeService} takes
	 * the same lock before it adds one, so a pack cannot slip in between this count and the unit
	 * changing; whichever arrives second waits, then sees what the first did.
	 */
	private void requireNoPackSizesAcrossFamilies(UUID id, Unit from, Unit to) {
		if (from.family() == to.family()) {
			return;
		}
		jdbc.query("SELECT id FROM ingredients WHERE id = ? FOR UPDATE", (rs, n) -> null, id);
		Integer packs = jdbc.queryForObject(
				"SELECT count(*) FROM ingredient_pack_sizes WHERE ingredient_id = ?", Integer.class, id);
		if (packs != null && packs > 0) {
			throw new ApplicationException(ErrorCode.INGREDIENT_UNIT_HAS_PACK_SIZES,
					Map.of("ingredientId", id, "from", from.name(), "to", to.name(), "packSizes", packs));
		}
	}

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
		Map<UUID, List<PackSizeView>> packs = Map.of(id, packSizeService.forIngredient(id));
		return jdbc.query("SELECT " + VIEW_COLUMNS + " FROM ingredients WHERE id = ?", viewMapper(packs), id)
				.stream().findFirst();
	}

	private boolean canManageDietaryPolicy(AuthenticatedUser actor) {
		return RolePermissions.forRole(actor.getRole()).contains(Permission.MANAGE_DIETARY_POLICY);
	}

	private boolean canManageBuyingPolicy(AuthenticatedUser actor) {
		return RolePermissions.forRole(actor.getRole()).contains(Permission.MANAGE_BUYING_POLICY);
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
			boolean notBought, List<String> aliases) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("name", name);
		snapshot.put("category", category);
		snapshot.put("unit", unit.name());
		snapshot.put("ekadashiProhibited", ekadashiProhibited);
		snapshot.put("supply", supply);
		snapshot.put("notBought", notBought);
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

	private static final String VIEW_COLUMNS = """
			id, name, category, canonical_unit, is_ekadashi_prohibited, is_supply, is_not_bought,
					library_derived, aliases, created_at, market_rate, market_rate_on, market_rate_source
			""";

	/** The full view, with each ingredient's pack sizes taken from {@code packs} (none if absent). */
	private static RowMapper<IngredientView> viewMapper(Map<UUID, List<PackSizeView>> packs) {
		return (rs, rowNum) -> {
			UUID id = rs.getObject("id", UUID.class);
			return new IngredientView(
					id,
					rs.getString("name"),
					rs.getString("category"),
					rs.getString("canonical_unit"),
					rs.getBoolean("is_ekadashi_prohibited"),
					rs.getBoolean("is_supply"),
					rs.getBoolean("is_not_bought"),
					rs.getBoolean("library_derived"),
					readAliases(rs),
					rs.getObject("created_at", OffsetDateTime.class).toInstant(),
					packs.getOrDefault(id, List.of()),
					rs.getBigDecimal("market_rate"),
					rs.getObject("market_rate_on", LocalDate.class),
					rs.getString("market_rate_source"));
		};
	}

	private static final RowMapper<IngredientSummary> SUMMARY_MAPPER = (rs, rowNum) -> new IngredientSummary(
			rs.getObject("id", UUID.class),
			rs.getString("name"),
			rs.getString("category"),
			rs.getString("canonical_unit"),
			rs.getBoolean("is_supply"));
}
