package org.iskcon.kms.communication;

import java.time.OffsetDateTime;
import java.util.HashMap;
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
import org.iskcon.kms.notification.NotificationRecipient;
import org.iskcon.kms.notification.NotificationService;
import org.iskcon.kms.notification.NotificationTemplate;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.user.User.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writing to the temple's community (E8-S2, E8-S3).
 *
 * <p>Three things happen here and they are deliberately separate acts: a communication is
 * <b>drafted</b> (and re-drafted, and previewed, as often as anyone likes), <b>tested</b> by sending
 * it to the author alone, and only then <b>sent</b>. Nothing about a draft touches a devotee.
 *
 * <p>The audience is computed at send time and written down. "Who did this reach" must stay
 * answerable a year later, when the list of devotees has changed and recomputing it would give a
 * different answer.
 *
 * <p>What is not here, and cannot be: a WhatsApp message carrying the letter. Meta delivers only
 * templates it has already approved, so the WhatsApp form of a communication is a short approved
 * announcement with a link back to the web copy. Saying otherwise on the screen would be promising
 * something the channel will refuse.
 */
@Service
public class CommunicationService {

	private static final Logger log = LoggerFactory.getLogger(CommunicationService.class);

	private final JdbcTemplate jdbc;
	private final NewsletterHtml html;
	private final UnsubscribeTokens tokens;
	private final NotificationService notifications;
	private final AuditService auditService;
	private final String webBaseUrl;

	public CommunicationService(
			JdbcTemplate jdbc, NewsletterHtml html, UnsubscribeTokens tokens,
			NotificationService notifications, AuditService auditService,
			@Value("${kms.web-base-url:http://localhost:3000}") String webBaseUrl) {
		this.jdbc = jdbc;
		this.html = html;
		this.tokens = tokens;
		this.notifications = notifications;
		this.auditService = auditService;
		this.webBaseUrl = webBaseUrl.endsWith("/")
				? webBaseUrl.substring(0, webBaseUrl.length() - 1) : webBaseUrl;
	}

	// ---- Drafting -------------------------------------------------------

	@Transactional(readOnly = true)
	public List<CommunicationView> list() {
		return jdbc.query(SELECT + " ORDER BY c.created_at DESC", MAPPER);
	}

	@Transactional(readOnly = true)
	public CommunicationView get(UUID id) {
		return find(id).orElseThrow(() -> notFound(id));
	}

	@Transactional
	public UUID save(AuthenticatedUser actor, UUID id, SaveCommunicationRequest request) {
		requireComposable(request.category());
		String safeHtml = html.sanitise(request.bodyHtml());
		String text = html.toPlainText(safeHtml);
		requireSomethingToSay(request, text);

		if (id == null) {
			UUID created = UUID.randomUUID();
			jdbc.update("""
					INSERT INTO communications (
						id, tenant_id, category, channel, subject, body_html, body_text,
						whatsapp_summary, created_by)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?, ?)
					""", created, request.category().name(), request.channel().name(),
					request.subject().trim(), safeHtml, text,
					trimToNull(request.whatsappSummary()), actor.getUserId());
			return created;
		}

		CommunicationView existing = find(id).orElseThrow(() -> notFound(id));
		requireDraft(existing);
		jdbc.update("""
				UPDATE communications SET category = ?, channel = ?, subject = ?, body_html = ?,
					body_text = ?, whatsapp_summary = ?, updated_at = now() WHERE id = ?
				""", request.category().name(), request.channel().name(), request.subject().trim(),
				safeHtml, text, trimToNull(request.whatsappSummary()), id);
		return id;
	}

	@Transactional
	public void delete(UUID id) {
		CommunicationView existing = find(id).orElseThrow(() -> notFound(id));
		requireDraft(existing);
		jdbc.update("DELETE FROM communications WHERE id = ?", id);
	}

	/**
	 * Exactly what will arrive, rendered the same way the send renders it.
	 *
	 * <p>The same method builds the preview and the message, which is the only way a preview is worth
	 * anything: two code paths that agree today would disagree the first time one was edited, and the
	 * preview would go on being reassuring while being wrong.
	 */
	@Transactional(readOnly = true)
	public PreviewView preview(UUID id) {
		CommunicationView c = find(id).orElseThrow(() -> notFound(id));
		String temple = templeName();
		return new PreviewView(
				c.subject(),
				html.frame(temple, c.subject(), c.bodyHtml(),
						// A sample link, because a preview has no recipient to issue a real one for.
						webBaseUrl + "/unsubscribe?token=sample", webUrl(c.publicToken())),
				NotificationTemplate.TEMPLE_ANNOUNCEMENT.render(Map.of(
						"temple", temple,
						"subject", c.subject(),
						"intro", c.whatsappSummary() == null ? "" : c.whatsappSummary(),
						"link", webUrl(c.publicToken()))).body(),
				c.bodyText());
	}

	// ---- Sending --------------------------------------------------------

	/**
	 * The author's own copy, before four hundred other people get one.
	 *
	 * <p>Offered because a preview and a real message are not quite the same claim. The preview shows
	 * what we will hand the relay; only an actual send shows what Gmail or Outlook then makes of it,
	 * and that is the thing nobody can promise from inside this system.
	 */
	@Transactional
	public void sendTest(AuthenticatedUser actor, UUID id) {
		CommunicationView c = find(id).orElseThrow(() -> notFound(id));
		queueFor(c, actor.getUserId(), true);
		log.info("Test copy of communication {} queued for its author {}", id, actor.getUserId());
	}

	/** Sends to everyone who has not declined this kind of message. */
	@Transactional
	public SendResultView send(AuthenticatedUser actor, UUID id) {
		CommunicationView c = find(id).orElseThrow(() -> notFound(id));
		requireDraft(c);

		List<UUID> audience = audienceFor(c);
		if (audience.isEmpty()) {
			throw new ApplicationException(ErrorCode.COMMUNICATION_HAS_NO_AUDIENCE,
					Map.of("communicationId", id, "category", c.category().name()));
		}

		int queued = 0;
		for (UUID userId : audience) {
			if (queueFor(c, userId, false)) {
				queued++;
			}
		}

		jdbc.update("""
				UPDATE communications SET status = 'SENT', sent_at = now(), audience_count = ?,
					updated_at = now() WHERE id = ?
				""", audience.size(), id);

		auditService.record(actor, AuditAction.COMMUNICATION_SENT,
				AuditEntityType.COMMUNICATION, id, null,
				Map.of("category", c.category().name(), "channel", c.channel().name(),
						"subject", c.subject(), "recipients", audience.size()),
				null);

		log.info("Communication {} sent to {} recipients ({} queued)", id, audience.size(), queued);
		return new SendResultView(audience.size(), queued);
	}

	/**
	 * Sends it again to the people it failed for, and to nobody else (B6).
	 *
	 * <p>Forty failed WhatsApp deliveries used to mean writing the whole letter again, with the list
	 * of who it failed for sitting on the screen in front of the person retyping it. Nothing about
	 * that was necessary: {@code communication_recipients} already names each person and the
	 * notification their copy became, so the failures are already known by name.
	 *
	 * <p><b>Only delivery is retried.</b> The letter itself stays frozen the moment it is sent —
	 * {@link #requireDraft} goes on refusing edit, delete and send exactly as before, and this path
	 * reads the subject and body back out of the row rather than taking anything from the request.
	 * The person who received a copy at nine o'clock and the person who receives one at ten must be
	 * reading the same words, or "who did this reach" stops meaning anything.
	 *
	 * <p>Only {@code FAILED} is a failure. {@code SUPPRESSED} is a decision — a devotee turned this
	 * kind off, or never agreed to be contacted at all — and sending to them again would be
	 * overriding them; the second attempt would be suppressed identically anyway. {@code PENDING} is
	 * a copy still on its way, and re-queueing it would deliver the message twice.
	 */
	@Transactional
	public RetryResultView retryFailed(AuthenticatedUser actor, UUID id) {
		CommunicationView c = find(id).orElseThrow(() -> notFound(id));

		lockForRetry(id);

		List<UUID> failed = failedRecipients(id);
		if (failed.isEmpty()) {
			throw nothingToRetry(id, c);
		}

		int retried = 0;
		for (UUID userId : failed) {
			// The same queueFor() the original send used, so a retry is the send it is retrying and
			// not a second implementation of it that would drift from it by the second edit.
			if (queueFor(c, userId, false)) {
				retried++;
			}
		}

		// Audited, and under an action of its own (T-084). Re-sending a newsletter to forty devotees
		// is the same class of act as sending it — outward-facing, to real people, by a named user —
		// so "who caused this message to reach this devotee, and when" has to be answerable for the
		// second attempt as it already was for the first, and a log line is not that: the audit log is
		// the per-tenant record operators and admins actually read.
		//
		// COMMUNICATION_RETRIED rather than a second COMMUNICATION_SENT carrying a distinguishing
		// field. A count over an action name goes on compiling perfectly when the meaning of the rows
		// underneath it changes, so anything totalling COMMUNICATION_SENT as "messages this temple
		// sent" would quietly begin counting retries of them as well, and nothing would ever say so.
		auditService.record(actor, AuditAction.COMMUNICATION_RETRIED,
				AuditEntityType.COMMUNICATION, id, null,
				Map.of("category", c.category().name(), "channel", c.channel().name(),
						"subject", c.subject(), "failed", failed.size(), "retried", retried),
				null);

		log.info("Communication {} retried by {} for {} of its {} failed recipients",
				id, actor.getUserId(), retried, failed.size());
		return new RetryResultView(retried);
	}

	/**
	 * Holds the communication row for the rest of this transaction, before a word is read about who
	 * its message failed for (T-084).
	 *
	 * <p>The same lock, for the same reason, as {@code ServedMealService.correct} and
	 * {@code SignupService.lockShift}: what follows is a read, a decision taken on it, and a write,
	 * and under READ COMMITTED those three do not belong to one another unless something says so.
	 * Two admins pressing <i>Send it to them again</i> at the same moment otherwise both run
	 * {@link #failedRecipients}, both see the identical list — neither has committed anything the
	 * other can see — and both queue a fresh copy for every name on it. <b>Every failed recipient is
	 * then written to twice.</b> {@code queueFor}'s {@code ON CONFLICT … DO UPDATE} does not save it:
	 * that clause decides only which of the two notifications the recipient row ends up naming, and
	 * the copy it does not name has already been handed to the relay. The screen's disabled button
	 * covers one browser tab and nothing else.
	 *
	 * <p>With the lock the second transaction waits, then reads the failed set the first one left
	 * behind — empty, because a retried copy is PENDING and no longer failed — and is refused. A
	 * refusal is the right answer there: the message <em>is</em> on its way, and telling somebody so
	 * is the whole of what they wanted to know.
	 *
	 * <p>{@code queryForList} rather than {@code queryForObject} so a row deleted between
	 * {@link #find} and here is no rows rather than an exception nobody could act on; the caller's
	 * own guards speak for that case.
	 */
	private void lockForRetry(UUID id) {
		jdbc.queryForList("SELECT id FROM communications WHERE id = ? FOR UPDATE", UUID.class, id);
	}

	/**
	 * Why there is nothing to send again — which is three different facts, and used to be one
	 * sentence (T-084).
	 *
	 * <p>An empty failed set has three causes and they are not variations on each other. Every copy
	 * genuinely arrived. Or copies are still on their way, so nothing has failed <em>yet</em> and
	 * nothing has been confirmed delivered either. Or the message is a draft and has no recipients at
	 * all, having never been sent to anybody. {@code NOTHING_FAILED_TO_RETRY} said <i>"Every copy of
	 * this message was delivered"</i> for all three, which for the last one told the sender that a
	 * letter nobody has ever received was fully delivered — the confidently wrong sentence the
	 * error-code rule exists to keep out.
	 *
	 * <p>The branch is on the recipient rows rather than on {@code communications.status}, because
	 * the rows are the thing the caller is being told about. A draft has none, and so does the one
	 * odd case a status check would misread: a message marked SENT for which every single queueing
	 * attempt threw, where nothing reached anybody and "every copy was delivered" would be just as
	 * untrue.
	 *
	 * <p>PENDING alone counts as in flight, deliberately, and SENT does not. SENT means a provider
	 * has taken it; for email no delivery receipt is ever coming, so treating SENT as unfinished
	 * would leave those messages saying <i>"check back shortly"</i> for ever and make the delivered
	 * case unreachable.
	 */
	private ApplicationException nothingToRetry(UUID id, CommunicationView c) {
		Map<String, Object> counts = jdbc.queryForMap("""
				SELECT count(*) AS recipients,
					count(*) FILTER (WHERE n.status = 'PENDING') AS still_pending
				FROM communication_recipients r
				LEFT JOIN notifications n ON n.id = r.notification_id
				WHERE r.communication_id = ?
				""", id);
		long recipients = ((Number) counts.get("recipients")).longValue();
		long stillPending = ((Number) counts.get("still_pending")).longValue();

		Map<String, Object> detail = Map.of("communicationId", id, "status", c.status().name(),
				"recipients", recipients, "stillPending", stillPending);

		if (recipients == 0) {
			return new ApplicationException(ErrorCode.COMMUNICATION_NOT_SENT, detail);
		}
		if (stillPending > 0) {
			return new ApplicationException(ErrorCode.NOTHING_FAILED_YET, detail);
		}
		return new ApplicationException(ErrorCode.NOTHING_FAILED_TO_RETRY, detail);
	}

	/**
	 * The people whose copy of this message failed — by name, from the notification that carries the
	 * outcome, rather than from anything copied into the recipient row and left to drift.
	 */
	private List<UUID> failedRecipients(UUID id) {
		return jdbc.queryForList("""
				SELECT r.recipient_user_id
				FROM communication_recipients r
				JOIN notifications n ON n.id = r.notification_id
				WHERE r.communication_id = ? AND n.status = 'FAILED'
				ORDER BY r.created_at
				""", UUID.class, id);
	}

	/**
	 * Everyone this may go to: the temple's devotees, minus those who have declined this category.
	 *
	 * <p>Staff are deliberately not on it. A newsletter is written for the community that comes to
	 * the temple, and the cooks already hear everything in the kitchen; if a temple wants its staff
	 * included, that is a decision to make out loud rather than a side effect of them holding an
	 * account.
	 */
	@Transactional(readOnly = true)
	public List<UUID> audienceFor(CommunicationView c) {
		return jdbc.queryForList("""
				SELECT u.id FROM users u
				WHERE u.role = 'VOLUNTEER'
				  AND u.status = 'ACTIVE'
				  AND u.contact_consent_at IS NOT NULL
				  AND u.optional_communications_opt_out_at IS NULL
				  AND NOT EXISTS (
					  SELECT 1 FROM communication_preferences p
					  WHERE p.user_id = u.id AND p.category = ?)
				ORDER BY u.full_name
				""", UUID.class, c.category().name());
	}

	/** How many people a draft would reach right now, for the confirmation before sending. */
	@Transactional(readOnly = true)
	public int audienceSize(UUID id) {
		return audienceFor(find(id).orElseThrow(() -> notFound(id))).size();
	}

	private boolean queueFor(CommunicationView c, UUID userId, boolean isTest) {
		UUID tenantId = TenantContext.get().orElseThrow(
				() -> new IllegalStateException("A communication is sent within a tenant context"));

		Map<String, Object> params = new HashMap<>();
		params.put("communicationId", c.id().toString());
		params.put("subject", c.subject());
		params.put("temple", templeName());
		params.put("link", webUrl(c.publicToken()));
		params.put("intro", c.whatsappSummary() == null ? "" : c.whatsappSummary());
		params.put("unsubscribeUrl", webBaseUrl + "/unsubscribe?token="
				+ tokens.issue(tenantId, userId, c.category()));

		NotificationTemplate template = c.channel() == CommunicationChannel.WHATSAPP
				? NotificationTemplate.TEMPLE_ANNOUNCEMENT
				: NotificationTemplate.TEMPLE_COMMUNICATION;
		NotificationChannel channel = c.channel() == CommunicationChannel.WHATSAPP
				? NotificationChannel.WHATSAPP
				: NotificationChannel.EMAIL;

		try {
			// A test copy carries the author's own category gate as any message would — if the person
			// sending the newsletter has opted out of newsletters, they should see that happen.
			UUID notificationId =
					notifications.notify(NotificationRecipient.user(userId), template, params,
							channel, c.category());
			if (!isTest) {
				jdbc.update("""
						INSERT INTO communication_recipients (
							id, tenant_id, communication_id, recipient_user_id, notification_id)
						VALUES (gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid,
							?, ?, ?)
						-- DO UPDATE, not DO NOTHING, and the difference is the whole of the retry
						-- (B6/T-015). One row per person per message is still the rule — the unique
						-- index says so — but the row's job is to name *the attempt that stands*, and
						-- a retry produces a new notification. Left as DO NOTHING the insert
						-- succeeded, changed nothing, and the recipient went on pointing at the
						-- notification that had already failed: the screen would keep saying "Failed"
						-- for somebody who had just been written to, and the retry would report a
						-- success it had not had. A send cannot conflict here at all (a draft is sent
						-- once, requireDraft() sees to that), so this clause only ever runs for a
						-- retry.
						ON CONFLICT (tenant_id, communication_id, recipient_user_id)
						DO UPDATE SET notification_id = EXCLUDED.notification_id
						""", c.id(), userId, notificationId);
			}
			return true;
		} catch (RuntimeException e) {
			// One unreachable devotee is not a reason to abandon the other three hundred and ninety.
			log.warn("Could not queue communication {} for {}: {}", c.id(), userId, e.toString());
			return false;
		}
	}

	// ---- The sent log ---------------------------------------------------

	/** Who it went to and what became of each one — the answer to "did it actually go?". */
	@Transactional(readOnly = true)
	public List<DeliveryView> deliveries(UUID id) {
		return jdbc.query("""
				SELECT u.full_name, n.status, n.final_channel, n.preferred_channel, n.suppressed_reason
				FROM communication_recipients r
				JOIN users u ON u.id = r.recipient_user_id
				LEFT JOIN notifications n ON n.id = r.notification_id
				WHERE r.communication_id = ?
				ORDER BY u.full_name
				""", (rs, n) -> new DeliveryView(
						rs.getString("full_name"),
						rs.getString("status") == null ? "UNKNOWN" : rs.getString("status"),
						rs.getString("final_channel") != null
								? rs.getString("final_channel") : rs.getString("preferred_channel"),
						rs.getString("suppressed_reason")), id);
	}

	/** The public web copy, by its unguessable name. Sent communications only. */
	@Transactional(readOnly = true)
	public Optional<PublicCommunicationView> publicCopy(String token) {
		return jdbc.query("""
				SELECT c.subject, c.body_html, c.sent_at, t.name AS temple_name
				FROM communications c JOIN tenants t ON t.id = c.tenant_id
				WHERE c.public_token = ? AND c.status = 'SENT'
				""", (rs, n) -> new PublicCommunicationView(
						rs.getString("temple_name"), rs.getString("subject"),
						rs.getString("body_html"),
						rs.getObject("sent_at", OffsetDateTime.class) == null
								? null : rs.getObject("sent_at", OffsetDateTime.class).toInstant()),
				token).stream().findFirst();
	}

	// ---------------------------------------------------------------------

	private String webUrl(String publicToken) {
		return webBaseUrl + "/c/" + publicToken;
	}

	private static void requireComposable(CommunicationCategory category) {
		if (category == null || !category.isOptional()) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "category", "reason",
							"a message somebody wrote is never operational — that category is for "
									+ "what the system sends of its own accord"));
		}
	}

	private static void requireSomethingToSay(SaveCommunicationRequest request, String text) {
		if (request.channel() == CommunicationChannel.EMAIL && text.isBlank()) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "bodyHtml", "reason", "an empty letter is not a letter"));
		}
		if (request.channel() == CommunicationChannel.WHATSAPP
				&& trimToNull(request.whatsappSummary()) == null) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "whatsappSummary", "reason",
							"WhatsApp carries one line and a link, so the line has to say something"));
		}
	}

	private static void requireDraft(CommunicationView c) {
		if (c.status() != CommunicationStatus.DRAFT) {
			throw new ApplicationException(ErrorCode.COMMUNICATION_ALREADY_SENT,
					Map.of("communicationId", c.id()));
		}
	}

	private String templeName() {
		try {
			return jdbc.queryForObject("""
					SELECT name FROM tenants WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""", String.class);
		} catch (RuntimeException e) {
			return "the temple";
		}
	}

	private Optional<CommunicationView> find(UUID id) {
		return jdbc.query(SELECT + " WHERE c.id = ?", MAPPER, id).stream().findFirst();
	}

	private ApplicationException notFound(UUID id) {
		return new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("communicationId", id));
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	private static final String SELECT = """
			SELECT c.id, c.category, c.channel, c.subject, c.body_html, c.body_text,
			       c.whatsapp_summary, c.status, c.audience_count, c.public_token,
			       c.created_at, c.sent_at, u.full_name AS author
			FROM communications c LEFT JOIN users u ON u.id = c.created_by
			""";

	private static final RowMapper<CommunicationView> MAPPER = (rs, n) -> new CommunicationView(
			rs.getObject("id", UUID.class),
			CommunicationCategory.valueOf(rs.getString("category")),
			CommunicationChannel.valueOf(rs.getString("channel")),
			rs.getString("subject"),
			rs.getString("body_html"),
			rs.getString("body_text"),
			rs.getString("whatsapp_summary"),
			CommunicationStatus.valueOf(rs.getString("status")),
			(Integer) rs.getObject("audience_count"),
			rs.getString("public_token"),
			rs.getString("author"),
			toInstant(rs.getObject("created_at", OffsetDateTime.class)),
			toInstant(rs.getObject("sent_at", OffsetDateTime.class)));

	private static java.time.Instant toInstant(OffsetDateTime odt) {
		return odt == null ? null : odt.toInstant();
	}

	/** Everything a temple's own screen shows about one communication. */
	public record SendResultView(int audience, int queued) {
	}

	/** How many people the message was re-queued for — never how many failed, which is not the same. */
	public record RetryResultView(int retried) {
	}

	/** The preview: the email exactly as framed, the WhatsApp line exactly as Meta would carry it. */
	public record PreviewView(String subject, String emailHtml, String whatsappText, String plainText) {
	}

	public record DeliveryView(
			String recipientName, String status, String channel, String suppressedReason) {
	}

	public record PublicCommunicationView(
			String templeName, String subject, String bodyHtml, java.time.Instant sentAt) {
	}
}
