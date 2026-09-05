package org.iskcon.kms.equipment;

import java.time.OffsetDateTime;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The firms and people who service the temple's equipment (E3-S10 D7).
 *
 * <p>A small list on purpose. A temple with one annual maintenance contract covering six machines
 * types the phone number once, and that is the whole job. Reusing {@code vendors} was considered and
 * rejected: a vendor carries purchase orders, payment terms, delivery performance and a contract
 * horizon, none of which mean anything for an engineer who comes to fix a boiler.
 *
 * <p><strong>This is a stated assumption, not a fact from the temple.</strong> Nobody has confirmed
 * whether the firms that service the equipment overlap with the firms that sell the groceries. If
 * they turn out to be the same people, the two lists reconcile later — a smaller mistake than
 * bolting servicing onto the purchasing machinery now.
 */
@Service
public class ServiceProviderService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public ServiceProviderService(JdbcTemplate jdbc, AuditService auditService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	/** The temple's providers, each with what it currently holds. */
	@Transactional(readOnly = true)
	public List<ServiceProviderView> list() {
		return jdbc.query(SELECT + " ORDER BY lower(p.name)", MAPPER);
	}

	@Transactional(readOnly = true)
	public ServiceProviderView get(UUID id) {
		return jdbc.query(SELECT + " WHERE p.id = ?", MAPPER, id)
				.stream().findFirst().orElseThrow(() -> notFound(id));
	}

	@Transactional
	public UUID create(AuthenticatedUser actor, ServiceProviderRequest request) {
		UUID id = UUID.randomUUID();
		jdbc.update(connection -> {
			var ps = connection.prepareStatement("""
					INSERT INTO service_providers (id, tenant_id, name, phone, email, note)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?)
					""");
			ps.setObject(1, id);
			ps.setString(2, request.name().trim());
			ps.setString(3, trimToNull(request.phone()));
			ps.setString(4, trimToNull(request.email()));
			ps.setString(5, trimToNull(request.note()));
			return ps;
		});

		auditService.record(actor, AuditAction.SERVICE_PROVIDER_ADDED, AuditEntityType.SERVICE_PROVIDER,
				id, null, snapshot(request), null);
		return id;
	}

	@Transactional
	public void update(AuthenticatedUser actor, UUID id, ServiceProviderRequest request) {
		ServiceProviderView before = get(id);

		jdbc.update("""
				UPDATE service_providers
				SET name = ?, phone = ?, email = ?, note = ?, updated_at = now()
				WHERE id = ?
				""",
				request.name().trim(), trimToNull(request.phone()), trimToNull(request.email()),
				trimToNull(request.note()), id);

		auditService.record(actor, AuditAction.SERVICE_PROVIDER_UPDATED, AuditEntityType.SERVICE_PROVIDER,
				id, snapshot(before), snapshot(request), null);
	}

	/**
	 * Removes a provider nothing names.
	 *
	 * <p>Refused with KMS-4017 where equipment still points at it, or where any recorded service
	 * says this firm came. The first is a tidy-up the temple can do — point the machines elsewhere
	 * and try again — and the second never clears: {@code equipment_services} is append-only, so a
	 * provider that has ever been named by a service stays in the list for good. That is the right
	 * answer rather than a limitation. "The grinder was serviced in March by nobody" is not a record
	 * worth keeping, and the alternative — nulling the reference on the way out — would produce
	 * exactly that.
	 *
	 * <p>Checked here rather than left to the foreign keys, which are RESTRICT and would otherwise
	 * surface as a failure carrying nothing the temple could act on.
	 */
	@Transactional
	public void delete(AuthenticatedUser actor, UUID id) {
		ServiceProviderView provider = get(id);

		int services = count("SELECT count(*) FROM equipment_services WHERE service_provider_id = ?", id);
		if (provider.equipmentCount() > 0 || services > 0) {
			throw new ApplicationException(ErrorCode.SERVICE_PROVIDER_IN_USE, Map.of(
					"serviceProviderId", id,
					"equipmentCount", provider.equipmentCount(),
					"serviceCount", services));
		}

		jdbc.update("DELETE FROM service_providers WHERE id = ?", id);
		auditService.record(actor, AuditAction.SERVICE_PROVIDER_REMOVED, AuditEntityType.SERVICE_PROVIDER,
				id, snapshot(provider), null, null);
	}

	// ---------------------------------------------------------------------

	private int count(String sql, UUID id) {
		Integer count = jdbc.queryForObject(sql, Integer.class, id);
		return count == null ? 0 : count;
	}

	private Map<String, Object> snapshot(ServiceProviderRequest request) {
		return snapshot(request.name().trim(), trimToNull(request.phone()), trimToNull(request.email()));
	}

	private Map<String, Object> snapshot(ServiceProviderView view) {
		return snapshot(view.name(), view.phone(), view.email());
	}

	private Map<String, Object> snapshot(String name, String phone, String email) {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("name", name);
		s.put("phone", phone);
		s.put("email", email);
		return s;
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private ApplicationException notFound(UUID id) {
		return new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("serviceProviderId", id));
	}

	// The equipment count is a correlated subquery rather than a join and a GROUP BY, because the
	// list is a handful of rows and the subquery keeps the mapper trivial. Both sides are inside the
	// tenant's RLS policy, so the count can only ever be of this temple's machines.
	private static final String SELECT = """
			SELECT p.id, p.name, p.phone, p.email, p.note, p.created_at,
				   (SELECT count(*) FROM equipment_items e WHERE e.service_provider_id = p.id)
					   AS equipment_count
			FROM service_providers p
			""";

	private static final RowMapper<ServiceProviderView> MAPPER = (rs, n) -> new ServiceProviderView(
			rs.getObject("id", UUID.class),
			rs.getString("name"),
			rs.getString("phone"),
			rs.getString("email"),
			rs.getString("note"),
			rs.getInt("equipment_count"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());
}
