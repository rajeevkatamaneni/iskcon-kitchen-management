package org.iskcon.kms.ingredient;

import java.math.BigDecimal;
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
import org.iskcon.kms.inventory.InventoryUnits;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An ingredient's pack sizes — its alternate units (R-ING-1): "Bag = 25 Kg", "Tin = 15 L", or a
 * plain "500 gm". One stock unit per ingredient, which is what is counted and cooked, plus
 * conversions defined once; stock and costing never leave the stock unit, and a pack is only how a
 * thing is bought and billed (Rajeev, 2026-09-19).
 *
 * <p><b>Who.</b> Adding and removing a pack is editing the ingredient, so it sits behind the same
 * permission as every other descriptive edit, {@code MANAGE_RECIPES}, and goes on the audit trail
 * the same way: as an {@code INGREDIENT_UPDATED} entry on the ingredient, with its packs before and
 * after. There is no pack-size audit action of its own, and none was reserved; a reviewer asking
 * "what happened to this ingredient" finds pack changes beside its renames, which is where they
 * belong.
 *
 * <p><b>Which rule lives where, and why.</b> V144 put four of the five rules in the database and
 * left two to the service. Each refusal here is either the database's own answer translated into a
 * code a person can act on, or a check the database cannot make reliably:
 *
 * <ul>
 *   <li><b>Positive, and a valid unit</b> — the request is bean-validated and the unit parsed before
 *       anything is written, so these are field errors next to the box. The CHECKs in V144 still
 *       stand behind them.
 *   <li><b>No duplicate</b> ({@code PACK_SIZE_ALREADY_THERE}, KMS-400157) — <em>translated only</em>,
 *       not pre-checked. The unique index {@code ingredient_pack_sizes_no_duplicate} compares the
 *       size in the family's base unit, so "500 gm" and "Pack = 0.5 Kg" collide whatever they are
 *       called (Q-14: Rajeev confirmed this as built, 2026-09-19). A pre-check here would be a
 *       second statement of the same rule that could only ever agree or be wrong, so the index is
 *       asked and its answer translated.
 *   <li><b>Same unit family</b> ({@code INCOMPATIBLE_UNIT}, KMS-400013) — <em>both</em>. The
 *       pre-check is {@link IngredientUnits#requireSameFamily}, the one rule every quantity in the
 *       product obeys, and it is there because it says <em>which</em> ingredient and which units in
 *       words ("Rice is measured in Kg, and there is no way to turn L into Kg."). The trigger
 *       {@code ingredient_pack_sizes_same_family} is translated to the same code for any path that
 *       gets past it — which, with the lock below, should be none.
 *   <li><b>At most 8</b> ({@code TOO_MANY_PACK_SIZES}, KMS-400158) — counted here, which V144 left
 *       to the service because a count in a trigger is a read-then-write two people saving at once
 *       would both pass. It is reliable here only because of the lock: see {@link #lockIngredient}.
 *   <li><b>In use</b> ({@code PACK_SIZE_IN_USE}, KMS-400159) — the RESTRICT foreign keys from
 *       {@code vendor_supplies}, {@code vendor_invoice_lines} and, since V146,
 *       {@code purchase_order_lines}, translated. A pack a vendor sells in must not vanish from under
 *       that vendor, and a pack an order was placed in must not either: the sheet that said "4 × Bag
 *       (25 Kg)" has to go on meaning what it told the vendor (R-SL-3, T-260).
 * </ul>
 *
 * <p>The fifth — refusing to move an ingredient's canonical unit into another family while it has
 * packs — belongs to {@link IngredientService#update}, which is where the unit is changed.
 *
 * <p>RLS scopes every statement to the acting temple. Another temple's ingredient is not found,
 * so a pack can neither be read from it nor added to it, and the trigger refuses the insert too if
 * anything ever did reach it.
 */
@Service
public class PackSizeService {

	/**
	 * The most pack sizes one ingredient may have (R-ING-1). A shopping list choosing "the fewest
	 * packs with the least left over" from more sizes than this is choosing between sizes nobody
	 * actually buys.
	 */
	static final int MAX_PACK_SIZES = 8;

	/** SQLSTATEs the refusals are recognised by. The driver is a runtime dependency, so no PSQLException here. */
	private static final String UNIQUE_VIOLATION = "23505";
	private static final String FOREIGN_KEY_VIOLATION = "23503";
	private static final String CHECK_VIOLATION = "23514";
	private static final String SAME_FAMILY_TRIGGER = "ingredient_pack_sizes_same_family";

	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final IngredientUnits ingredientUnits;

	public PackSizeService(JdbcTemplate jdbc, AuditService auditService, IngredientUnits ingredientUnits) {
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.ingredientUnits = ingredientUnits;
	}

	// ---- Reads -----------------------------------------------------------

	/**
	 * Every pack size in the temple, grouped by ingredient, smallest first — for the catalogue list.
	 *
	 * <p>One query for the whole catalogue (about 230 ingredients), not one per row. The list is the
	 * screen every picker and the ingredients page load, and a query per ingredient there would be
	 * 230 round trips to draw a page.
	 */
	@Transactional(readOnly = true)
	public Map<UUID, List<PackSizeView>> allByIngredient() {
		Map<UUID, List<PackSizeView>> byIngredient = new LinkedHashMap<>();
		jdbc.query(SELECT_PACKS + " ORDER BY p.ingredient_id, p.base_quantity, p.name NULLS FIRST",
				rs -> {
					byIngredient.computeIfAbsent(rs.getObject("ingredient_id", UUID.class), k -> new ArrayList<>())
							.add(PACK_MAPPER.mapRow(rs, 0));
				});
		return byIngredient;
	}

	/** One ingredient's pack sizes, smallest first. Empty for an ingredient this temple cannot see. */
	@Transactional(readOnly = true)
	public List<PackSizeView> forIngredient(UUID ingredientId) {
		return jdbc.query(SELECT_PACKS + " WHERE p.ingredient_id = ? ORDER BY p.base_quantity, p.name NULLS FIRST",
				PACK_MAPPER, ingredientId);
	}

	// ---- Writes ----------------------------------------------------------

	@Transactional
	public UUID add(AuthenticatedUser actor, UUID ingredientId, AddPackSizeRequest request) {
		Unit unit = IngredientUnits.parse(request.unit());
		// A blank name is no name. The column refuses a blank rather than storing one, so it is
		// turned into the null that means "a plain size" here instead of failing a CHECK.
		String name = request.name() == null || request.name().isBlank() ? null : request.name().trim();

		String ingredientName = lockIngredient(ingredientId);
		ingredientUnits.requireSameFamily(ingredientId, unit);

		List<PackSizeView> before = forIngredient(ingredientId);
		if (before.size() >= MAX_PACK_SIZES) {
			throw new ApplicationException(ErrorCode.TOO_MANY_PACK_SIZES,
					Map.of("ingredientId", ingredientId, "count", before.size()));
		}

		UUID id;
		try {
			id = jdbc.queryForObject("""
					INSERT INTO ingredient_pack_sizes (tenant_id, ingredient_id, name, quantity, unit)
					VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?)
					RETURNING id
					""", UUID.class, ingredientId, name, request.quantity(), unit.name());
		} catch (DataIntegrityViolationException e) {
			String state = sqlState(e);
			if (UNIQUE_VIOLATION.equals(state)) {
				// The only unique index a fresh row can meet: the same size in base units as a pack
				// this ingredient already has, whatever either is called (V144; Q-14, confirmed
				// by Rajeev as built, 2026-09-19).
				throw new ApplicationException(ErrorCode.PACK_SIZE_ALREADY_THERE,
						Map.of("ingredientId", ingredientId, "quantity", request.quantity(), "unit", unit.name()), e);
			}
			if (CHECK_VIOLATION.equals(state) && String.valueOf(e.getMostSpecificCause().getMessage())
					.contains(SAME_FAMILY_TRIGGER)) {
				throw new ApplicationException(ErrorCode.INCOMPATIBLE_UNIT,
						Map.of("ingredientId", ingredientId, "given", unit.name()), e);
			}
			throw e;
		}

		List<PackSizeView> after = forIngredient(ingredientId);
		String label = after.stream().filter(p -> p.id().equals(id)).findFirst()
				.map(PackSizeView::label).orElse("");
		audit(actor, ingredientId, ingredientName, before, after, "Added pack size " + label + ".");
		return id;
	}

	@Transactional
	public void remove(AuthenticatedUser actor, UUID ingredientId, UUID packSizeId) {
		String ingredientName = lockIngredient(ingredientId);
		List<PackSizeView> before = forIngredient(ingredientId);
		PackSizeView pack = before.stream().filter(p -> p.id().equals(packSizeId)).findFirst()
				.orElseThrow(() -> new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND,
						Map.of("ingredientId", ingredientId, "packSizeId", packSizeId)));

		try {
			jdbc.update("DELETE FROM ingredient_pack_sizes WHERE id = ? AND ingredient_id = ?",
					packSizeId, ingredientId);
		} catch (DataIntegrityViolationException e) {
			if (FOREIGN_KEY_VIOLATION.equals(sqlState(e))) {
				// vendor_supplies.pack_size_id ("sells it as"), vendor_invoice_lines.pack_size_id or
				// purchase_order_lines.pack_size_id (V146, T-260) — all three RESTRICT, and every
				// order line that uses the pack counts, whatever the order's status: a cancelled
				// order's sheet still said "4 × Bag (25 Kg)". Translated rather than pre-checked: the
				// foreign key is the rule, and a pre-check would race a vendor or an order choosing
				// this pack in the same moment. A fourth table pointing at packs needs nothing here
				// beyond a RESTRICT foreign key; PackSizeIT proves each one reaches this line.
				throw new ApplicationException(ErrorCode.PACK_SIZE_IN_USE,
						Map.of("ingredientId", ingredientId, "packSizeId", packSizeId), e);
			}
			throw e;
		}

		List<PackSizeView> after = forIngredient(ingredientId);
		audit(actor, ingredientId, ingredientName, before, after, "Removed pack size "
				// Rebuilt from the pack's fields rather than read off the view: UnitLabelAgreementTest
				// scans files that mention Unit for a bare label() call, and this one is the chip's.
				+ label(pack.name(), pack.quantity(), Unit.valueOf(pack.unit())) + ".");
	}

	// ---------------------------------------------------------------------

	/**
	 * Takes the ingredient's row lock for the rest of the transaction and returns its name, or
	 * refuses as not found — which is also what another temple's ingredient is, by RLS.
	 *
	 * <p><b>Why a lock.</b> The limit of 8 is a count followed by an insert. Two people adding a
	 * ninth and a tenth size at once would both count seven and both insert. Locking the ingredient
	 * first makes the second wait until the first commits, and then it counts eight. The same lock
	 * is what {@link IngredientService#update} takes before it checks for packs on a family change,
	 * so a pack cannot be added under a unit that is changing family in the same moment either.
	 */
	private String lockIngredient(UUID ingredientId) {
		return jdbc.query("SELECT name FROM ingredients WHERE id = ? FOR UPDATE",
						(rs, n) -> rs.getString("name"), ingredientId)
				.stream().findFirst()
				.orElseThrow(() -> new ApplicationException(
						ErrorCode.RESOURCE_NOT_FOUND, Map.of("ingredientId", ingredientId)));
	}

	private void audit(AuthenticatedUser actor, UUID ingredientId, String ingredientName,
			List<PackSizeView> before, List<PackSizeView> after, String reason) {
		auditService.record(actor, AuditAction.INGREDIENT_UPDATED, AuditEntityType.INGREDIENT, ingredientId,
				packSnapshot(ingredientName, before), packSnapshot(ingredientName, after), reason);
	}

	private static Map<String, Object> packSnapshot(String ingredientName, List<PackSizeView> packs) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("name", ingredientName);
		snapshot.put("packSizes", packs.stream().map(PackSizeView::label).toList());
		return snapshot;
	}

	/** The SQLSTATE of the database error under a Spring data-access exception, or null. */
	private static String sqlState(DataIntegrityViolationException e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof SQLException sql && sql.getSQLState() != null) {
				return sql.getSQLState();
			}
		}
		return null;
	}

	/** The chip text: "250 gm", "1 Kg", "Bag = 25 Kg" — the size in readable units, named if it has one. */
	static String label(String name, BigDecimal quantity, Unit unit) {
		String size = Quantities.exact(quantity, unit);
		return name == null ? size : name + " = " + size;
	}

	/*
	  The ingredient's canonical unit comes along so baseQuantity can be given in it. The join runs
	  under RLS like everything else, so it adds no reach: a pack is only ever read beside its own
	  temple's ingredient.
	*/
	private static final String SELECT_PACKS = """
			SELECT p.id, p.ingredient_id, p.name, p.quantity, p.unit, p.base_quantity,
					i.canonical_unit
			FROM ingredient_pack_sizes p
			JOIN ingredients i ON i.id = p.ingredient_id
			""";

	private static final RowMapper<PackSizeView> PACK_MAPPER = (rs, rowNum) -> {
		Unit unit = Unit.valueOf(rs.getString("unit"));
		Unit canonical = Unit.valueOf(rs.getString("canonical_unit"));
		BigDecimal quantity = rs.getBigDecimal("quantity").stripTrailingZeros();
		if (quantity.scale() < 0) {
			quantity = quantity.setScale(0);
		}
		String name = rs.getString("name");
		return new PackSizeView(
				rs.getObject("id", UUID.class),
				name,
				quantity,
				unit.name(),
				// base_quantity is in grams, millilitres or pieces; fromBase says it in the canonical
				// unit, and the same-family rule is what guarantees the two share a base.
				InventoryUnits.fromBase(rs.getBigDecimal("base_quantity"), canonical),
				label(name, quantity, unit));
	};
}
