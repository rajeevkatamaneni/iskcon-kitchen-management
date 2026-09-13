package org.iskcon.kms.ops;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.notification.NotificationTemplate;
import org.iskcon.kms.notification.WhatsAppTemplateComparison;
import org.iskcon.kms.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A temple's stored copy of Meta's answer for each WhatsApp template, and the counts built from every
 * temple's copy (T-178).
 *
 * <p>Rajeev, 2026-09-13: <em>"The super admin's SHOULD have a way to see all of the message templates ,
 * date created, updated and Meta approval status."</em> The catalogue (T-177) shows the wording. This is
 * Meta's status, which only exists per temple, because Meta is asked through each temple's own business
 * account and token.
 *
 * <p><strong>What is stored is T-173's comparison, not a new reading of Meta.</strong> The copy is
 * {@link WhatsAppTemplateComparison#compare()}'s answer, one row per template, so the status an operator
 * reads was read by exactly the call Reload makes when it decides whether to reword. See V131 for why it
 * is stored at all and why it is tenant data.
 *
 * <p><strong>Two writers, one of them audited.</strong> {@link #replace} is the refresh at the end of a
 * temple's own Reload: that temple acted, its Reload is already audited as {@code SETTINGS_UPDATED}, and
 * an operator entry would claim somebody acted who did not. {@link #replaceForOperator} is the operator's
 * Refresh: the temple's secret is used to call an outside service on the operator's behalf, which is an act
 * on the temple, so it writes {@code WHATSAPP_TEMPLATE_STATUS_REFRESHED} on that temple, in the same
 * transaction as the copy. Both expect the temple's context to be set by the caller and never take a
 * temple id from anything else.
 *
 * <p><strong>The groups the counts use, and why.</strong> Meta's status values, from the Graph API
 * reference for the template node (https://developers.facebook.com/docs/graph-api/reference/whats-app-business-hsm/):
 * APPROVED, IN_APPEAL, PENDING, REJECTED, PENDING_DELETION, DELETED, DISABLED, PAUSED, LIMIT_EXCEEDED.
 * <ul>
 *   <li><em>Approved</em>: APPROVED. The only status under which Meta sends the template.
 *   <li><em>Pending</em>: PENDING and IN_APPEAL. Meta has not decided yet, and Reload treats the two
 *       alike for the same reason (it will not edit either).
 *   <li><em>Refused</em>: REJECTED, PAUSED and DISABLED. In each, Meta has decided the template is not to
 *       be sent: refused at review, stopped for its quality, or stopped for good. An operator reading
 *       "refused in 2" needs to know it is not going out there, and splitting those three would say the
 *       same thing three ways.
 *   <li>Everything else, including not held and not answered, is counted in {@code templesCounted} only.
 *       The screen shows the remainder as its own number, so the groups never have to add up by force.
 * </ul>
 *
 * <p><strong>A formatting refusal.</strong> Meta returns {@code rejected_reason} on the template, with
 * {@code INVALID_FORMAT} among its values (same reference). Meta's webhook reference describes that value
 * as "Template has invalid formatting"
 * (https://developers.facebook.com/documentation/business-messaging/whatsapp/webhooks/reference/message_template_status_update),
 * and the other values are about content or category: ABUSIVE_CONTENT, PROMOTIONAL, SCAM,
 * TAG_CONTENT_MISMATCH, INCORRECT_CATEGORY, and NONE for a paused one. So a formatting refusal is exactly
 * {@code REJECTED} with {@code INVALID_FORMAT}, and nothing is inferred from the wording of anything.
 *
 * <p><strong>The counts never read across temples in one query.</strong> The app role has no BYPASSRLS,
 * so there is no such query to write. {@link #countsAcrossTemples} adopts each temple's context in turn,
 * reads that temple's rows under its own policy and clears the context in {@code finally}, exactly as
 * {@code OpsService.notificationMetrics} does. It is deliberately not transactional: the context reaches
 * PostgreSQL when a connection is checked out, so each read has to take its own.
 */
@Service
public class TemplateStatusCopy {

	static final Set<String> APPROVED_STATUSES = Set.of("APPROVED");
	static final Set<String> PENDING_STATUSES = Set.of("PENDING", "IN_APPEAL");
	static final Set<String> REFUSED_STATUSES = Set.of("REJECTED", "PAUSED", "DISABLED");

	/** Meta's category for a template it treats as marketing. */
	static final String MARKETING = "MARKETING";

	/** Meta's {@code rejected_reason} for a template refused for its formatting. */
	static final String FORMATTING_REASON = "INVALID_FORMAT";

	static final String REFRESHED_BY_OPERATOR =
			"WhatsApp template status refreshed from Meta by the platform operator.";

	private static final String WHERE_THIS_TEMPLE =
			"WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid";

	private static final String READ = """
			SELECT template_name, our_category, meta_status, meta_category, held, wording_matches,
			       meta_rejected_reason, lookup_problem, taken_at
			FROM whatsapp_template_status_copy
			""" + WHERE_THIS_TEMPLE;

	private static final String INSERT = """
			INSERT INTO whatsapp_template_status_copy (tenant_id, template_name, our_category, meta_status,
			    meta_category, held, wording_matches, meta_rejected_reason, lookup_problem)
			VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?, ?, ?)
			""";

	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public TemplateStatusCopy(JdbcTemplate jdbc, AuditService auditService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	/**
	 * Replaces the current temple's copy with this comparison, and writes no audit. For a temple's own
	 * Reload, inside its transaction.
	 *
	 * <p>Delete then insert in one transaction, so a reader sees the old copy or the new one, never half of
	 * each. Every row takes {@code now()}, which is the transaction's start time, so one copy has one "as
	 * of". Returns what is now stored, read back, never the report it was given.
	 */
	@Transactional
	public View replace(WhatsAppTemplateComparison.Report report) {
		UUID tenantId = currentTemple();
		jdbc.update("DELETE FROM whatsapp_template_status_copy " + WHERE_THIS_TEMPLE);
		List<Object[]> rows = new ArrayList<>();
		for (WhatsAppTemplateComparison.Entry entry : report.templates()) {
			rows.add(new Object[] {entry.name(), entry.ourCategory(), entry.metaStatus(), entry.metaCategory(),
					entry.held(), Boolean.TRUE.equals(entry.held()) ? entry.bodyMatchesAfterTrim() : null,
					entry.metaRejectedReason(), entry.lookupProblem()});
		}
		jdbc.batchUpdate(INSERT, rows);
		return read(tenantId);
	}

	/**
	 * The operator's Refresh: the same replacement, plus one audit entry on the temple, atomic with it.
	 *
	 * <p>The audit's before and after are both read from the table, so the entry says what was stored
	 * rather than what Meta was asked. It carries counts and the "as of" time, never a token.
	 */
	@Transactional
	public View replaceForOperator(AuthenticatedUser actor, WhatsAppTemplateComparison.Report report) {
		UUID tenantId = currentTemple();
		View before = read(tenantId);
		View after = replace(report);
		auditService.record(actor, AuditAction.WHATSAPP_TEMPLATE_STATUS_REFRESHED, AuditEntityType.TENANT, tenantId, summary(before), summary(after), REFRESHED_BY_OPERATOR);
		return after;
	}

	/**
	 * The current temple's copy, one row per stored template: those this release sends in its own order,
	 * then any the release no longer has, by name. {@code asOf} is null when no copy was ever taken.
	 */
	@Transactional(readOnly = true)
	public View read(UUID tenantId) {
		List<Stored> stored = jdbc.query(READ, TemplateStatusCopy::stored);
		Instant asOf = stored.stream().map(Stored::takenAt).max(Comparator.naturalOrder()).orElse(null);
		List<Row> rows = stored.stream()
				.sorted(Comparator.comparingInt((Stored s) -> releaseOrder(s.name())).thenComparing(Stored::name))
				.map(Stored::row)
				.toList();
		return new View(tenantId, asOf, rows);
	}

	/**
	 * One {@link TemplateStatusCounts} per template this release sends, in its order, summed from every
	 * temple's copy read inside that temple's own context. Never names a temple.
	 */
	public List<TemplateStatusCounts> countsAcrossTemples() {
		Map<String, Tally> byName = new LinkedHashMap<>();
		for (NotificationTemplate template : NotificationTemplate.values()) {
			byName.put(template.whatsappTemplateName(), new Tally());
		}
		for (UUID temple : jdbc.queryForList("SELECT id FROM tenants ORDER BY id", UUID.class)) {
			TenantContext.set(temple);
			try {
				for (Stored row : jdbc.query(READ, TemplateStatusCopy::stored)) {
					Tally tally = byName.get(row.name());
					if (tally != null) {
						tally.add(row);
					}
				}
			} finally {
				TenantContext.clear();
			}
		}
		List<TemplateStatusCounts> counts = new ArrayList<>();
		byName.forEach((name, t) -> counts.add(new TemplateStatusCounts(
				name, t.counted, t.approved, t.pending, t.refused, t.marketing, t.formattingRefusal)));
		return List.copyOf(counts);
	}

	private static UUID currentTemple() {
		return TenantContext.get().orElseThrow(
				() -> new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "tenant")));
	}

	private static int releaseOrder(String name) {
		NotificationTemplate[] all = NotificationTemplate.values();
		for (int i = 0; i < all.length; i++) {
			if (all[i].whatsappTemplateName().equals(name)) {
				return i;
			}
		}
		return all.length;
	}

	/** Counts for an audit entry: what the copy said, read from the table. Nulls kept, so no Map.of. */
	private static Map<String, Object> summary(View view) {
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("asOf", view.asOf() == null ? null : view.asOf().toString());
		summary.put("templates", view.templates().size());
		summary.put("approved", view.templates().stream().filter(r -> in(APPROVED_STATUSES, r.metaStatus())).count());
		summary.put("pending", view.templates().stream().filter(r -> in(PENDING_STATUSES, r.metaStatus())).count());
		summary.put("refused", view.templates().stream().filter(r -> in(REFUSED_STATUSES, r.metaStatus())).count());
		summary.put("notHeld", view.templates().stream().filter(r -> Boolean.FALSE.equals(r.held())).count());
		summary.put("notAnswered", view.templates().stream().filter(r -> r.held() == null).count());
		return summary;
	}

	private static boolean in(Set<String> group, String value) {
		return value != null && group.contains(value.toUpperCase(Locale.ROOT));
	}

	private static Stored stored(ResultSet rs, int n) throws SQLException {
		return new Stored(
				rs.getString("template_name"),
				rs.getString("our_category"),
				rs.getString("meta_status"),
				rs.getString("meta_category"),
				(Boolean) rs.getObject("held"),
				(Boolean) rs.getObject("wording_matches"),
				rs.getString("meta_rejected_reason"),
				rs.getString("lookup_problem"),
				rs.getObject("taken_at", OffsetDateTime.class).toInstant());
	}

	/** One stored row, with the two facts the screen does not show: the reason and the time. */
	private record Stored(String name, String ourCategory, String metaStatus, String metaCategory, Boolean held,
			Boolean wordingMatches, String rejectedReason, String lookupProblem, Instant takenAt) {

		Row row() {
			return new Row(name, ourCategory, metaStatus, metaCategory, held, wordingMatches, lookupProblem);
		}
	}

	/** One temple's running totals for one template. */
	private static final class Tally {
		int counted;
		int approved;
		int pending;
		int refused;
		int marketing;
		boolean formattingRefusal;

		void add(Stored row) {
			counted++;
			if (in(APPROVED_STATUSES, row.metaStatus())) {
				approved++;
			} else if (in(PENDING_STATUSES, row.metaStatus())) {
				pending++;
			} else if (in(REFUSED_STATUSES, row.metaStatus())) {
				refused++;
			}
			if (row.metaCategory() != null && MARKETING.equalsIgnoreCase(row.metaCategory())) {
				marketing++;
			}
			if (in(Set.of("REJECTED"), row.metaStatus()) && row.rejectedReason() != null
					&& FORMATTING_REASON.equalsIgnoreCase(row.rejectedReason())) {
				formattingRefusal = true;
			}
		}
	}

	/**
	 * One temple's copy. Matches {@code TempleTemplateStatusView} in {@code frontend/lib/api.ts}.
	 *
	 * @param asOf when the copy was taken; null if never
	 */
	public record View(UUID tenantId, Instant asOf, List<Row> templates) {
	}

	/**
	 * One template in one temple's copy. Matches {@code TempleTemplateStatus} in {@code frontend/lib/api.ts}.
	 *
	 * @param wordingMatches Meta's wording equals ours after trimming, as Reload compares; null unless held
	 * @param lookupProblem  a plain sentence where Meta could not be asked for this template
	 */
	public record Row(String name, String ourCategory, String metaStatus, String metaCategory, Boolean held,
			Boolean wordingMatches, String lookupProblem) {
	}
}
