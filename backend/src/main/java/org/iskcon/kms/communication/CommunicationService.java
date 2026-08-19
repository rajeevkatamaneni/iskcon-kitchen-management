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
						ON CONFLICT (tenant_id, communication_id, recipient_user_id) DO NOTHING
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
