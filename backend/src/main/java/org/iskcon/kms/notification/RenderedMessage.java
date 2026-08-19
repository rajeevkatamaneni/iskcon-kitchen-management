package org.iskcon.kms.notification;

/**
 * A template rendered for a specific recipient: what SMS and email send, and a subject line.
 *
 * <p>{@code html} is set only where there is a richer form worth sending — today, a newsletter a
 * temple wrote (E8-S2). Where it is null the plain body is the whole message, which is what every
 * reminder and receipt has always been: a sentence needs no markup, and sending one wrapped in a
 * table would be pretence.
 */
public record RenderedMessage(String subject, String body, String html) {

	public RenderedMessage(String subject, String body) {
		this(subject, body, null);
	}
}
