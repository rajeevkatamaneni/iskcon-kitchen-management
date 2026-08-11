package org.iskcon.kms.payment;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * A stored, signature-verified payment webhook event handed to handlers (E7-S9). {@code payload} is
 * the provider's parsed JSON; the donation handlers read the order/subscription/payment refs from it.
 */
public record PaymentEvent(UUID id, String eventType, JsonNode payload) {
}
