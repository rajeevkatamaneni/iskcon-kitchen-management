package org.iskcon.kms.ops;

/**
 * One WhatsApp template's status counted across every temple's stored copy (T-178). Matches
 * {@code TemplateStatusCounts} in {@code frontend/lib/api.ts}.
 *
 * <p><strong>Counts only, never which temples.</strong> The platform operator reads a temple's own
 * answer one temple at a time, on that temple's page. What crosses the boundary here is the same kind of
 * thing {@link NotificationMetrics} carries: numbers with no temple attached. There is deliberately no
 * field that could hold a temple's id or name, and {@code TemplateStatusCountsIT} pins the keys.
 *
 * <p>The groups are defined in {@link TemplateStatusCopy}, beside the statuses they are made of.
 *
 * @param name              Meta's template name
 * @param templesCounted    temples whose stored copy has a row for this template; the M in "approved in N
 *                          of M". A temple with no WhatsApp connection, or never refreshed, has no copy
 *                          and is not counted
 * @param approved          temples where Meta's status is {@code APPROVED}
 * @param pending           temples where it is {@code PENDING} or {@code IN_APPEAL}
 * @param refused           temples where it is {@code REJECTED}, {@code PAUSED} or {@code DISABLED}
 * @param marketing         temples where Meta holds it under {@code MARKETING}, whatever its status
 * @param formattingRefusal true when any temple's copy is {@code REJECTED} with Meta's reason
 *                          {@code INVALID_FORMAT}: Meta's formatting rules are the same for every temple,
 *                          so a refusal of the wording's form in one is a warning for all
 */
public record TemplateStatusCounts(
		String name,
		int templesCounted,
		int approved,
		int pending,
		int refused,
		int marketing,
		boolean formattingRefusal) {
}
