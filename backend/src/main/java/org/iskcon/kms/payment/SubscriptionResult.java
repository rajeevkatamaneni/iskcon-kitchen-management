package org.iskcon.kms.payment;

/** A subscription created at the provider (E7-S3): its id, and an optional mandate-authorization URL. */
public record SubscriptionResult(String subscriptionId, String shortUrl) {
}
