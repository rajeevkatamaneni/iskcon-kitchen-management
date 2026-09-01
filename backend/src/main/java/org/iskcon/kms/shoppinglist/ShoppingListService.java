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
 * while unedited lines refresh and lines no longer needed drop off. Sattvic-prohibited ingredients
 * never enter via the threshold stream; they can only appear through a shortfall, which itself only
 * exists when an E2-S4 override legitimately put the ingredient in a planned recipe.
 */
@Service
public class ShoppingListService {

	private static final BigDecimal SAFETY_FACTOR = new BigDecimal("1.2");
	private static final int LEAD_BUFFER_DAYS = 2;

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;
	private final SufficiencyService sufficiencyService;
	private final InventoryItemService inventoryItemService;
	private final VendorService vendorService;

	public ShoppingListService(
			JdbcTemplate jdbc, ObjectMapper objectMapper, SufficiencyService sufficiencyService,
			InventoryItemService inventoryItemService, VendorService vendorService) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
		this.sufficiencyService = sufficiencyService;
		this.vendorService = vendorService;
		this.inventoryItemService = inventoryItemService;
	}

	@Transactional(readOnly = true)
	public List<ShoppingListLineView> list() {
		return jdbc.query("""
				SELECT o.ingredient_id, i.name AS ingredient_name, o.current_stock, o.unit,
					   o.suggested_qty, o.needed_by, o.suggested_vendor_id, v.name AS vendor_name,
					   o.provenance, o.included, o.edited
				FROM shopping_list_lines o
				JOIN ingredients i ON i.id = o.ingredient_id
				LEFT JOIN vendors v ON v.id = o.suggested_vendor_id
				ORDER BY i.name
				""", viewMapper());
	}

	/** A human edit — marks the line so a later regeneration leaves it alone. */
	@Transactional
	public void updateLine(UUID ingredientId, UpdateShoppingListLineRequest request) {
		int updated = jdbc.update("""
				UPDATE shopping_list_lines
				SET suggested_qty = COALESCE(?, suggested_qty), suggested_vendor_id = ?, included = ?,
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

		// Stream 1: meal-plan shortfall (already gated by recipe sattvic overrides).
		for (ShortfallItem s : sufficiencyService.shortfallFeed()) {
			merged.computeIfAbsent(s.ingredientId(), k -> new Contribution()).shortfall = s.shortBy();
		}

		// Stream 2: below-threshold stock, topped up to reorder level × safety. Sattvic-prohibited
		// ingredients are excluded here — they may only reach the list via a shortfall.
		for (StockItemView item : inventoryItemService.lowStock()) {
			IngredientRef ref = refs.get(item.ingredientId());
			if (ref == null || ref.sattvicProhibited() || item.reorderThreshold() == null) {
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
		jdbc.query("SELECT id, canonical_unit, is_sattvic_prohibited FROM ingredients", rs -> {
			refs.put(rs.getObject("id", UUID.class), new IngredientRef(
					Unit.valueOf(rs.getString("canonical_unit")), rs.getBoolean("is_sattvic_prohibited")));
		});
		return refs;
	}

	private Map<UUID, BigDecimal> onHandBaseByIngredient() {
		Map<UUID, BigDecimal> map = new LinkedHashMap<>();
		jdbc.query("""
				SELECT ingredient_id,
					   SUM(to_base_qty(quantity, unit)) AS base
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

	private record IngredientRef(Unit unit, boolean sattvicProhibited) {
	}

	/** Outstanding PO demand for one ingredient: total in base units and the PO numbers behind it. */
	private record PoOutstanding(BigDecimal base, List<String> poNumbers) {
	}
}
