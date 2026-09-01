package org.iskcon.kms.vendor;

import java.math.BigDecimal;
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
import org.iskcon.kms.shift.TenantSettingsService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
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

	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final TenantSettingsService tenantSettings;

	public VendorService(
			JdbcTemplate jdbc, AuditService auditService, TenantSettingsService tenantSettings) {
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.tenantSettings = tenantSettings;
	}

	@Transactional(readOnly = true)
	public List<VendorView> list(boolean includeInactive) {
		String sql = SELECT + (includeInactive ? "" : " WHERE active = true") + " ORDER BY name";
		return jdbc.query(sql, vendorMapper());
	}

	@Transactional(readOnly = true)
	public VendorDetailView get(UUID id) {
		VendorView vendor = findById(id).orElseThrow(() -> notFound(id));
		List<VendorSupplyView> supplies = jdbc.query("""
				SELECT vs.ingredient_id, i.name AS ingredient_name, vs.last_price, vs.preferred
				FROM vendor_supplies vs
				JOIN ingredients i ON i.id = vs.ingredient_id
				WHERE vs.vendor_id = ?
				ORDER BY i.name
				""", SUPPLY_MAPPER, id);
		return new VendorDetailView(vendor, supplies, statusHistory(id));
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
				ps.setString(4, request.phone().trim());
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
				null, Map.of("name", request.name().trim(), "phone", request.phone().trim()), null);
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
					request.name().trim(), trimToNull(request.contactPerson()), request.phone().trim(),
					trimToNull(request.email()), trimToNull(request.address()), trimToNull(request.gstin()),
					language(request.preferredLanguage()), trimToNull(request.notes()),
					request.contractEndDate(), id);
		} catch (DuplicateKeyException e) {
			throw new ApplicationException(ErrorCode.VENDOR_ALREADY_EXISTS, Map.of("name", request.name()), e);
		}
		auditService.record(actor, AuditAction.VENDOR_UPDATED, AuditEntityType.VENDOR, id,
				Map.of("name", before.name(), "phone", before.phone()),
				Map.of("name", request.name().trim(), "phone", request.phone().trim()), null);
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

	@Transactional
	public void setSupply(UUID vendorId, SetVendorSupplyRequest request) {
		findById(vendorId).orElseThrow(() -> notFound(vendorId));
		// A preferred designation is exclusive per ingredient — clear any other vendor's first.
		if (request.preferred()) {
			jdbc.update("UPDATE vendor_supplies SET preferred = false, updated_at = now() "
					+ "WHERE ingredient_id = ? AND preferred", request.ingredientId());
		}
		jdbc.update("""
				INSERT INTO vendor_supplies (id, tenant_id, vendor_id, ingredient_id, last_price, preferred)
				VALUES (gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?)
				ON CONFLICT (vendor_id, ingredient_id) DO UPDATE
				SET last_price = EXCLUDED.last_price, preferred = EXCLUDED.preferred, updated_at = now()
				""", vendorId, request.ingredientId(), request.lastPrice(), request.preferred());
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
		return LocalDate.now(TEMPLE_ZONE).plusDays(tenantSettings.contractEndWarningDays());
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

	private static final RowMapper<VendorSupplyView> SUPPLY_MAPPER = (rs, n) -> new VendorSupplyView(
			rs.getObject("ingredient_id", UUID.class),
			rs.getString("ingredient_name"),
			(BigDecimal) rs.getObject("last_price"),
			rs.getBoolean("preferred"));
}
