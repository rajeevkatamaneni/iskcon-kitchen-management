package org.iskcon.kms.shoppinglist;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.ingredient.IngredientUnits;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.inventory.InventoryItemService;
import org.iskcon.kms.inventory.InventoryUnits;
import org.iskcon.kms.inventory.StockItemView;
import org.iskcon.kms.meal.ShortfallItem;
import org.iskcon.kms.meal.SufficiencyService;
import org.iskcon.kms.vendor.VendorService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The suggested shopping list (E5-S2): procurement that starts from data, not memory.
 *
 * <p>Regeneration merges two demand streams per ingredient — the meal-plan shortfall (E4-S5) and
 * below-threshold stock topped up to its reorder level × a safety factor (E3-S3) — suggests the
 * preferred vendor and a need-by date, and rounds up to whole purchase units. It is
 * <strong>edit-preserving</strong>: a line the staff has touched survives regeneration unchanged,
 * while unedited lines refresh and lines no longer needed drop off.
 *
 * <p>The threshold stream used to skip any ingredient flagged sattvic-prohibited, on the reasoning
 * that such a thing could only reach the list through a recipe an admin had overridden. D-18 deleted
 * that flag on 2026-09-08, so every ingredient the temple keeps stock of is now topped up on the
 * same terms — which is the accepted consequence of the ruling, not an oversight: a temple that does
 * not stock garlic has no inventory row for it to fall below.
 */
@Service
public class ShoppingListService {

	private static final BigDecimal SAFETY_FACTOR = new BigDecimal("1.2");
	private static final int LEAD_BUFFER_DAYS = 2;

	/**
	 * Every column a {@link ShoppingListLineView} is built from. Shared by the list and by the
	 * single-line read that answers a hand-add, so the two can never disagree about what a line is.
	 */
	private static final String LINE_SELECT = """
			SELECT o.ingredient_id, i.name AS ingredient_name, o.current_stock, o.unit,
				   o.suggested_qty, o.needed_by, o.suggested_vendor_id, v.name AS vendor_name,
				   o.provenance, o.included, o.edited
			FROM shopping_list_lines o
			JOIN ingredients i ON i.id = o.ingredient_id
			LEFT JOIN vendors v ON v.id = o.suggested_vendor_id
			""";

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;
	private final SufficiencyService sufficiencyService;
	private final InventoryItemService inventoryItemService;
	private final VendorService vendorService;
	private final IngredientUnits ingredientUnits;

	public ShoppingListService(
			JdbcTemplate jdbc, ObjectMapper objectMapper, SufficiencyService sufficiencyService,
			InventoryItemService inventoryItemService, VendorService vendorService,
			IngredientUnits ingredientUnits) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
		this.sufficiencyService = sufficiencyService;
		this.vendorService = vendorService;
		this.inventoryItemService = inventoryItemService;
		this.ingredientUnits = ingredientUnits;
	}

	@Transactional(readOnly = true)
	public List<ShoppingListLineView> list() {
		return jdbc.query(LINE_SELECT + "ORDER BY i.name", viewMapper());
	}

	/**
	 * Adds a line by hand (T-027) — something the cook knows is needed that no demand stream
	 * suggested. Returns the line as the screen will render it.
	 *
	 * <p><strong>It is written {@code edited = true}, and that is the whole substance of this
	 * method.</strong> Regeneration ends by deleting every line it did not just suggest and that no
	 * human has touched ({@code WHERE edited = false}, below). A hand-added line is by definition one
	 * no stream suggests, so written {@code edited = false} it would survive exactly until 04:30 the
	 * next morning and then vanish with no trace and nobody watching — the failure this whole
	 * edit-preserving design exists to prevent. {@code HandAddedLineIT} asserts it against a real
	 * regeneration rather than against the column, because the column is only evidence and the
	 * survival is the fact.
	 *
	 * <p><strong>A duplicate is refused, not merged.</strong> {@code shopping_list_lines} is unique
	 * on {@code (tenant_id, ingredient_id)}, so an ingredient already listed cannot become a second
	 * row. Of the two honest answers — overwrite the existing line, or say so — this says so
	 * (KMS-400131): the quantity on the existing line may be one the regenerator computed or one a
	 * colleague typed, and somebody adding what they think is a new line did not ask for either to be
	 * replaced. The line they wanted is already on the screen in front of them, with a box to change.
	 *
	 * <p>The refusal is read off {@code ON CONFLICT DO NOTHING} rather than a {@code SELECT} first:
	 * one statement, so two people adding the same thing at once get one line and one clean refusal
	 * instead of a race and a constraint violation nobody can read. Zero rows here can only mean the
	 * conflict — an insert barred by row-level security raises rather than silently affecting
	 * nothing.
	 *
	 * <p>{@code needed_by} is left null on purpose. Every other line's date is derived from the
	 * meal plan that demanded it, minus the lead buffer; nothing demanded this one, so there is no
	 * such date to compute and the screen prints an em dash rather than a guess.
	 */
	@Transactional
	public ShoppingListLineView addLine(AddShoppingListLineRequest request) {
		// Refuses an ingredient that does not exist. The lookup runs on the tenant-scoped
		// connection, so another temple's id is simply not found — the tenant comes from the
		// verified token by way of RLS, never from anything in this request body.
		Unit unit = ingredientUnits.canonicalUnit(request.ingredientId());

		// The vendor regeneration would have suggested, unless the caller named one. Without it the
		// line is not orderable at all: generation only picks up lines that have a vendor, so
		// deriving it here is the difference between a line somebody can act on and one that has to
		// be edited again before it can be.
		UUID vendorId = request.suggestedVendorId() != null
				? request.suggestedVendorId()
				: vendorService.preferredVendorId(request.ingredientId()).orElse(null);

		// The same context figure regeneration writes, for the same reason: the reviewer is deciding
		// how much to buy and needs to see what is already in the store room.
		BigDecimal currentStock = InventoryUnits.fromBase(onHandBase(request.ingredientId()), unit);

		int inserted = jdbc.update("""
				INSERT INTO shopping_list_lines (
					id, tenant_id, ingredient_id, suggested_qty, unit, current_stock, needed_by,
					suggested_vendor_id, provenance, included, edited)
				VALUES (gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid,
					?, ?, ?, ?, NULL, ?, '{}'::jsonb, true, true)
				ON CONFLICT (tenant_id, ingredient_id) DO NOTHING
				""", request.ingredientId(), request.suggestedQty(), unit.name(), currentStock, vendorId);
		if (inserted == 0) {
			throw new ApplicationException(
					ErrorCode.ALREADY_ON_THE_SHOPPING_LIST, Map.of("ingredientId", request.ingredientId()));
		}
		return findLine(request.ingredientId());
	}

	/**
	 * A human edit — marks the line so a later regeneration leaves it alone.
	 *
	 * <p>This is a {@code PATCH}: a field the caller did not mention keeps the value it had. Hence the
	 * {@code COALESCE} on both nullable columns. The vendor one matters most — the shopping-list screen
	 * sends only quantity and inclusion when a box is ticked or a quantity typed, and writing the
	 * absent id straight through nulled the suggested vendor on every such edit. That was invisible on
	 * the screen (a blank vendor cell either way, and a 204 back) but cost the next step: generation
	 * only picks up lines that have a vendor, so the line quietly stopped being orderable. Nothing is
	 * lost by coalescing — no screen offers clearing a vendor, and regeneration writes vendors through
	 * its own upsert rather than through here. {@code included} stays unconditional on purpose: both
	 * callers always send it, and it is {@code NOT NULL}, so an omission fails loudly instead of
	 * destroying a value.
	 */
	@Transactional
	public void updateLine(UUID ingredientId, UpdateShoppingListLineRequest request) {
		int updated = jdbc.update("""
				UPDATE shopping_list_lines
				SET suggested_qty = COALESCE(?, suggested_qty),
					suggested_vendor_id = COALESCE(?, suggested_vendor_id), included = ?,
					edited = true, updated_at = now()
				WHERE ingredient_id = ?
				""", request.suggestedQty(), request.suggestedVendorId(), request.included(), ingredientId);
		if (updated == 0) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("ingredientId", ingredientId));
		}
	}

	/**
	 * Regenerates the draft list for the current tenant, merging the two streams and preserving human
	 * edits. Returns the number of lines the generation produced (fresh suggestions).
	 */
	@Transactional
	public int regenerateForCurrentTenant() {
		Map<UUID, IngredientRef> refs = ingredientRefs();
		Map<UUID, BigDecimal> onHandBase = onHandBaseByIngredient();
		Map<UUID, LocalDate> earliestDemand = earliestDemandByIngredient();

		Map<UUID, Contribution> merged = new LinkedHashMap<>();

		// Stream 1: meal-plan shortfall.
		for (ShortfallItem s : sufficiencyService.shortfallFeed()) {
			merged.computeIfAbsent(s.ingredientId(), k -> new Contribution()).shortfall = s.shortBy();
		}

		// Stream 2: below-threshold stock, topped up to reorder level × safety.
		for (StockItemView item : inventoryItemService.lowStock()) {
			IngredientRef ref = refs.get(item.ingredientId());
			if (ref == null || item.reorderThreshold() == null) {
				continue;
			}
			BigDecimal target = item.reorderThreshold().multiply(SAFETY_FACTOR);
			BigDecimal topUp = target.subtract(item.onHand());
			if (topUp.signum() > 0) {
				merged.computeIfAbsent(item.ingredientId(), k -> new Contribution()).thresholdTopUp = topUp;
			}
		}

		// Stream 3: quantities still outstanding on sent / partially-received POs (E5-S6). A short
		// delivery re-feeds here so what was ordered but never arrived comes round again, traceable
		// to the PO that fell short.
		Map<UUID, PoOutstanding> poOutstanding = poOutstandingByIngredient();
		for (UUID ingredientId : poOutstanding.keySet()) {
			merged.computeIfAbsent(ingredientId, k -> new Contribution());
		}

		Set<UUID> fresh = merged.keySet();
		for (Map.Entry<UUID, Contribution> e : merged.entrySet()) {
			UUID ingredientId = e.getKey();
			Contribution c = e.getValue();
			IngredientRef ref = refs.get(ingredientId);
			if (ref == null) {
				continue;
			}
			PoOutstanding po = poOutstanding.get(ingredientId);
			if (po != null) {
				c.poOutstanding = InventoryUnits.fromBase(po.base(), ref.unit());
				c.shortPurchaseOrders = po.poNumbers();
			}
			BigDecimal qty = c.shortfall.max(c.thresholdTopUp).max(c.poOutstanding)
					.setScale(0, RoundingMode.CEILING);
			if (qty.signum() <= 0) {
				continue;
			}
			BigDecimal currentStock = InventoryUnits.fromBase(
					onHandBase.getOrDefault(ingredientId, BigDecimal.ZERO), ref.unit());
			LocalDate neededBy = earliestDemand.containsKey(ingredientId)
					? earliestDemand.get(ingredientId).minusDays(LEAD_BUFFER_DAYS) : null;
			UUID vendorId = vendorService.preferredVendorId(ingredientId).orElse(null);
			upsertLine(ingredientId, qty, ref.unit().name(), currentStock, neededBy, vendorId,
					provenanceJson(c));
		}

		// Drop unedited lines that are no longer suggested.
		if (fresh.isEmpty()) {
			jdbc.update("DELETE FROM shopping_list_lines WHERE edited = false");
		} else {
			String placeholders = fresh.stream().map(x -> "?").collect(Collectors.joining(", "));
			jdbc.update("DELETE FROM shopping_list_lines WHERE edited = false AND ingredient_id NOT IN ("
					+ placeholders + ")", fresh.toArray());
		}
		return fresh.size();
	}

	// ---------------------------------------------------------------------

	private void upsertLine(UUID ingredientId, BigDecimal qty, String unit, BigDecimal currentStock,
			LocalDate neededBy, UUID vendorId, String provenance) {
		jdbc.update(connection -> {
			var ps = connection.prepareStatement("""
					INSERT INTO shopping_list_lines (
						id, tenant_id, ingredient_id, suggested_qty, unit, current_stock, needed_by,
						suggested_vendor_id, provenance, included, edited)
					VALUES (gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						?, ?, ?, ?, ?, ?, ?::jsonb, true, false)
					ON CONFLICT (tenant_id, ingredient_id) DO UPDATE SET
						suggested_qty = CASE WHEN shopping_list_lines.edited
							THEN shopping_list_lines.suggested_qty ELSE EXCLUDED.suggested_qty END,
						suggested_vendor_id = CASE WHEN shopping_list_lines.edited
							THEN shopping_list_lines.suggested_vendor_id ELSE EXCLUDED.suggested_vendor_id END,
						included = CASE WHEN shopping_list_lines.edited
							THEN shopping_list_lines.included ELSE EXCLUDED.included END,
						unit = EXCLUDED.unit, current_stock = EXCLUDED.current_stock,
						needed_by = EXCLUDED.needed_by, provenance = EXCLUDED.provenance, updated_at = now()
					""");
			ps.setObject(1, ingredientId);
			ps.setBigDecimal(2, qty);
			ps.setString(3, unit);
			ps.setBigDecimal(4, currentStock);
			ps.setObject(5, neededBy);
			ps.setObject(6, vendorId);
			ps.setString(7, provenance);
			return ps;
		});
	}

	/**
	 * One line, as the screen renders it — the answer to a hand-add, read back through the same
	 * projection as the list so what the caller is handed is exactly what a reload would show.
	 */
	private ShoppingListLineView findLine(UUID ingredientId) {
		return jdbc.query(LINE_SELECT + "WHERE o.ingredient_id = ?", viewMapper(), ingredientId)
				.stream().findFirst()
				.orElseThrow(() -> new ApplicationException(
						ErrorCode.RESOURCE_NOT_FOUND, Map.of("ingredientId", ingredientId)));
	}

	/**
	 * What is in the store room for one ingredient, in base units. The same sum
	 * {@link #onHandBaseByIngredient()} takes for every ingredient at once, narrowed to one — a
	 * hand-add is about a single line and has no reason to read the whole ledger.
	 *
	 * <p>Both go through {@code to_on_hand_qty} (V116, T-122), which is what keeps the two of them —
	 * and the four elsewhere — saying the same number. See {@link #onHandBaseByIngredient()}.
	 */
	private BigDecimal onHandBase(UUID ingredientId) {
		BigDecimal base = jdbc.queryForObject("""
				SELECT COALESCE(SUM(to_on_hand_qty(quantity, unit, movement_type)), 0)
				FROM stock_movements WHERE ingredient_id = ?
				""", BigDecimal.class, ingredientId);
		return base == null ? BigDecimal.ZERO : base;
	}

	private String provenanceJson(Contribution c) {
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("shortfall", c.shortfall);
		p.put("thresholdTopUp", c.thresholdTopUp);
		p.put("poOutstanding", c.poOutstanding);
		if (!c.shortPurchaseOrders.isEmpty()) {
			p.put("shortPurchaseOrders", c.shortPurchaseOrders);
		}
		try {
			return objectMapper.writeValueAsString(p);
		} catch (JsonProcessingException e) {
			throw new ApplicationException(ErrorCode.UNEXPECTED_FAILURE, Map.of(), e);
		}
	}

	/**
	 * Per ingredient, what is still outstanding across SENT and PARTIALLY_RECEIVED POs — the ordered
	 * quantity minus everything received so far — summed in base units, with the PO numbers that fell
	 * short. Rejected goods are not received, so they remain outstanding and come round again.
	 *
	 * <p><strong>Described PO lines are excluded, and the exclusion is the whole point</strong>
	 * (T-024). A line may now name something the catalogue has never heard of — four plastic stools
	 * — in which case {@code ingredient_id} is null. This method keys a map by that column, and a
	 * null key is not a refusal: it is a perfectly valid {@code LinkedHashMap} key. Every described
	 * line on every live order would therefore have collapsed into one bucket under the key
	 * {@code null}, adding stools to extension cords in base units, and whatever ingredient row
	 * later asked the map for its outstanding quantity would have got an answer computed from
	 * furniture. Silent, and wrong in the direction that under-orders food.
	 *
	 * <p>Excluded in SQL rather than skipped in the handler so that the intent survives a later
	 * edit to the loop, and so the reason sits next to the column it is about.
	 */
	private Map<UUID, PoOutstanding> poOutstandingByIngredient() {
		Map<UUID, PoOutstanding> map = new LinkedHashMap<>();
		jdbc.query("""
				SELECT pol.ingredient_id, po.po_number, pol.unit,
					   pol.quantity - COALESCE(r.received, 0) AS outstanding
				FROM purchase_order_lines pol
				JOIN purchase_orders po ON po.id = pol.po_id
				LEFT JOIN (
					SELECT po_line_id, SUM(received_qty) AS received
					FROM goods_receipt_lines GROUP BY po_line_id
				) r ON r.po_line_id = pol.id
				WHERE po.status IN ('SENT', 'PARTIALLY_RECEIVED')
				  AND pol.ingredient_id IS NOT NULL
				""", rs -> {
			BigDecimal outstanding = rs.getBigDecimal("outstanding");
			if (outstanding == null || outstanding.signum() <= 0) {
				return;
			}
			UUID ingredientId = rs.getObject("ingredient_id", UUID.class);
			BigDecimal base = InventoryUnits.toBase(outstanding, Unit.valueOf(rs.getString("unit")));
			PoOutstanding agg = map.computeIfAbsent(ingredientId,
					k -> new PoOutstanding(BigDecimal.ZERO, new ArrayList<>()));
			agg.poNumbers().add(rs.getString("po_number"));
			map.put(ingredientId, new PoOutstanding(agg.base().add(base), agg.poNumbers()));
		});
		return map;
	}

	private Map<UUID, IngredientRef> ingredientRefs() {
		Map<UUID, IngredientRef> refs = new LinkedHashMap<>();
		jdbc.query("SELECT id, canonical_unit FROM ingredients", rs -> {
			refs.put(rs.getObject("id", UUID.class),
					new IngredientRef(Unit.valueOf(rs.getString("canonical_unit"))));
		});
		return refs;
	}

	/**
	 * What the store room holds, per ingredient, in base units.
	 *
	 * <p><strong>{@code to_on_hand_qty}, never {@code to_base_qty} (V116, T-122).</strong> A
	 * {@code USED_BEYOND_RECORDED_STOCK} row says a meal was cooked with more of something than the
	 * books held. It is a record of a discrepancy rather than a movement of stock, and it counts as
	 * zero here as it does everywhere else.
	 *
	 * <p>It is worth being explicit about what that costs this list, because it is a real change and
	 * it is the right one. While the shortfall subtracted, an ingredient forty kilos in the red
	 * pulled forty extra kilos onto the shopping list, and that pressure was defended as what gets
	 * the missing delivery written down. It is not: the temple would have bought forty kilos of rice
	 * it may well already have, on the strength of a paperwork failure. The suggestion is now
	 * computed from a shelf of zero, and the thing that gets chased is the row in the ledger with
	 * the ingredient's name in it.
	 */
	private Map<UUID, BigDecimal> onHandBaseByIngredient() {
		Map<UUID, BigDecimal> map = new LinkedHashMap<>();
		jdbc.query("""
				SELECT ingredient_id,
					   SUM(to_on_hand_qty(quantity, unit, movement_type)) AS base
				FROM stock_movements GROUP BY ingredient_id
				""", rs -> {
			map.put(rs.getObject("ingredient_id", UUID.class), rs.getBigDecimal("base"));
		});
		return map;
	}

	private Map<UUID, LocalDate> earliestDemandByIngredient() {
		Map<UUID, LocalDate> map = new LinkedHashMap<>();
		jdbc.query("""
				SELECT ri.ingredient_id, MIN(mp.plan_date) AS earliest
				FROM meal_plans mp
				JOIN recipe_ingredients ri ON ri.recipe_id = mp.recipe_id
				WHERE mp.status = 'PLANNED' AND mp.plan_date >= CURRENT_DATE
				GROUP BY ri.ingredient_id
				""", rs -> {
			map.put(rs.getObject("ingredient_id", UUID.class), rs.getObject("earliest", LocalDate.class));
		});
		return map;
	}

	private RowMapper<ShoppingListLineView> viewMapper() {
		return (rs, n) -> {
			String provenance = rs.getString("provenance");
			Map<String, BigDecimal> prov = parseProvenance(provenance);
			return new ShoppingListLineView(
					rs.getObject("ingredient_id", UUID.class),
					rs.getString("ingredient_name"),
					rs.getBigDecimal("current_stock"),
					rs.getString("unit"),
					rs.getBigDecimal("suggested_qty"),
					rs.getObject("needed_by", LocalDate.class),
					rs.getObject("suggested_vendor_id", UUID.class),
					rs.getString("vendor_name"),
					prov.getOrDefault("shortfall", BigDecimal.ZERO),
					prov.getOrDefault("thresholdTopUp", BigDecimal.ZERO),
					prov.getOrDefault("poOutstanding", BigDecimal.ZERO),
					parseShortPurchaseOrders(provenance),
					rs.getBoolean("included"),
					rs.getBoolean("edited"));
		};
	}

	private List<String> parseShortPurchaseOrders(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<>() {
			});
			Object list = raw.get("shortPurchaseOrders");
			if (list instanceof List<?> l) {
				return l.stream().map(String::valueOf).toList();
			}
			return List.of();
		} catch (JsonProcessingException e) {
			return List.of();
		}
	}

	private Map<String, BigDecimal> parseProvenance(String json) {
		if (json == null || json.isBlank()) {
			return Map.of();
		}
		try {
			Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<>() {
			});
			return raw.entrySet().stream()
					.filter(e -> e.getValue() instanceof Number)
					.collect(Collectors.toMap(Map.Entry::getKey, e -> new BigDecimal(e.getValue().toString())));
		} catch (JsonProcessingException e) {
			return Map.of();
		}
	}

	private static final class Contribution {
		BigDecimal shortfall = BigDecimal.ZERO;
		BigDecimal thresholdTopUp = BigDecimal.ZERO;
		BigDecimal poOutstanding = BigDecimal.ZERO;
		List<String> shortPurchaseOrders = List.of();
	}

	private record IngredientRef(Unit unit) {
	}

	/** Outstanding PO demand for one ingredient: total in base units and the PO numbers behind it. */
	private record PoOutstanding(BigDecimal base, List<String> poNumbers) {
	}
}
