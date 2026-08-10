package org.iskcon.kms.vendor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vendor records and who supplies what (E5-S1). Staff-managed; RLS confines everything to the tenant.
 * A vendor is deactivated, never deleted — its purchase-order history must keep rendering — and one
 * vendor can be the preferred source for an ingredient, which the order-list suggestions consume.
 */
@Service
public class VendorService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public VendorService(JdbcTemplate jdbc, AuditService auditService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	@Transactional(readOnly = true)
	public List<VendorView> list(boolean includeInactive) {
		String sql = SELECT + (includeInactive ? "" : " WHERE active = true") + " ORDER BY name";
		return jdbc.query(sql, VENDOR_MAPPER);
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
		return new VendorDetailView(vendor, supplies);
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
							preferred_language, notes)
						VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?, ?, ?)
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
						preferred_language = ?, notes = ?, updated_at = now()
					WHERE id = ?
					""",
					request.name().trim(), trimToNull(request.contactPerson()), request.phone().trim(),
					trimToNull(request.email()), trimToNull(request.address()), trimToNull(request.gstin()),
					language(request.preferredLanguage()), trimToNull(request.notes()), id);
		} catch (DuplicateKeyException e) {
			throw new ApplicationException(ErrorCode.VENDOR_ALREADY_EXISTS, Map.of("name", request.name()), e);
		}
		auditService.record(actor, AuditAction.VENDOR_UPDATED, AuditEntityType.VENDOR, id,
				Map.of("name", before.name(), "phone", before.phone()),
				Map.of("name", request.name().trim(), "phone", request.phone().trim()), null);
	}

	@Transactional
	public void setActive(AuthenticatedUser actor, UUID id, boolean active) {
		VendorView before = findById(id).orElseThrow(() -> notFound(id));
		if (before.active() == active) {
			return;
		}
		jdbc.update("UPDATE vendors SET active = ?, updated_at = now() WHERE id = ?", active, id);
		auditService.record(actor,
				active ? AuditAction.VENDOR_REACTIVATED : AuditAction.VENDOR_DEACTIVATED,
				AuditEntityType.VENDOR, id,
				Map.of("active", before.active()), Map.of("active", active), null);
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
		return jdbc.query(SELECT + " WHERE id = ?", VENDOR_MAPPER, id).stream().findFirst();
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
				   active, whatsapp_reachable, created_at
			FROM vendors
			""";

	private static final RowMapper<VendorView> VENDOR_MAPPER = (rs, n) -> new VendorView(
			rs.getObject("id", UUID.class),
			rs.getString("name"),
			rs.getString("contact_person"),
			rs.getString("phone"),
			rs.getString("email"),
			rs.getString("address"),
			rs.getString("gstin"),
			rs.getString("preferred_language"),
			rs.getString("notes"),
			rs.getBoolean("active"),
			rs.getBoolean("whatsapp_reachable"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());

	private static final RowMapper<VendorSupplyView> SUPPLY_MAPPER = (rs, n) -> new VendorSupplyView(
			rs.getObject("ingredient_id", UUID.class),
			rs.getString("ingredient_name"),
			(BigDecimal) rs.getObject("last_price"),
			rs.getBoolean("preferred"));
}
