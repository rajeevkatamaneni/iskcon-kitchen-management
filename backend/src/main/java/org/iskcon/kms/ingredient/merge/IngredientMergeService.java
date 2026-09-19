package org.iskcon.kms.ingredient.merge;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.error.ErrorResponse.FieldError;
import org.iskcon.kms.ingredient.IngredientNameMatcher;
import org.iskcon.kms.ingredient.PackSizeService;
import org.iskcon.kms.ingredient.PackSizeView;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.ingredient.merge.IngredientReferences.Reference;
import org.iskcon.kms.inventory.InventoryUnits;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one-time merge tool for duplicate ingredients (PROCUREMENT-REQUIREMENTS.md §9A, R-DUP-3).
 * Temple Admin only — {@code MERGE_INGREDIENTS}, declared on every endpoint.
 *
 * <p><b>Why it exists.</b> The recipe library import turned preparations into ingredients ("Curd,
 * sour", "Cashew, halved"), so a temple held curd in four places, bought it on four shopping-list
 * lines and priced it four ways. R-DUP-1 and R-DUP-2 stop new duplicates; this settles the ones
 * already there.
 *
 * <p><b>What one approved group does, in one transaction</b> — a failure anywhere leaves every table
 * exactly as it was:
 * <ol>
 *   <li>Every row that points at a merged-away ingredient is moved onto the kept one. Which tables
 *       those are is read from the database catalogue on every run ({@link IngredientReferences}),
 *       and the merge refuses to start if a column appears that {@link #RULES} has no rule for.</li>
 *   <li>The recipe lines get the preparation note ("Curd, sour" becomes Curd · sour).</li>
 *   <li>Stock is combined by re-pointing the ledger, never by writing a balancing entry: the history
 *       stays, and on hand for the kept ingredient is the sum of the group's before.</li>
 *   <li>Vendor supplies, pack sizes, the market rate and price history are merged.</li>
 *   <li>One {@code INGREDIENT_MERGED} audit entry on the kept ingredient, its after-state read back
 *       from the rows; and one {@code INGREDIENT_DELETED} on each merged-away ingredient, so reading
 *       the history of "Curd, sour" says where it went.</li>
 * </ol>
 * The merged-away names become aliases of the kept ingredient, and the merged-away rows are removed.
 *
 * <p><b>Units</b> (conductor's ruling 2026-09-19). Within one kind of unit — Kg and gm — the merge
 * converts into the kept ingredient's unit with {@link Unit#baseFactor()}; across kinds (Kg and
 * pieces) it refuses with KMS-400171. What "converts" means depends on how each figure is stored, and
 * that is the part worth reading twice:
 * <ul>
 *   <li><b>A figure that carries its own unit</b> — a stock movement of 500 GM, a PO line of 2 KG, a
 *       recipe line, a receipt line, an invoice line, a pack size — is left exactly as it is. It still
 *       means what it meant, and every sum over it already goes through {@code to_base_qty}. Rewriting
 *       500 GM as 0.5 KG would change a recorded fact to say the same thing.</li>
 *   <li><b>A figure stored per one canonical unit, with no unit beside it</b> — a vendor's list price,
 *       a price-history row, the market rate and its history, an inventory reorder level — would
 *       silently change meaning when its ingredient's unit does, so it is converted: ₹0.065 per gm on
 *       "Curd, sour" is ₹65 per Kg on Curd.</li>
 *   <li>A shopping-list decision carries its own unit column, and is converted anyway, because it is
 *       folded into the kept ingredient's line and that line is read in the canonical unit.</li>
 * </ul>
 *
 * <p><b>The append-only ledgers</b> ({@code stock_movements}, {@code goods_receipt_lines},
 * {@code vendor_price_history}, {@code ingredient_market_rate_history}) cannot be updated by the
 * application role, correctly. They are re-pointed through {@code merge_ingredient_ledger_rows}
 * (V147), which can do nothing but this re-point, and works out any rate conversion itself from the
 * two ingredients' units. Its comment says why that is narrower than a flag the trigger honours.
 */
@Service
public class IngredientMergeService {

	/**
	 * What the merge does with each column that points at an ingredient. The catalogue decides which
	 * columns exist; this decides what happens to each, and a column missing from here stops the
	 * merge before it writes anything. {@code IngredientMergeCatalogueIT} compares the two.
	 */
	enum Rule {
		/** Re-pointed as it stands: every row just names the kept ingredient instead. */
		REPOINT,
		/** Re-pointed, and the preparation note moves onto the line (R-DUP-1, R-DUP-3 step 2). */
		RECIPE_LINES,
		/** Append-only: re-pointed through {@code merge_ingredient_ledger_rows} (V147). */
		LEDGER,
		/** One row per (vendor, ingredient): folded, with the price conflict rule. */
		VENDOR_SUPPLIES,
		/** One row per ingredient: folded into the kept ingredient's. */
		INVENTORY_ITEM,
		/** One decision row per ingredient: folded into the kept ingredient's line. */
		SHOPPING_LIST_DECISION,
		/** Pack sizes: the same size collapses into the kept ingredient's pack. */
		PACK_SIZES,
		/** Alias rows: re-pointed, and the merged-away names are added. */
		ALIASES
	}

	static final Map<String, Rule> RULES = Map.ofEntries(
			Map.entry("recipe_ingredients.ingredient_id", Rule.RECIPE_LINES),
			Map.entry("stock_movements.ingredient_id", Rule.LEDGER),
			Map.entry("goods_receipt_lines.ingredient_id", Rule.LEDGER),
			Map.entry("vendor_price_history.ingredient_id", Rule.LEDGER),
			Map.entry("ingredient_market_rate_history.ingredient_id", Rule.LEDGER),
			Map.entry("vendor_supplies.ingredient_id", Rule.VENDOR_SUPPLIES),
			Map.entry("inventory_items.ingredient_id", Rule.INVENTORY_ITEM),
			Map.entry("shopping_list_lines.ingredient_id", Rule.SHOPPING_LIST_DECISION),
			Map.entry("ingredient_pack_sizes.ingredient_id", Rule.PACK_SIZES),
			Map.entry("ingredient_aliases.ingredient_id", Rule.ALIASES),
			Map.entry("purchase_order_lines.ingredient_id", Rule.REPOINT),
			Map.entry("vendor_invoice_lines.ingredient_id", Rule.REPOINT),
			Map.entry("ingredient_request_lines.ingredient_id", Rule.REPOINT));

	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final PackSizeService packSizes;
	private final IngredientReferences references;

	public IngredientMergeService(
			JdbcTemplate jdbc, AuditService auditService, PackSizeService packSizes,
			IngredientReferences references) {
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.packSizes = packSizes;
		this.references = references;
	}

	// =====================================================================
	// Proposals
	// =====================================================================

	/**
	 * The groups the tool proposes: ingredients whose names are the same once
	 * {@link IngredientNameMatcher#normalise} has taken off preparations, plurals, case and
	 * punctuation — "Curd", "Curd, fresh", "Curd, sour", "Curd, whisked" are all "curd".
	 *
	 * <p><b>Only exact matches of the normalised name are proposed</b>, not the matcher's CLOSE ones.
	 * CLOSE is "Rice" against "Rice, basmati" and "Tomato" against "Tomato, ripe": right to put in front
	 * of somebody typing a new name, wrong to put forward as a merge, because a variety is not a
	 * duplicate. The admin can still build any group by hand; preview and merge check it from scratch.
	 *
	 * <p><b>Which one is kept</b> — the clarifier confirmed the document's examples keep the base name
	 * without a preparation word ("… → Curd", "Cashew, halved → Cashew"), and left the rest to the admin:
	 * <ol>
	 *   <li>a member whose name carries no preparation (the matcher's split finds none) over one that
	 *       does;</li>
	 *   <li>then the one most recipe lines use, since those lines are the ones the temple cooks from;</li>
	 *   <li>then the oldest, which is the one the temple has had longest;</li>
	 *   <li>then by name, so the answer is the same on every call.</li>
	 * </ol>
	 */
	@Transactional(readOnly = true)
	public List<MergeProposalView> proposals() {
		List<Ingredient> all = jdbc.query(
				"SELECT " + INGREDIENT_COLUMNS + " FROM ingredients ORDER BY name", INGREDIENT_MAPPER);
		Map<UUID, Integer> lines = countBy("SELECT ingredient_id, count(*) FROM recipe_ingredients GROUP BY ingredient_id");
		Map<UUID, Integer> supplies = countBy("SELECT ingredient_id, count(*) FROM vendor_supplies GROUP BY ingredient_id");
		Map<UUID, BigDecimal> onHandBase = onHandBase(null);

		Map<String, List<Ingredient>> byKey = new LinkedHashMap<>();
		for (Ingredient ingredient : all) {
			String key = IngredientNameMatcher.normalise(ingredient.name());
			if (!key.isBlank()) {
				byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(ingredient);
			}
		}

		Comparator<Ingredient> keepFirst = Comparator
				.comparing((Ingredient i) -> IngredientNameMatcher.split(i.name()).hasPreparation())
				.thenComparing((Ingredient i) -> -lines.getOrDefault(i.id(), 0))
				.thenComparing(Ingredient::createdAt)
				.thenComparing(Ingredient::name);

		List<MergeProposalView> out = new ArrayList<>();
		for (List<Ingredient> group : byKey.values()) {
			if (group.size() < 2) {
				continue;
			}
			List<Ingredient> ordered = new ArrayList<>(group);
			ordered.sort(keepFirst);
			Ingredient keep = ordered.get(0);
			List<MergeCandidateView> merge = new ArrayList<>();
			for (Ingredient other : ordered.subList(1, ordered.size())) {
				merge.add(candidate(other, IngredientNameMatcher.split(other.name()).preparation(),
						lines, supplies, onHandBase));
			}
			merge.sort(Comparator.comparing(MergeCandidateView::name, String.CASE_INSENSITIVE_ORDER));
			out.add(new MergeProposalView(candidate(keep, null, lines, supplies, onHandBase), merge));
		}
		out.sort(Comparator.comparing(p -> p.keep().name(), String.CASE_INSENSITIVE_ORDER));
		return out;
	}

	// =====================================================================
	// Preview
	// =====================================================================

	/**
	 * What {@link #merge} would do with this group, without doing it. Refuses a group that cannot be
	 * read as one (KMS-400173) — there is nothing to preview — but not one that mixes kinds of unit or
	 * has an unchosen price: those are exactly what the preview is for, so they come back as
	 * {@code unitProblem} and {@code conflicts}.
	 */
	@Transactional(readOnly = true)
	public MergePreviewView preview(MergeGroupInput input) {
		Group group = resolve(input);
		Set<UUID> ids = group.ids();
		Map<UUID, Integer> lines = countBy(
				"SELECT ingredient_id, count(*) FROM recipe_ingredients WHERE ingredient_id = ANY (?) GROUP BY ingredient_id", ids);
		Map<UUID, Integer> supplies = countBy(
				"SELECT ingredient_id, count(*) FROM vendor_supplies WHERE ingredient_id = ANY (?) GROUP BY ingredient_id", ids);
		Map<UUID, BigDecimal> onHandBase = onHandBase(ids);

		BigDecimal base = BigDecimal.ZERO;
		for (Ingredient member : group.all()) {
			if (member.unit().family() == group.keep().unit().family()) {
				base = base.add(onHandBase.getOrDefault(member.id(), BigDecimal.ZERO));
			}
		}

		List<MergeCandidateView> merge = new ArrayList<>();
		for (Ingredient m : group.merged()) {
			merge.add(candidate(m, group.notes().get(m.id()), lines, supplies, onHandBase));
		}
		Ingredient mismatch = group.firstOtherFamily();
		return new MergePreviewView(
				candidate(group.keep(), null, lines, supplies, onHandBase),
				merge,
				InventoryUnits.fromBase(base, group.keep().unit()),
				conflicts(group, loadSupplies(ids)),
				mismatch == null ? null : unitProblem(group.keep(), mismatch));
	}

	// =====================================================================
	// Merge
	// =====================================================================

	/**
	 * Merges one approved group, in one transaction (R-DUP-3: "It runs in one transaction per group").
	 *
	 * <p>Refused, before anything is written, with:
	 * <ul>
	 *   <li>KMS-400173 when the group cannot be read as one: no kept ingredient, nothing to merge, the
	 *       kept one also in the merge list, an id twice, or an id this temple does not have — another
	 *       temple's ingredient is not found, because row-level security never shows it;</li>
	 *   <li>KMS-400171 when a member is counted in a different kind of unit from the kept one;</li>
	 *   <li>KMS-400172 when a vendor supplies two of the group at different list prices and
	 *       {@code supplyPriceChoices} does not say whose price it keeps.</li>
	 * </ul>
	 */
	@Transactional
	public MergeResultView merge(AuthenticatedUser actor, MergeGroupInput input) {
		// Lock the whole group first, in id order so two merges over overlapping groups queue rather
		// than deadlock, and so nobody renames or deletes a member between the checks and the writes.
		lockGroup(input);
		Group group = resolve(input);

		Ingredient mismatch = group.firstOtherFamily();
		if (mismatch != null) {
			throw new ApplicationException(ErrorCode.MERGE_UNITS_DIFFER,
					Map.of("keep", group.keep().id(), "other", mismatch.id()),
					List.of(new FieldError("keptIngredient", group.keep().name()),
							new FieldError("keptUnit", unitWord(group.keep().unit())),
							new FieldError("otherIngredient", mismatch.name()),
							new FieldError("otherUnit", unitWord(mismatch.unit()))),
					null);
		}

		Set<UUID> ids = group.ids();
		List<Supply> supplies = loadSupplies(ids);
		Map<UUID, UUID> priceFrom = resolveChoices(group, conflicts(group, supplies), input);

		List<Reference> refs = references.all();
		List<String> unknown = refs.stream().map(Reference::key).filter(k -> !RULES.containsKey(k)).toList();
		if (!unknown.isEmpty()) {
			// A table added after this class was written. Refusing is the only safe answer: a CASCADE
			// key would take its rows with the merged-away ingredient, a RESTRICT one would fail the
			// delete at the end. Technical, so a 500 with the detail in the log, never to the screen.
			throw new IllegalStateException(
					"R-DUP-3 merge has no rule for these ingredient references: " + unknown);
		}

		Map<String, Object> before = new LinkedHashMap<>();
		before.put("kept", snapshot(group.keep()));
		List<Map<String, Object>> mergedBefore = new ArrayList<>();
		for (Ingredient m : group.merged()) {
			mergedBefore.add(snapshot(m));
		}
		before.put("merged", mergedBefore);

		List<UUID> mergedIds = group.merged().stream().map(Ingredient::id).toList();
		Map<String, Long> repointed = references.countPointingAt(mergedIds);

		// The three composite (pack, ingredient) keys are DEFERRABLE for exactly this (V144, V146): a
		// pack and the rows that name it move in separate statements, and only the end state has to
		// agree. Transaction-local, and put back to IMMEDIATE below so any disagreement is raised
		// here, inside the merge, rather than at commit.
		jdbc.execute("SET CONSTRAINTS ALL DEFERRED");

		UUID keep = group.keep().id();
		mergePackSizes(group);
		mergeSupplies(group, supplies, priceFrom);
		mergeInventoryItems(group);
		mergeShoppingListDecisions(group);
		Set<UUID> recipes = mergeRecipeLines(group);

		for (Reference ref : refs) {
			Rule rule = RULES.get(ref.key());
			if (ref.appendOnly() || rule == Rule.LEDGER) {
				// Both ways round on purpose: a table the catalogue shows append-only goes through the
				// function whatever the map says, and the function refuses a table it does not know.
				for (UUID m : mergedIds) {
					jdbc.queryForObject("SELECT merge_ingredient_ledger_rows(?, ?, ?)", Long.class,
							ref.table(), m, keep);
				}
			} else if (rule == Rule.REPOINT || rule == Rule.ALIASES) {
				repoint(ref, mergedIds, keep);
			}
		}

		mergeMarketRate(group);
		List<String> aliasesAdded = addAliases(group);

		if (!recipes.isEmpty()) {
			// Every edit to a recipe bumps its version so cached translations are dropped (RecipeService,
			// E2-S6). A cached Kannada card still saying "Curd, sour" is exactly that stale translation.
			jdbc.update(connection -> {
				var ps = connection.prepareStatement(
						"UPDATE recipes SET version = version + 1, updated_at = now() WHERE id = ANY (?)");
				ps.setArray(1, connection.createArrayOf("uuid", recipes.toArray()));
				return ps;
			});
		}

		Map<String, Long> left = references.countPointingAt(mergedIds);
		if (left.values().stream().anyMatch(n -> n > 0)) {
			throw new IllegalStateException("R-DUP-3 merge left references behind: " + left);
		}
		jdbc.update(connection -> {
			var ps = connection.prepareStatement("DELETE FROM ingredients WHERE id = ANY (?)");
			ps.setArray(1, connection.createArrayOf("uuid", mergedIds.toArray()));
			return ps;
		});
		jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");

		audit(actor, group, before, mergedBefore, repointed, aliasesAdded);
		return new MergeResultView(keep, mergedIds, aliasesAdded, repointed);
	}

	// ---------------------------------------------------------------------
	// The steps
	// ---------------------------------------------------------------------

	/**
	 * Pack sizes: a merged-away pack of a size the kept ingredient already has (the same amount in the
	 * family's base unit — the rule V144's unique index holds) is folded into the kept pack, and every
	 * row that named it follows; any other pack moves across as it is. The pack stores the size as it
	 * was entered, so a "Bag = 25 Kg" moving onto an ingredient counted in gm is still exactly true.
	 *
	 * <p>The kept ingredient may end up with more than the eight packs PackSizeService allows a person
	 * to add. That limit is on adding, one at a time; a merge adds none that were not already there.
	 */
	private void mergePackSizes(Group group) {
		UUID keep = group.keep().id();
		List<String> packColumns = references.packColumns();
		for (Ingredient m : group.merged()) {
			List<Map<String, Object>> packs = jdbc.queryForList(
					"SELECT id, base_quantity FROM ingredient_pack_sizes WHERE ingredient_id = ?", m.id());
			for (Map<String, Object> pack : packs) {
				UUID packId = (UUID) pack.get("id");
				List<UUID> same = jdbc.queryForList("""
						SELECT id FROM ingredient_pack_sizes WHERE ingredient_id = ? AND base_quantity = ?
						""", UUID.class, keep, pack.get("base_quantity"));
				if (same.isEmpty()) {
					jdbc.update("UPDATE ingredient_pack_sizes SET ingredient_id = ? WHERE id = ?", keep, packId);
					continue;
				}
				UUID kept = same.get(0);
				for (String column : packColumns) {
					String[] parts = column.split("\\.");
					jdbc.update("UPDATE " + IngredientReferences.quote(parts[0]) + " SET "
							+ IngredientReferences.quote(parts[1]) + " = ? WHERE "
							+ IngredientReferences.quote(parts[1]) + " = ?", kept, packId);
				}
				jdbc.update("DELETE FROM ingredient_pack_sizes WHERE id = ?", packId);
			}
		}
	}

	/**
	 * Vendor supplies (R-DUP-3 step 4). One vendor, one row per ingredient (V24), so a vendor that
	 * supplied two of the group ends with one row on the kept ingredient:
	 * <ul>
	 *   <li>its price is the chosen ingredient's where the prices differed, else the kept
	 *       ingredient's row, else the first priced row — converted to the kept unit;</li>
	 *   <li>its pack and pack price come with the price, since a pack price and the list price derived
	 *       from it are one fact;</li>
	 *   <li>its lead time is that row's, or the first the vendor gave for any of the group.</li>
	 * </ul>
	 * One preferred vendor per ingredient (V24's partial unique index): the kept ingredient's own
	 * preferred vendor stays preferred; if it had none, the first merged-away ingredient's does.
	 */
	private void mergeSupplies(Group group, List<Supply> supplies, Map<UUID, UUID> priceFrom) {
		UUID keep = group.keep().id();
		Map<UUID, List<Supply>> byVendor = new LinkedHashMap<>();
		for (Supply s : supplies) {
			byVendor.computeIfAbsent(s.vendorId(), v -> new ArrayList<>()).add(s);
		}
		UUID preferredVendor = supplies.stream().filter(s -> s.preferred() && s.ingredientId().equals(keep))
				.map(Supply::vendorId).findFirst()
				.orElseGet(() -> group.merged().stream()
						.flatMap(m -> supplies.stream().filter(s -> s.preferred() && s.ingredientId().equals(m.id())))
						.map(Supply::vendorId).findFirst().orElse(null));

		jdbc.update(connection -> {
			var ps = connection.prepareStatement(
					"UPDATE vendor_supplies SET preferred = false WHERE ingredient_id = ANY (?) AND preferred");
			ps.setArray(1, connection.createArrayOf("uuid", group.ids().toArray()));
			return ps;
		});

		for (Map.Entry<UUID, List<Supply>> entry : byVendor.entrySet()) {
			List<Supply> rows = entry.getValue();
			UUID chosenIngredient = priceFrom.get(entry.getKey());
			Supply winner = rows.stream().filter(s -> s.ingredientId().equals(chosenIngredient)).findFirst()
					.or(() -> rows.stream().filter(s -> s.ingredientId().equals(keep)).findFirst())
					.or(() -> rows.stream().filter(s -> s.lastPrice() != null).findFirst())
					.orElse(rows.get(0));
			Integer leadTime = winner.leadTimeDays() != null ? winner.leadTimeDays()
					: rows.stream().map(Supply::leadTimeDays).filter(Objects::nonNull).findFirst().orElse(null);

			for (Supply loser : rows) {
				if (loser != winner) {
					jdbc.update("DELETE FROM vendor_supplies WHERE id = ?", loser.id());
				}
			}
			Unit from = group.byId(winner.ingredientId()).unit();
			jdbc.update("""
					UPDATE vendor_supplies
					SET ingredient_id = ?, last_price = ?, lead_time_days = ?, preferred = ?, updated_at = now()
					WHERE id = ?
					""", keep, priceIn(winner.lastPrice(), from, group.keep().unit()), leadTime,
					entry.getKey().equals(preferredVendor), winner.id());
		}
	}

	/**
	 * Inventory items, one per ingredient (V15). Metadata only — stock is the ledger, moved elsewhere
	 * — so this keeps the kept ingredient's row, or the first merged one's, and fills any blank on it
	 * (storage place, reorder level, notes) from the others. A reorder level is held in the canonical
	 * unit with no unit beside it, so it is converted.
	 */
	private void mergeInventoryItems(Group group) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Ingredient member : group.all()) {
			rows.addAll(jdbc.queryForList("""
					SELECT id, ingredient_id, storage_location, reorder_threshold, notes
					FROM inventory_items WHERE ingredient_id = ?
					""", member.id()));
		}
		if (rows.isEmpty()) {
			return;
		}
		Map<String, Object> winner = rows.get(0);
		String location = null;
		BigDecimal threshold = null;
		String notes = null;
		for (Map<String, Object> row : rows) {
			Unit from = group.byId((UUID) row.get("ingredient_id")).unit();
			location = location != null ? location : (String) row.get("storage_location");
			notes = notes != null ? notes : (String) row.get("notes");
			if (threshold == null && row.get("reorder_threshold") != null) {
				threshold = quantityIn((BigDecimal) row.get("reorder_threshold"), from, group.keep().unit());
			}
			if (row != winner) {
				jdbc.update("DELETE FROM inventory_items WHERE id = ?", row.get("id"));
			}
		}
		jdbc.update("""
				UPDATE inventory_items
				SET ingredient_id = ?, storage_location = ?, reorder_threshold = ?, notes = ?, updated_at = now()
				WHERE id = ?
				""", group.keep().id(), location, threshold, notes, winner.get("id"));
	}

	/**
	 * Shopping-list decisions (T-132: the list is derived; a row here is only what a person decided
	 * about a line). The group becomes one line, so its decisions become one: a typed quantity is the
	 * sum of the typed quantities, converted to the kept unit (none typed stays none, and the list
	 * goes on computing it); the line is ticked if anybody ticked any of them, and hand-added if any
	 * was. Nothing a person typed is dropped, which is the same rule the conductor gave for notes.
	 */
	private void mergeShoppingListDecisions(Group group) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Ingredient member : group.all()) {
			rows.addAll(jdbc.queryForList("""
					SELECT id, suggested_qty, unit, included, hand_added
					FROM shopping_list_lines WHERE ingredient_id = ?
					""", member.id()));
		}
		if (rows.isEmpty()) {
			return;
		}
		Unit keepUnit = group.keep().unit();
		BigDecimal qty = null;
		boolean included = false;
		boolean handAdded = false;
		for (Map<String, Object> row : rows) {
			if (row.get("suggested_qty") != null) {
				BigDecimal converted = quantityIn((BigDecimal) row.get("suggested_qty"),
						Unit.valueOf((String) row.get("unit")), keepUnit);
				qty = qty == null ? converted : qty.add(converted);
			}
			included |= (Boolean) row.get("included");
			handAdded |= (Boolean) row.get("hand_added");
		}
		Map<String, Object> winner = rows.get(0);
		for (Map<String, Object> row : rows) {
			if (row != winner) {
				jdbc.update("DELETE FROM shopping_list_lines WHERE id = ?", row.get("id"));
			}
		}
		jdbc.update("""
				UPDATE shopping_list_lines
				SET ingredient_id = ?, suggested_qty = ?, unit = ?, included = ?, hand_added = ?, updated_at = now()
				WHERE id = ?
				""", group.keep().id(), qty, keepUnit.name(), included, handAdded, winner.get("id"));
	}

	/**
	 * Recipe lines move to the kept ingredient with the preparation note (R-DUP-3 step 2). A recipe
	 * that used both Curd and "Curd, sour" keeps two lines — Curd, and Curd · sour — because different
	 * preparations are separate lines of a recipe; two lines with the same ingredient and note stay
	 * two as well, and no quantities are added together (conductor's ruling 2026-09-19). The line's own
	 * quantity and unit are untouched.
	 *
	 * @return the recipes whose lines changed, so their versions can be bumped
	 */
	private Set<UUID> mergeRecipeLines(Group group) {
		Set<UUID> recipes = new LinkedHashSet<>();
		for (Ingredient m : group.merged()) {
			String moved = group.notes().get(m.id());
			List<Map<String, Object>> lines = jdbc.queryForList(
					"SELECT id, recipe_id, preparation_note FROM recipe_ingredients WHERE ingredient_id = ?", m.id());
			for (Map<String, Object> line : lines) {
				jdbc.update("UPDATE recipe_ingredients SET ingredient_id = ?, preparation_note = ? WHERE id = ?",
						group.keep().id(), combineNotes(moved, (String) line.get("preparation_note")), line.get("id"));
				recipes.add((UUID) line.get("recipe_id"));
			}
		}
		return recipes;
	}

	/**
	 * A line that already has a note keeps it: the moved preparation first, then the existing note,
	 * leaving out any part already there — "sour" onto "whisked" is "sour, whisked", "sour" onto
	 * "sour" is "sour". Conductor's ruling 2026-09-19, "nothing a person wrote is lost", after the
	 * clarifier found the document silent on it.
	 */
	static String combineNotes(String moved, String existing) {
		List<String> parts = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String source : new String[] {moved, existing}) {
			if (source == null) {
				continue;
			}
			for (String part : source.split(",")) {
				String trimmed = part.strip();
				if (!trimmed.isEmpty() && seen.add(trimmed.toLowerCase(Locale.ROOT))) {
					parts.add(trimmed);
				}
			}
		}
		return parts.isEmpty() ? null : String.join(", ", parts);
	}

	private void repoint(Reference ref, List<UUID> from, UUID to) {
		jdbc.update(connection -> {
			var ps = connection.prepareStatement("UPDATE " + IngredientReferences.quote(ref.table())
					+ " SET " + IngredientReferences.quote(ref.column()) + " = ? WHERE "
					+ IngredientReferences.quote(ref.column()) + " = ANY (?)");
			ps.setObject(1, to);
			ps.setArray(2, connection.createArrayOf("uuid", from.toArray()));
			return ps;
		});
	}

	/**
	 * The market rate (R-ING-3) the kept ingredient carries afterwards is the most recent any member
	 * had, converted to the kept unit — a rate is a statement about the price on a day, so the newest
	 * one is the best one. On a tie the kept ingredient's own stands. Its history was moved with the
	 * other ledgers.
	 */
	private void mergeMarketRate(Group group) {
		Ingredient best = null;
		for (Ingredient member : group.all()) {
			if (member.marketRate() != null
					&& (best == null || member.marketRateOn().isAfter(best.marketRateOn()))) {
				best = member;
			}
		}
		if (best == null || best == group.keep()) {
			return;
		}
		jdbc.update("""
				UPDATE ingredients SET market_rate = ?, market_rate_on = ?, market_rate_source = ?, updated_at = now()
				WHERE id = ?
				""", priceIn(best.marketRate(), best.unit(), group.keep().unit()), best.marketRateOn(),
				best.marketRateSource(), group.keep().id());
	}

	/**
	 * The merged-away names, and the aliases those ingredients carried, become the kept ingredient's
	 * aliases — "so searching for it, or re-importing it, finds the kept ingredient" (R-DUP-3).
	 *
	 * <p><b>They go into both places an alias lives</b>, and both are needed:
	 * <ul>
	 *   <li>the {@code aliases} array on the row, which is what the catalogue screen shows and what
	 *       {@code IngredientService.update} treats as the truth: saving the kept ingredient later
	 *       re-derives its alias rows from this array and deletes the rest, so a name held only in
	 *       the table would be lost on the next unrelated edit;</li>
	 *   <li>the {@code ingredient_aliases} table (V144), which the library import and the duplicate
	 *       guard read. The merged-away ingredients' own rows are re-pointed with the other
	 *       references; here each merged-away name gets its row, keyed by
	 *       {@link IngredientNameMatcher#normalise} as {@code IngredientService} keys them. Several
	 *       names that normalise alike ("Curd, fresh" and "Curd, sour" are both "curd") are one row,
	 *       and a key some other ingredient already answers to is left with that ingredient: the name
	 *       is still on this one's array, which the search reads.</li>
	 * </ul>
	 *
	 * @return the names added to the array, in the order the group was given
	 */
	private List<String> addAliases(Group group) {
		Ingredient keep = group.keep();
		List<String> array = new ArrayList<>(keep.aliases());
		Set<String> present = new HashSet<>();
		present.add(keep.name().strip().toLowerCase(Locale.ROOT));
		array.forEach(a -> present.add(a.strip().toLowerCase(Locale.ROOT)));

		List<String> added = new ArrayList<>();
		for (Ingredient m : group.merged()) {
			List<String> names = new ArrayList<>();
			names.add(m.name());
			names.addAll(m.aliases());
			for (String name : names) {
				String trimmed = name.strip();
				if (!trimmed.isEmpty() && present.add(trimmed.toLowerCase(Locale.ROOT))) {
					array.add(trimmed);
					added.add(trimmed);
				}
				String key = IngredientNameMatcher.normalise(trimmed);
				if (!key.isBlank()) {
					jdbc.update("""
							INSERT INTO ingredient_aliases (tenant_id, ingredient_id, alias, normalised_alias)
							VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
							ON CONFLICT ON CONSTRAINT ingredient_aliases_one_per_name DO NOTHING
							""", keep.id(), trimmed, key);
				}
			}
		}
		if (!added.isEmpty()) {
			jdbc.update(connection -> {
				var ps = connection.prepareStatement(
						"UPDATE ingredients SET aliases = ?, updated_at = now() WHERE id = ?");
				ps.setArray(1, connection.createArrayOf("text", array.toArray()));
				ps.setObject(2, keep.id());
				return ps;
			});
		}
		return added;
	}

	/**
	 * One {@code INGREDIENT_MERGED} entry on the kept ingredient. The before-state is what the group
	 * was; the after-state is <em>read back from the rows</em> (docs/work/README.md lesson 2), never
	 * built from the request — including a count of what still points at the merged-away ingredients,
	 * which a reader can see is zero. And one {@code INGREDIENT_DELETED} on each merged-away
	 * ingredient, so its own history ends by saying where it went.
	 */
	private void audit(AuthenticatedUser actor, Group group, Map<String, Object> before,
			List<Map<String, Object>> mergedBefore, Map<String, Long> repointed, List<String> aliasesAdded) {
		UUID keep = group.keep().id();
		Ingredient keptNow = load(Set.of(keep)).get(keep);
		Map<String, Object> after = new LinkedHashMap<>();
		after.put("kept", snapshot(keptNow));
		after.put("aliasRows", jdbc.queryForList(
				"SELECT alias FROM ingredient_aliases WHERE ingredient_id = ? ORDER BY normalised_alias, alias",
				String.class, keep));
		after.put("mergedIngredientIds", group.merged().stream().map(i -> i.id().toString()).toList());
		after.put("stillPointingAtMerged", references.countPointingAt(
				group.merged().stream().map(Ingredient::id).toList()));
		after.put("repointed", repointed);
		after.put("aliasesAdded", aliasesAdded);

		String names = String.join(", ", group.merged().stream().map(Ingredient::name).toList());
		auditService.record(actor, AuditAction.INGREDIENT_MERGED, AuditEntityType.INGREDIENT, keep,
				before, after, "Merged " + names + " into " + keptNow.name() + ".");

		for (int i = 0; i < group.merged().size(); i++) {
			Ingredient m = group.merged().get(i);
			auditService.record(actor, AuditAction.INGREDIENT_DELETED, AuditEntityType.INGREDIENT, m.id(),
					mergedBefore.get(i), null, "Merged into " + keptNow.name() + ".");
		}
	}

	// ---------------------------------------------------------------------
	// Reading the group
	// ---------------------------------------------------------------------

	/**
	 * Reads and checks the group. Everything here is read on the caller's tenant-scoped connection,
	 * so an id from another temple is simply not there — refused as an invalid group, the same answer
	 * as an id that does not exist, and the same answer the tenant is given everywhere else.
	 */
	private Group resolve(MergeGroupInput input) {
		UUID keepId = input == null ? null : input.keepIngredientId();
		List<MergeGroupInput.Member> members = input == null ? List.of() : input.members();
		Set<UUID> ids = new LinkedHashSet<>();
		if (keepId == null || members.isEmpty()) {
			throw invalid("empty", keepId);
		}
		ids.add(keepId);
		for (MergeGroupInput.Member member : members) {
			if (member == null || member.ingredientId() == null || !ids.add(member.ingredientId())) {
				throw invalid("repeated", keepId);
			}
		}
		Map<UUID, Ingredient> found = load(ids);
		if (found.size() != ids.size()) {
			throw invalid("unknown", keepId);
		}

		List<Ingredient> merged = new ArrayList<>();
		Map<UUID, String> notes = new HashMap<>();
		for (MergeGroupInput.Member member : members) {
			Ingredient m = found.get(member.ingredientId());
			merged.add(m);
			String note = member.preparationNote() == null
					? IngredientNameMatcher.split(m.name()).preparation()
					: member.preparationNote();
			notes.put(m.id(), note == null || note.isBlank() ? null : note.strip());
		}
		return new Group(found.get(keepId), List.copyOf(merged), notes);
	}

	private void lockGroup(MergeGroupInput input) {
		if (input == null || input.keepIngredientId() == null) {
			return;
		}
		List<UUID> ids = new ArrayList<>();
		ids.add(input.keepIngredientId());
		input.members().stream().filter(Objects::nonNull).map(MergeGroupInput.Member::ingredientId)
				.filter(Objects::nonNull).forEach(ids::add);
		jdbc.query(connection -> {
			var ps = connection.prepareStatement(
					"SELECT id FROM ingredients WHERE id = ANY (?) ORDER BY id FOR UPDATE");
			ps.setArray(1, connection.createArrayOf("uuid", ids.toArray()));
			return ps;
		}, rs -> { });
	}

	private static ApplicationException invalid(String why, UUID keep) {
		return new ApplicationException(ErrorCode.MERGE_GROUP_INVALID,
				keep == null ? Map.of("why", why) : Map.of("why", why, "keep", keep));
	}

	private Map<UUID, Ingredient> load(Set<UUID> ids) {
		Map<UUID, Ingredient> out = new LinkedHashMap<>();
		jdbc.query(connection -> {
			var ps = connection.prepareStatement(
					"SELECT " + INGREDIENT_COLUMNS + " FROM ingredients WHERE id = ANY (?)");
			ps.setArray(1, connection.createArrayOf("uuid", ids.toArray()));
			return ps;
		}, rs -> {
			Ingredient i = INGREDIENT_MAPPER.mapRow(rs, 0);
			out.put(i.id(), i);
		});
		return out;
	}

	/**
	 * Every vendor that supplies two or more of the group at list prices that differ once they are
	 * in the kept unit. Only members in the kept ingredient's kind of unit are compared — a price per
	 * piece has nothing to be compared with — and a supply with no price conflicts with nothing.
	 */
	private List<MergeSupplyConflictView> conflicts(Group group, List<Supply> supplies) {
		Map<UUID, List<Supply>> byVendor = new LinkedHashMap<>();
		for (Supply s : supplies) {
			Ingredient member = group.byId(s.ingredientId());
			if (s.lastPrice() != null && member.unit().family() == group.keep().unit().family()) {
				byVendor.computeIfAbsent(s.vendorId(), v -> new ArrayList<>()).add(s);
			}
		}
		List<MergeSupplyConflictView> out = new ArrayList<>();
		for (List<Supply> rows : byVendor.values()) {
			Set<BigDecimal> distinct = new HashSet<>();
			for (Supply s : rows) {
				distinct.add(priceIn(s.lastPrice(), group.byId(s.ingredientId()).unit(), group.keep().unit())
						.stripTrailingZeros());
			}
			if (distinct.size() < 2) {
				continue;
			}
			List<MergeSupplyConflictView.Price> prices = new ArrayList<>();
			for (Supply s : rows) {
				Ingredient member = group.byId(s.ingredientId());
				prices.add(new MergeSupplyConflictView.Price(member.id(), member.name(), s.lastPrice(),
						member.unit().name(), packLabel(member.id(), s.packSizeId())));
			}
			out.add(new MergeSupplyConflictView(rows.get(0).vendorId(), rows.get(0).vendorName(), prices));
		}
		return out;
	}

	/**
	 * The admin's choices, checked against the conflicts actually there. A choice for a vendor with
	 * no conflict, or naming an ingredient that vendor does not price, is not a choice this group can
	 * take — KMS-400173. A conflict with no choice is KMS-400172, naming the vendors.
	 */
	private Map<UUID, UUID> resolveChoices(
			Group group, List<MergeSupplyConflictView> conflicts, MergeGroupInput input) {
		Map<UUID, UUID> chosen = new HashMap<>();
		for (MergeGroupInput.SupplyPriceChoice choice : input.choices()) {
			if (choice == null || choice.vendorId() == null || choice.keepPriceFromIngredientId() == null) {
				throw invalid("choice", group.keep().id());
			}
			MergeSupplyConflictView conflict = conflicts.stream()
					.filter(c -> c.vendorId().equals(choice.vendorId())).findFirst()
					.orElseThrow(() -> invalid("choice-without-conflict", group.keep().id()));
			boolean priced = conflict.prices().stream()
					.anyMatch(p -> p.ingredientId().equals(choice.keepPriceFromIngredientId()));
			if (!priced || chosen.put(choice.vendorId(), choice.keepPriceFromIngredientId()) != null) {
				throw invalid("choice-not-in-conflict", group.keep().id());
			}
		}
		List<MergeSupplyConflictView> open = conflicts.stream()
				.filter(c -> !chosen.containsKey(c.vendorId())).toList();
		if (!open.isEmpty()) {
			List<FieldError> details = new ArrayList<>();
			for (MergeSupplyConflictView c : open) {
				details.add(new FieldError("vendorId", c.vendorId().toString()));
				details.add(new FieldError("vendorName", c.vendorName()));
			}
			throw new ApplicationException(ErrorCode.MERGE_SUPPLY_PRICE_CHOICE_NEEDED,
					Map.of("keep", group.keep().id(), "vendors", open.size()), details, null);
		}
		return chosen;
	}

	private List<Supply> loadSupplies(Set<UUID> ids) {
		return jdbc.query(connection -> {
			var ps = connection.prepareStatement("""
					SELECT s.id, s.vendor_id, v.name AS vendor_name, s.ingredient_id, s.last_price,
						   s.preferred, s.pack_size_id, s.lead_time_days
					FROM vendor_supplies s
					JOIN vendors v ON v.id = s.vendor_id
					WHERE s.ingredient_id = ANY (?)
					ORDER BY v.name, s.created_at, s.id
					""");
			ps.setArray(1, connection.createArrayOf("uuid", ids.toArray()));
			return ps;
		}, (rs, n) -> new Supply(
				rs.getObject("id", UUID.class),
				rs.getObject("vendor_id", UUID.class),
				rs.getString("vendor_name"),
				rs.getObject("ingredient_id", UUID.class),
				rs.getBigDecimal("last_price"),
				rs.getBoolean("preferred"),
				rs.getObject("pack_size_id", UUID.class),
				(Integer) rs.getObject("lead_time_days")));
	}

	private String packLabel(UUID ingredientId, UUID packSizeId) {
		if (packSizeId == null) {
			return null;
		}
		return packSizes.forIngredient(ingredientId).stream()
				.filter(p -> p.id().equals(packSizeId))
				.map(PackSizeView::label)
				.findFirst().orElse(null);
	}

	/** On hand per ingredient in its family's base unit (gm, ml, pieces), from the ledger. */
	private Map<UUID, BigDecimal> onHandBase(Set<UUID> ids) {
		Map<UUID, BigDecimal> out = new HashMap<>();
		String sql = """
				SELECT ingredient_id, COALESCE(SUM(to_on_hand_qty(quantity, unit, movement_type)), 0) AS base
				FROM stock_movements
				""" + (ids == null ? "" : " WHERE ingredient_id = ANY (?)") + " GROUP BY ingredient_id";
		jdbc.query(connection -> {
			var ps = connection.prepareStatement(sql);
			if (ids != null) {
				ps.setArray(1, connection.createArrayOf("uuid", ids.toArray()));
			}
			return ps;
		}, rs -> {
			out.put(rs.getObject("ingredient_id", UUID.class), rs.getBigDecimal("base"));
		});
		return out;
	}

	private Map<UUID, Integer> countBy(String sql) {
		Map<UUID, Integer> out = new HashMap<>();
		jdbc.query(sql, rs -> {
			out.put(rs.getObject(1, UUID.class), rs.getInt(2));
		});
		return out;
	}

	private Map<UUID, Integer> countBy(String sql, Set<UUID> ids) {
		Map<UUID, Integer> out = new HashMap<>();
		jdbc.query(connection -> {
			var ps = connection.prepareStatement(sql);
			ps.setArray(1, connection.createArrayOf("uuid", ids.toArray()));
			return ps;
		}, rs -> {
			out.put(rs.getObject(1, UUID.class), rs.getInt(2));
		});
		return out;
	}

	private static MergeCandidateView candidate(Ingredient i, String note, Map<UUID, Integer> lines,
			Map<UUID, Integer> supplies, Map<UUID, BigDecimal> onHandBase) {
		return new MergeCandidateView(i.id(), i.name(), i.unit().name(), note,
				lines.getOrDefault(i.id(), 0),
				InventoryUnits.fromBase(onHandBase.getOrDefault(i.id(), BigDecimal.ZERO), i.unit()),
				supplies.getOrDefault(i.id(), 0));
	}

	/** An ingredient as the audit keeps it, read from the row and the rows around it. */
	private Map<String, Object> snapshot(Ingredient i) {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("id", i.id().toString());
		s.put("name", i.name());
		s.put("unit", i.unit().name());
		s.put("aliases", i.aliases());
		s.put("onHand", InventoryUnits.fromBase(
				onHandBase(Set.of(i.id())).getOrDefault(i.id(), BigDecimal.ZERO), i.unit()));
		s.put("recipeLines", jdbc.queryForObject(
				"SELECT count(*) FROM recipe_ingredients WHERE ingredient_id = ?", Integer.class, i.id()));
		s.put("supplies", jdbc.queryForList("""
				SELECT v.name AS vendor, s.last_price AS "listPrice", s.preferred
				FROM vendor_supplies s JOIN vendors v ON v.id = s.vendor_id
				WHERE s.ingredient_id = ? ORDER BY v.name
				""", i.id()));
		s.put("packSizes", packSizes.forIngredient(i.id()).stream().map(PackSizeView::label).toList());
		s.put("marketRate", i.marketRate());
		return s;
	}

	/**
	 * A price per one {@code from} unit as a price per one {@code to} unit, to the four places the
	 * price columns hold: ₹0.065 per gm is ₹65 per Kg. Same family only; the caller has checked.
	 */
	static BigDecimal priceIn(BigDecimal price, Unit from, Unit to) {
		if (price == null) {
			return null;
		}
		return price.multiply(BigDecimal.valueOf(to.baseFactor()))
				.divide(BigDecimal.valueOf(from.baseFactor()), 4, RoundingMode.HALF_UP);
	}

	/** A quantity in {@code from} as a quantity in {@code to}, to the three places quantities hold. */
	static BigDecimal quantityIn(BigDecimal quantity, Unit from, Unit to) {
		return InventoryUnits.toBase(quantity, from)
				.divide(BigDecimal.valueOf(to.baseFactor()), 3, RoundingMode.HALF_UP);
	}

	/**
	 * "Kg", "pieces": a unit named with no count beside it, in the sentence that says two ingredients
	 * are counted in different kinds of unit. Listed in UnitLabelAgreementTest's ALLOWED for that reason.
	 */
	private static String unitWord(Unit unit) {
		return unit.label();
	}

	private static String unitProblem(Ingredient keep, Ingredient other) {
		return keep.name() + " is in " + unitWord(keep.unit()) + ", " + other.name() + " is in "
				+ unitWord(other.unit());
	}

	// ---------------------------------------------------------------------

	record Ingredient(
			UUID id, String name, Unit unit, List<String> aliases, OffsetDateTime createdAt,
			BigDecimal marketRate, LocalDate marketRateOn, String marketRateSource) {
	}

	record Supply(
			UUID id, UUID vendorId, String vendorName, UUID ingredientId, BigDecimal lastPrice,
			boolean preferred, UUID packSizeId, Integer leadTimeDays) {
	}

	/** A resolved group: the kept ingredient, the others in the order given, and each one's note. */
	record Group(Ingredient keep, List<Ingredient> merged, Map<UUID, String> notes) {

		List<Ingredient> all() {
			List<Ingredient> all = new ArrayList<>();
			all.add(keep);
			all.addAll(merged);
			return all;
		}

		Set<UUID> ids() {
			Set<UUID> ids = new LinkedHashSet<>();
			all().forEach(i -> ids.add(i.id()));
			return ids;
		}

		Ingredient byId(UUID id) {
			return all().stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow();
		}

		/** The first member counted in a different kind of unit from the kept one, or null. */
		Ingredient firstOtherFamily() {
			return merged.stream().filter(m -> m.unit().family() != keep.unit().family()).findFirst().orElse(null);
		}
	}

	private static final String INGREDIENT_COLUMNS = """
			id, name, canonical_unit, aliases, created_at, market_rate, market_rate_on, market_rate_source
			""";

	private static final org.springframework.jdbc.core.RowMapper<Ingredient> INGREDIENT_MAPPER =
			(rs, n) -> new Ingredient(
					rs.getObject("id", UUID.class),
					rs.getString("name"),
					Unit.valueOf(rs.getString("canonical_unit")),
					readAliases(rs),
					rs.getObject("created_at", OffsetDateTime.class),
					rs.getBigDecimal("market_rate"),
					rs.getObject("market_rate_on", LocalDate.class),
					rs.getString("market_rate_source"));

	private static List<String> readAliases(ResultSet rs) throws SQLException {
		Array array = rs.getArray("aliases");
		return array == null ? List.of() : List.of((String[]) array.getArray());
	}
}
