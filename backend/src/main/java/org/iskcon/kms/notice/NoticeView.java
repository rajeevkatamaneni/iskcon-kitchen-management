package org.iskcon.kms.notice;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One notice as a reader sees it (E9-S1).
 *
 * <p>The same payload serves the band at the top of Today and the permanent board at /notices, on
 * purpose: they are the same notice, and a second shape for the second screen is how two screens
 * start disagreeing about what a temple was told.
 *
 * <p>What is deliberately absent is the raiser's <em>name</em>. §11 asks that every notice carry the
 * raising temple's name in the open, and it does — {@link #raisedBy()} — but the person behind it is
 * that temple's business and the platform audit log's, not two hundred other temples'. A recall is
 * answered by ringing the temple, not by naming an individual to strangers.
 *
 * @param raisedBy         the raising temple's name, or the platform's for one raised by an operator
 *                         or by automation. Captured when the notice was written, so it still reads
 *                         correctly after a temple leaves the platform.
 * @param withdrawnReason  why it was taken down. Never null on a withdrawn notice — a retraction
 *                         without a reason leaves every temple guessing whether the original was
 *                         wrong or merely finished.
 * @param mine             raised by the reader's own temple. Drives nothing on its own; it is what
 *                         lets a screen say "your temple posted this" rather than repeating the name
 *                         the reader already knows.
 * @param canWithdraw      whether <em>this</em> reader may take it down — the raising temple's own
 *                         admin, or a platform operator, and only while it still stands. Computed
 *                         here rather than inferred on the client, because the client inferring an
 *                         authorisation rule is how the client and the server end up disagreeing
 *                         about one.
 */
public record NoticeView(
		UUID id,
		NoticeSeverity severity,
		String subject,
		String body,
		String raisedBy,
		OffsetDateTime raisedAt,
		boolean withdrawn,
		String withdrawnBy,
		OffsetDateTime withdrawnAt,
		String withdrawnReason,
		boolean mine,
		boolean canWithdraw) {
}
