package org.iskcon.kms.communication;

import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.notification.NotificationTemplate;
import org.iskcon.kms.notification.OutboundBodySource;
import org.iskcon.kms.notification.RenderedMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Where a newsletter's body actually comes from (E8-S2).
 *
 * <p>Every other message the system sends is a sentence built from four or five values, so the
 * values travel with it. A letter cannot: storing a copy of it against each of four hundred
 * recipients would put the same six hundred words in the database four hundred times. So the
 * notification carries the communication's id, and this fetches the letter once per send.
 *
 * <p>It also frames it — the temple's name above, the way out below — and the unsubscribe link is
 * the one part that genuinely differs per recipient, which is why that much does travel in the
 * parameters.
 */
@Component
public class CommunicationBodySource implements OutboundBodySource {

	private final JdbcTemplate jdbc;
	private final NewsletterHtml html;

	public CommunicationBodySource(JdbcTemplate jdbc, NewsletterHtml html) {
		this.jdbc = jdbc;
		this.html = html;
	}

	@Override
	public boolean handles(NotificationTemplate template) {
		return template == NotificationTemplate.TEMPLE_COMMUNICATION;
	}

	@Override
	public RenderedMessage render(Map<String, Object> params) {
		String subject = string(params, "subject");
		String temple = string(params, "temple");
		UUID id = uuid(params.get("communicationId"));

		if (id == null) {
			return new RenderedMessage(subject, subject);
		}

		return jdbc.query("SELECT body_html, body_text FROM communications WHERE id = ?",
				(rs, n) -> new RenderedMessage(
						subject,
						rs.getString("body_text"),
						html.frame(temple, subject, rs.getString("body_html"),
								string(params, "unsubscribeUrl"), string(params, "link"))),
				id).stream().findFirst()
				// The letter has been deleted between queueing and sending. Say the subject rather
				// than an empty message: an empty one reads as a fault at our end.
				.orElseGet(() -> new RenderedMessage(subject, subject));
	}

	private static String string(Map<String, Object> params, String key) {
		Object value = params == null ? null : params.get(key);
		return value == null ? "" : value.toString();
	}

	private static UUID uuid(Object value) {
		try {
			return value == null ? null : UUID.fromString(value.toString());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
