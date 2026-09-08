package org.iskcon.kms.tenant;

import java.math.BigDecimal;
import java.time.ZoneId;
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
import org.iskcon.kms.error.ErrorResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Corrects the three things about a temple that actually change over its life: its name, its
 * address, and whether it is approved for 80G receipts (T-008, narrowed by D-17).
 *
 * <p>The counterpart to {@link TenantProvisioningService} that the product went without: every one
 * of these columns was written once, by the provisioning insert, and never again. The only
 * {@code UPDATE tenants} anywhere else in the backend touches {@code locale}. So a temple with a
 * misspelled name could not be corrected without deleting it, and {@code is_80g_approved} — which
 * arrives from the Income Tax department months after a temple starts using the product — could
 * never be recorded at all, so that temple's receipts stayed wrong for good.
 *
 * <p><strong>Four of the seven fields it accepts are frozen, and this class is what freezes
 * them.</strong> Ruled by Rajeev in {@code docs/work/DECISIONS.md} D-17, field by field over the
 * screen this endpoint was built for:
 *
 * <ul>
 *   <li>{@code latitude} and {@code longitude} — the building does not move. A temple is a large
 *       establishment that took years and a great deal of money to build, and the only other reason
 *       to edit a coordinate is a provisioning typo, which is dealt with below.
 *   <li>{@code currency} — set once, when the temple is created. Changed afterwards it does not
 *       convert anything: every invoice, payment and donation already recorded silently starts
 *       being displayed in a currency it was never in.
 *   <li>{@code timezone} — the same argument as the coordinates, applied consistently. A temple
 *       that cannot move cannot change timezone either.
 * </ul>
 *
 * <p><strong>Why they are still accepted in the payload.</strong> {@link UpdateTenantRequest} is a
 * whole-record replacement, so the caller sends all seven fields and these four arrive carrying
 * whatever is already stored. Deleting them from the request would be worse than refusing them:
 * Spring Boot leaves Jackson's {@code FAIL_ON_UNKNOWN_PROPERTIES} off, so an unknown property is
 * dropped in silence and the caller is told the save succeeded — a caller who moved a temple and
 * was thanked for it. Declared and refused, the same request gets an answer it can read. This is
 * the reasoning {@link #rejectSlugChange} was already built on; the four fields here simply differ
 * in that they are refused on a <em>change</em> rather than on their presence.
 *
 * <p><strong>What freezing the timezone removed.</strong> This class used to re-queue the temple's
 * calendar precompute whenever the zone changed, because {@code calendar_days} is computed per
 * tenant from that column and a corrected zone left an uncorrected calendar — every tithi, Ekadashi
 * and sunrise worked out against the wrong zone. That path was never fixing a defect of its own; it
 * was the safety requirement of allowing the field to be edited. With the field frozen at this
 * layer the calendar cannot fall out of step with the column, so the rebuild is not merely unused
 * but unreachable, and it is gone. The refusal below is what keeps that true: deleting the rebuild
 * while still accepting a changed zone would have left an operator with {@code MANAGE_TENANTS} able
 * to hand-{@code PATCH} a new timezone, change the column, and leave the whole calendar wrong with
 * nothing anywhere saying so.
 *
 * <p><strong>The provisioning typo, and why it is accepted.</strong> Frozen coordinates mean a
 * coordinate typed in wrongly at provisioning cannot be corrected here or through this API at all.
 * D-17 accepts that deliberately: a coordinate error large enough to change the calendar — wrong
 * city, transposed digits, wrong hemisphere — is large enough to be obvious immediately, and one
 * small enough to go unnoticed shifts sunrise by seconds and moves no tithi. A typo caught during
 * onboarding costs nothing, because the temple has no data yet: delete it and create it again.
 *
 * <p><strong>Who may do this: the operator alone, and not the temple.</strong> Settled by D-13,
 * against a recommendation to split the fields so a temple admin could correct its own address. The
 * cost is real and deliberate — a temple cannot fix its own street name, and every correction is an
 * operator ticket — and what it buys is one auditable answer to "who may change what a temple is",
 * with {@code is_80g_approved} on the operator's side: it is a legal status no temple should be
 * able to assert about itself.
 *
 * <p>JDBC rather than JPA, for the same reason provisioning uses it: this writes a tenant's row
 * from outside any tenant context, and the honest way to express that is explicit SQL in an
 * audited service rather than an entity manager quietly acting for a tenant nobody is.
 */
@Service
public class TenantUpdateService {

	/**
	 * The words a frozen field is refused in. Deliberately the same sentence shape as the slug's,
	 * because to the operator these are the same kind of refusal: a thing about a temple that was
	 * settled when the temple was created. Each says which field it is about, because "some of the
	 * information isn't valid" on a screen of seven fields is not something anyone can act on.
	 */
	private static final String LOCATION_IS_FIXED =
			"A temple's location is fixed when it is created and can't be changed.";
	private static final String TIMEZONE_IS_FIXED =
			"A temple's timezone is fixed when it is created and can't be changed.";
	private static final String CURRENCY_IS_FIXED =
			"A temple's currency is fixed when it is created and can't be changed.";

	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public TenantUpdateService(JdbcTemplate jdbc, AuditService auditService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	/**
	 * Applies a correction to one temple.
	 *
	 * <p>Order matters and is not arbitrary. The slug is refused first, because its presence alone
	 * is the fault and no state is needed to see it. The before-state is read next, because it is
	 * both the not-found guard and the only chance to capture what the row said — and, since D-17,
	 * it is also what the four frozen fields are checked against, so a temple that does not exist
	 * is a 404 rather than a refusal listing fields nobody can compare. The write happens after
	 * that, and the audit event is written inside the same transaction, so there is no correction
	 * without its record and no record without its correction.
	 */
	@Transactional
	public void update(UUID tenantId, UpdateTenantRequest request, AuthenticatedUser actor) {
		rejectSlugChange(request);
		validateTimezone(request.timezone());

		Map<String, Object> before = loadSnapshot(tenantId);
		rejectFrozenFieldChanges(request, before);

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

		// The four frozen columns are still in the SET list, and that is not an oversight: by the
		// time the statement runs they have been proved equal to what is already stored, so writing
		// them back is a no-op that keeps this one statement the whole of what the endpoint does.
		// Splitting it into "the editable three" would put the guarantee in two places — the SET
		// list and the refusal — and leave a later hand able to loosen one without the other.

		// The event belongs to the temple whose record changed, not to the tenantless operator who
		// changed it — the same choice provisioning makes, and for the same reason: a Temple Admin
		// must be able to see that, and by whom, their 80G status was altered.
		//
		// TENANT_UPDATED rather than SETTINGS_UPDATED, which is what a temple admin does to its own
		// settings. This is the operator changing what a temple *is*, and 80G is the field that
		// makes it a different kind of act: a legal status a receipt quotes. Filed under the general
		// settings action it would not be visible to anybody not already looking for it.
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
	 * Refuses a change to any of the four fields D-17 froze, and says which.
	 *
	 * <p>The difference from {@link #rejectSlugChange} is the whole design: a slug is refused for
	 * being <em>present</em>, because no caller has any business sending one, whereas these four are
	 * required in every request and are refused only for <em>differing</em> from what is stored. The
	 * edit screen sends all seven fields on every save — it is a whole-record replacement — so the
	 * frozen four arrive on the ordinary path of correcting a name, carrying the values they already
	 * had, and must go through.
	 *
	 * <p><strong>{@code compareTo}, never {@code equals}, for the coordinates.</strong>
	 * {@code latitude} and {@code longitude} are {@code NUMERIC(9,6)}, so the row holds
	 * {@code 12.971600} while the screen sends back the number it was given and Jackson reads it as
	 * {@code 12.9716}. {@link BigDecimal#equals} compares scale as well as value and calls those two
	 * different; a check written with it would refuse an operator their own untouched coordinates
	 * and make the screen unsaveable. This exact scale difference has already caught this endpoint
	 * once, from the other side: the first audit snapshot built its after-state from the request and
	 * so claimed every correction had moved the temple. See {@link #loadSnapshot}.
	 *
	 * <p>Every offending field is collected before throwing rather than the first one thrown at
	 * once, so a caller sending a wholly stale record is told everything that is wrong with it in
	 * one answer instead of discovering the four in turn.
	 */
	private void rejectFrozenFieldChanges(UpdateTenantRequest request, Map<String, Object> stored) {
		List<ErrorResponse.FieldError> refused = new ArrayList<>();

		if (numberChanged(request.latitude(), stored.get("latitude"))) {
			refused.add(new ErrorResponse.FieldError("latitude", LOCATION_IS_FIXED));
		}
		if (numberChanged(request.longitude(), stored.get("longitude"))) {
			refused.add(new ErrorResponse.FieldError("longitude", LOCATION_IS_FIXED));
		}
		if (!request.timezone().equals(stored.get("timezone"))) {
			refused.add(new ErrorResponse.FieldError("timezone", TIMEZONE_IS_FIXED));
		}
		if (!request.currency().equals(stored.get("currency"))) {
			refused.add(new ErrorResponse.FieldError("currency", CURRENCY_IS_FIXED));
		}

		if (!refused.isEmpty()) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED,
					Map.of(
							"tenantId", stored.get("tenantId"),
							"frozenFields", refused.stream().map(ErrorResponse.FieldError::field).toList()),
					refused,
					null);
		}
	}

	/**
	 * True when a coordinate sent differs in value from the one stored. The stored side arrives from
	 * {@link #loadSnapshot} already stringified, which is exactly the form to compare from: parsing
	 * it back gives a {@link BigDecimal} of the column's own scale, and {@code compareTo} then
	 * ignores the scale that {@code equals} would have tripped over.
	 *
	 * <p>A null sent value counts as a change. Validation rejects it before this is reached — the
	 * field is {@code @NotNull} — so this is only about never writing a null into a {@code NOT NULL}
	 * column if that ever stops being true.
	 */
	private static boolean numberChanged(BigDecimal sent, Object stored) {
		return sent == null || new BigDecimal(String.valueOf(stored)).compareTo(sent) != 0;
	}

	/**
	 * The same check provisioning makes. It runs before the frozen-field comparison rather than
	 * after it so that a zone that is not a zone at all is refused for what it is, rather than for
	 * differing from a stored value it could never have matched. The valid set is the JVM's zone
	 * database rather than a pattern, so it cannot be an annotation.
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
	 * The temple as it was, for the audit before-state — and the not-found guard, and since D-17 the
	 * values the four frozen fields are checked against. Reads the tenant registry, which is not
	 * RLS-protected (V1: it is the registry that defines tenants), so no context is needed and none
	 * is set yet at this point.
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
