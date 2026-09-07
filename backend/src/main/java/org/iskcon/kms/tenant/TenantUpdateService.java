package org.iskcon.kms.tenant;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.calendar.CalendarPrecomputeScheduler;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.error.ErrorResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Corrects what a temple <em>is</em>, after it has been brought onto the platform (T-008).
 *
 * <p>The counterpart to {@link TenantProvisioningService} that the product went without: every one
 * of these columns was written once, by the provisioning insert, and never again. The only
 * {@code UPDATE tenants} anywhere else in the backend touches {@code locale}. So a temple typed in
 * wrongly at provisioning — a transposed coordinate, a misspelled name, the wrong timezone — could
 * be fixed only by deleting the temple, and 80G approval, which arrives from the Income Tax
 * department months after a temple starts using the product, could never be recorded at all.
 *
 * <p><strong>Who may do this: the operator alone, and not the temple.</strong> Settled by
 * {@code docs/work/DECISIONS.md} D-13, against a recommendation to split the fields so a temple
 * admin could correct its own address. The cost is real and deliberate — a temple cannot fix its
 * own street name, and every correction is an operator ticket — and what it buys is one auditable
 * answer to "who may change what a temple is", with the two genuinely dangerous fields on the
 * operator's side without a field-by-field permission boundary: {@code timezone}, which rewrites
 * the temple's whole calendar, and {@code is_80g_approved}, which is a legal status no temple
 * should be able to assert about itself.
 *
 * <p><strong>Timezone is not an ordinary column.</strong> {@code calendar_days} is precomputed per
 * tenant from it — {@link org.iskcon.kms.calendar.CalendarService} resolves the tenant's UTC offset
 * once and computes tithi at local sunrise from it — so a timezone corrected here and left there
 * would leave the temple reading tithi, Ekadashi and sunrise rows worked out against the zone it
 * was wrongly provisioned in, and every "today" in the product silently disagreeing with the
 * panchanga. So the precompute is re-queued, through the same
 * {@link CalendarPrecomputeScheduler#enqueueForTenant} hook provisioning uses. The upsert on the
 * other end corrects the rows it recomputes rather than adding to them
 * ({@code ON CONFLICT (tenant_id, cal_date) DO UPDATE}); what it does not reach is documented on
 * {@link #reprecomputeCalendar} below, and it matters.
 *
 * <p>JDBC rather than JPA, for the same reason provisioning uses it: this writes a tenant's row
 * from outside any tenant context, and the honest way to express that is explicit SQL in an
 * audited service rather than an entity manager quietly acting for a tenant nobody is.
 */
@Service
public class TenantUpdateService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final CalendarPrecomputeScheduler calendarScheduler;

	public TenantUpdateService(
			JdbcTemplate jdbc,
			AuditService auditService,
			CalendarPrecomputeScheduler calendarScheduler) {
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.calendarScheduler = calendarScheduler;
	}

	/**
	 * Applies a correction to one temple.
	 *
	 * <p>Order matters and is not arbitrary. The before-state is read first, because it is both the
	 * not-found guard and the only chance to capture what the row said; the write happens next; the
	 * audit event is written inside the same transaction, so there is no correction without its
	 * record and no record without its correction; and the calendar is re-queued last, after the
	 * decision to re-queue has been made from the before/after that is now known.
	 */
	@Transactional
	public void update(UUID tenantId, UpdateTenantRequest request, AuthenticatedUser actor) {
		rejectSlugChange(request);
		validateTimezone(request.timezone());

		Map<String, Object> before = loadSnapshot(tenantId);

		jdbc.update("""
				UPDATE tenants SET
					name = ?,
					address = ?,
					latitude = ?,
					longitude = ?,
					timezone = ?,
					currency = ?,
					is_80g_approved = ?,
					updated_at = now()
				WHERE id = ?
				""",
				request.name().trim(),
				blankToNull(request.address()),
				request.latitude(),
				request.longitude(),
				request.timezone(),
				request.currency(),
				request.is80gApproved(),
				tenantId);

		// The event belongs to the temple whose record changed, not to the tenantless operator who
		// changed it — the same choice provisioning makes, and for the same reason: a Temple Admin
		// must be able to see that, and by whom, their 80G status or their timezone was altered.
		//
		// TENANT_UPDATED rather than SETTINGS_UPDATED, which is what a temple admin does to its own
		// settings. This is the operator changing what a temple *is*, and the two fields that make
		// it a different kind of act are the two this endpoint exists for: timezone rewrites the
		// precomputed calendar, and 80G is a legal status a receipt quotes. Filed under the general
		// settings action, neither would be visible to anybody not already looking for it.
		// The temple's own log is RLS-protected, so establishing the context transaction-locally is
		// what lets the insert through; it disappears at commit.
		establishTenantContext(tenantId);

		Map<String, Object> after = loadSnapshot(tenantId);

		auditService.record(
				actor,
				AuditAction.TENANT_UPDATED,
				AuditEntityType.TENANT,
				tenantId,
				before,
				after,
				"Temple profile corrected by the platform operator.");

		if (!request.timezone().equals(before.get("timezone"))) {
			reprecomputeCalendar(tenantId);
		}
	}

	/**
	 * Re-queues the temple's calendar after its timezone changed.
	 *
	 * <p>Best-effort, exactly as at provisioning: the scheduler lives where the worker runs, so in a
	 * context without one this does nothing and the nightly sweep rebuilds the calendar within the
	 * day. A correction to a temple's record must never fail because a queue was unavailable.
	 *
	 * <p><strong>What the re-run does and does not fix.</strong>
	 * {@code CalendarService.upsertDays} writes {@code ON CONFLICT (tenant_id, cal_date) DO UPDATE},
	 * setting every computed column from the new value — so days it recomputes are genuinely
	 * corrected, not duplicated and not merely added to. Its horizon, however, starts at the first
	 * of the <em>current</em> month and runs forward 550 days, so <em>days already past</em> keep
	 * the tithi and sunrise they were computed with under the old zone. That is the right trade and
	 * not a gap worth widening here: what a temple cooked last March is history, and rewriting the
	 * calendar underneath a meal that was already planned and served would change the record of what
	 * happened. Everything the temple will plan against is corrected.
	 */
	private void reprecomputeCalendar(UUID tenantId) {
		calendarScheduler.enqueueForTenant(tenantId);
	}

	/**
	 * Refuses a slug rather than dropping it.
	 *
	 * <p>{@code slug} is {@code updatable=false} on {@link Tenant} and is the temple's permanent
	 * identity — it is in URLs, in export filenames, and in whatever an operator has written down.
	 * Jackson would discard an unexpected property silently (Spring Boot leaves
	 * {@code FAIL_ON_UNKNOWN_PROPERTIES} off), and a caller told their save succeeded while the web
	 * address stayed as it was is worse served than one told plainly that it cannot change.
	 *
	 * <p>Carries a field-level message rather than only the generic code, because "some of the
	 * information isn't valid" on a screen with seven fields is not something anyone can act on.
	 */
	private void rejectSlugChange(UpdateTenantRequest request) {
		if (request.carriesSlug()) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED,
					Map.of("field", "slug", "value", request.slug()),
					List.of(new ErrorResponse.FieldError(
							"slug",
							"A temple's web address is fixed when it is created and can't be changed.")),
					null);
		}
	}

	/**
	 * The same check provisioning makes, and it belongs here at least as much: at provisioning a
	 * wrong zone produces a wrong calendar from the start, whereas here it silently invalidates a
	 * calendar that was right. The valid set is the JVM's zone database rather than a pattern, so it
	 * cannot be an annotation.
	 */
	private void validateTimezone(String timezone) {
		try {
			ZoneId.of(timezone);
		} catch (Exception e) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED,
					Map.of("field", "timezone", "value", timezone),
					e);
		}
	}

	/**
	 * The temple as it was, for the audit before-state — and the not-found guard. Reads the tenant
	 * registry, which is not RLS-protected (V1: it is the registry that defines tenants), so no
	 * context is needed and none is set yet at this point.
	 *
	 * <p>Called twice — once before the write and once after — so that <em>both</em> halves of the
	 * audit event are the database's own rendering of the row. Building the after-state from the
	 * request instead was the obvious thing and was wrong: {@code latitude} is {@code NUMERIC(9,6)},
	 * so the before-state reads {@code "12.971600"} and a request-built after-state would read
	 * {@code "12.9716"}, and every event would appear to have moved a temple that nobody moved. It
	 * also means the record says what was stored rather than what was asked for, which is the more
	 * useful of the two things for an audit trail to say.
	 *
	 * <p>Coordinates are stringified rather than left as numbers for the same reason: the scale is
	 * the value here, and a JSON number would quietly drop it.
	 */
	private Map<String, Object> loadSnapshot(UUID tenantId) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT slug, name, address, latitude, longitude, timezone, currency, is_80g_approved
				FROM tenants WHERE id = ?
				""", tenantId);

		if (rows.isEmpty()) {
			throw new ApplicationException(ErrorCode.TENANT_NOT_FOUND, Map.of("tenantId", tenantId));
		}

		Map<String, Object> row = rows.get(0);
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("tenantId", tenantId.toString());
		snapshot.put("slug", row.get("slug"));
		snapshot.put("name", row.get("name"));
		snapshot.put("address", row.get("address"));
		snapshot.put("latitude", String.valueOf(row.get("latitude")));
		snapshot.put("longitude", String.valueOf(row.get("longitude")));
		snapshot.put("timezone", row.get("timezone"));
		snapshot.put("currency", row.get("currency"));
		snapshot.put("is80gApproved", row.get("is_80g_approved"));
		return snapshot;
	}

	/**
	 * Establishes the temple's context for the rest of this transaction, so the audit insert lands
	 * on that temple's log. The operator has no tenant of their own, so without this the write is
	 * refused by the policy on {@code audit_events} — which is isolation working, not an obstacle.
	 * The third argument makes the setting transaction-local: it disappears at commit and cannot
	 * leak into whatever this pooled connection does next. Copied from
	 * {@link TenantProvisioningService}, which needs it for the same reason.
	 */
	private void establishTenantContext(UUID tenantId) {
		jdbc.queryForObject(
				"SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
	}

	/**
	 * An address cleared on the screen arrives as an empty string and is stored as NULL, matching
	 * the column, which is nullable and which provisioning leaves null when nothing was typed.
	 * Without this a cleared address would read as "" everywhere a null address reads as "—".
	 */
	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
