package org.iskcon.kms.vendor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.ingredient.Quantities;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.shift.TenantSettingsService;
import org.iskcon.kms.tenancy.TempleClock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vendor records and who supplies what (E5-S1). Staff-managed; RLS confines everything to the tenant.
 * A vendor is deactivated, never deleted — its purchase-order history must keep rendering — and one
 * vendor can be the preferred source for an ingredient, which the shopping-list suggestions consume.
 *
 * <p>Deactivating one requires a reason, and every such change is kept as history rather than as a
 * field: see {@link #setActive}. A contract end date can be recorded alongside, and is warned about
 * on the screens; nothing here reads it to decide anything.
 */
@Service
public class VendorService {


	private final TempleClock clock;
	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final TenantSettingsService tenantSettings;
	private final VendorPriceHistoryService priceHistory;

	public VendorService(
			JdbcTemplate jdbc, AuditService auditService, TenantSettingsService tenantSettings, TempleClock clock,
			VendorPriceHistoryService priceHistory) {
		this.clock = clock;
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.tenantSettings = tenantSettings;
		this.priceHistory = priceHistory;
	}

	@Transactional(readOnly = true)
	public List<VendorView> list(boolean includeInactive) {
		String sql = SELECT + (includeInactive ? "" : " WHERE active = true") + " ORDER BY name";
		return jdbc.query(sql, vendorMapper());
	}

	@Transactional(readOnly = true)
	public VendorDetailView get(UUID id) {
		VendorView vendor = findById(id).orElseThrow(() -> notFound(id));
		List<VendorSupplyView> supplies = jdbc.query(
				SUPPLY_SELECT + " WHERE vs.vendor_id = ? ORDER BY i.name", SUPPLY_MAPPER, id);
		return new VendorDetailView(vendor, supplies, statusHistory(id));
	}

	/**
	 * Every vendor that supplies an ingredient, seen from the ingredient's page (R-ING-2), in vendor
	 * name order.
	 *
	 * <p>Every supply row is returned, a deactivated vendor's included, because this is a read of what
	 * {@code vendor_supplies} says, the same as the vendor page's table. Which vendors the ingredient
	 * page's dropdown <em>offers</em> for a new link (active ones only, the conductor's call of
	 * 2026-09-19) is the screen's concern and a later task. Setting a link from the ingredient side
	 * is the same {@link #setSupply} the vendor page uses, so there is one write path, not two.
	 */
	@Transactional(readOnly = true)
	public List<IngredientSupplyView> suppliesOf(UUID ingredientId) {
		return jdbc.query(SUPPLY_SELECT + " WHERE vs.ingredient_id = ? ORDER BY v.name, v.id",
				(rs, n) -> IngredientSupplyView.of(
						rs.getObject("vendor_id", UUID.class), rs.getString("vendor_name"),
						SUPPLY_MAPPER.mapRow(rs, n)),
				ingredientId);
	}

	/**
	 * Why this vendor has been dropped, and brought back, most recent first.
	 *
	 * <p>Read here rather than from the audit log on purpose. The audit log answers to
	 * {@code VIEW_AUDIT_LOG}, which only a Temple Admin holds; vendors answer to
	 * {@code MANAGE_VENDORS}, which a Kitchen Manager and Kitchen Staff hold too. The person
	 * deciding whether to bring a supplier back is usually not the person who may read the temple's
	 * donations and pay changes, and this history should not cost them that permission.
	 */
	private List<VendorStatusChange> statusHistory(UUID vendorId) {
		return jdbc.query("""
				SELECT c.id, c.from_active, c.to_active, c.reason,
					   c.actor_user_id, u.full_name AS actor_name, c.created_at
				FROM vendor_status_changes c
				LEFT JOIN users u ON u.id = c.actor_user_id
				WHERE c.vendor_id = ?
				ORDER BY c.created_at DESC, c.id DESC
				""", STATUS_MAPPER, vendorId);
	}

	/** The preferred vendor for an ingredient, if one is designated (E5-S2). */
	@Transactional(readOnly = true)
	public Optional<UUID> preferredVendorId(UUID ingredientId) {
		return jdbc.query(
				"SELECT vendor_id FROM vendor_supplies WHERE ingredient_id = ? AND preferred",
				(rs, n) -> rs.getObject("vendor_id", UUID.class), ingredientId).stream().findFirst();
	}

	/**
	 * Every ingredient in the temple that has a preferred vendor, and who that vendor is (R-VEN-2).
	 *
	 * <p>What the vendor page reads to say "Preferred (replaces Anand Stores)" beside a tick before it
	 * is saved: the vendor being replaced is not on that page, so the page cannot know it any other
	 * way. One list for the whole temple rather than a call per row, because the Other ingredients
	 * table holds about two hundred rows.
	 *
	 * <p>A deactivated vendor is listed too. Deactivating one leaves it the preferred source (see
	 * {@link #contractEndingSoon}), so ticking Preferred elsewhere really does replace it, and the
	 * sentence should say so. At most one row per ingredient, because V24's unique index allows no
	 * more; row-level security keeps it to this temple.
	 */
	@Transactional(readOnly = true)
	public List<PreferredVendorView> preferredVendors() {
		return jdbc.query("""
				SELECT vs.ingredient_id, vs.vendor_id, v.name AS vendor_name
				FROM vendor_supplies vs
				JOIN vendors v ON v.id = vs.vendor_id
				WHERE vs.preferred
				ORDER BY vs.ingredient_id
				""", (rs, n) -> new PreferredVendorView(
						rs.getObject("ingredient_id", UUID.class),
						rs.getObject("vendor_id", UUID.class),
						rs.getString("vendor_name")));
	}

	@Transactional
	public UUID create(AuthenticatedUser actor, CreateVendorRequest request) {
		UUID id = UUID.randomUUID();
		try {
			jdbc.update(connection -> {
				var ps = connection.prepareStatement("""
						INSERT INTO vendors (
							id, tenant_id, name, contact_person, phone, email, address, gstin,
							preferred_language, notes, contract_end_date)
						VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?)
						""");
				ps.setObject(1, id);
				ps.setString(2, request.name().trim());
				ps.setString(3, trimToNull(request.contactPerson()));
				// trimToNull, not trim: the phone is optional now (T-025), and .trim() on a vendor
				// created without one is a NullPointerException before the row is ever attempted.
				ps.setString(4, trimToNull(request.phone()));
				ps.setString(5, trimToNull(request.email()));
				ps.setString(6, trimToNull(request.address()));
				ps.setString(7, trimToNull(request.gstin()));
				ps.setString(8, language(request.preferredLanguage()));
				ps.setString(9, trimToNull(request.notes()));
				ps.setObject(10, request.contractEndDate());
				return ps;
			});
		} catch (DuplicateKeyException e) {
			throw new ApplicationException(ErrorCode.VENDOR_ALREADY_EXISTS, Map.of("name", request.name()), e);
		}
		auditService.record(actor, AuditAction.VENDOR_ADDED, AuditEntityType.VENDOR, id,
				null, snapshot(findById(id).orElseThrow(() -> notFound(id))), null);
		return id;
	}

	@Transactional
	public void update(AuthenticatedUser actor, UUID id, UpdateVendorRequest request) {
		VendorView before = findById(id).orElseThrow(() -> notFound(id));
		try {
			jdbc.update("""
					UPDATE vendors
					SET name = ?, contact_person = ?, phone = ?, email = ?, address = ?, gstin = ?,
						preferred_language = ?, notes = ?, contract_end_date = ?, updated_at = now()
					WHERE id = ?
					""",
					request.name().trim(), trimToNull(request.contactPerson()), trimToNull(request.phone()),
					trimToNull(request.email()), trimToNull(request.address()), trimToNull(request.gstin()),
					language(request.preferredLanguage()), trimToNull(request.notes()),
					request.contractEndDate(), id);
		} catch (DuplicateKeyException e) {
			throw new ApplicationException(ErrorCode.VENDOR_ALREADY_EXISTS, Map.of("name", request.name()), e);
		}
		auditService.record(actor, AuditAction.VENDOR_UPDATED, AuditEntityType.VENDOR, id,
				snapshot(before), snapshot(findById(id).orElseThrow(() -> notFound(id))), null);
	}

	/**
	 * Drops a vendor, or brings one back, and records why.
	 *
	 * <p>The reason is required on the way out and never overwritten: each deactivation leaves its
	 * own row, so a vendor dropped twice for two different reasons reads as two entries and not as
	 * one edited line. It is optional on the way back in — somebody restoring a supplier may have
	 * nothing to add beyond having done it.
	 *
	 * <p>The audit event still fires, unchanged, and now carries the reason as its note. The two are
	 * not redundant: the audit log is the tamper-evident record of who did what across the whole
	 * temple, read by a Temple Admin; {@code vendor_status_changes} is the vendor's own history,
	 * read by whoever manages vendors, on the vendor's own page.
	 */
	@Transactional
	public void setActive(AuthenticatedUser actor, UUID id, boolean active, String rawReason) {
		String reason = trimToNull(rawReason);
		if (!active && reason == null) {
			throw new ApplicationException(
					ErrorCode.VENDOR_DEACTIVATION_REASON_REQUIRED, Map.of("vendorId", id));
		}
		VendorView before = findById(id).orElseThrow(() -> notFound(id));
		if (before.active() == active) {
			return;
		}
		jdbc.update("UPDATE vendors SET active = ?, updated_at = now() WHERE id = ?", active, id);
		recordStatusChange(actor, id, before.active(), active, reason);
		auditService.record(actor,
				active ? AuditAction.VENDOR_REACTIVATED : AuditAction.VENDOR_DEACTIVATED,
				AuditEntityType.VENDOR, id,
				Map.of("active", before.active()), Map.of("active", active), reason);
	}

	private void recordStatusChange(
			AuthenticatedUser actor, UUID vendorId, boolean from, boolean to, String reason) {
		jdbc.update(connection -> {
			var ps = connection.prepareStatement("""
					INSERT INTO vendor_status_changes (
						tenant_id, vendor_id, from_active, to_active, reason, actor_user_id)
					VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?)
					""");
			ps.setObject(1, vendorId);
			ps.setBoolean(2, from);
			ps.setBoolean(3, to);
			ps.setString(4, reason);
			ps.setObject(5, actor.getUserId());
			return ps;
		});
	}

	// ---- Supply mapping -------------------------------------------------

	/**
	 * Sets one supply from the vendor page's table or the ingredient page's vendor link. A price
	 * change here is recorded in the history as {@code MANUAL} (R-VEN-4).
	 */
	@Transactional
	public void setSupply(AuthenticatedUser actor, UUID vendorId, SetVendorSupplyRequest request) {
		findById(vendorId).orElseThrow(() -> notFound(vendorId));
		writeSupply(actor, vendorId, request, VendorPriceHistoryService.Source.MANUAL);
	}

	/**
	 * The ticked rows of the vendor page's "Other ingredients" table, saved together (R-VEN-1).
	 *
	 * <p>One transaction for the lot: the person pressed Save once, and a list half-saved because
	 * row 7 named a pack of another ingredient would leave them to work out which six went in. Any
	 * refusal refuses every row. Each row is exactly one {@link #setSupply}, so the preferred rule,
	 * the pack check and the price derivation are the same code; the only difference is that a
	 * price typed here is recorded as {@code ONBOARDING}, the vendor's list being entered for the
	 * first time.
	 */
	@Transactional
	public void addSupplies(AuthenticatedUser actor, UUID vendorId, List<SetVendorSupplyRequest> rows) {
		findById(vendorId).orElseThrow(() -> notFound(vendorId));
		for (SetVendorSupplyRequest row : rows) {
			writeSupply(actor, vendorId, row, VendorPriceHistoryService.Source.ONBOARDING);
		}
	}

	/**
	 * The one place a supply row is written, and the one place its list price is worked out.
	 *
	 * <p><strong>With a pack and a price per pack</strong> ("Bag = 25 Kg" at ₹1,500), the list price
	 * per canonical unit is derived: ₹1,500 ÷ 25 = ₹60 / Kg. It is never typed twice (§3), so a
	 * per-unit {@code lastPrice} sent alongside is ignored, and one sent with a pack but <em>no</em>
	 * pack price is refused ({@code KMS-400163}): with a pack, the price is entered per pack only
	 * (the conductor's call, 2026-09-19). A pack with no price at all is fine; the list price is
	 * optional (R-VEN-1).
	 *
	 * <p><strong>Without a pack</strong>, {@code lastPrice} is typed per unit, as it always was.
	 *
	 * <p>The pack must be one of this ingredient's own. V144's composite foreign key would refuse it
	 * anyway, but as a constraint violation nobody can read; this says it in words
	 * ({@code KMS-400162}). A pack of another temple's ingredient is invisible under row-level
	 * security, finds no row here, and is refused the same way.
	 */
	private void writeSupply(
			AuthenticatedUser actor, UUID vendorId, SetVendorSupplyRequest request,
			VendorPriceHistoryService.Source source) {
		UUID ingredientId = request.ingredientId();
		Pack pack = null;
		if (request.packSizeId() != null) {
			pack = packOf(request.packSizeId(), ingredientId);
			if (request.pricePerPack() == null && request.lastPrice() != null) {
				throw new ApplicationException(ErrorCode.SUPPLY_PRICE_PER_PACK_ONLY,
						Map.of("vendorId", vendorId, "ingredientId", ingredientId));
			}
		}
		BigDecimal pricePerPack = pack == null ? null : request.pricePerPack();
		BigDecimal pricePerUnit = pricePerPack != null
				? pricePerPack.divide(pack.canonicalQuantity(), PRICE_SCALE, RoundingMode.HALF_UP)
				: pack == null ? request.lastPrice() : null;

		// One preferred vendor per ingredient (R-VEN-2): ticking Preferred here takes it from whoever
		// holds it, in this transaction and before this row is written, so the move never collides
		// with vendor_supplies_one_preferred (V24, unique on (tenant_id, ingredient_id) WHERE
		// preferred). The screen names the vendor it replaces before Save, from preferredVendors().
		//
		// Only the flag moves. The other vendor's list price is not touched and no price history is
		// written for it: its price did not change, and a history row would put a flat dash beside a
		// figure nobody edited. Row-level security confines the UPDATE to this temple's rows, so
		// another temple's preference for anything is never cleared (OnePreferredVendorIT).
		//
		// The statement appeared twice here after T-252, once with ingredientId and once with
		// request.ingredientId(), the same value; the second was a no-op and was removed (T-258).
		if (request.preferred()) {
			jdbc.update("UPDATE vendor_supplies SET preferred = false, updated_at = now() "
					+ "WHERE ingredient_id = ? AND preferred", ingredientId);
		}
		// lead_time_days is written exactly as it arrives, null included (T-090). A supply row whose
		// lead time is cleared goes back to "nobody has said", which is a true statement and the one
		// the readers fall back from; coalescing it to a number here would fabricate an answer.
		jdbc.update("""
				INSERT INTO vendor_supplies (
					id, tenant_id, vendor_id, ingredient_id, last_price, lead_time_days, preferred,
					pack_size_id, price_per_pack)
				VALUES (gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (vendor_id, ingredient_id) DO UPDATE
				SET last_price = EXCLUDED.last_price, lead_time_days = EXCLUDED.lead_time_days,
					preferred = EXCLUDED.preferred, pack_size_id = EXCLUDED.pack_size_id,
					price_per_pack = EXCLUDED.price_per_pack, updated_at = now()
				""", vendorId, ingredientId, pricePerUnit, request.leadTimeDays(), request.preferred(),
				request.packSizeId(), pricePerPack);

		// In the same transaction as the supply, so a price is in the history exactly when it was
		// saved. The history service decides whether it is a change; no change, no row.
		priceHistory.record(vendorId, ingredientId, pricePerUnit, pricePerPack,
				pack == null || pricePerPack == null ? null : pack.chipText(),
				LocalDate.now(clock.zone()), source, null, actor.getUserId());
	}

	/**
	 * A saved invoice line's price becomes this vendor's list price for the ingredient (R-VEN-4,
	 * R-INV-6). Called by {@code InvoicePriceStep}, in the invoice's transaction, and nowhere else.
	 *
	 * <p>It lives here, beside {@link #writeSupply}, because this class is the one place a supply row
	 * is written and its list price worked out; the invoice service asking for a price change rather
	 * than writing {@code vendor_supplies} itself keeps it that way. What it writes:
	 *
	 * <ul>
	 *   <li><strong>{@code last_price}</strong>, per the ingredient's stock unit, always — the "List
	 *       price" every screen and costing reads.</li>
	 *   <li><strong>{@code price_per_pack}</strong> when the vendor's supply is sold in a pack ("Sells it
	 *       as"). When the bill line was in that very pack, it is the bill's own figure, Amount ÷ packs
	 *       (₹6,000 ÷ 4 = ₹1,500 a bag), so nothing is lost to rounding through the per-unit rate;
	 *       otherwise it is the per-unit rate times the pack's size. The pack the vendor sells in is
	 *       not changed by a bill: that is the vendor page's setting.</li>
	 *   <li><strong>a {@code vendor_price_history} row</strong>, source {@code INVOICE}, per stock unit,
	 *       naming the line — through {@link VendorPriceHistoryService#record}, which writes it only when
	 *       the price is a change, the same rule every other price follows.</li>
	 * </ul>
	 *
	 * <p><strong>A vendor with no supply row for the ingredient gets one, not preferred</strong>
	 * (conductor's ruling for T-271): the bill proves they sell it, and the price has to hang
	 * somewhere; whether they become the preferred source is a person's decision on the vendor page,
	 * and quietly taking the preference from another vendor would change what the shopping list
	 * suggests. Lead time is left unknown (null), which is what "nobody has said" means.
	 *
	 * <p><strong>Dated by the bill</strong> (conductor's ruling for T-271): {@code effectiveOn} is the
	 * invoice date, and the history row takes that date. A bill older than the newest price already
	 * recorded for this vendor and ingredient is history, not news: its row is written in date order,
	 * but the list price is left as the later bill set it. The arrows compare the newest two rows by
	 * date, and the list price is then always the newest row's.
	 *
	 * @param ratePerStockUnit Amount ÷ Billed qty, in rupees per one of the ingredient's canonical
	 *     unit; positive (the caller never passes a ₹0 rate — §13: a bill never sets a price of nothing)
	 * @param billedPack the pack the bill line was in, or null
	 * @param billedPackPrice Amount ÷ packCount for that line, or null
	 * @return true when the list price was moved
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean setListPriceFromInvoice(
			AuthenticatedUser actor, UUID vendorId, UUID ingredientId, BigDecimal ratePerStockUnit,
			UUID billedPack, BigDecimal billedPackPrice, UUID invoiceLineId, LocalDate effectiveOn) {
		// The link, created if the bill is the first news that this vendor sells it. ON CONFLICT DO
		// NOTHING, so an existing supply keeps its preference, lead time and pack exactly as they were.
		jdbc.update("""
				INSERT INTO vendor_supplies (id, tenant_id, vendor_id, ingredient_id, preferred)
				VALUES (gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						?, ?, false)
				ON CONFLICT (vendor_id, ingredient_id) DO NOTHING
				""", vendorId, ingredientId);

		UUID soldIn = jdbc.queryForObject(
				"SELECT pack_size_id FROM vendor_supplies WHERE vendor_id = ? AND ingredient_id = ?",
				UUID.class, vendorId, ingredientId);
		Pack pack = soldIn == null ? null : packOf(soldIn, ingredientId);
		BigDecimal pricePerPack = null;
		if (pack != null) {
			pricePerPack = soldIn.equals(billedPack) && billedPackPrice != null
					? billedPackPrice.setScale(2, RoundingMode.HALF_UP)
					: ratePerStockUnit.multiply(pack.canonicalQuantity()).setScale(2, RoundingMode.HALF_UP);
		}

		// Read before the history row is written, or the row this call adds would always be "the
		// newest" and an old bill would move the price after all.
		Boolean later = jdbc.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM vendor_price_history
							   WHERE vendor_id = ? AND ingredient_id = ? AND effective_on > ?)
				""", Boolean.class, vendorId, ingredientId, effectiveOn);
		boolean moved = !Boolean.TRUE.equals(later);
		if (moved) {
			jdbc.update("""
					UPDATE vendor_supplies SET last_price = ?, price_per_pack = ?, updated_at = now()
					WHERE vendor_id = ? AND ingredient_id = ?
					""", ratePerStockUnit, pricePerPack, vendorId, ingredientId);
		}
		priceHistory.record(vendorId, ingredientId, ratePerStockUnit, pricePerPack,
				pack == null ? null : pack.chipText(), effectiveOn,
				VendorPriceHistoryService.Source.INVOICE, invoiceLineId, actor.getUserId());
		return moved;
	}

	/**
	 * Per-unit prices derived from a pack price are kept to four places, the scale of
	 * {@code vendor_price_history.price_per_unit} (V144): ₹65 / Kg is ₹0.065 / gm, and two places would
	 * make it ₹0.07. {@code vendor_supplies.last_price} was widened to the same NUMERIC(14, 4) in V145
	 * for exactly this reason, so the list price and its history row hold the same figure.
	 */
	private static final int PRICE_SCALE = 4;

	/** One of an ingredient's pack sizes, as a supply needs it. */
	private record Pack(String name, BigDecimal quantity, Unit unit, BigDecimal canonicalQuantity) {
		/*
		  Named chipText, not label: UnitLabelAgreementTest scans files that mention Unit for a bare
		  label() call, and this is the pack's chip text, not Unit's plural word.
		*/
		String chipText() {
			return packLabel(name, quantity, unit);
		}
	}

	private Pack packOf(UUID packSizeId, UUID ingredientId) {
		return jdbc.query("""
				SELECT p.name, p.quantity, p.unit, p.base_quantity, i.canonical_unit
				FROM ingredient_pack_sizes p
				JOIN ingredients i ON i.id = p.ingredient_id
				WHERE p.id = ? AND p.ingredient_id = ?
				""", (rs, n) -> {
					Unit canonical = Unit.valueOf(rs.getString("canonical_unit"));
					// base_quantity is in the family's base unit (gm, ml, pieces); the price is per
					// the canonical unit, which for Kg or L is a thousand of those.
					BigDecimal canonicalQuantity = rs.getBigDecimal("base_quantity")
							.divide(BigDecimal.valueOf(canonical.baseFactor()), 6, RoundingMode.HALF_UP);
					return new Pack(rs.getString("name"), rs.getBigDecimal("quantity"),
							Unit.valueOf(rs.getString("unit")), canonicalQuantity);
				}, packSizeId, ingredientId).stream().findFirst()
				.orElseThrow(() -> new ApplicationException(ErrorCode.SUPPLY_PACK_NOT_THIS_INGREDIENT,
						Map.of("packSizeId", packSizeId, "ingredientId", ingredientId)));
	}

	/**
	 * A pack as its chip reads: "Bag = 25 Kg" when it has a name, "500 gm" when it does not. The
	 * same rule as the ingredient's own chips ({@code PackSizeService.label}, T-253), built the same
	 * way — the size through {@link Quantities#exact} — so the vendor page and the ingredient page
	 * never word one pack two ways. Written out here rather than called because that method is
	 * package-private to {@code ingredient}, a file outside this task. The same text goes into the
	 * price history as its pack snapshot.
	 */
	static String packLabel(String name, BigDecimal quantity, Unit unit) {
		String size = Quantities.exact(quantity, unit);
		return name == null ? size : name + " = " + size;
	}

	@Transactional
	public void removeSupply(UUID vendorId, UUID ingredientId) {
		jdbc.update("DELETE FROM vendor_supplies WHERE vendor_id = ? AND ingredient_id = ?",
				vendorId, ingredientId);
	}

	// ---------------------------------------------------------------------

	private Optional<VendorView> findById(UUID id) {
		return jdbc.query(SELECT + " WHERE id = ?", vendorMapper(), id).stream().findFirst();
	}

	/**
	 * What the audit trail records about a vendor, before and after.
	 *
	 * <p>Two things about this are deliberate, and both were mistakes here until T-025.
	 *
	 * <p><strong>It is built from the stored row, never from the request.</strong> An audit trail
	 * that records what was <em>asked for</em> is not an audit trail — it agrees with the caller by
	 * construction, and a value the database normalised, defaulted or rejected would be recorded as
	 * though it had been stored. Both sides therefore come from a {@link VendorView} read back
	 * through {@link #findById}. That is one extra query on a rare staff action, and it buys a
	 * record that says what the temple's data actually became.
	 *
	 * <p><strong>It is a {@link LinkedHashMap}, not {@link Map#of}.</strong> {@code Map.of} throws a
	 * NullPointerException on a null value, and the phone is nullable from T-025 onwards — so the
	 * immutable form would have turned "this vendor has no number", the entire point of the feature,
	 * into a 500 on the way in and again on every later edit of that vendor. A null belongs in the
	 * trail: "the number was cleared" is exactly the kind of change somebody reads this to find.
	 */
	private static Map<String, Object> snapshot(VendorView vendor) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("name", vendor.name());
		fields.put("phone", vendor.phone());
		return fields;
	}

	private static String language(String lang) {
		String t = trimToNull(lang);
		return t == null ? "en" : t;
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	private ApplicationException notFound(UUID id) {
		return new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("vendorId", id));
	}

	private static final String SELECT = """
			SELECT id, name, contact_person, phone, email, address, gstin, preferred_language, notes,
				   contract_end_date, active, whatsapp_reachable, created_at
			FROM vendors
			""";

	/**
	 * The last date a contract may end on before it is worth saying so.
	 *
	 * <p>How far ahead that is, is the temple's own answer (V85:
	 * {@code tenant_settings.contract_end_warning_days}, thirty days unless a temple has said
	 * otherwise). It was seven — borrowed from the stock screens, because there was no evidence at
	 * the time that a contract wanted a different number from a sack of flour. There is now: seven
	 * days is not enough notice to renegotiate an agreement. Both horizons moved into settings
	 * together, so they cannot quietly disagree, which is what E5-S1 D2 said would happen.
	 *
	 * <p>Worked out once per query rather than once per row. The setting is a second trip to the
	 * database, and a hundred vendors on a list should not make a hundred of them.
	 */
	private LocalDate contractWarningCutoff() {
		return LocalDate.now(clock.zone()).plusDays(tenantSettings.contractEndWarningDays());
	}

	/**
	 * True once the contract has run out, or runs out on or before {@code cutoff}.
	 *
	 * <p>Read by the screens and by nothing else. There is no scheduled job behind this and no query
	 * anywhere filters on it: a vendor past their contract end date stays active, stays in every
	 * picker, and stays the preferred source for whatever they supply, until a person decides
	 * otherwise and says why.
	 */
	static boolean contractEndingSoon(LocalDate contractEnd, LocalDate cutoff) {
		return contractEnd != null && !contractEnd.isAfter(cutoff);
	}

	private RowMapper<VendorView> vendorMapper() {
		LocalDate cutoff = contractWarningCutoff();
		return (rs, n) -> {
			LocalDate contractEnd = rs.getObject("contract_end_date", LocalDate.class);
			return new VendorView(
					rs.getObject("id", UUID.class),
					rs.getString("name"),
					rs.getString("contact_person"),
					rs.getString("phone"),
					rs.getString("email"),
					rs.getString("address"),
					rs.getString("gstin"),
					rs.getString("preferred_language"),
					rs.getString("notes"),
					contractEnd,
					contractEndingSoon(contractEnd, cutoff),
					rs.getBoolean("active"),
					rs.getBoolean("whatsapp_reachable"),
					rs.getObject("created_at", OffsetDateTime.class).toInstant());
		};
	}

	private static final RowMapper<VendorStatusChange> STATUS_MAPPER = (rs, n) -> new VendorStatusChange(
			rs.getObject("id", UUID.class),
			rs.getObject("from_active", Boolean.class),
			rs.getBoolean("to_active"),
			rs.getString("reason"),
			rs.getObject("actor_user_id", UUID.class),
			rs.getString("actor_name"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());

	/**
	 * A supply row as both views read it. The previous price is the second-newest history row for
	 * the pair (R-VEN-3), in the same order {@link VendorPriceHistoryService} compares against;
	 * the newest is the current list price.
	 */
	private static final String SUPPLY_SELECT = """
			SELECT vs.vendor_id, v.name AS vendor_name, vs.ingredient_id, i.name AS ingredient_name,
				   i.canonical_unit, vs.last_price, vs.pack_size_id, p.name AS pack_name,
				   p.quantity AS pack_quantity, p.unit AS pack_unit, vs.price_per_pack,
				   prev.price_per_unit AS previous_price, prev.effective_on AS previous_price_on,
				   vs.lead_time_days, vs.preferred
			FROM vendor_supplies vs
			JOIN ingredients i ON i.id = vs.ingredient_id
			JOIN vendors v ON v.id = vs.vendor_id
			LEFT JOIN ingredient_pack_sizes p ON p.id = vs.pack_size_id
			LEFT JOIN LATERAL (
				SELECT h.price_per_unit, h.effective_on
				FROM vendor_price_history h
				WHERE h.vendor_id = vs.vendor_id AND h.ingredient_id = vs.ingredient_id
				ORDER BY h.effective_on DESC, h.created_at DESC
				OFFSET 1 LIMIT 1
			) prev ON true
			""";

	private static final RowMapper<VendorSupplyView> SUPPLY_MAPPER = (rs, n) -> {
		String packUnit = rs.getString("pack_unit");
		return new VendorSupplyView(
				rs.getObject("ingredient_id", UUID.class),
				rs.getString("ingredient_name"),
				(BigDecimal) rs.getObject("last_price"),
				rs.getString("canonical_unit"),
				rs.getObject("pack_size_id", UUID.class),
				packUnit == null ? null
						: packLabel(rs.getString("pack_name"), rs.getBigDecimal("pack_quantity"), Unit.valueOf(packUnit)),
				(BigDecimal) rs.getObject("price_per_pack"),
				(BigDecimal) rs.getObject("previous_price"),
				rs.getObject("previous_price_on", LocalDate.class),
				// getObject, never getInt: getInt answers 0 for a SQL null, and 0 here would mean the
				// vendor delivers the same day. The one value this column must never be mistaken for.
				rs.getObject("lead_time_days", Integer.class),
				rs.getBoolean("preferred"));
	};
}
