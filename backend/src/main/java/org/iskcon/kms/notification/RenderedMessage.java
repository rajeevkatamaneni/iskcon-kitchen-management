package org.iskcon.kms.notification;

/** A template rendered for a specific recipient: what SMS/email send, and a subject line. */
public record RenderedMessage(String subject, String body) {
}
