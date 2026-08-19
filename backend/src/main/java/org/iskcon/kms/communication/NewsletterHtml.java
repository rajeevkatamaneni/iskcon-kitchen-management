package org.iskcon.kms.communication;

import java.util.regex.Pattern;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;
import org.springframework.stereotype.Component;

/**
 * Turning what an admin wrote or pasted into something we are willing to send (E8-S2).
 *
 * <p>Two jobs, and the order matters: <b>sanitise</b>, then <b>frame</b>.
 *
 * <p><b>Sanitising</b> is not optional and not hand-rolled. A newsletter arrives one of two ways —
 * typed into the composer, or pasted out of Word or Google Docs — and the paste is the hard case: it
 * carries pages of vendor markup, conditional comments, class names pointing at stylesheets that do
 * not exist here, and occasionally a script. What survives is a deliberately small vocabulary: the
 * things a temple letter is actually made of. Everything else is dropped rather than escaped, so the
 * reader gets the words without the wreckage.
 *
 * <p><b>Framing</b> is the part people underestimate. An email client is not a browser: there is no
 * external stylesheet, `<style>` blocks are unreliable, and floats and flexbox are not to be trusted.
 * So the body is wrapped in a table-based shell with every rule inlined — the shape email has used
 * for twenty years, because it is the shape that renders.
 *
 * <p>And the foot of every optional message carries a way out. That is not politeness: it is Gmail's
 * bulk-sender requirement and, under the DPDP Act, the withdrawal of consent that has to be as easy
 * as the giving of it was.
 */
@Component
public class NewsletterHtml {

	/**
	 * What a temple letter is made of. Headings, paragraphs, emphasis, lists, links, images, rules,
	 * quotes and simple tables — and nothing that can carry behaviour or reach off the page.
	 *
	 * <p>Notably absent: {@code style} and {@code class} attributes. A pasted Google Docs style
	 * attribute describes a document that does not exist here and reliably makes the letter look
	 * worse, not better. The shell supplies the typography.
	 */
	private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
			.allowElements(
					"p", "br", "strong", "b", "em", "i", "u", "s",
					"h1", "h2", "h3", "h4",
					"ul", "ol", "li",
					"blockquote", "hr",
					"table", "thead", "tbody", "tr", "th", "td",
					"span", "div")
			.allowAttributes("colspan", "rowspan").onElements("td", "th")
			.toFactory()
			// Links and images get their own vetted policies: protocols are checked, and every link
			// is rewritten to open in a new tab with rel=noopener.
			.and(Sanitizers.LINKS)
			.and(Sanitizers.IMAGES);

	private static final Pattern TAGS = Pattern.compile("<[^>]+>");
	private static final Pattern BLANK_LINES = Pattern.compile("\n{3,}");

	/** What is safe to store and send. Runs on the way in, so nothing unsafe is ever at rest. */
	public String sanitise(String html) {
		return html == null ? null : POLICY.sanitize(html);
	}

	/**
	 * The plain-text half of the message.
	 *
	 * <p>A multipart email without one is treated as suspicious by filters, and it is also the whole
	 * of what WhatsApp and SMS can carry. Block-level tags become line breaks so the result reads as
	 * paragraphs rather than one long run-on.
	 */
	public String toPlainText(String html) {
		if (html == null || html.isBlank()) {
			return "";
		}
		String withBreaks = html
				.replaceAll("(?i)<br\\s*/?>", "\n")
				.replaceAll("(?i)</(p|div|h[1-4]|li|tr|blockquote)>", "\n")
				.replaceAll("(?i)<li[^>]*>", "• ");
		String text = TAGS.matcher(withBreaks).replaceAll("");
		text = text.replace("&nbsp;", " ")
				.replace("&amp;", "&")
				.replace("&lt;", "<")
				.replace("&gt;", ">")
				.replace("&quot;", "\"")
				.replace("&#39;", "'");
		return BLANK_LINES.matcher(text).replaceAll("\n\n").trim();
	}

	/**
	 * The letter as it will actually arrive: the temple's name above it, the body in the middle, and
	 * the way out at the foot.
	 *
	 * @param unsubscribeUrl the one-category link, or null for a message with no opt-out (which today
	 *                       means only a preview — everything composed here is declinable)
	 */
	public String frame(String templeName, String subject, String bodyHtml,
			String unsubscribeUrl, String webUrl) {

		StringBuilder out = new StringBuilder(2048);
		out.append("""
				<!doctype html>
				<html><head><meta charset="utf-8">
				<meta name="viewport" content="width=device-width, initial-scale=1">
				<title>""").append(escape(subject)).append("""
				</title></head>
				<body style="margin:0;padding:0;background:#f5f2ee;">
				<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f5f2ee;">
				<tr><td align="center" style="padding:24px 12px;">
				<table role="presentation" width="100%" cellpadding="0" cellspacing="0"
				       style="max-width:600px;background:#ffffff;border-radius:8px;overflow:hidden;">
				<tr><td style="padding:24px 28px 8px 28px;font-family:Georgia,'Times New Roman',serif;
				               font-size:18px;color:#8c3b1e;">""")
				.append(escape(templeName)).append("""
				</td></tr>
				<tr><td style="padding:0 28px 24px 28px;font-family:-apple-system,BlinkMacSystemFont,
				               'Segoe UI',Roboto,sans-serif;font-size:16px;line-height:1.6;color:#2b2622;">
				""");

		out.append(bodyHtml == null ? "" : bodyHtml);

		out.append("""
				</td></tr>
				<tr><td style="padding:16px 28px 24px 28px;border-top:1px solid #e7e0d8;
				               font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
				               font-size:12px;line-height:1.5;color:#7a7168;">
				""");

		if (webUrl != null) {
			out.append("<p style=\"margin:0 0 8px 0;\">")
					.append("<a href=\"").append(escape(webUrl))
					.append("\" style=\"color:#8c3b1e;\">Read this in your browser</a></p>");
		}
		if (unsubscribeUrl != null) {
			out.append("<p style=\"margin:0;\">You are receiving this because you registered at ")
					.append(escape(templeName))
					.append(". <a href=\"").append(escape(unsubscribeUrl))
					.append("\" style=\"color:#8c3b1e;\">Stop receiving these</a> ")
					.append("— your shift reminders and receipts are not affected.</p>");
		}

		out.append("""
				</td></tr>
				</table></td></tr></table>
				</body></html>
				""");
		return out.toString();
	}

	private static String escape(String s) {
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
				.replace("\"", "&quot;");
	}
}
