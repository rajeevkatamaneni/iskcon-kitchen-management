package org.iskcon.kms.notification;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.iskcon.kms.user.User.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Email, over an authenticated SMTP relay we already have an account with (E1).
 *
 * <p>No transactional-mail service and no new bill. Cloud Run blocks outbound port 25 permanently,
 * so this cannot be a mail server of its own; what it is instead is a client of a relay on 587,
 * which is the one thing a cloud host does allow.
 *
 * <p>The From <em>address</em> is always the platform's, and only the display name changes:
 *
 * <pre>From: ISKCON South Bengaluru via ISKCON Kitchen &lt;noreply@…&gt;</pre>
 *
 * <p>That is not a cosmetic choice. SPF and DKIM are records on the domain a message claims to come
 * from, and a temple cannot pass them for a domain it does not control — so sending as the temple's
 * own address would put every reminder in a spam folder. The temple's name is right there in the
 * envelope, and {@code Reply-To} is the temple's own address, so a devotee who replies reaches the
 * temple and not us.
 */
@Component
public class SmtpEmailAdapter implements ChannelAdapter {

	private static final Logger log = LoggerFactory.getLogger(SmtpEmailAdapter.class);

	/**
	 * Optional, because Spring only creates a mail sender when a relay host is configured. A
	 * deployment without one must still start — and must say plainly that it cannot send email,
	 * rather than refusing to boot or, worse, reporting messages sent.
	 */
	private final ObjectProvider<JavaMailSender> mailSender;
	private final TenantEmailIdentityService identities;
	private final String fromAddress;
	private final String platformName;

	public SmtpEmailAdapter(
			ObjectProvider<JavaMailSender> mailSender,
			TenantEmailIdentityService identities,
			@Value("${kms.notifications.email.from:}") String fromAddress,
			@Value("${kms.notifications.email.platform-name:ISKCON Kitchen}") String platformName) {
		this.mailSender = mailSender;
		this.identities = identities;
		this.fromAddress = fromAddress;
		this.platformName = platformName;
	}

	@Override
	public NotificationChannel channel() {
		return NotificationChannel.EMAIL;
	}

	@Override
	public SendResult send(String address, OutboundMessage message) {
		JavaMailSender sender = mailSender.getIfAvailable();
		if (sender == null || fromAddress == null || fromAddress.isBlank()) {
			// Say it out loud. This returned a quiet failure once and cost an evening: the deployment
			// had SMTP_HOST set but an image built before application.yml mapped it, so there was no
			// mail sender, the send failed silently, and the absence of any log line read exactly
			// like success. A channel that cannot send must be noisy about why.
			log.warn("Email not sent: no relay configured (mail sender {}, from address {}). "
							+ "Check spring.mail.host and kms.notifications.email.from.",
					sender == null ? "absent" : "present",
					fromAddress == null || fromAddress.isBlank() ? "unset" : "set");
			return SendResult.failed("no email sender is configured for this deployment");
		}

		TenantEmailIdentityService.Identity identity = identities.current();
		try {
			MimeMessage mime = sender.createMimeMessage();
			MimeMessageHelper helper =
					new MimeMessageHelper(mime, false, StandardCharsets.UTF_8.name());

			helper.setFrom(new InternetAddress(fromAddress, senderName(identity.templeName()),
					StandardCharsets.UTF_8.name()));
			helper.setTo(address);
			helper.setSubject(message.rendered().subject());
			helper.setText(message.rendered().body(), false);
			if (identity.replyTo() != null) {
				helper.setReplyTo(identity.replyTo());
			}

			sender.send(mime);

			// SMTP hands back no id worth keeping, and there is no delivery callback to key on one —
			// this is here so the attempt record has something stable to point at.
			String providerMessageId = "smtp-" + UUID.randomUUID();
			// Logged because the alternative is what happened before: no line for success, no line for
			// the quiet failure, and no way to tell them apart from outside.
			log.info("Email sent to {} for template {} (id {})",
					address, message.template().whatsappTemplateName(), providerMessageId);
			return SendResult.sent(providerMessageId);

		} catch (UnsupportedEncodingException | jakarta.mail.MessagingException e) {
			log.warn("Email send failed for template {}: {}",
					message.template().whatsappTemplateName(), e.toString());
			return SendResult.failed(e.getMessage());
		} catch (RuntimeException e) {
			// A relay that refuses, times out, or rejects our credentials. One message failing is not
			// a reason to stop; it is a reason for this attempt to be recorded as failed.
			log.warn("Email send failed for template {}: {}",
					message.template().whatsappTemplateName(), e.toString());
			return SendResult.failed(e.getMessage());
		}
	}

	/** "ISKCON South Bengaluru via ISKCON Kitchen", or just ours when we do not know the temple. */
	private String senderName(String templeName) {
		return templeName == null || templeName.isBlank()
				? platformName
				: templeName + " via " + platformName;
	}
}
